package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

fun Application.configureRoomEnrollmentRoutes() {
    val rooms = attributes[RoomServiceKey]
    val enrollment = attributes[RoomEnrollmentServiceKey]
    routing {
        authenticate {
            roomAccess(rooms, enrollment)
            roomJoin(rooms, enrollment)
            roomJoinRequests(rooms, enrollment)
        }
    }
}

private fun Route.roomAccess(rooms: RoomService, enrollment: RoomEnrollmentService) {
    get<RoomsRoute.Id.Access> { route ->
        val caller = call.caller()
        rooms.discoverableStanding(caller, route.parent.id)
        call.respond(HttpStatusCode.OK, enrollment.access(route.parent.id, caller.userId).orNotFound("Room"))
    }
}

private fun Route.roomJoin(rooms: RoomService, enrollment: RoomEnrollmentService) {
    rateLimit(RateLimitName(RateLimits.ROOM_MEMBERS)) {
        post<RoomsRoute.Id.Join> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            rooms.discoverableStanding(caller, roomId)
            if (enrollment.join(roomId, caller)) {
                audit(
                    "room.member_added",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "targetUserId" to caller.userId.toLong(),
                    "viaTeamId" to null,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.roomJoinRequests(rooms: RoomService, enrollment: RoomEnrollmentService) {
    rateLimit(RateLimitName(RateLimits.ROOM_MEMBERS)) {
        post<RoomsRoute.Id.JoinRequests> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            rooms.discoverableStanding(caller, roomId)
            if (enrollment.requestJoin(roomId, caller)) {
                audit("room.join_requested", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    get<RoomsRoute.Id.JoinRequests> { route ->
        val caller = call.caller()
        val roomId = route.parent.id
        requireRequestOwner(rooms.visibleStanding(caller, roomId))
        val paging = call.parsePaging(sortable = ROOM_JOIN_REQUEST_SORT_FIELDS)
        val result = enrollment.list(roomId, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    rateLimit(RateLimitName(RateLimits.ROOM_MEMBERS)) {
        put<RoomsRoute.Id.JoinRequests.Request> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.id
            requireRequestOwner(rooms.visibleStanding(caller, roomId))
            val request = call.receive<RoomJoinDecisionRequest>()
            val outcome = enrollment.decide(roomId, route.requestId, caller, request)
            val event = if (outcome.decision == RoomJoinDecision.APPROVE) "room.join_request_approved" else "room.join_request_rejected"
            audit(
                event,
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "requestId" to route.requestId.toLong(),
                "targetUserId" to outcome.userId.toLong(),
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun requireRequestOwner(standing: ch.nokillswit.authz.RoomStanding) {
    if (standing.role != RoomRole.OWNER || standing.accessType != RoomAccessType.RESTRICTED_VISIBLE) throw ForbiddenException()
}
