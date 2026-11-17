package ch.nokillswit.workflows

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.revision.expectedRevision
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/workflow-templates")
class WorkflowTemplatesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: WorkflowTemplatesRoute = WorkflowTemplatesRoute(), val id: UInt)
}

fun Application.configureWorkflowTemplateRoutes() {
    val service = attributes[WorkflowTemplateServiceKey]

    routing {
        authenticate {
            // Reads are any-authenticated (the room-create form picks a template); every mutation is ADMIN only with the guard
            // BEFORE the header, the body decode and the id lookup - a non-admin probe gets a uniform 403 whether or not the id
            // exists and whatever the body is. The PUT's order: guard, header (400), sanitise + validate (400), then the service
            // locks the row, compares the revision (409) and writes.
            get<WorkflowTemplatesRoute> {
                call.caller()
                val paging = call.parsePaging(sortable = WORKFLOW_TEMPLATE_SORT_FIELDS)
                val name = call.request.queryParameters.optionalString("name")
                if (name != null && name.length > MAX_TEMPLATE_NAME_LENGTH) {
                    throw BadRequestException("name must be at most $MAX_TEMPLATE_NAME_LENGTH characters")
                }
                val filter = WorkflowTemplateListFilter(name = name)
                val result = service.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<WorkflowTemplatesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedWorkflowTemplate(call.receive())
                validateWorkflowTemplate(request)
                val id = service.create(request)
                audit(
                    "workflow_template.created",
                    "byUserId" to caller.userId.toLong(),
                    "templateId" to id.toLong(),
                    "name" to request.name,
                    "statuses" to request.statuses.size,
                    "fields" to request.fields.size,
                )
                call.response.header(HttpHeaders.Location, call.application.href(WorkflowTemplatesRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, service.read(id).orVanished("Workflow template", id))
            }
            get<WorkflowTemplatesRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, service.read(route.id).orNotFound("Workflow template"))
            }
            put<WorkflowTemplatesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val expected = call.expectedRevision()
                val request = sanitizedWorkflowTemplate(call.receive())
                validateWorkflowTemplate(request)
                val revision = service.update(route.id, expected, request).orNotFound("Workflow template")
                audit(
                    "workflow_template.updated",
                    "byUserId" to caller.userId.toLong(),
                    "templateId" to route.id.toLong(),
                    "name" to request.name,
                    "revision" to revision,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<WorkflowTemplatesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                service.delete(route.id).orNotFound("Workflow template")
                audit("workflow_template.deleted", "byUserId" to caller.userId.toLong(), "templateId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
