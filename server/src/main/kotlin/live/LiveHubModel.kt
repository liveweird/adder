package ch.nokillswit.live

import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Frames a socket's own writer has not yet sent; past it the socket gets ONE `resync` instead (`live.md` "The hub"). */
const val OUTBOUND_QUEUE_CAPACITY = 64

/** Hints waiting for the actor; past it a hint becomes a gap (one `resync` to every socket) instead of growing the inbox without bound. */
const val MAX_QUEUED_HINTS = 1024

/** How long [LiveHub.shutdown] waits for the sockets to leave after asking them to close 1001 (`live.md` "Shutdown"). */
const val SHUTDOWN_GRACE_MILLIS = 2_000L

/** Whether a subscribed room already receives hints, or is still being checked (the subscribe race rule, `live.md` "The hub"). */
internal enum class RoomState { PENDING, ACTIVE }

/**
 * What the hub knows about one authenticated socket. [rooms] belongs to the hub's actor coroutine and is touched by nothing else; the
 * socket's session owns [outbound]'s receiving end (its writer) and [closeRequest]'s waiting end. Only the hub `trySend`s into [outbound].
 */
internal class HubSocket(
    val userId: UInt,
    val isAdmin: Boolean,
    /** The minting access token's `exp` (Unix millis): the session closes the socket 4001 at it. */
    val tokenExpiresAtMillis: Long,
    /** The token facts the account sweep re-judges (`accessVerdicts(jti, userId, credentialRevision, roles)`). */
    val jti: String = "",
    val credentialRevision: Long = 0,
    val roles: Set<String> = emptySet(),
    val outbound: Channel<LiveServerFrame> = Channel(OUTBOUND_QUEUE_CAPACITY),
    /** Completed by the hub to make the session close with that reason (server stopping, a failed account verdict). */
    val closeRequest: CompletableDeferred<CloseReason> = CompletableDeferred(),
) {
    val rooms = HashMap<UInt, RoomState>()
}

/** A socket and a COPY of the rooms it is ACTIVE in at the moment of the snapshot (the hub's own set is the actor's alone). */
internal class SocketView(val socket: HubSocket, val activeRooms: List<UInt>)

internal enum class RegisterResult { OK, USER_CAP, FULL, UNAVAILABLE }

/**
 * The outcome of the first half of a subscribe: [toCheck] are registered PENDING and need the readable check; [already] are
 * subscribed; [capped] are over the limit.
 */
internal class SubscribeBegin(val already: List<UInt>, val toCheck: List<UInt>, val capped: List<UInt>)
