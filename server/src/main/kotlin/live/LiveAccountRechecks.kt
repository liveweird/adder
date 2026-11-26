package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.AccessCheck
import ch.nokillswit.auth.AccessVerdict
import ch.nokillswit.auth.TokenBlocklistService
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.live.LiveAccountRechecks")

/** Distinct account ids held between worker passes; overflow deliberately widens the next pass to every socket. */
internal const val MAX_PENDING_ACCOUNT_RECHECKS = 1024

private sealed interface RecheckRequest {
    data object All : RecheckRequest
    class Users(val ids: Set<UInt>) : RecheckRequest
}

/**
 * Prompt account rechecks driven by the private NOTIFY channel. Producers only coalesce ids and signal a conflated wake-up; database
 * work and socket snapshots happen in this worker, never in the hub actor or listener callback. An overflow or listener gap widens the
 * pass to all current sockets. A failed pass is retried once after five seconds against a fresh socket snapshot.
 */
internal class LiveAccountRechecks(
    private val snapshot: suspend (Set<UInt>?) -> List<SocketView>,
    private val judge: suspend (List<AccessCheck>) -> List<AccessVerdict>,
    private val retryDelayMillis: Long = SWEEP_RETRY_MILLIS,
    private val maxPending: Int = MAX_PENDING_ACCOUNT_RECHECKS,
) {
    constructor(
        hub: LiveHub,
        blocklist: TokenBlocklistService,
        retryDelayMillis: Long = SWEEP_RETRY_MILLIS,
        maxPending: Int = MAX_PENDING_ACCOUNT_RECHECKS,
    ) : this(hub::snapshot, blocklist::accessVerdicts, retryDelayMillis, maxPending)

    private val lock = Any()
    private val pending = LinkedHashSet<UInt>()
    private var all = false
    private val wakes = Channel<Unit>(Channel.CONFLATED)

    fun accountChanged(userId: UInt) {
        synchronized(lock) {
            if (all || userId in pending) Unit else if (pending.size < maxPending) pending += userId else {
                all = true
                pending.clear()
            }
        }
        wakes.trySend(Unit)
    }

    fun gap() {
        synchronized(lock) {
            all = true
            pending.clear()
        }
        wakes.trySend(Unit)
    }

    fun start(scope: CoroutineScope) {
        scope.launch(CoroutineName("adder-live-account-rechecks")) {
            for (ignored in wakes) runWithRetry(drain())
        }
    }

    /** Test seam: processes the currently coalesced request synchronously. */
    suspend fun recheckNow(): Int = runOnce(drain())

    private fun drain(): RecheckRequest = synchronized(lock) {
        if (all) {
            all = false
            RecheckRequest.All
        } else {
            RecheckRequest.Users(pending.toSet()).also { pending.clear() }
        }
    }

    private suspend fun runWithRetry(request: RecheckRequest) {
        try {
            runOnce(request)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            retry(request, e)
        } catch (e: Exception) {
            retry(request, e)
        }
    }

    private suspend fun retry(request: RecheckRequest, first: Exception) {
        log.warn("live account recheck failed ({}); retrying once", first.javaClass.simpleName)
        delay(retryDelayMillis)
        try {
            runOnce(request)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log.warn("live account recheck retry failed ({})", e.javaClass.simpleName)
        } catch (e: Exception) {
            log.warn("live account recheck retry failed ({})", e.javaClass.simpleName)
        }
    }

    private suspend fun runOnce(request: RecheckRequest): Int {
        val views = when (request) {
            RecheckRequest.All -> snapshot(null)
            is RecheckRequest.Users -> snapshot(request.ids)
        }
        return closeRevokedSockets(views, judge).closed
    }
}

/** Re-judges [views] and audits only sockets whose close transition this call actually won. */
internal suspend fun closeRevokedSockets(
    views: List<SocketView>,
    judge: suspend (List<AccessCheck>) -> List<AccessVerdict>,
): RevokedSockets {
    if (views.isEmpty()) return RevokedSockets(emptySet(), 0)
    val verdicts = judge(views.map { it.socket.accessCheck() })
    check(verdicts.size == views.size) { "The access verdict count did not match the socket count" }
    val revoked = views.zip(verdicts).filter { (_, verdict) -> verdict != AccessVerdict.LIVE }
    val closed = revoked.filter { (view, _) ->
        view.socket.closeRequest.complete(
            CloseReason(CLOSE_REVOKED, "The session is no longer valid"),
        )
    }
    closed.groupBy({ it.first.socket.userId }, { it.second }).forEach { (userId, found) ->
        audit(
            "live.revoked", "byUserId" to userId.toLong(), "reason" to "verdict",
            "verdict" to found.map { it.name.lowercase() }.distinct().sorted().joinToString(","), "sockets" to found.size,
        )
    }
    return RevokedSockets(revoked.mapTo(HashSet()) { it.first }, closed.size)
}

internal class RevokedSockets(val revoked: Set<SocketView>, val closed: Int)

private fun HubSocket.accessCheck() = AccessCheck(jti, userId, credentialRevision, roles)
