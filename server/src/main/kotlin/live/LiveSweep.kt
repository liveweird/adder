package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.AccessCheck
import ch.nokillswit.auth.AccessVerdict
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.rooms.RoomService
import io.ktor.util.AttributeKey
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

private val log = LoggerFactory.getLogger("ch.nokillswit.live.LiveSweep")

/** How often every open socket's account is re-judged (`live.md` "The hub": the 60 s of the user's decision D2). */
const val SWEEP_INTERVAL_MILLIS = 60_000L

/** How long a failed half of a run waits before its ONE retry: a database blip should not leave a revoked account open for a minute. */
const val SWEEP_RETRY_MILLIS = 5_000L

/** The sweep, published by `configureLive`; a TEST seam (`sweepNow()`), nothing else reads it. */
val LiveSweepKey = AttributeKey<LiveSweep>("LiveSweep")

/** The two independent halves of a run: a failure of one never skips the other. */
private enum class Half { VERDICTS, MEMBERSHIPS }

/** What one pass did: the sockets it closed and, per half that failed, why. */
private class Pass(val closed: Int, val failures: Map<Half, Exception>)

/**
 * The account sweep (`live.md` "Account standing"): every [intervalMillis] the standing of every open socket's token is re-judged by
 * [TokenBlocklistService.accessVerdicts] (batched; the same decision as the per-request check), and a socket whose verdict is not LIVE is
 * closed 4003 with ONE `live.revoked` line per user per sweep. This is how a logout, a user deletion, a password change and a role change
 * (a demoted ADMIN's room subscriptions included) end a socket without any `auth/` or `users/` write path knowing about the hub.
 *
 * The same pass re-checks access to ACTIVE rooms. A socket that lost access gets `revoked` (and one `live.revoked reason=membership`
 * line per user). Removal and deletion are normally pushed at once by their
 * hints; this is the net for a hint lost in a listener gap (so the bound is "immediate, or at most [intervalMillis] after a gap"), and a
 * gap also triggers it straight away ([hub]'s observer). The two halves run independently, and a half that fails is retried ONCE after
 * [retryDelayMillis], so neither a failing verdict query nor a failing membership query leaves the other undone, and a blip does not
 * cost a whole interval. Each tick also flushes the unwritten tail of the coalesced `live.subscribe_denied` audit ([audits]).
 * [judge] (the verdicts) and [lost] (the membership read) are test seams.
 */
class LiveSweep internal constructor(
    private val hub: LiveHub,
    blocklist: TokenBlocklistService,
    rooms: RoomService,
    private val intervalMillis: Long = SWEEP_INTERVAL_MILLIS,
    private val retryDelayMillis: Long = SWEEP_RETRY_MILLIS,
    private val audits: LiveAudit? = null,
    private val judge: suspend (List<AccessCheck>) -> List<AccessVerdict> = blocklist::accessVerdicts,
    private val lost: suspend (Collection<Pair<UInt, UInt>>) -> Set<Pair<UInt, UInt>> = rooms::lostMemberships,
) {
    /**
     * Starts the periodic sweep and the gap-triggered membership re-check in [scope] (cancelled with it). A failed run is logged and the
     * next one runs on time; a [CancellationException] that is not the scope's own cancellation (a cancelled transaction, a timeout) is a
     * failed run too, never the end of the loop.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(CoroutineName("adder-live-sweep")) {
            while (true) {
                delay(intervalMillis)
                flushAudits()
                runWithRetry(Half.entries.toSet())
            }
        }
        val gaps = Channel<Unit>(Channel.CONFLATED)
        hub.gapObserver = { gaps.trySend(Unit) }
        scope.launch(CoroutineName("adder-live-recheck")) {
            for (ignored in gaps) runWithRetry(setOf(Half.MEMBERSHIPS))
        }
    }

    private fun flushAudits() {
        try {
            audits?.flushDenied()
        } catch (e: Exception) {
            log.warn("live audit flush failed ({})", e.javaClass.simpleName)
        }
    }

    /** Runs [halves], then runs again whichever failed after [retryDelayMillis]; a second failure waits for the next tick. */
    private suspend fun runWithRetry(halves: Set<Half>) {
        var failed = pass(halves).failures
        if (failed.isNotEmpty()) {
            log.warn("live sweep run failed ({}); retrying once", failed.values.joinToString { it.javaClass.simpleName })
            delay(retryDelayMillis)
            failed = pass(failed.keys).failures
            if (failed.isNotEmpty()) log.warn("live sweep retry failed ({})", failed.values.joinToString { it.javaClass.simpleName })
        }
    }

    /** One sweep, now: the number of sockets it closed (memberships it revoked are not counted); throws the first failure of a half. */
    suspend fun sweepNow(): Int {
        val pass = pass(Half.entries.toSet())
        pass.failures.values.firstOrNull()?.let { throw it }
        return pass.closed
    }

    private suspend fun pass(halves: Set<Half>): Pass {
        val views = hub.snapshot()
        if (views.isEmpty()) return Pass(0, emptyMap())
        val failures = LinkedHashMap<Half, Exception>()
        var closing = emptySet<SocketView>()
        if (Half.VERDICTS in halves) {
            attempt(failures, Half.VERDICTS) { closing = closeRevoked(views) }
        }
        if (Half.MEMBERSHIPS in halves) {
            attempt(failures, Half.MEMBERSHIPS) { revokeLostMemberships(views.filter { it !in closing }) }
        }
        return Pass(closing.size, failures)
    }

    /**
     * Runs one half, recording its failure instead of throwing it. A [CancellationException] that is not the scope's own cancellation (a
     * cancelled transaction, a timeout) is a failure too: [ensureActive] lets the scope's own through.
     */
    private suspend fun attempt(failures: MutableMap<Half, Exception>, half: Half, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failures[half] = e
        } catch (e: Exception) {
            failures[half] = e
        }
    }

    /** Re-judges every socket's token; closes the ones that are no longer LIVE 4003 and audits one line per user. */
    private suspend fun closeRevoked(views: List<SocketView>): Set<SocketView> {
        val verdicts = judge(views.map { AccessCheck(it.socket.jti, it.socket.userId, it.socket.credentialRevision, it.socket.roles) })
        val revoked = views.zip(verdicts).filter { (_, verdict) -> verdict != AccessVerdict.LIVE }
        revoked.forEach { (view, _) ->
            view.socket.closeRequest.complete(CloseReason(CLOSE_REVOKED, "The session is no longer valid"))
        }
        revoked.groupBy({ it.first.socket.userId }, { it.second }).forEach { (userId, found) ->
            val names = found.map { it.name.lowercase() }.distinct().sorted().joinToString(",")
            audit(
                "live.revoked", "byUserId" to userId.toLong(), "reason" to "verdict", "verdict" to names, "sockets" to found.size,
            )
        }
        return revoked.mapTo(HashSet()) { it.first }
    }

    private suspend fun revokeLostMemberships(views: List<SocketView>): Int {
        val held = views.filter { it.activeRooms.isNotEmpty() }
        val gone = lost(held.flatMap { view -> view.activeRooms.map { view.socket.userId to it } })
        var revoked = 0
        val perUser = LinkedHashMap<UInt, MutableSet<UInt>>()
        val socketsPerUser = HashMap<UInt, MutableSet<HubSocket>>()
        held.forEach { view ->
            view.activeRooms.filter { (view.socket.userId to it) in gone }.forEach {
                hub.revokeRoom(view.socket, it)
                perUser.getOrPut(view.socket.userId) { LinkedHashSet() }.add(it)
                socketsPerUser.getOrPut(view.socket.userId) { HashSet() }.add(view.socket)
                revoked++
            }
        }
        // One line per user per pass: a hint normally pushes these at once, so a line here means the hint was lost.
        perUser.forEach { (userId, roomIds) ->
            audit(
                "live.revoked", "byUserId" to userId.toLong(), "reason" to "membership", "rooms" to roomIds.size,
                "sockets" to socketsPerUser.getValue(userId).size,
            )
        }
        return revoked
    }
}
