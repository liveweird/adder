package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
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
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/threads`: the room's threads (a child of [RoomsRoute.Id]); a thread is addressed by its per-room number. */
@Serializable
@Resource("threads")
class ThreadsRoute(val parent: RoomsRoute.Id) {
    @Serializable
    @Resource("{number}")
    class Number(val parent: ThreadsRoute, val number: UInt) {
        @Serializable
        @Resource("events")
        class Events(val parent: Number)
    }
}

fun Application.configureThreadRoutes() {
    val roomService = attributes[RoomServiceKey]
    val threadService = attributes[ThreadServiceKey]
    val eventService = attributes[ThreadEventServiceKey]

    routing {
        authenticate {
            // The guard order of every thread route (chat-model.md "Guard order and locking"): 401 from `authenticate`; the per-user
            // `chat-post` bucket on the POST; visibility (`visibleStanding`: 404 for a missing room and a non-member alike, audited
            // `room.access_denied`); the pre-check (member: 403); the body (400); then the service re-runs the guard under the room lock.
            threadReads(roomService, threadService, eventService)
            threadCreate(roomService, threadService)
        }
    }
}

private fun Route.threadReads(roomService: RoomService, threadService: ThreadService, eventService: ThreadEventService) {
    get<ThreadsRoute> { route ->
        val roomId = route.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        val paging = call.parsePaging(sortable = THREAD_SORT_FIELDS)
        val result = threadService.list(roomId, call.request.queryParameters.optionalBoolean("closed"), paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<ThreadsRoute.Number> { route ->
        val roomId = route.parent.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        call.respond(HttpStatusCode.OK, threadService.read(roomId, route.number.toInt()).orNotFound("Thread"))
    }
    get<ThreadsRoute.Number.Events> { route ->
        val roomId = route.parent.parent.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        val threadId = threadService.idOf(roomId, route.parent.number.toInt()).orNotFound("Thread")
        val paging = call.parsePaging(sortable = EVENT_LOG_SORT_FIELDS, defaultSort = EVENT_LOG_DEFAULT_SORT)
        val result = eventService.list(threadId, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

/** The per-USER `chat-post` bucket (120/min, shared with the message POST), inside `authenticate`, before every guard. */
private fun Route.threadCreate(roomService: RoomService, threadService: ThreadService) {
    rateLimit(RateLimitName(RateLimits.CHAT_POST)) {
        post<ThreadsRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            requireRoomMember(caller, roomService.visibleStanding(caller, roomId))
            val request = sanitizedThreadCreate(call.receive())
            validateThreadCreate(request)
            val created = threadService.create(roomId, caller, request)
            // The opening message is part of the create: one `thread.created` line names it, with no separate `message.posted`.
            // Ids and counts only, never the title, the body or a field value.
            audit(
                "thread.created",
                "byUserId" to caller.userId.toLong(),
                "roomId" to roomId.toLong(),
                "threadId" to created.id.toLong(),
                "number" to created.number,
                "messageId" to created.messageId.toLong(),
            )
            val location = ThreadsRoute.Number(ThreadsRoute(RoomsRoute.Id(id = roomId)), created.number.toUInt())
            call.response.header(HttpHeaders.Location, call.application.href(location))
            call.respond(HttpStatusCode.Created, threadService.read(roomId, created.number).orVanished("Thread", created.number))
        }
    }
}
