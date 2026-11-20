package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.infra.revision.expectedRevision
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

fun Application.configureRoomWorkflowRoutes() {
    val roomService = attributes[RoomServiceKey]
    val workflowService = attributes[RoomWorkflowServiceKey]

    routing {
        authenticate {
            // The room's own workflow: a reader (member or ADMIN) sees it, everyone else the uniform 404 (`visibleStanding`).
            get<RoomsRoute.Id.Workflow> { route ->
                val roomId = route.parent.id
                roomService.visibleStanding(call.caller(), roomId)
                call.respond(HttpStatusCode.OK, workflowService.read(roomId, call.workflowUsage()).orNotFound("Room"))
            }
            // The whole-document replace, in the documented guard order (chat-model.md "Rooms"): visibility 404, manager 403, the
            // `X-Expected-Revision` header 400, the body 400; the service then re-runs the guard and every state check (409) under the
            // room lock. The audit line is emitted after the transaction commits.
            // It rides the per-USER `chat-write` bucket (240/min) before every guard: it takes the room's write lock (`security.md`).
            rateLimit(RateLimitName(RateLimits.CHAT_WRITE)) {
                put<RoomsRoute.Id.Workflow> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.id
                    requireRoomManager(caller, roomService.visibleStanding(caller, roomId))
                    val expected = call.expectedRevision()
                    val request = sanitizedRoomWorkflow(call.receive())
                    validateRoomWorkflow(request)
                    val change = workflowService.replace(roomId, caller, managerGuard, expected, request, call.workflowUsage())
                    audit(
                        "room.workflow_updated",
                        "byUserId" to caller.userId.toLong(),
                        "roomId" to roomId.toLong(),
                        "revision" to change.revision,
                        "statusesAdded" to change.statusesAdded,
                        "statusesRetired" to change.statusesRetired,
                        "fieldsAdded" to change.fieldsAdded,
                        "fieldsRetired" to change.fieldsRetired,
                        "threadsMoved" to change.threadsMoved,
                    )
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

/** Resolved per request, so a test's `application { }` block can overwrite the registered port with a fake (`WorkflowUsage.kt`). */
private fun ApplicationCall.workflowUsage(): WorkflowUsage = application.attributes[WorkflowUsageKey]
