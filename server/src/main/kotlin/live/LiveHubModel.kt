package ch.nokillswit.live

import ch.nokillswit.infra.notify.LiveKind
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Frames a socket's own writer has not yet sent; past it the socket gets ONE `resync` instead (`live.md` "The hub"). */
const val OUTBOUND_QUEUE_CAPACITY = 64

/** Hints waiting for the actor; past it a hint becomes a gap (one `resync` to every socket) instead of growing the inbox without bound. */
const val MAX_QUEUED_HINTS = 1024

/** How long the LISTEN session may be down before the channel counts as deaf (`LiveHub.isDeaf`: a mint answers 503, sockets close 1013). */
const val LISTENER_DEAF_MILLIS = 10_000L

/** How often the hub checks for a deaf channel. */
const val DEAF_CHECK_MILLIS = 1_000L

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

/**
 * `trySend`, never a suspension. A full queue loses what a refetch recovers (events, a pending `resync`, a pong) to ONE `resync`, and
 * keeps what changes the client's state (`ready`, `subscribed`, `revoked`); if even that cannot fit, the socket is closed (1013) rather
 * than left with a silent gap. Called by the hub's actor only.
 */
internal fun queueFrame(socket: HubSocket, frame: LiveServerFrame) {
    if (socket.outbound.trySend(frame).isSuccess) return
    val kept = ArrayList<LiveServerFrame>()
    while (true) {
        val queued = socket.outbound.tryReceive().getOrNull() ?: break
        if (queued.changesState()) kept += queued
    }
    if (frame.changesState()) kept += frame
    val requeued = kept.all { socket.outbound.trySend(it).isSuccess } && socket.outbound.trySend(LiveServerFrame.Resync).isSuccess
    if (!requeued) socket.closeRequest.complete(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too slow"))
}

private fun LiveServerFrame.changesState(): Boolean =
    this is LiveServerFrame.Ready || this is LiveServerFrame.Subscribed || this is LiveServerFrame.Revoked

/** Who an `event` frame of a hint reaches (`live.md` "Hints"). A socket gets AT MOST ONE frame per hint whatever the audience. */
internal enum class Audience {
    /** The ACTIVE subscribers of the hint's room. */
    SUBSCRIBERS,

    /** The subscribers and, on every socket they have (subscribed to the room or not), the people the hint names in `targetUserIds`. */
    SUBSCRIBERS_AND_TARGETS,

    /** The named people only: the room has no subscribers that should hear it (a new room, for its creator alone). */
    TARGETS,
}

/**
 * The delivery class of a plain `event`. `room.deleted` and `room.member_removed` are not events for everybody they reach (they drop the
 * room and send `revoked`), so the hub gives them their own branches before it asks; their entry here is the default.
 */
internal fun LiveKind.audience(): Audience = when (this) {
    LiveKind.ROOM_CREATED, LiveKind.NOTIFICATION_CREATED -> Audience.TARGETS
    LiveKind.ROOM_MEMBER_ADDED, LiveKind.ROOM_MEMBER_ROLE_CHANGED -> Audience.SUBSCRIBERS_AND_TARGETS
    else -> Audience.SUBSCRIBERS
}
