package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireMessageAuthor
import ch.nokillswit.authz.requireMessageRemover
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomsRoute
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/threads/{number}/messages`: the conversation of one thread (a child of [ThreadsRoute.Number]). */
@Serializable
@Resource("messages")
class ThreadMessagesRoute(val parent: ThreadsRoute.Number)

/**
 * `/api/v1/rooms/{id}/messages/{messageId}`: ONE message, addressed through its room (API-RES-006 bans a path of three parameters), so
 * the route checks that the message belongs to that room: a message of another room is the uniform `404`.
 */
@Serializable
@Resource("messages")
class RoomMessagesRoute(val parent: RoomsRoute.Id) {
    @Serializable
    @Resource("{messageId}")
    class Id(val parent: RoomMessagesRoute, val messageId: UInt)
}

/** The largest `id[lt]` the spec admits: message ids are int32 sequences (a wider value is a `400`, never a wrapped id). */
private val MAX_MESSAGE_ID = Int.MAX_VALUE.toUInt()

fun Application.configureMessageRoutes() {
    val roomService = attributes[RoomServiceKey]
    val threadService = attributes[ThreadServiceKey]
    val messageService = attributes[MessageServiceKey]

    routing {
        authenticate {
            // The guard order of every message route (chat-model.md "Guard order and locking"): 401 from `authenticate`; the per-user
            // `chat-post` bucket on the POST and the per-user `chat-write` bucket on every other write; visibility (`visibleStanding`:
            // 404 for a missing room and a non-member alike, audited `room.access_denied`); the pre-check (member for the POST, the
            // author or a remover for the writes: 403, a missing or deleted message is 404 first); the body (400); then the service
            // re-runs the guards under the room lock. Audit lines carry ids, never a body.
            messageReads(roomService, threadService, messageService)
            messagePost(roomService, messageService)
            messageEdit(roomService, messageService)
            messageDelete(roomService, messageService)
        }
    }
}

private fun Route.messageReads(roomService: RoomService, threadService: ThreadService, messageService: MessageService) {
    get<ThreadMessagesRoute> { route ->
        val roomId = route.parent.parent.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        val number = route.parent.number.toInt()
        val threadId = threadService.idOf(roomId, number).orNotFound("Thread")
        // Newest first by default, so the keyset (`id[lt]` on the smallest id loaded) is right without a `sort` parameter.
        val paging = call.parsePaging(sortable = MESSAGE_SORT_FIELDS, defaultSort = listOf(SortField("id", descending = true)))
        val idLt = call.request.queryParameters.optionalUInt("id[lt]")
        if (idLt != null && idLt !in 1u..MAX_MESSAGE_ID) throw BadRequestException("id[lt] must be between 1 and $MAX_MESSAGE_ID")
        val result = messageService.list(threadId, number, idLt, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<RoomMessagesRoute.Id> { route ->
        val roomId = route.parent.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        call.respond(HttpStatusCode.OK, messageService.read(roomId, route.messageId).orNotFound("Message"))
    }
}

/** The per-USER `chat-post` bucket (120/min, shared with the thread POST), inside `authenticate`, before every guard. */
private fun Route.messagePost(roomService: RoomService, messageService: MessageService) {
    rateLimit(RateLimitName(RateLimits.CHAT_POST)) {
        post<ThreadMessagesRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
            val request = sanitizedMessage(call.receive())
            validateMessage(request)
            val number = route.parent.number.toInt()
            val posted = messageService.post(roomId, number, caller, request)
            audit(
                "message.posted",
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "threadId" to posted.threadId.toLong(),
                "number" to number,
                "messageId" to posted.message.id.toLong(),
            )
            val location = RoomMessagesRoute.Id(RoomMessagesRoute(RoomsRoute.Id(id = roomId)), posted.message.id)
            call.response.header(HttpHeaders.Location, call.application.href(location))
            call.respond(HttpStatusCode.Created, posted.message)
        }
    }
}

/**
 * The edit (per-USER `chat-write` bucket, 240/min, before every guard), in the documented guard order: visibility (404, audited), the
 * author pre-check on a lock-free read of the author (a missing, deleted or foreign-room message is `404`, a caller who is not the
 * author `403`), the body (400); the service repeats the author guard on the fresh rows under the room lock, then the archived and
 * closed `409`s. The audit line follows the commit, only on a change.
 */
private fun Route.messageEdit(roomService: RoomService, messageService: MessageService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<RoomMessagesRoute.Id> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.id
            val standing = roomService.visibleStanding(caller, roomId)
            requireMessageAuthor(caller, standing, messageService.authorOf(roomId, route.messageId).orNotFound("Message"))
            val request = sanitizedMessage(call.receive())
            validateMessage(request)
            val edit = messageService.edit(roomId, route.messageId, caller, request)
            if (edit != null) {
                audit(
                    "message.edited",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to edit.threadId.toLong(),
                    "messageId" to edit.messageId.toLong(),
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The delete (`chat-write` bucket; D2: the author while a member, the room OWNER or an ADMIN): visibility (404), the remover pre-check
 * on a lock-free read of the author (404 when the message is missing, deleted or in another room; `403` otherwise), then the service
 * repeats the guard under the room lock, the archived and closed `409`s, and scrubs the body. The audit line follows the commit and
 * says whether it was moderated.
 */
private fun Route.messageDelete(roomService: RoomService, messageService: MessageService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        delete<RoomMessagesRoute.Id> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.id
            val standing = roomService.visibleStanding(caller, roomId)
            requireMessageRemover(caller, standing, messageService.authorOf(roomId, route.messageId).orNotFound("Message"))
            val removal = messageService.delete(roomId, route.messageId, caller)
            audit(
                "message.deleted",
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "threadId" to removal.threadId.toLong(),
                "messageId" to removal.messageId.toLong(),
                "authorId" to removal.authorId.toLong(),
                "moderated" to removal.moderated,
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
