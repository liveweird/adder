package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoundedString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
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

/** The paged full roster behind one reaction summary. */
@Serializable
@Resource("reactors")
class MessageReactorsRoute(val parent: RoomMessagesRoute.Id)

fun Application.configureReactionRoutes() {
    val roomService = attributes[RoomServiceKey]
    val messageService = attributes[MessageServiceKey]
    val reactionService = attributes[MessageReactionServiceKey]

    routing {
        authenticate {
            messageReactors(roomService, messageService, reactionService)
            messageReaction(roomService, reactionService)
        }
    }
}

private val REACTION_KEY = Regex("^[0-9a-f]{64}$")
private val REACTOR_SORT = setOf("createdAt")

/** A room reader may inspect the people behind a reaction already visible on the message. */
private fun Route.messageReactors(
    roomService: RoomService,
    messageService: MessageService,
    reactionService: MessageReactionService,
) {
    get<MessageReactorsRoute> { route ->
        val caller = call.caller()
        val roomId = route.parent.parent.parent.id
        roomService.visibleStanding(caller, roomId)
        messageService.read(roomId, route.parent.messageId, caller.userId).orNotFound("Message")
        val key = call.request.queryParameters.optionalBoundedString("reactionKey", 64)
        if (key == null || !REACTION_KEY.matches(key)) throw BadRequestException("reactionKey must be a reaction key")
        val paging = call.parsePaging(REACTOR_SORT, listOf(SortField("createdAt", descending = false)))
        val result = reactionService.reactors(route.parent.messageId, key, paging).orNotFound("Reaction")
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
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
                auditNoticesCreated(caller.userId, roomId, change.threadId, change.messageId, change.notices)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
