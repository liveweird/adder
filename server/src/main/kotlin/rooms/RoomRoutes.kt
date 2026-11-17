package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.users.DEFAULT_LANGUAGE
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

@Serializable
@Resource("/api/v1/rooms")
class RoomsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: RoomsRoute = RoomsRoute(), val id: UInt) {
        @Serializable
        @Resource("archive")
        class Archive(val parent: Id)

        @Serializable
        @Resource("unarchive")
        class Unarchive(val parent: Id)

        @Serializable
        @Resource("workflow")
        class Workflow(val parent: Id)
    }
}

fun Application.configureRoomRoutes() {
    val roomService = attributes[RoomServiceKey]

    routing {
        authenticate {
            // The guard order of every room route (chat-model.md "Rooms"): 401 from `authenticate`; visibility (`visibleStanding`: 404
            // for a missing room and for a non-member alike, `room.access_denied` audited for a room that exists); role (403); then the
            // header and the body (400); then the service re-runs the guard under the room lock before it writes.
            roomReads(roomService)
            roomCreate(roomService)
            roomWrites(roomService)
        }
    }
}

private fun Route.roomReads(roomService: RoomService) {
    get<RoomsRoute> {
        val caller = call.caller()
        val paging = call.parsePaging(sortable = ROOM_SORT_FIELDS)
        val params = call.request.queryParameters
        val name = params.optionalString("name")
        if (name != null && name.length > MAX_ROOM_NAME_LENGTH) {
            throw BadRequestException("name must be at most $MAX_ROOM_NAME_LENGTH characters")
        }
        val filter = RoomListFilter(name = name, archived = params.optionalBoolean("archived"), member = params.optionalBoolean("member"))
        val result = roomService.list(caller.userId, caller.isAdmin(), filter, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<RoomsRoute.Id> { route ->
        val caller = call.caller()
        roomService.visibleStanding(caller, route.id)
        // A delete can land between the visibility check and the read: that is a plain 404, not a server fault.
        call.respond(HttpStatusCode.OK, roomService.read(route.id, caller.userId).orNotFound("Room"))
    }
}

/** The one endpoint where any user mints name-reserving state: a per-USER bucket (`room-create`, 30/min), mounted inside `authenticate`. */
private fun Route.roomCreate(roomService: RoomService) {
    rateLimit(RateLimitName(RateLimits.ROOM_CREATE)) {
        post<RoomsRoute> {
            val caller = call.caller()
            val request = sanitizedRoomCreate(call.receive())
            validateRoomCreate(request)
            val id = roomService.create(caller.userId, request)
            audit(
                "room.created",
                "byUserId" to caller.userId.toLong(),
                "roomId" to id.toLong(),
                "name" to request.name,
                "templateId" to request.templateId.toLong(),
                "language" to (request.language ?: DEFAULT_LANGUAGE),
            )
            call.response.header(HttpHeaders.Location, call.application.href(RoomsRoute.Id(id = id)))
            call.respond(HttpStatusCode.Created, roomService.read(id, caller.userId).orVanished("Room", id))
        }
    }
}

private fun Route.roomWrites(roomService: RoomService) {
    put<RoomsRoute.Id> { route ->
        val caller = call.caller()
        requireRoomManager(caller, roomService.visibleStanding(caller, route.id))
        val request = sanitizedRoomUpdate(call.receive())
        validateRoomUpdate(request)
        roomService.update(route.id, caller.userId, managerGuard(caller), request)
        audit("room.updated", "byUserId" to caller.userId.toLong(), "roomId" to route.id.toLong(), "name" to request.name)
        call.respond(HttpStatusCode.NoContent)
    }
    post<RoomsRoute.Id.Archive> { route ->
        val caller = call.caller()
        val roomId = route.parent.id
        requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
        // Idempotent: archiving an archived room is a 204 with no write and no audit line.
        if (roomService.setArchived(roomId, caller.userId, managerGuard(caller), archived = true)) {
            audit("room.archived", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
        }
        call.respond(HttpStatusCode.NoContent)
    }
    post<RoomsRoute.Id.Unarchive> { route ->
        val caller = call.caller()
        val roomId = route.parent.id
        requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
        if (roomService.setArchived(roomId, caller.userId, managerGuard(caller), archived = false)) {
            audit("room.unarchived", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
        }
        call.respond(HttpStatusCode.NoContent)
    }
    delete<RoomsRoute.Id> { route ->
        val caller = call.caller()
        // Reader first (a non-member gets the 404 and cannot learn that the room exists), THEN the ADMIN-only 403.
        roomService.visibleStanding(caller, route.id)
        requireAdmin(caller)
        roomService.delete(route.id, caller.userId) { standing ->
            requireRoomReader(caller, standing)
            requireAdmin(caller)
        }
        audit("room.deleted", "byUserId" to caller.userId.toLong(), "roomId" to route.id.toLong())
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The guard a room write re-runs on the standing it re-reads under the room lock. */
private fun managerGuard(caller: CallerPrincipal): (RoomStanding?) -> Unit = { standing -> requireRoomManager(caller, standing) }
