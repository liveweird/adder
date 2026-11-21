package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.plugins.RateLimits
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
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/threads/{number}/watch`: the CALLER's own watch of one thread (a child of [ThreadsRoute.Number]). */
@Serializable
@Resource("watch")
class ThreadWatchRoute(val parent: ThreadsRoute.Number)

fun Application.configureThreadWatchRoutes() {
    val roomService = attributes[RoomServiceKey]
    val watchService = attributes[ThreadWatchServiceKey]

    routing {
        authenticate {
            // The per-USER `thread-watch` bucket (30/min, its own: the write is audited) comes before every guard, then the
            // documented order: 401 from `authenticate`; visibility (`visibleStanding`: 404 for a missing room and a non-member alike,
            // audited `room.access_denied`); the member guard (403: an ADMIN outside the room cannot watch) BEFORE the body; the body
            // (400); then the service holds the caller's own membership row (404 when it is gone), finds the thread (404) and writes.
            // Own state: no room lock, no live hint (`chat-model.md` "Watching (V26)"). 204 whether the state changed or not; the audit
            // line follows the commit and only on a change, because a watch changes who a later write notifies.
            rateLimit(RateLimitName(RateLimits.THREAD_WATCH)) {
                put<ThreadWatchRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.parent.parent.id
                    requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
                    val request = call.receive<WatchRequest>()
                    val number = route.parent.number.toInt()
                    val change = watchService.setWatching(roomId, number, caller.userId, request.watching)
                    if (change != null) {
                        audit(
                            "thread.watch_changed",
                            "byUserId" to caller.userId.toLong(),
                            "roomId" to roomId.toLong(),
                            "threadId" to change.threadId.toLong(),
                            "number" to number,
                            "watching" to request.watching,
                        )
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
