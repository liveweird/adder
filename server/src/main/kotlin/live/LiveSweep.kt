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

/** The sweep, published by `configureLive`; a TEST seam (`sweepNow()`), nothing else reads it. */
val LiveSweepKey = AttributeKey<LiveSweep>("LiveSweep")

/**
 * The account sweep (`live.md` "Account standing"): every [intervalMillis] the standing of every open socket's token is re-judged by
 * [TokenBlocklistService.accessVerdicts] (batched; the same decision as the per-request check), and a socket whose verdict is not LIVE is
 * closed 4003 with ONE `live.revoked` line per user per sweep. This is how a logout, a user deletion, a password change and a role change
 * (a demoted ADMIN's room subscriptions included) end a socket without any `auth/` or `users/` write path knowing about the hub.
 *
 * The same pass re-checks MEMBERSHIPS: a non-ADMIN socket's ACTIVE room whose owner is no longer an active member of an existing room
 * gets `revoked`. Removal and deletion are normally pushed at once by their hints; this is the net for a hint lost in a listener gap
 * (so the bound is "immediate, or at most [intervalMillis] after a gap"), and a gap also triggers it straight away ([hub]'s observer).
 * [judge] is the verdict source, a test seam.
 */
class LiveSweep internal constructor(
    private val hub: LiveHub,
    blocklist: TokenBlocklistService,
    private val rooms: RoomService,
    private val intervalMillis: Long = SWEEP_INTERVAL_MILLIS,
    private val judge: suspend (List<AccessCheck>) -> List<AccessVerdict> = blocklist::accessVerdicts,
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
                survive { sweepNow() }
            }
        }
        val gaps = Channel<Unit>(Channel.CONFLATED)
        hub.gapObserver = { gaps.trySend(Unit) }
        scope.launch(CoroutineName("adder-live-recheck")) {
            for (ignored in gaps) survive { recheckMemberships() }
        }
    }

    private suspend fun survive(run: suspend () -> Unit) {
        try {
            run()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the scope itself ended: let it
            log.warn("live sweep run cancelled ({})", e.javaClass.simpleName)
        } catch (e: Exception) {
            log.warn("live sweep run failed ({})", e.javaClass.simpleName)
        }
    }

    /** One sweep, now: the number of sockets it closed (memberships it revoked are not counted). */
    suspend fun sweepNow(): Int {
        val views = hub.snapshot()
        if (views.isEmpty()) return 0
        val verdicts = judge(views.map { AccessCheck(it.socket.jti, it.socket.userId, it.socket.credentialRevision, it.socket.roles) })
        val revoked = views.zip(verdicts).filter { (_, verdict) -> verdict != AccessVerdict.LIVE }
        revoked.forEach { (view, _) ->
            view.socket.closeRequest.complete(CloseReason(CLOSE_REVOKED, "The session is no longer valid"))
        }
        revoked.groupBy({ it.first.socket.userId }, { it.second }).forEach { (userId, found) ->
            val names = found.map { it.name.lowercase() }.distinct().sorted().joinToString(",")
            audit("live.revoked", "byUserId" to userId.toLong(), "verdict" to names, "sockets" to found.size)
        }
        val closing = revoked.map { it.first }.toSet()
        revokeLostMemberships(views.filter { it !in closing })
        return revoked.size
    }

    /** Re-checks every open socket's memberships now (what a gap triggers); the number of subscriptions revoked. */
    suspend fun recheckMemberships(): Int = revokeLostMemberships(hub.snapshot())

    private suspend fun revokeLostMemberships(views: List<SocketView>): Int {
        // An ADMIN socket reads every room, member or not: only the other sockets can lose a room to a removal.
        val held = views.filter { !it.socket.isAdmin && it.activeRooms.isNotEmpty() }
        val lost = rooms.lostMemberships(held.flatMap { view -> view.activeRooms.map { view.socket.userId to it } })
        var revoked = 0
        held.forEach { view ->
            view.activeRooms.filter { (view.socket.userId to it) in lost }.forEach {
                hub.revokeRoom(view.socket, it)
                revoked++
            }
        }
        return revoked
    }
}
