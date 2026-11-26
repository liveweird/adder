package ch.nokillswit.threads

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** The inert prior bodies of one live message. */
@Serializable
@Resource("revisions")
class MessageRevisionsRoute(val parent: RoomMessagesRoute.Id)

fun Application.configureMessageRevisionRoutes() {
    val revisions = attributes[MessageRevisionServiceKey]
    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.CHAT_READ)) {
                get<MessageRevisionsRoute> { route ->
                    val caller = call.caller()
                    val roomId = route.parent.parent.parent.id
                    val result = revisions.list(caller, roomId, route.parent.messageId) {
                        call.parsePaging(
                            sortable = MESSAGE_REVISION_SORT_FIELDS,
                            defaultSort = listOf(SortField("id", descending = true)),
                        )
                    }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(HttpStatusCode.OK, result)
                }
            }
        }
    }
}
