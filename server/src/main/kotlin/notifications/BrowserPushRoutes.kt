package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.accessToken
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/notifications/push")
class BrowserPushRoute {
    @Serializable
    @Resource("config")
    class Config(val parent: BrowserPushRoute = BrowserPushRoute(), val subscriptionId: Int? = null)

    @Serializable
    @Resource("subscriptions")
    class Subscriptions(val parent: BrowserPushRoute = BrowserPushRoute()) {
        @Serializable
        @Resource("{id}")
        class Id(val parent: Subscriptions = Subscriptions(), val id: Int)
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class BrowserPushConfigResponse(
    val available: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val publicKey: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val registered: Boolean? = null,
)

@Serializable
class BrowserPushSubscriptionRequest(val endpoint: String)

@Serializable
data class BrowserPushSubscriptionResponse(val id: UInt)

fun Application.configureBrowserPushRoutes() {
    val service = attributes[BrowserPushServiceKey]
    routing {
        authenticate {
            get<BrowserPushRoute.Config> { route ->
                val config = service.config
                val registered = route.subscriptionId?.let { id ->
                    if (id < 1) throw BadRequestException("subscriptionId must be a positive int32")
                    config != null && service.isRegistered(
                        call.caller().userId,
                        call.accessToken().credentialRevision,
                        id.toUInt(),
                        nowMillis() - PUSH_SUBSCRIPTION_RETENTION_MILLIS,
                    )
                }
                call.respond(HttpStatusCode.OK, BrowserPushConfigResponse(config != null, config?.publicKey, registered))
            }
            rateLimit(RateLimitName(RateLimits.NOTIFICATION_PREFS)) {
                post<BrowserPushRoute.Subscriptions> {
                    val caller = call.caller()
                    val request = call.receive<BrowserPushSubscriptionRequest>()
                    if (service.config == null) {
                        call.respondProblem(HttpStatusCode.ServiceUnavailable, "Browser push is not configured")
                        return@post
                    }
                    val id = service.subscribe(caller.userId, call.accessToken().credentialRevision, request.endpoint)
                    audit("notification.push_subscribed", "userId" to caller.userId.toLong(), "subscriptionId" to id.toLong())
                    call.respond(HttpStatusCode.OK, BrowserPushSubscriptionResponse(id))
                }
                delete<BrowserPushRoute.Subscriptions.Id> { route ->
                    if (route.id < 1) throw BadRequestException("id must be a positive int32")
                    val caller = call.caller()
                    service.unsubscribe(caller.userId, route.id.toUInt())
                    audit("notification.push_unsubscribed", "userId" to caller.userId.toLong(), "subscriptionId" to route.id.toLong())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
