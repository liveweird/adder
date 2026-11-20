package ch.nokillswit.live

import ch.nokillswit.auth.AccessVerdict
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.infra.clientAddressKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.rooms.RoomService
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.live.LiveSocket")

/** The application close codes of `live.md` "Frames and close codes"; the standard ones are `CloseReason.Codes`. */
internal const val CLOSE_TOKEN_EXPIRED: Short = 4001
internal const val CLOSE_REVOKED: Short = 4003
internal const val CLOSE_NO_TICKET: Short = 4401
internal const val CLOSE_SOCKET_CAP: Short = 4429

/** What a session needs from the rest of the application; assembled once by `configureLive`. */
internal class LiveSessionDeps(
    val config: LiveConfig,
    val tickets: LiveTickets,
    val hub: LiveHub,
    val blocklist: TokenBlocklistService,
    val rooms: RoomService,
    val audits: LiveAudit,
    val clock: () -> Long = ::nowMillis,
)

private sealed interface Next {
    class Incoming(val result: ChannelResult<Frame>) : Next
    class CloseRequested(val reason: CloseReason) : Next
}

/**
 * One live socket, from the upgrade to the close (`live.md` "Handshake", "The hub"). Nothing escapes it: StatusPages cannot answer after a
 * 101, so any failure is logged (class name only) and the socket closes 1011.
 *
 * 1. **Authenticate.** The FIRST frame must be `{"type":"auth","ticket":...}` within `live.authTimeoutMillis` (else 4401); the
 *    unauthenticated time is counted in the hub's pending slots (in total and per client address, the caps the admission checks).
 *    Redemption, in order: the ticket
 *    (single use; unknown, used and expired are one answer, 4401), `accessVerdict` of the token it was minted with (not LIVE: 4003),
 *    the token's `exp` (4001), then the hub's register (the per-user cap: 4429), which queues `ready` first.
 * 2. **Serve.** One writer drains the socket's queue; the reading loop answers `ping`, subscribes and unsubscribes, and ends on a
 *    close from either side. A subscribe registers the rooms PENDING in the hub, judges them with `RoomService.readableRoomIds` OUTSIDE
 *    the hub, and lets the hub activate the ones still PENDING and queue the ack in one step.
 *
 * A binary frame is 1003, a malformed, unknown or misplaced one 1008 (audited, coalesced); a frame over 8 KiB is Ktor's 1009 (Ktor
 * reassembles a fragmented text message before the reading loop sees it, and the limit applies to the whole message); more than
 * [INBOUND_FRAME_LIMIT] frames in [INBOUND_WINDOW_MILLIS] is 1008 too. The socket also ends 4001 at its token's `exp`.
 */
internal suspend fun DefaultWebSocketServerSession.liveSession(deps: LiveSessionDeps) {
    val hub = deps.hub
    // The advisory check at admission can be raced by a few upgrades in flight; this claim is the authoritative one. The address is the
    // one the per-IP bucket keyed by (the real client behind a proxy), an IPv6 one by its /64.
    val address = call.clientAddressKey()
    val claim = hub.pending.claim(address, deps.config.maxPendingAuth, deps.config.maxPendingPerAddress)
    if (claim != PendingClaim.OK) {
        val limit = if (claim == PendingClaim.ADDRESS_FULL) "pending-per-address" else "pending-auth"
        deps.audits.refused("capacity", null, "limit" to limit)
        closeWith(CloseReason.Codes.TRY_AGAIN_LATER.code, "Too many unauthenticated sockets")
        return
    }
    var pendingHeld = true
    var socket: HubSocket? = null
    var writer: Job? = null
    var expiry: Job? = null
    try {
        val binding = authenticate(deps)
        hub.pending.release(address)
        pendingHeld = false
        if (binding == null) return
        val registered = HubSocket(
            binding.userId, binding.isAdmin, binding.tokenExpiresAtMillis, binding.jti, binding.credentialRevision, binding.roles,
        )
        // Set BEFORE the register: a session cancelled while it waits for the hub's reply must still unregister, or the actor's
        // Register (FIFO, so followed by this Unregister) would leave a slot nobody ever frees. The hub ignores an unknown socket.
        socket = registered
        if (!register(deps, binding, registered)) return
        writer = launch { writeFrames(registered) }
        // The socket never outlives its token: at `exp` the hub-independent timer asks the reading loop to close 4001.
        expiry = launch { closeAtExpiry(deps, registered) }
        serve(deps, registered)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The class name only: a driver or parser message can carry a value from the wire.
        log.warn("live socket failed ({})", e.javaClass.simpleName)
        closeWith(CloseReason.Codes.INTERNAL_ERROR.code, "Internal error")
    } finally {
        if (pendingHeld) hub.pending.release(address)
        socket?.let {
            hub.unregister(it)
            it.outbound.close()
        }
        writer?.cancel()
        expiry?.cancel()
    }
}

/** Closes [socket] 4001 when its minting access token expires (`live.md`: "Each socket closes with 4001 at its token's `exp`"). */
private suspend fun closeAtExpiry(deps: LiveSessionDeps, socket: HubSocket) {
    delay((socket.tokenExpiresAtMillis - deps.clock()).coerceAtLeast(0))
    log.debug("live socket reached its access token's expiry")
    socket.closeRequest.complete(CloseReason(CLOSE_TOKEN_EXPIRED, "The access token expired"))
}

private suspend fun DefaultWebSocketSession.closeWith(code: Short, reason: String) {
    // A peer that is already gone makes the close itself fail; there is nothing left to tell it.
    try {
        close(CloseReason(code, reason))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // ignored: see above
    }
}

/** The ticket redemption of [liveSession]: the binding of a good first frame, or null after the socket was closed with the right code. */
private suspend fun DefaultWebSocketSession.authenticate(deps: LiveSessionDeps): LiveTicketBinding? {
    val auth = awaitAuthFrame(deps) ?: return null
    return redeem(deps, auth)
}

/** The first frame, which must be `auth`, within the deadline; null after the socket was closed (or the client left). */
private suspend fun DefaultWebSocketSession.awaitAuthFrame(deps: LiveSessionDeps): LiveClientFrame.Auth? {
    val first = withTimeoutOrNull(deps.config.authTimeoutMillis.toLong()) { incoming.receiveCatching() }
    if (first == null) {
        deps.audits.refused("auth", null, "detail" to "deadline")
        closeWith(CLOSE_NO_TICKET, "No ticket within the deadline")
        return null
    }
    val frame = first.getOrNull() ?: return null // the client left before sending anything
    val parsed = parse(deps, frame, userId = null) ?: return null // closed by `parse`
    if (parsed !is LiveClientFrame.Auth) {
        // A well-formed frame that is not `auth` is a frame before `auth`.
        badFrame(deps, null, "The first frame must be auth")
        return null
    }
    return parsed
}

/** The redemption order of `live.md` "Tickets": the ticket, the token's verdict, the token's `exp`. */
private suspend fun DefaultWebSocketSession.redeem(deps: LiveSessionDeps, auth: LiveClientFrame.Auth): LiveTicketBinding? {
    val binding = deps.tickets.redeem(auth.ticket)
    if (binding == null) {
        deps.audits.refused("auth", null, "detail" to "ticket")
        closeWith(CLOSE_NO_TICKET, "Unknown, expired or used ticket")
        return null
    }
    // Re-judged HERE: a logout, a password change or a role change between the mint and the redeem ends the token.
    val verdict = deps.blocklist.accessVerdict(binding.jti, binding.userId, binding.credentialRevision, binding.roles)
    if (verdict != AccessVerdict.LIVE) {
        deps.audits.refused("auth", binding.userId, "detail" to "verdict", "verdict" to verdict.name.lowercase())
        closeWith(CLOSE_REVOKED, "The session is no longer valid")
        return null
    }
    // The ticket's own deadline is clamped to the token's, so this is belt and braces (a clock step between the two reads).
    if (deps.clock() >= binding.tokenExpiresAtMillis) {
        closeWith(CLOSE_TOKEN_EXPIRED, "The access token expired")
        return null
    }
    return binding
}

/** Registers [socket] with the hub (which queues `ready`); on a refusal closes the socket and says false. */
private suspend fun DefaultWebSocketSession.register(deps: LiveSessionDeps, binding: LiveTicketBinding, socket: HubSocket): Boolean {
    when (deps.hub.register(socket)) {
        RegisterResult.OK -> return true
        RegisterResult.USER_CAP -> {
            deps.audits.refused("socket-cap", binding.userId, "limit" to deps.config.maxSocketsPerUser)
            closeWith(CLOSE_SOCKET_CAP, "Too many sockets for this account")
        }
        RegisterResult.FULL -> {
            deps.audits.refused("capacity", binding.userId, "limit" to "sockets")
            closeWith(CloseReason.Codes.TRY_AGAIN_LATER.code, "The server is full")
        }
        RegisterResult.UNAVAILABLE -> closeWith(CloseReason.Codes.GOING_AWAY.code, "Server stopping")
    }
    return false
}

private suspend fun DefaultWebSocketSession.writeFrames(socket: HubSocket) {
    try {
        for (frame in socket.outbound) send(Frame.Text(frame.toJson()))
    } catch (_: CancellationException) {
        // the session ended
    } catch (_: Exception) {
        // the peer is gone: the reading loop sees the close and tears the socket down
    }
}

/** The reading loop: frames from the client and close requests from the hub, until either side ends the socket. */
private suspend fun DefaultWebSocketSession.serve(deps: LiveSessionDeps, socket: HubSocket) {
    val rate = InboundRate()
    while (true) {
        // `select` is biased to the FIRST clause: a close request (stopping, a failed verdict) is never starved by a chatty client.
        val next = select<Next> {
            socket.closeRequest.onAwait { Next.CloseRequested(it) }
            incoming.onReceiveCatching { Next.Incoming(it) }
        }
        when (next) {
            is Next.CloseRequested -> {
                closeWith(next.reason.code, next.reason.message)
                return
            }
            is Next.Incoming -> {
                val frame = next.result.getOrNull() ?: return
                // Before parsing: a flood costs a counter, not a JSON parse or a database transaction per frame.
                if (!rate.allow()) {
                    deps.audits.refused("inbound-rate", socket.userId, "limit" to INBOUND_FRAME_LIMIT)
                    closeWith(CloseReason.Codes.VIOLATED_POLICY.code, "Too many frames")
                    return
                }
                val parsed = parse(deps, frame, socket.userId) ?: return
                if (!handle(deps, socket, parsed)) return
            }
        }
    }
}

/** Handles one parsed frame; false when it closed the socket. */
private suspend fun DefaultWebSocketSession.handle(deps: LiveSessionDeps, socket: HubSocket, frame: LiveClientFrame): Boolean {
    when (frame) {
        is LiveClientFrame.Auth -> {
            badFrame(deps, socket.userId, "A second auth frame")
            return false
        }
        LiveClientFrame.Ping -> deps.hub.pong(socket)
        is LiveClientFrame.Subscribe -> {
            val ids = validIds(frame.roomIds)
            if (ids == null) {
                badFrame(deps, socket.userId, "Invalid room ids")
                return false
            }
            subscribe(deps, socket, ids)
        }
        is LiveClientFrame.Unsubscribe -> {
            val ids = validIds(frame.roomIds)
            if (ids == null) {
                badFrame(deps, socket.userId, "Invalid room ids")
                return false
            }
            deps.hub.unsubscribe(socket, ids)
        }
    }
    return true
}

private suspend fun subscribe(deps: LiveSessionDeps, socket: HubSocket, ids: List<UInt>) {
    val begin = deps.hub.beginSubscribe(socket, ids)
    if (begin.capped.isNotEmpty()) deps.audits.refused("subscription-cap", socket.userId, "limit" to MAX_SUBSCRIPTIONS)
    if (begin.toCheck.isEmpty()) {
        deps.hub.completeSubscribe(socket, begin, emptySet())
        return
    }
    val readable = try {
        deps.rooms.readableRoomIds(socket.userId, begin.toCheck)
    } catch (e: Exception) {
        // The PENDING registrations must not outlive a failed check; the session then closes 1011.
        deps.hub.abortSubscribe(socket, begin.toCheck)
        throw e
    }
    // Only EXISTING rooms the caller cannot read are audited; an id that matches no room is silent (and indistinguishable on the wire).
    if (readable.deniedExisting.isNotEmpty()) deps.audits.subscribeDenied(socket.userId, readable.deniedExisting)
    deps.hub.completeSubscribe(socket, begin, readable.readable)
}

/**
 * One client frame parsed, or null after the socket was closed for it: binary is 1003; text that is not a well-formed, known frame is
 * 1008. A fragmented text message arrives here already reassembled (Ktor's `incoming` only delivers whole messages, so the 8 KiB limit
 * has been applied to the total and there is no partial frame to refuse).
 */
private suspend fun DefaultWebSocketSession.parse(deps: LiveSessionDeps, frame: Frame, userId: UInt?): LiveClientFrame? {
    if (frame is Frame.Binary) {
        deps.audits.refused("bad-frame", userId, "detail" to "binary")
        closeWith(CloseReason.Codes.CANNOT_ACCEPT.code, "Binary frames are not accepted")
        return null
    }
    val parsed = if (frame is Frame.Text) {
        try {
            parseClientFrame(frame.readText())
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    if (parsed == null) badFrame(deps, userId, "Malformed frame")
    return parsed
}

private suspend fun DefaultWebSocketSession.badFrame(deps: LiveSessionDeps, userId: UInt?, reason: String) {
    deps.audits.refused("bad-frame", userId, "detail" to reason)
    closeWith(CloseReason.Codes.VIOLATED_POLICY.code, reason)
}
