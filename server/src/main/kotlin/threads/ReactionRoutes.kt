package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/**
 * `/api/v1/rooms/{id}/messages/{messageId}/reaction`: the caller's own reaction of ONE emoji on a message, a single-value set with a
 * body (API-RES-006 allows two path parameters at most, and the emoji must not travel in a path: every `path` field of an audit line, a
 * rate-limit line and the access log would carry it).
 */
@Serializable
@Resource("reaction")
class MessageReactionRoute(val parent: RoomMessagesRoute.Id)

fun Application.configureReactionRoutes() {
    val roomService = attributes[RoomServiceKey]
    val reactionService = attributes[MessageReactionServiceKey]

    routing {
        authenticate {
            messageReaction(roomService, reactionService)
        }
    }
}

/**
 * The reaction PUT (the per-USER `chat-write` bucket, before every guard), in the documented guard order: 401 from `authenticate`, the
 * bucket (429), visibility (`visibleStanding`: 404 for a missing room and a non-member alike, audited `room.access_denied`), the member
 * guard (403: an ADMIN outside the room speaks to nobody), the body (400, a fixed detail that echoes nothing); the service then takes the
 * room lock and answers the message (404), the archived room and the closed thread (409), the limits (409 `reaction-limit`). The audit
 * line follows the commit, on a change only, with ids and `reacted` - never the emoji.
 */
private fun Route.messageReaction(roomService: RoomService, reactionService: MessageReactionService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<MessageReactionRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
            val request = call.receive<ReactionRequest>()
            // Adding is judged by the one-emoji rule; removing looks the stored value up within the input bounds only.
            if (request.reacted) requireReactionEmoji(request.emoji) else requireReactionLookup(request.emoji)
            val change = reactionService.set(roomId, route.parent.messageId, caller, request.emoji, request.reacted)
            if (change != null) {
                audit(
                    "message.reaction_changed",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to change.threadId.toLong(),
                    "messageId" to change.messageId.toLong(),
                    "reacted" to change.reacted,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
