package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.infra.revision.expectedRevision
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

/** `/api/v1/rooms/{id}/threads/{number}/tags`: the thread's curated tags (a child of [ThreadsRoute.Number]). */
@Serializable
@Resource("tags")
class ThreadTagsRoute(val parent: ThreadsRoute.Number)

fun Application.configureThreadTagRoutes() {
    val roomService = attributes[RoomServiceKey]
    val tagService = attributes[ThreadTagServiceKey]

    routing {
        authenticate {
            // The per-USER `chat-write` bucket (240/min, shared with the other thread writes) comes before every guard, then the order of
            // `chat-model.md` "Tags": 401 from `authenticate`; visibility (`visibleStanding`: 404 for a missing room and a non-member
            // alike, audited `room.access_denied`); a lock-free read of the thread (404) and, when it is CLOSED, the steward guard (403: on
            // an open thread any reader tags, on a closed one only its owner, the room OWNER or an ADMIN, 1.2 D1) BEFORE the header and
            // the body; `X-Expected-Revision` (400); the body (400: more than 20 names, judged before any is normalised; the grammar; a
            // repeat). The service then re-runs the guards under the room lock (thread 404, steward 403 on the fresh row, archived 409,
            // revision 409, a name that is no LIVE tag 400). The audit line follows the commit and only on a real change; the same set
            // again is a `204` that writes and audits nothing.
            rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
                put<ThreadTagsRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.parent.parent.id
                    val standing = roomService.visibleStanding(caller, roomId)
                    val number = route.parent.number.toInt()
                    val view = tagService.stewardView(roomId, number).orNotFound("Thread")
                    if (view.closed) requireThreadSteward(caller, standing, view.ownerId)
                    val expected = call.expectedRevision()
                    val request = normalizedThreadTags(call.receive())
                    validateThreadTags(request)
                    val change = tagService.replace(roomId, number, caller, expected, request)
                    if (change != null) {
                        // The registry's tag names are an ADMIN-curated vocabulary (the status-label precedent, 1.2 A5), not user content.
                        audit(
                            "thread.tags_changed",
                            "byUserId" to caller.userId.toLong(),
                            "roomId" to roomId.toLong(),
                            "threadId" to change.threadId.toLong(),
                            "number" to number,
                            "revision" to change.revision,
                            "addedCount" to change.diff.added.size,
                            "removedCount" to change.diff.removed.size,
                            "added" to change.diff.added.joinToString(","),
                            "removed" to change.diff.removed.joinToString(","),
                        )
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
