package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.infra.JsonInt
import ch.nokillswit.infra.mutationId
import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalBoundedString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.singleValue
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.revision.expectedRevision
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomsRoute
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.delete
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
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

        @Serializable
        @Resource("status")
        class Status(val parent: Number)

        @Serializable
        @Resource("owner")
        class Owner(val parent: Number)

        @Serializable
        @Resource("merge")
        class Merge(val parent: Number)
    }
}

/** `/api/v1/rooms/{id}/tags`: the room's hashtags with their thread counts (a child of [RoomsRoute.Id]). */
@Serializable
@Resource("tags")
class RoomTagsRoute(val parent: RoomsRoute.Id)

/** `/api/v1/rooms/{id}/hashtags`: the clearer path alias of [RoomTagsRoute]. */
@Serializable
@Resource("hashtags")
class RoomHashtagsRoute(val parent: RoomsRoute.Id)

fun Application.configureThreadRoutes() {
    val roomService = attributes[RoomServiceKey]
    val threadService = attributes[ThreadServiceKey]
    val statusService = attributes[ThreadStatusServiceKey]
    val tagService = attributes[MessageTagServiceKey]
    val lifecycle = attributes[ThreadLifecycleServiceKey]

    routing {
        authenticate {
            // The guard order of every thread route (chat-model.md "Guard order and locking"): 401 from `authenticate`; the per-user
            // `chat-post` bucket on the POST, the per-user `chat-write` bucket on every other write and the per-user `chat-read` bucket on
            // the thread and tag lists; visibility (`visibleStanding`:
            // 404 for a missing room and a non-member alike, audited `room.access_denied`); the pre-check (member: 403); the body
            // (400); then the service re-runs the guard under the room lock.
            threadReads(roomService, threadService)
            roomTags(roomService, tagService)
            threadCreate(roomService, threadService)
            threadDetails(roomService, threadService)
            threadStatus(roomService, statusService)
            threadOwner(roomService, threadService, statusService)
            threadLifecycle(roomService, threadService, lifecycle)
        }
    }
}

@Serializable
private data class ThreadMergeRequest(val targetNumber: JsonInt)

private fun Route.threadLifecycle(roomService: RoomService, threads: ThreadService, lifecycle: ThreadLifecycleService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        delete<ThreadsRoute.Number> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.id
            val number = route.number.toInt()
            val standing = roomService.visibleStanding(caller, roomId)
            requireThreadSteward(caller, standing, threads.ownerOf(roomId, number).orNotFound("Thread"))
            val threadId = lifecycle.delete(roomId, number, caller)
            audit("thread.deleted", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong(), "threadId" to threadId.toLong())
            call.respond(HttpStatusCode.NoContent)
        }
        put<ThreadsRoute.Number.Merge> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            val number = route.parent.number.toInt()
            val standing = roomService.visibleStanding(caller, roomId)
            requireThreadSteward(caller, standing, threads.ownerOf(roomId, number).orNotFound("Thread"))
            val request = call.receive<ThreadMergeRequest>()
            requireDistinctMergeTarget(number, request.targetNumber)
            val threadId = lifecycle.merge(roomId, number, request.targetNumber, caller) { call.mutationId() }
            if (threadId != null) {
                audit(
                    "thread.merged",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to threadId.toLong(),
                    "targetNumber" to request.targetNumber,
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun Route.threadReads(roomService: RoomService, threadService: ThreadService) {
    // The thread list rides the per-USER `chat-read` bucket (with the tag list), before the visibility guard like every bucket.
    rateLimit(RateLimitName(RateLimits.CHAT_READ)) {
        get<ThreadsRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            roomService.visibleStanding(caller, roomId)
            val paging = call.parsePaging(sortable = THREAD_SORT_FIELDS)
            val params = call.request.queryParameters
            val filter = params.threadListFilter(
                params.optionalBoolean("closed"),
                params.optionalBoolean("watching"),
                params.optionalBoolean("mentionsMe"),
                params.optionalBoolean("unread"),
            )
            val result = threadService.list(caller.userId, roomId, filter, paging)
            call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
        }
    }
    get<ThreadsRoute.Number> { route ->
        val roomId = route.parent.parent.id
        val caller = call.caller()
        roomService.visibleStanding(caller, roomId)
        call.respond(HttpStatusCode.OK, threadService.read(roomId, route.number.toInt(), caller.userId).orNotFound("Thread"))
    }
    get<ThreadsRoute.Number.Events> { route ->
        val roomId = route.parent.parent.parent.id
        roomService.visibleStanding(call.caller(), roomId)
        val threadId = threadService.idOf(roomId, route.parent.number.toInt()).orNotFound("Thread")
        val paging = call.parsePaging(sortable = EVENT_LOG_SORT_FIELDS, defaultSort = EVENT_LOG_DEFAULT_SORT)
        val result = threadService.history(roomId, threadId, paging, call.request.queryParameters.historyWindow())
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

private fun Parameters.historyWindow(): LongRange? {
    fun bound(name: String): Long? = singleValue(name)?.let { raw ->
        raw.toLongOrNull()?.takeIf { it >= 0 } ?: throw BadRequestException("$name must be a non-negative epoch millisecond")
    }
    val from = bound("createdAt[gte]")
    val to = bound("createdAt[lte]")
    if (from == null && to == null) return null
    val window = (from ?: 0)..(to ?: Long.MAX_VALUE)
    if (window.isEmpty()) throw BadRequestException("createdAt[gte] must not be after createdAt[lte]")
    return window
}

/**
 * `GET /rooms/{id}/tags` and its `/hashtags` alias (reader, one per-USER `chat-read` bucket shared with the thread list): the guard order
 * of every read - 401, the bucket, visibility (404), then the `400`s (paging, then the substring). The substring is at most
 * [MAX_TAG_FILTER_LENGTH] characters (a longer one could match no tag). Both registrations call one handler so their behavior cannot
 * drift.
 */
private fun Route.roomTags(roomService: RoomService, tagService: MessageTagService) {
    rateLimit(RateLimitName(RateLimits.CHAT_READ)) {
        get<RoomTagsRoute> { route ->
            call.respondRoomTags(route.parent.id, roomService, tagService)
        }
        get<RoomHashtagsRoute> { route ->
            call.respondRoomTags(route.parent.id, roomService, tagService)
        }
    }
}

private suspend fun ApplicationCall.respondRoomTags(roomId: UInt, roomService: RoomService, tagService: MessageTagService) {
    val caller = caller()
    roomService.visibleStanding(caller, roomId)
    val paging = parsePaging(sortable = ROOM_TAG_SORT_FIELDS, defaultSort = ROOM_TAG_DEFAULT_SORT)
    val q = request.queryParameters.optionalBoundedString("q", MAX_TAG_FILTER_LENGTH)
    val result = tagService.list(caller.userId, roomId, RoomTagListFilter(q), paging)
    respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
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
            auditNoticesCreated(caller.userId, roomId, created.id, created.messageId, created.notices)
            val location = ThreadsRoute.Number(ThreadsRoute(RoomsRoute.Id(id = roomId)), created.number.toUInt())
            call.response.header(HttpHeaders.Location, call.application.href(location))
            val thread = threadService.readCreated(roomId, created.number, caller.userId).orVanished("Thread", created.number)
            call.respond(HttpStatusCode.Created, thread)
        }
    }
}

/**
 * The details PUT (per-USER `chat-write` bucket, 240/min, before every guard), in the documented guard order: visibility (404,
 * audited), the `X-Expected-Revision` header (400), the body (400); the service then re-runs the reader guard and every state check
 * under the room lock (thread 404, archived, closed, revision: 409, the kernel's 400s). Any reader edits the OPEN-side metadata, so
 * there is no 403 here. The audit line is emitted after the commit, only when something changed (a no-op writes and audits nothing),
 * with ids and keys, never the title or a value.
 */
private fun Route.threadDetails(roomService: RoomService, threadService: ThreadService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<ThreadsRoute.Number> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.id
            roomService.visibleStanding(caller, roomId)
            val expected = call.expectedRevision()
            val mutationId = call.mutationId()
            val request = sanitizedThreadUpdate(call.receive())
            validateThreadUpdate(request)
            val number = route.number.toInt()
            val change = threadService.updateDetails(roomId, number, caller, expected, request, mutationId)
            if (change != null) {
                audit(
                    "thread.updated",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to change.threadId.toLong(),
                    "number" to number,
                    "revision" to change.revision,
                    "titleChanged" to change.titleChanged,
                    "fieldsChanged" to change.fieldKeys.joinToString(","),
                )
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The status PUT (`chat-write` bucket): visibility (404), the optional mutation header and the body (400), then everything else under
 * the room lock - the one
 * body-dependent guard, because whether a steward is needed depends on the target's CATEGORY, so the service resolves the target
 * (400), runs the steward rule (403) and the archived 409 on the fresh rows. Any reader may move a thread between OPEN statuses. The
 * audit line follows the commit, on a change.
 */
private fun Route.threadStatus(roomService: RoomService, statusService: ThreadStatusService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<ThreadsRoute.Number.Status> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            roomService.visibleStanding(caller, roomId)
            val mutationId = call.mutationId()
            val request = call.receive<ThreadStatusRequest>()
            val number = route.parent.number.toInt()
            val move = statusService.setStatus(roomId, number, caller, request.status, mutationId)
            if (move != null) {
                audit(
                    "thread.status_changed",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to move.threadId.toLong(),
                    "number" to number,
                    "from" to move.from,
                    "to" to move.to,
                    "transition" to move.transition.name,
                )
                auditNoticesCreated(caller.userId, roomId, move.threadId, null, move.notices)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * The owner PUT (`chat-write` bucket): visibility (404), the steward pre-check on a lock-free read of the owner (a missing thread is
 * `404`, a non-steward `403`), the body (400); the service repeats the steward guard on the fresh row under the room lock, then the
 * archived and closed 409s and the new owner's membership (400). The audit line follows the commit, on a change.
 */
private fun Route.threadOwner(roomService: RoomService, threadService: ThreadService, statusService: ThreadStatusService) {
    rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
        put<ThreadsRoute.Number.Owner> { route ->
            val caller = call.caller()
            val roomId = route.parent.parent.parent.id
            val standing = roomService.visibleStanding(caller, roomId)
            val number = route.parent.number.toInt()
            requireThreadSteward(caller, standing, threadService.ownerOf(roomId, number).orNotFound("Thread"))
            val mutationId = call.mutationId()
            val request = call.receive<ThreadOwnerRequest>()
            val move = statusService.setOwner(roomId, number, caller, request.ownerId, mutationId)
            if (move != null) {
                audit(
                    "thread.owner_changed",
                    "byUserId" to caller.userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "threadId" to move.threadId.toLong(),
                    "number" to number,
                    "fromUserId" to move.fromUserId.toLong(),
                    "toUserId" to move.toUserId.toLong(),
                )
                auditNoticesCreated(caller.userId, roomId, move.threadId, null, move.notices)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
