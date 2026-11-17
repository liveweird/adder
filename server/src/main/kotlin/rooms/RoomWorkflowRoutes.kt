package ch.nokillswit.rooms

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
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
                // Resolved per request, so a test (and 0.3) can register its own port; absent = no threads yet.
                val usage = call.application.attributes.getOrNull(WorkflowUsageKey) ?: NoThreadsYet
                call.respond(HttpStatusCode.OK, workflowService.read(roomId, usage).orNotFound("Room"))
            }
        }
    }
}
