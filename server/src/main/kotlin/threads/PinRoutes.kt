package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomsRoute
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/pins`: the room's pinned threads and messages (a child of [RoomsRoute.Id]). */
@Serializable
@Resource("pins")
class RoomPinsRoute(val parent: RoomsRoute.Id)

/** `/api/v1/rooms/{id}/threads/{number}/pin`: whether the thread is pinned (a child of [ThreadsRoute.Number]). */
@Serializable
@Resource("pin")
class ThreadPinRoute(val parent: ThreadsRoute.Number)

/** `/api/v1/rooms/{id}/messages/{messageId}/pin`: whether the message is pinned (a child of [RoomMessagesRoute.Id]). */
@Serializable
@Resource("pin")
class MessagePinRoute(val parent: RoomMessagesRoute.Id)

/** Newest pin first; `-id` breaks a tie of one millisecond (ids are allocated under the room lock, so a later pin has the larger). */
private val PIN_DEFAULT_SORT = listOf(SortField("pinnedAt", descending = true), SortField("id", descending = true))

fun Application.configurePinRoutes() {
    val roomService = attributes[RoomServiceKey]
    val pinService = attributes[PinServiceKey]

    routing {
        authenticate {
            // The guard order of the pin routes (chat-model.md "Pins"): 401 from `authenticate`; the per-user `chat-write` bucket on the
            // two PUTs; visibility (`visibleStanding`: 404 for a missing room and a non-member alike, audited `room.access_denied`); the
            // member guard (403); the body (400); then the service re-runs the guard under the room lock. Audit lines carry ids only.
            pinList(roomService, pinService)
            threadPin(roomService, pinService)
            messagePin(roomService, pinService)
        }
    }
}

/** `GET /rooms/{id}/pins`: a reader (a member or an ADMIN); at most 50 rows exist, so the unbucketed read is bounded. */
private fun Route.pinList(roomService: RoomService, pinService: PinService) {
    get<RoomPinsRoute> { route ->
        val roomId = route.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        val paging = call.parsePaging(sortable = PIN_SORT_FIELDS, defaultSort = PIN_DEFAULT_SORT)
        val result = pinService.list(roomId, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

/**
 * The thread pin PUT (`chat-write` bucket, before every guard): visibility (404), the member guard (403, an ADMIN outside the room
 * included), the body (400); the service then takes the room lock and re-runs the guard, the thread (404), the archived 409, the state
 * it already has (204, nothing written) and the 51st pin (409). The audit line follows the commit, only on a change.
 */
private fun Route.threadPin(roomService: RoomService, pinService: PinService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<ThreadPinRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
            val request = call.receive<PinRequest>()
            val number = route.parent.number.toInt()
            val change = pinService.setThreadPin(roomId, number, caller, request.pinned)
            if (change != null) {
                audit(
                    "room.pin_changed",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to change.threadId.toLong(),
                    "messageId" to null,
                    "pinned" to change.pinned,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The message pin PUT: the order of [threadPin]; the message (404 unless live and of the room) is the first lookup under the lock. */
private fun Route.messagePin(roomService: RoomService, pinService: PinService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<MessagePinRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
            val request = call.receive<PinRequest>()
            val change = pinService.setMessagePin(roomId, route.parent.messageId, caller, request.pinned)
            if (change != null) {
                audit(
                    "room.pin_changed",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to change.threadId.toLong(),
                    "messageId" to change.messageId?.toLong(),
                    "pinned" to change.pinned,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
