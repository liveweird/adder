package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalBoundedString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.isUniqueViolation
import ch.nokillswit.users.DEFAULT_LANGUAGE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
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

        @Serializable
        @Resource("seen")
        class Seen(val parent: Id)

        @Serializable
        @Resource("members")
        class Members(val parent: Id) {
            @Serializable
            @Resource("{userId}")
            class User(val parent: Members, val userId: UInt)
        }
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
        val filter = RoomListFilter(
            name = params.optionalBoundedString("name", MAX_ROOM_NAME_LENGTH),
            archived = params.optionalBoolean("archived"),
            member = params.optionalBoolean("member"),
        )
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

/** A write into the room-name namespace: the per-USER `room-name` bucket (30/min, shared by create and rename), inside `authenticate`. */
private fun Route.roomCreate(roomService: RoomService) {
    rateLimit(RateLimitName(RateLimits.ROOM_NAME)) {
        post<RoomsRoute> {
            val caller = call.caller()
            val request = sanitizedRoomCreate(call.receive())
            validateRoomCreate(request)
            val id = auditingNameConflict(caller.userId, roomId = null) { roomService.create(caller.userId, request) }
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
    // The rename is the second write into the room-name namespace: it rides the SAME per-user bucket as the create (a free 409 on
    // a clash would otherwise be an unbounded "does a room with this name exist" probe).
    rateLimit(RateLimitName(RateLimits.ROOM_NAME)) {
        put<RoomsRoute.Id> { route ->
            val caller = call.caller()
            requireRoomManager(caller, roomService.visibleStanding(caller, route.id))
            val request = sanitizedRoomUpdate(call.receive())
            validateRoomUpdate(request)
            auditingNameConflict(caller.userId, route.id) { roomService.update(route.id, caller, managerGuard, request) }
            audit("room.updated", "byUserId" to caller.userId.toLong(), "roomId" to route.id.toLong(), "name" to request.name)
            call.respond(HttpStatusCode.NoContent)
        }
    }
    // The room-lock writes that are not a name write (archive, unarchive, delete) ride the per-USER `chat-write` bucket (240/min, the
    // thread and message writes' bucket), BEFORE every guard: each takes the room's write lock and a pooled connection while it waits.
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        post<RoomsRoute.Id.Archive> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
            // Idempotent: archiving an archived room is a 204 with no write and no audit line.
            if (roomService.setArchived(roomId, caller, managerGuard, archived = true)) {
                audit("room.archived", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post<RoomsRoute.Id.Unarchive> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
            if (roomService.setArchived(roomId, caller, managerGuard, archived = false)) {
                audit("room.unarchived", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
            }
            call.respond(HttpStatusCode.NoContent)
        }
        delete<RoomsRoute.Id> { route ->
            val caller = call.caller()
            // Reader first (a non-member gets the 404 and cannot learn that the room exists), THEN the ADMIN-only 403.
            roomService.visibleStanding(caller, route.id)
            requireAdmin(caller)
            roomService.delete(route.id, caller) { fresh, standing ->
                requireRoomReader(fresh, standing)
                requireAdmin(fresh)
            }
            audit("room.deleted", "byUserId" to caller.userId.toLong(), "roomId" to route.id.toLong())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Runs [block] (a write that can clash on the room name) and audits a name clash as `room.name_conflict` (who, and which room for a
 * rename; null for a create) before the central 23505 -> 409 mapping answers. The line is the trace of someone using the 409 as a
 * "does a room with this name exist" oracle; the per-user `room-name` bucket bounds how often it can happen.
 */
private suspend fun <T> auditingNameConflict(byUserId: UInt, roomId: UInt?, block: suspend () -> T): T = catchingFailures(
    { block() },
    {
        if (it.isUniqueViolation()) {
            audit("room.name_conflict", "byUserId" to byUserId.toLong(), "roomId" to roomId?.toLong())
        }
        throw it
    },
)
