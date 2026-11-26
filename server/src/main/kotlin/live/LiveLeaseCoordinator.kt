package ch.nokillswit.live

import io.ktor.websocket.CloseReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val leaseLog = LoggerFactory.getLogger("ch.nokillswit.live.LiveLeaseCoordinator")

internal const val LIVE_LEASE_HEARTBEAT_MILLIS = 20_000L
private const val LIVE_LEASE_WATCHDOG_MILLIS = 250L
private const val FOREGROUND_COALESCE_MILLIS = 25L
private const val LIVE_LEASE_BATCH_SIZE = 1_000

/** One process's batched renewals and foreground writes; PostgreSQL remains the authority for admission and expiry. */
internal class LiveLeaseCoordinator(
    private val store: LiveLeaseStore,
    scope: CoroutineScope,
    private val maxSessionsPerUser: Int,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val heartbeatMillis: Long = LIVE_LEASE_HEARTBEAT_MILLIS,
    private val watchdogMillis: Long = LIVE_LEASE_WATCHDOG_MILLIS,
) {
    private val sessions = ConcurrentHashMap<UUID, State>()
    private val foregroundSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(CoroutineName("adder-live-lease-renew")) { renewLoop() }
        scope.launch(CoroutineName("adder-live-foreground")) { foregroundLoop() }
        scope.launch(CoroutineName("adder-live-lease-watchdog")) { watchdogLoop() }
        scope.launch(CoroutineName("adder-live-lease-prune")) { pruneLoop() }
    }

    suspend fun admit(userId: UInt, tokenExpiresAtMillis: Long, closeRequest: CompletableDeferred<CloseReason>): LeaseOpen {
        val id = UUID.randomUUID()
        val began = monotonicNanos()
        val admission = try {
            val admitted = withContext(NonCancellable) { store.admit(userId, id, tokenExpiresAtMillis, maxSessionsPerUser) }
            currentCoroutineContext().ensureActive()
            admitted
        } catch (failure: CancellationException) {
            releaseStored(id)
            throw failure
        } catch (failure: Exception) {
            // Losing the result can leave an accepted row behind. Compensate only for this generated id.
            releaseStored(id)
            leaseLog.warn("live lease admission failed ({})", failure.javaClass.simpleName)
            return LeaseOpen.Unavailable
        }
        if (admission !is LiveLeaseAdmission.Accepted) return LeaseOpen.Refused(admission as LiveLeaseAdmission.Refused)
        val state = State(
            closeRequest,
            tokenExpiresAtMillis,
            conservativeDeadline(began, admission.serverNowMillis, admission.expiresAtMillis),
            conservativeDeadline(began, admission.serverNowMillis, tokenExpiresAtMillis),
        )
        sessions[id] = state
        return LeaseOpen.Accepted(LeaseHandle(id, state, this))
    }

    fun foreground(handle: LeaseHandle, visible: Boolean) {
        if (sessions[handle.id] !== handle.state) return
        handle.state.foreground.updateAndGet { Intent(visible, it.version + 1) }
        foregroundSignal.trySend(Unit)
    }

    fun isLive(handle: LeaseHandle): Boolean {
        val deadline = handle.state.deadline.get()
        return sessions[handle.id] === handle.state && !deadline.expired && monotonicNanos() < deadline.leaseNanos
    }

    fun expire(handle: LeaseHandle): Boolean =
        sessions[handle.id] !== handle.state || expireIfDue(handle.state, monotonicNanos())

    suspend fun release(handle: LeaseHandle) {
        if (sessions.remove(handle.id, handle.state)) releaseStored(handle.id)
    }

    private suspend fun releaseStored(id: UUID) {
        withContext(NonCancellable) { safely("release") { store.release(listOf(id)) } }
    }

    private suspend fun renewLoop() {
        while (true) {
            delay(heartbeatMillis)
            val snapshot = sessions.toMap()
            if (snapshot.isEmpty()) continue
            snapshot.entries.chunked(LIVE_LEASE_BATCH_SIZE).forEach { renewBatch(it) }
        }
    }

    private suspend fun renewBatch(batch: List<Map.Entry<UUID, State>>) {
        val began = monotonicNanos()
        val renewal = safely("renew") { store.renew(batch.map { it.key }) } ?: return
        batch.forEach { (id, state) ->
            val deadline = renewal.deadlines[id]
            if (deadline == null) {
                expireIfDue(state, Long.MAX_VALUE)
            } else {
                val next = Deadline(
                    conservativeDeadline(began, renewal.serverNowMillis, deadline),
                    conservativeDeadline(began, renewal.serverNowMillis, state.tokenExpiresAtMillis),
                )
                state.deadline.updateAndGet { if (it.expired) it else next }
            }
        }
    }

    private suspend fun foregroundLoop() {
        val heartbeatNanos = heartbeatMillis * 1_000_000
        var nextRefreshNanos = monotonicNanos() + heartbeatNanos
        while (true) {
            val untilRefreshMillis = (nextRefreshNanos - monotonicNanos()).coerceAtLeast(0) / 1_000_000
            val changed = withTimeoutOrNull(untilRefreshMillis) { foregroundSignal.receive() } != null
            if (changed) {
                delay(FOREGROUND_COALESCE_MILLIS)
                while (foregroundSignal.tryReceive().isSuccess) {
                    // Drain all changes into the single state snapshot below.
                }
            }
            val now = monotonicNanos()
            val refresh = !changed || now >= nextRefreshNanos
            if (refresh) nextRefreshNanos = now + heartbeatNanos
            flushForeground(refreshVisible = refresh)
        }
    }

    private suspend fun flushForeground(refreshVisible: Boolean) {
        val snapshot = sessions.mapNotNull { (id, state) ->
            val intent = state.foreground.get()
            val applied = state.appliedForegroundVersion.get()
            if (intent.version != applied || refreshVisible && intent.visible) Triple(id, state, intent) else null
        }
        for (visible in listOf(false, true)) {
            snapshot.filter { it.third.visible == visible }.chunked(LIVE_LEASE_BATCH_SIZE)
                .forEach { applyForegroundBatch(it, visible) }
        }
    }

    private suspend fun applyForegroundBatch(selected: List<Triple<UUID, State, Intent>>, visible: Boolean) {
        val changed = safely("foreground") {
            store.setForeground(selected.map { it.first }, visible).updatedSessions
        } ?: return
        selected.filter { it.first in changed }.forEach { (_, state, intent) ->
            if (state.foreground.get() == intent) state.appliedForegroundVersion.set(intent.version)
        }
    }

    private suspend fun watchdogLoop() {
        while (true) {
            delay(watchdogMillis)
            val now = monotonicNanos()
            sessions.values.forEach { expireIfDue(it, now) }
        }
    }

    private suspend fun pruneLoop() {
        while (true) {
            delay(LIVE_SESSION_LEASE_MILLIS)
            safely("prune") { store.pruneExpired() }
        }
    }

    private fun expireIfDue(state: State, now: Long): Boolean {
        while (true) {
            val deadline = state.deadline.get()
            if (deadline.expired) return true
            if (now < deadline.leaseNanos) return false
            // A concurrent renewal can replace this deadline before the close. Once this CAS wins, it cannot reopen the socket.
            if (!state.deadline.compareAndSet(deadline, deadline.copy(expired = true))) continue
            val reason = if (deadline.tokenNanos <= deadline.leaseNanos) CloseReason(CLOSE_TOKEN_EXPIRED, "The access token expired")
            else CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Live session expired")
            state.closeRequest.complete(reason)
            return true
        }
    }

    private fun conservativeDeadline(beganNanos: Long, serverNowMillis: Long, expiresAtMillis: Long): Long =
        beganNanos + (expiresAtMillis - serverNowMillis).coerceAtLeast(0) * 1_000_000

    private suspend fun <T> safely(operation: String, block: suspend () -> T): T? = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        leaseLog.warn("live lease {} failed ({})", operation, failure.javaClass.simpleName)
        null
    }

    internal class State(
        val closeRequest: CompletableDeferred<CloseReason>,
        val tokenExpiresAtMillis: Long,
        initialDeadlineNanos: Long,
        initialTokenDeadlineNanos: Long,
    ) {
        val deadline = AtomicReference(Deadline(initialDeadlineNanos, initialTokenDeadlineNanos))
        val foreground = AtomicReference(Intent(false, 0))
        val appliedForegroundVersion = AtomicLong(0)
    }

    internal data class Deadline(val leaseNanos: Long, val tokenNanos: Long, val expired: Boolean = false)
    internal data class Intent(val visible: Boolean, val version: Long)
}

internal sealed interface LeaseOpen {
    data class Accepted(val handle: LeaseHandle) : LeaseOpen
    data class Refused(val refusal: LiveLeaseAdmission.Refused) : LeaseOpen
    data object Unavailable : LeaseOpen
}

internal class LeaseHandle internal constructor(
    internal val id: UUID,
    internal val state: LiveLeaseCoordinator.State,
    private val owner: LiveLeaseCoordinator,
) {
    fun isLive(): Boolean = owner.isLive(this)
    fun expire() = owner.expire(this)
    fun foreground(visible: Boolean) = owner.foreground(this, visible)
    suspend fun release() = owner.release(this)
}
