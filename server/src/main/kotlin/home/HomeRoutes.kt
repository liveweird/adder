package ch.nokillswit.home

import ch.nokillswit.authz.caller
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/home")
class HomeRoute

fun Application.configureHomeRoutes() {
    val service = attributes[HomeServiceKey]
    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.CHAT_READ)) {
                get<HomeRoute> {
                    val caller = call.caller()
                    call.respond(HttpStatusCode.OK, service.read(caller.userId))
                }
            }
        }
    }
}
