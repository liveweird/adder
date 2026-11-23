package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.revision.expectedRevision
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/room-organization")
class RoomOrganizationRoute

internal fun Route.roomOrganizationRoutes(service: RoomOrganizationService) {
    get<RoomOrganizationRoute> {
        call.respond(HttpStatusCode.OK, service.read(call.caller().userId))
    }
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<RoomOrganizationRoute> {
            val caller = call.caller()
            val expected = call.expectedRevision()
            val request = call.receive<RoomOrganizationUpdateRequest>()
            val result = service.replace(caller.userId, expected, request)
            if (result.changed) {
                audit(
                    "room.organization_changed",
                    "byUserId" to caller.userId.toLong(),
                    "groupCount" to result.response.groups.size,
                    "roomCount" to result.response.rooms.size,
                )
            }
            call.respond(HttpStatusCode.OK, result.response)
        }
    }
}
