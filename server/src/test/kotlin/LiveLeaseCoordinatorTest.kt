package ch.nokillswit

import ch.nokillswit.live.CLOSE_TOKEN_EXPIRED
import ch.nokillswit.live.LIVE_SESSION_LEASE_MILLIS
import ch.nokillswit.live.LeaseOpen
import ch.nokillswit.live.LiveForegroundUpdate
import ch.nokillswit.live.LiveLeaseAdmission
import ch.nokillswit.live.LiveLeaseCoordinator
import ch.nokillswit.live.LiveLeaseRenewal
import ch.nokillswit.live.LiveLeaseStore
import io.ktor.websocket.CloseReason
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    private class FakeLeaseStore(
        private val renewNone: Boolean = false,
        private val failAdmission: Boolean = false,
    ) : LiveLeaseStore {
        private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
        val renewBatches = CopyOnWriteArrayList<Set<UUID>>()
        val foregroundBatches = CopyOnWriteArrayList<Pair<Set<UUID>, Boolean>>()
        val released = CopyOnWriteArrayList<UUID>()
        private val clock = AtomicInteger()

        override suspend fun admit(userId: UInt, sessionId: UUID, tokenExpiresAtMillis: Long, maxSessions: Int): LiveLeaseAdmission {
            if (failAdmission) error("store unavailable")
            sessions += sessionId
            return LiveLeaseAdmission.Accepted(clock.get().toLong(), LIVE_SESSION_LEASE_MILLIS)
        }

        override suspend fun hasCapacity(userId: UInt, maxSessions: Int): Boolean = true

        override suspend fun renew(sessionIds: Collection<UUID>): LiveLeaseRenewal {
            renewBatches += sessionIds.toSet()
            val now = clock.incrementAndGet().toLong()
            val deadlines = if (renewNone) emptyMap() else sessionIds.associateWith { now + LIVE_SESSION_LEASE_MILLIS }
            return LiveLeaseRenewal(now, deadlines)
        }

        override suspend fun setForeground(sessionIds: Collection<UUID>, visible: Boolean): LiveForegroundUpdate {
            foregroundBatches += sessionIds.toSet() to visible
            return LiveForegroundUpdate(0, sessionIds.toSet(), LIVE_SESSION_LEASE_MILLIS.takeIf { visible })
        }

        override suspend fun release(sessionIds: Collection<UUID>): Int {
            released += sessionIds
            return sessionIds.count { sessions.remove(it) }
        }

        override suspend fun pruneExpired(batchSize: Int): Int = 0
    }
}
