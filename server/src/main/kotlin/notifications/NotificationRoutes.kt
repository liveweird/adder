package ch.nokillswit.notifications

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/notifications`: the caller's own notices (a list), and the two writes that mark them seen. */
@Serializable
@Resource("/api/v1/notifications")
class NotificationsRoute {
    /** `/api/v1/notifications/{id}`: ONE notice of the caller's; only its `seen` child is a route. */
    @Serializable
    @Resource("{id}")
    class Id(val parent: NotificationsRoute = NotificationsRoute(), val id: UInt) {
        @Serializable
        @Resource("seen")
        class Seen(val parent: Id)
    }

    @Serializable
    @Resource("seen-all")
    class SeenAll(val parent: NotificationsRoute = NotificationsRoute())
}

fun Application.configureNotificationRoutes() {
    val notifications = attributes[NotificationServiceKey]
    routing {
        authenticate {
            notificationList(notifications)
            notificationSeen(notifications)
            notificationSeenAll(notifications)
        }
    }
}

/**
 * `GET /notifications` (any authenticated caller, no guard, no bucket): the caller's OWN notices only, so there is nothing to disclose
 * and no room to hide; the scope (their active rooms, an ADMIN's too) is in the service's predicate. A bad `page`, `pageSize`, `sort` or
 * `seen` is a `400`. A read: no audit line.
 */
private fun Route.notificationList(notifications: NotificationService) {
    get<NotificationsRoute> {
        val caller = call.caller()
        val paging = call.parsePaging(sortable = NOTIFICATION_SORT_FIELDS, defaultSort = listOf(SortField("id", descending = true)))
        val seen = call.request.queryParameters.optionalBoolean("seen")
        val result = notifications.list(caller.userId, seen, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
}

/**
 * `POST /notifications/{id}/seen`: the per-USER `read-state` bucket first of all (an id sweep spends tokens), then the update scoped to
 * the caller's own row. Someone else's notice is the same `404` as one that does not exist (no `403` to probe with); seen again is a
 * `204`. View state: not audited and no hint (`observability.md`, `live.md`).
 */
private fun Route.notificationSeen(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.READ_STATE)) {
        post<NotificationsRoute.Id.Seen> { route ->
            val found = notifications.markSeen(call.caller().userId, route.parent.id, nowMillis())
            if (!found) throw NotFoundException("Notification not found")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** `POST /notifications/seen-all`: every unseen notice of the caller, nobody else's, on the same bucket; `204` even if none was unseen. */
private fun Route.notificationSeenAll(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.READ_STATE)) {
        post<NotificationsRoute.SeenAll> {
            notifications.markAllSeen(call.caller().userId, nowMillis())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
