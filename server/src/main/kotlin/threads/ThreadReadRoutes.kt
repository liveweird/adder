package ch.nokillswit.threads

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.plugins.RateLimits
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
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/threads/{number}/read-marker`: the CALLER's own read marker in one thread (a child of [ThreadsRoute.Number]). */
@Serializable
@Resource("read-marker")
class ThreadReadMarkerRoute(val parent: ThreadsRoute.Number)

/** `/api/v1/rooms/{id}/read-all`: "Mark all as read" of the CALLER in one room (a child of [RoomsRoute.Id]). */
@Serializable
@Resource("read-all")
class RoomReadAllRoute(val parent: RoomsRoute.Id)

fun Application.configureThreadReadRoutes() {
    val roomService = attributes[RoomServiceKey]
    val readService = attributes[ThreadReadServiceKey]

    routing {
        authenticate {
            // The per-USER `read-state` bucket (240/min, shared with the other "seen" writes) comes before every guard, then the documented
            // order: 401 from `authenticate`; visibility (`visibleStanding`: 404 for a missing room and a non-member alike, audited
            // `room.access_denied`); the member guard (403: an ADMIN outside the room has no read state); the body (400); then the
            // service answers the thread, the message and the membership with a 404. View state: no audit line, no hint, no room lock
            // (`observability.md`, `chat-model.md` "Unread (V19)"). 204 whether the marker moved or not.
            rateLimit(RateLimitName(RateLimits.READ_STATE)) {
                put<ThreadReadMarkerRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.parent.parent.id
                    requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
                    val request = call.receive<ReadMarkerRequest>()
                    requireReadMarkerMessageId(request.messageId)
                    readService.markRead(roomId, route.parent.number.toInt(), caller.userId, request.messageId)
                    call.respond(HttpStatusCode.NoContent)
                }
                // "Mark all as read" (1.2): the same order up to the member guard (there is no body), then the caller's own floor and
                // notices (`ThreadReadService.markRoomRead`: one in flight per person, else the typed 429 `read-busy`). 204 whether
                // anything was unread or not; an archived room is marked too. NO live hint. The service writes ONE audit line
                // `room.read_all` for a call that settled at least one notice (`complete=true`) or failed or was cancelled after the floor
                // was raised (`complete=false`); a no-op call and a refusal (403, 404) write none, and the in-flight 429 writes its own
                // coalesced `read.busy` line (`resource=read-all`).
                post<RoomReadAllRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.id
                    requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
                    readService.markRoomRead(roomId, caller.userId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
