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
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/notifications/push")
class BrowserPushRoute {
    @Serializable
    @Resource("config")
    class Config(val parent: BrowserPushRoute = BrowserPushRoute(), val subscriptionId: UInt? = null)

    @Serializable
    @Resource("subscriptions")
    class Subscriptions(val parent: BrowserPushRoute = BrowserPushRoute()) {
        @Serializable
        @Resource("{id}")
        class Id(val parent: Subscriptions = Subscriptions(), val id: UInt)
    }
}

@Serializable
data class BrowserPushConfigResponse(
    val available: Boolean,
    val publicKey: String? = null,
    val registered: Boolean? = null,
)

@Serializable
private data class BrowserPushUnavailableResponse(val available: Boolean = false)

@Serializable
private data class BrowserPushUnavailableRegisteredResponse(val available: Boolean = false, val registered: Boolean = false)

@Serializable
private data class BrowserPushAvailableResponse(val available: Boolean = true, val publicKey: String)

@Serializable
private data class BrowserPushAvailableRegisteredResponse(
    val available: Boolean = true,
    val publicKey: String,
    val registered: Boolean,
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
                val subscriptionId = route.subscriptionId
                if (subscriptionId != null && subscriptionId !in 1u..Int.MAX_VALUE.toUInt()) {
                    throw BadRequestException("subscriptionId must be a positive int32")
                }
                if (config == null) {
                    val response = if (subscriptionId == null) {
                        BrowserPushUnavailableResponse()
                    } else {
                        BrowserPushUnavailableRegisteredResponse()
                    }
                    call.respond(HttpStatusCode.OK, response)
                    return@get
                }
                if (subscriptionId == null) {
                    call.respond(HttpStatusCode.OK, BrowserPushAvailableResponse(publicKey = config.publicKey))
                    return@get
                }
                val caller = call.caller()
                val registered = service.isRegistered(
                    caller.userId,
                    call.accessToken().credentialRevision,
                    subscriptionId,
                    nowMillis() - PUSH_SUBSCRIPTION_RETENTION_MILLIS,
                )
                call.respond(
                    HttpStatusCode.OK,
                    BrowserPushAvailableRegisteredResponse(publicKey = config.publicKey, registered = registered),
                )
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
                    if (route.id == 0u || route.id > Int.MAX_VALUE.toUInt()) throw BadRequestException("id must be a positive int32")
                    val caller = call.caller()
                    service.unsubscribe(caller.userId, route.id)
                    audit("notification.push_unsubscribed", "userId" to caller.userId.toLong(), "subscriptionId" to route.id.toLong())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
