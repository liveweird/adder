package ch.nokillswit

import ch.nokillswit.live.CLOSE_TOKEN_EXPIRED
import ch.nokillswit.live.LIVE_SESSION_LEASE_MILLIS
import ch.nokillswit.live.LeaseOpen
import ch.nokillswit.live.LiveLeaseAdmission
import ch.nokillswit.live.LiveLeaseCoordinator
import ch.nokillswit.live.LiveLeaseRenewal
import ch.nokillswit.live.LiveLeaseStore
import io.ktor.websocket.CloseReason
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LiveLeaseCoordinatorTest {
    @Test
    fun `renews a thousand sessions and coalesces foreground changes in bounded batches`() = runBlocking {
        val store = FakeLeaseStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 16, monotonicNanos = { 0 }, heartbeatMillis = 100)
            val leases = (0 until 1_000).map { index ->
                val open = coordinator.admit(index.toUInt(), 60_000, CompletableDeferred())
                assertIs<LeaseOpen.Accepted>(open).handle
            }

            leases.take(500).forEach { it.foreground(true) }
            await { store.foregroundBatches.isNotEmpty() && store.renewBatches.isNotEmpty() }

            assertEquals(1, store.foregroundBatches.count { it.second })
            assertEquals(500, store.foregroundBatches.single { it.second }.first.size)
            assertEquals(1_000, store.renewBatches.first().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `missing renewal closes the socket and release remains instance cleanup`() = runBlocking {
        val store = FakeLeaseStore(renewNone = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val closed = CompletableDeferred<CloseReason>()
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1, monotonicNanos = { 0 }, heartbeatMillis = 25)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, closed)).handle

            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, withTimeout(2_000) { closed.await() }.code)
            handle.release()
            assertEquals(listOf(handle.id), store.released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `admission store outage fails closed without creating local lease state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(FakeLeaseStore(failAdmission = true), scope, 16)
            assertEquals(LeaseOpen.Unavailable, coordinator.admit(7u, Long.MAX_VALUE, CompletableDeferred()))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a token-clamped lease preserves the token-expired close contract`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val closed = CompletableDeferred<CloseReason>()
            val coordinator = LiveLeaseCoordinator(
                FakeLeaseStore(renewNone = true), scope, maxSessionsPerUser = 1, monotonicNanos = { 0 }, heartbeatMillis = 25,
            )
            assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, LIVE_SESSION_LEASE_MILLIS, closed))

            assertEquals(CLOSE_TOKEN_EXPIRED, withTimeout(2_000) { closed.await() }.code)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling a client after the shared admission frees its slot`() = runBlocking {
        val inserted = CompletableDeferred<UUID>()
        val finishAdmission = CompletableDeferred<Unit>()
        val store = FakeLeaseStore(afterAdmit = { id ->
            inserted.complete(id)
            finishAdmission.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1)
            val job = async { coordinator.admit(7u, 300_000, CompletableDeferred()) }
            val id = withTimeout(2_000) { inserted.await() }
            job.cancel()
            finishAdmission.complete(Unit)
            withTimeout(2_000) { job.join() }
            assertEquals(listOf(id), store.released)
        } finally {
            finishAdmission.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `an admission that cannot report its commit is released by its exact id`() = runBlocking {
        var uncertainId: UUID? = null
        val store = FakeLeaseStore(afterAdmit = { id ->
            uncertainId = id
            error("store lost the commit response")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1)
            assertEquals(LeaseOpen.Unavailable, coordinator.admit(7u, 300_000, CompletableDeferred()))
            assertEquals(listOf(requireNotNull(uncertainId)), store.released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an expiry observation cannot close a socket after its lease renewed`() = runBlocking {
        val now = AtomicLong()
        val startedRenewal = CompletableDeferred<Unit>()
        val completeRenewal = CompletableDeferred<Unit>()
        val store = FakeLeaseStore(afterRenew = {
            if (now.get() > 0) {
                startedRenewal.complete(Unit)
                completeRenewal.await()
            }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val close = CompletableDeferred<CloseReason>()
            val coordinator = LiveLeaseCoordinator(store, scope, 1, now::get, heartbeatMillis = 25, watchdogMillis = 30_000)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, close)).handle
            now.set(59_000_000_000L)
            withTimeout(2_000) { startedRenewal.await() }
            now.set(60_000_000_000L)
            assertFalse(handle.isLive(), "the writer or watchdog observed the previous deadline")
            completeRenewal.complete(Unit)
            await { handle.isLive() }
            handle.expire()
            assertFalse(close.isCompleted, "the current deadline superseded that observation")
            handle.release()
        } finally {
            completeRenewal.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `shared admission refusals create no local lease`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for (refusal in ch.nokillswit.live.LiveLeaseRefusal.entries) {
                val store = FakeLeaseStore(admissionRefusal = refusal)
                val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1)
                val opened = assertIs<LeaseOpen.Refused>(coordinator.admit(7u, 300_000, CompletableDeferred()))
                assertEquals(refusal, opened.refusal.reason)
                assertTrue(store.released.isEmpty())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `released handles stay stale and duplicate release does not touch shared storage`() = runBlocking {
        val store = FakeLeaseStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.release()
            handle.foreground(true)

            assertFalse(handle.isLive())
            assertTrue(handle.expire())
            handle.release()
            delay(50)
            assertEquals(listOf(handle.id), store.released)
            assertTrue(store.foregroundBatches.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed foreground refresh is retried and failed release remains fail closed`() = runBlocking {
        val store = FakeLeaseStore(dropFirstForeground = true, failRelease = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1, heartbeatMillis = 75)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)

            await { store.foregroundBatches.count { it.second } >= 2 }
            handle.foreground(false)
            await { store.foregroundBatches.any { !it.second } }
            handle.release()
            assertEquals(listOf(handle.id), store.released)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a socket heartbeat alone does not renew the last client foreground frame`() = runBlocking {
        val store = FakeLeaseStore()
        val now = AtomicLong()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, 1, now::get, heartbeatMillis = 25, watchdogMillis = 30_000)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)
            await { store.foregroundBatches.size == 1 }

            val renews = store.renewAttempts.get()
            now.set((LIVE_SESSION_LEASE_MILLIS + 1) * 1_000_000)
            await { store.renewAttempts.get() >= renews + 3 }
            assertEquals(listOf(true), store.foregroundBatches.map { it.second })

            handle.foreground(true)
            await { store.foregroundBatches.size == 2 }
            assertEquals(listOf(true, true), store.foregroundBatches.map { it.second })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a visible frame that aged out before retry cannot restore foreground presence`() = runBlocking {
        val firstWrite = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        val now = AtomicLong()
        val store = FakeLeaseStore(dropFirstForeground = true, afterForeground = {
            if (firstWrite.complete(Unit)) finishFirstWrite.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, 1, now::get, heartbeatMillis = 25, watchdogMillis = 30_000)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)
            withTimeout(5_000) { firstWrite.await() }
            now.set((LIVE_SESSION_LEASE_MILLIS + 1) * 1_000_000)
            finishFirstWrite.complete(Unit)
            await { store.foregroundBatches.size >= 2 }
            assertEquals(listOf(true, false), store.foregroundBatches.take(2).map { it.second })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a visible write completing late cannot grant a new sixty seconds`() = runBlocking {
        val firstWrite = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        val now = AtomicLong()
        val databaseNow = AtomicLong()
        val store = FakeLeaseStore(databaseTimeMillis = databaseNow::get, afterForeground = {
            firstWrite.complete(Unit)
            finishFirstWrite.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, 1, now::get, heartbeatMillis = 25, watchdogMillis = 30_000)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)
            withTimeout(5_000) { firstWrite.await() }
            databaseNow.set(LIVE_SESSION_LEASE_MILLIS + 1)
            now.set(databaseNow.get() * 1_000_000)
            finishFirstWrite.complete(Unit)
            await { store.storedForegroundDeadlines.isNotEmpty() }
            val storedDeadline = store.storedForegroundDeadlines.single()
            assertTrue(storedDeadline == null || storedDeadline <= LIVE_SESSION_LEASE_MILLIS)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `renewal and foreground outages do not cancel the coordinator loops`() = runBlocking {
        val store = FakeLeaseStore(failRenew = true, failForeground = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1, heartbeatMillis = 50)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)
            await { store.renewAttempts.get() >= 2 && store.foregroundAttempts.get() >= 2 }

            assertTrue(handle.isLive())
            handle.release()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty coordinator skips renewal and retries failed bounded pruning`() = runBlocking {
        val store = FakeLeaseStore(failPrune = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1, heartbeatMillis = 20, pruneMillis = 20)
            await { store.pruneAttempts.get() >= 2 }
            assertEquals(0, store.renewAttempts.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `expired handle remains expired and cannot be reopened by an in-flight renewal`() = runBlocking {
        val now = AtomicLong()
        val renewalStarted = CompletableDeferred<Unit>()
        val finishRenewal = CompletableDeferred<Unit>()
        val store = FakeLeaseStore(afterRenew = {
            renewalStarted.complete(Unit)
            finishRenewal.await()
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val closed = CompletableDeferred<CloseReason>()
            val coordinator = LiveLeaseCoordinator(store, scope, 1, now::get, heartbeatMillis = 20, watchdogMillis = 30_000)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, closed)).handle
            withTimeout(2_000) { renewalStarted.await() }
            now.set(60_000_000_000L)

            assertTrue(handle.expire())
            assertTrue(handle.expire(), "a repeated expiry observes the terminal state")
            assertFalse(handle.isLive())
            finishRenewal.complete(Unit)
            delay(50)
            assertFalse(handle.isLive(), "a renewal result cannot reopen an expired handle")
        } finally {
            finishRenewal.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun `watchdog closes a locally elapsed lease before the next database heartbeat`() = runBlocking {
        val now = AtomicLong()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val closed = CompletableDeferred<CloseReason>()
            val coordinator = LiveLeaseCoordinator(
                FakeLeaseStore(), scope, maxSessionsPerUser = 1, monotonicNanos = now::get,
                heartbeatMillis = 300_000, watchdogMillis = 20,
            )
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, closed)).handle
            now.set(60_000_000_000L)

            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, withTimeout(2_000) { closed.await() }.code)
            assertFalse(handle.isLive())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `foreground intent changed during persistence is not marked as applied`() = runBlocking {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val finishFirstWrite = CompletableDeferred<Unit>()
        val store = FakeLeaseStore(afterForeground = { visible ->
            if (visible && !firstWriteStarted.isCompleted) {
                firstWriteStarted.complete(Unit)
                finishFirstWrite.await()
            }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = LiveLeaseCoordinator(store, scope, maxSessionsPerUser = 1, heartbeatMillis = 250)
            val handle = assertIs<LeaseOpen.Accepted>(coordinator.admit(7u, 300_000, CompletableDeferred())).handle
            handle.foreground(true)
            withTimeout(2_000) { firstWriteStarted.await() }
            handle.foreground(false)
            finishFirstWrite.complete(Unit)

            await { store.foregroundBatches.any { !it.second } }
            assertEquals(listOf(true, false), store.foregroundBatches.map { it.second })
        } finally {
            finishFirstWrite.complete(Unit)
            scope.cancel()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private class FakeLeaseStore(
        private val renewNone: Boolean = false,
        private val failAdmission: Boolean = false,
        private val afterAdmit: suspend (UUID) -> Unit = {},
        private val afterRenew: suspend () -> Unit = {},
        private val admissionRefusal: ch.nokillswit.live.LiveLeaseRefusal? = null,
        private val dropFirstForeground: Boolean = false,
        private val failRenew: Boolean = false,
        private val failForeground: Boolean = false,
        private val failRelease: Boolean = false,
        private val failPrune: Boolean = false,
        private val databaseTimeMillis: () -> Long = { 0 },
        private val afterForeground: suspend (Boolean) -> Unit = {},
    ) : LiveLeaseStore {
        private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
        val renewBatches = CopyOnWriteArrayList<Set<UUID>>()
        val foregroundBatches = CopyOnWriteArrayList<Pair<Set<UUID>, Boolean>>()
        val storedForegroundDeadlines = CopyOnWriteArrayList<Long?>()
        val released = CopyOnWriteArrayList<UUID>()
        val renewAttempts = AtomicInteger()
        val foregroundAttempts = AtomicInteger()
        val pruneAttempts = AtomicInteger()
        private val firstForeground = AtomicBoolean(true)
        private val clock = AtomicInteger()

        override suspend fun admit(userId: UInt, sessionId: UUID, tokenExpiresAtMillis: Long, maxSessions: Int): LiveLeaseAdmission {
            if (failAdmission) error("store unavailable")
            admissionRefusal?.let { return LiveLeaseAdmission.Refused(clock.get().toLong(), it) }
            sessions += sessionId
            afterAdmit(sessionId)
            return LiveLeaseAdmission.Accepted(clock.get().toLong(), LIVE_SESSION_LEASE_MILLIS)
        }

        override suspend fun hasCapacity(userId: UInt, maxSessions: Int): Boolean = true

        override suspend fun renew(sessionIds: Collection<UUID>): LiveLeaseRenewal {
            renewAttempts.incrementAndGet()
            if (failRenew) error("renew unavailable")
            renewBatches += sessionIds.toSet()
            afterRenew()
            val now = clock.incrementAndGet().toLong()
            val deadlines = if (renewNone) emptyMap() else sessionIds.associateWith { now + LIVE_SESSION_LEASE_MILLIS }
            return LiveLeaseRenewal(now, deadlines)
        }

        override suspend fun setForeground(expiresAtBySession: Map<UUID, Long?>): Set<UUID> {
            foregroundAttempts.incrementAndGet()
            if (failForeground) error("foreground unavailable")
            expiresAtBySession.entries.groupBy { it.value != null }.forEach { (visible, group) ->
                foregroundBatches += group.map { it.key }.toSet() to visible
                afterForeground(visible)
            }
            if (dropFirstForeground && firstForeground.compareAndSet(true, false)) return emptySet()
            expiresAtBySession.values.forEach { expiresAt ->
                storedForegroundDeadlines += expiresAt?.takeIf { it > databaseTimeMillis() }
            }
            return expiresAtBySession.keys
        }

        override suspend fun release(sessionIds: Collection<UUID>): Int {
            released += sessionIds
            if (failRelease) error("release unavailable")
            return sessionIds.count { sessions.remove(it) }
        }

        override suspend fun pruneExpired(batchSize: Int): Int {
            pruneAttempts.incrementAndGet()
            if (failPrune) error("prune unavailable")
            return 0
        }
    }
}
