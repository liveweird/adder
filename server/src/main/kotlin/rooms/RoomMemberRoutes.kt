package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

fun Application.configureRoomMemberRoutes() {
    val roomService = attributes[RoomServiceKey]
    val memberService = attributes[RoomMemberServiceKey]

    routing {
        authenticate {
            // The guard order of every room route (chat-model.md "Rooms"): 401; visibility (404 for a missing room and for a non-member
            // alike); role (403); the body (400); then, under the room lock, the guard again, the 409 checks and the write. Membership
            // management is allowed on an ARCHIVED room.
            memberList(roomService, memberService)
            memberAdd(roomService, memberService)
            memberRole(roomService, memberService)
            memberRemove(roomService, memberService)
        }
    }
}

private fun Route.memberList(roomService: RoomService, memberService: RoomMemberService) {
    get<RoomsRoute.Id.Members> { route ->
        val caller = call.caller()
        roomService.visibleStanding(caller, route.parent.id)
        val paging = call.parsePaging(sortable = ROOM_MEMBER_SORT_FIELDS)
        val params = call.request.queryParameters
        val name = params.optionalString("name")
        if (name != null && name.codePointCount(0, name.length) > MAX_MEMBER_NAME_FILTER_LENGTH) {
            throw BadRequestException("name must be at most $MAX_MEMBER_NAME_FILTER_LENGTH characters")
        }
        val filter = RoomMemberListFilter(role = params.optionalEnum("role"), name = name)
        val result = memberService.list(route.parent.id, filter, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

private fun Route.memberAdd(roomService: RoomService, memberService: RoomMemberService) {
    // Per-USER `room-members` bucket (60/min): the add names inactive ids in its 400, so an id sweep is bounded (`security.md`).
    rateLimit(RateLimitName(RateLimits.ROOM_MEMBERS)) {
        post<RoomsRoute.Id.Members> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
            val request = call.receive<RoomMembersAddRequest>()
            validateRoomMembersAdd(request)
            val outcome = memberService.add(roomId, caller, managerGuard, request)
            // One line per person actually added; a person already in the room is counted in the response and audited nowhere.
            for (userId in outcome.addedUserIds) {
                audit(
                    "room.member_added",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "targetUserId" to userId.toLong(),
                    "viaTeamId" to (if (userId in outcome.viaTeamUserIds) request.teamId?.toLong() else null),
                )
            }
            call.respond(HttpStatusCode.OK, RoomMembersAddResponse(outcome.addedUserIds.size, outcome.alreadyMembers))
        }
    }
}

private fun Route.memberRole(roomService: RoomService, memberService: RoomMemberService) {
    put<RoomsRoute.Id.Members.User> { route ->
        val caller = call.caller()
        val roomId = route.parent.parent.id
        requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
        val request = call.receive<RoomMemberRoleRequest>()
        // Idempotent: the same role again is a 204 with no write and no audit line.
        val roleFrom = memberService.setRole(roomId, route.userId, caller, managerGuard, request.role)
        if (roleFrom != null) {
            audit(
                "room.member_role_changed",
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "targetUserId" to route.userId.toLong(),
                "roleFrom" to roleFrom.name,
                "roleTo" to request.role.name,
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.memberRemove(roomService: RoomService, memberService: RoomMemberService) {
    delete<RoomsRoute.Id.Members.User> { route ->
        val caller = call.caller()
        val roomId = route.parent.parent.id
        val self = route.userId == caller.userId
        val standing = roomService.visibleStanding(caller, roomId)
        // Leaving needs only the reader's standing (a plain member may leave); removing someone else is a manager's act.
        if (!self) requireRoomManager(caller, standing)
        memberService.remove(roomId, route.userId, caller, if (self) readerGuard else managerGuard)
        audit(
            "room.member_removed",
            "byUserId" to caller.userId.toLong(),
            "roomId" to roomId.toLong(),
            "targetUserId" to route.userId.toLong(),
            "self" to self,
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
