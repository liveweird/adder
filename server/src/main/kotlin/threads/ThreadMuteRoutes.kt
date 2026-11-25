package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("mute")
internal class ThreadMuteRoute(val parent: ThreadsRoute.Number)

fun Application.configureThreadMuteRoutes() {
    val rooms = attributes[RoomServiceKey]
    val mutes = attributes[ThreadMuteServiceKey]
    routing {
        authenticate {
            // Watch and mute writes share the caller's personal thread settings budget.
            rateLimit(RateLimitName(RateLimits.THREAD_WATCH)) {
                put<ThreadMuteRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.parent.parent.id
                    requireRoomMember(caller, rooms.visibleStanding(caller, roomId))
                    val request = call.receive<ThreadMuteRequest>()
                    val threadId = mutes.setMuted(roomId, route.parent.number.toInt(), caller.userId, request.muted)
                    if (threadId != null) {
                        audit("thread.mute_changed", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong(),
                            "threadId" to threadId.toLong(), "muted" to request.muted)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
