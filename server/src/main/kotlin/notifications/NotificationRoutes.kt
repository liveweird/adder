package ch.nokillswit.notifications

import ch.nokillswit.infra.JsonUInt
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/notifications`: the caller's own notices (a list), and the two writes that mark them seen. */
@Serializable
@Resource("/api/v1/notifications")
class NotificationsRoute {
    @Serializable
    @Resource("feed")
    class Feed(val parent: NotificationsRoute = NotificationsRoute())

    @Serializable
    @Resource("reply-groups/{roomId}/{threadNumber}")
    class ReplyGroup(
        val parent: NotificationsRoute = NotificationsRoute(),
        val roomId: UInt,
        val threadNumber: UInt,
    ) {
        @Serializable
        @Resource("seen")
        class Seen(val parent: ReplyGroup)
    }

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
            notificationFeed(notifications)
            notificationSeen(notifications)
            notificationReplyGroupSeen(notifications)
            notificationSeenAll(notifications)
        }
    }
}

/**
 * `GET /notifications` (any authenticated caller, no guard; the per-USER `notification-read` bucket, 240 a minute, and the shared
 * `ReadLimiter`): the caller's OWN notices only, so there is nothing to disclose
 * and no room to hide; the scope (their active rooms, an ADMIN's too) is in the service's predicate. Only notices that show in the app
 * are listed (`in_app`). `type` repeats (any-of, every distinct kind at most once); an unknown name, or a bad `page`, `pageSize`, `sort` or
 * `seen`, is a `400`. A read: no audit line.
 */
private fun Route.notificationList(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.NOTIFICATION_READ)) {
        get<NotificationsRoute> {
            val caller = call.caller()
            val paging = call.parsePaging(sortable = NOTIFICATION_SORT_FIELDS, defaultSort = listOf(SortField("id", descending = true)))
            val seen = call.request.queryParameters.optionalBoolean("seen")
            val types = call.request.queryParameters.notificationTypes(THREAD_NOTIFICATION_TYPES)
            val result = notifications.list(caller.userId, seen, types, paging)
            call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
        }
    }
}

private fun Route.notificationFeed(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.NOTIFICATION_READ)) {
        get<NotificationsRoute.Feed> {
            val caller = call.caller()
            val paging = call.parsePaging(sortable = NOTIFICATION_SORT_FIELDS, defaultSort = listOf(SortField("id", descending = true)))
            val seen = call.request.queryParameters.optionalBoolean("seen")
            val groupReplies = call.request.queryParameters.optionalBoolean("groupReplies") ?: false
            val types = call.request.queryParameters.notificationTypes(NotificationType.entries.toSet())
            val result = notifications.feed(caller.userId, seen, types, paging, groupReplies)
            call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
        }
    }
}

@Serializable
data class NotificationReplyGroupSeenRequest(val fromId: JsonUInt, val throughId: JsonUInt)

/** Marks only the caller's still-unseen plain replies in this room/thread through the feed snapshot ceiling. */
private fun Route.notificationReplyGroupSeen(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.READ_STATE)) {
        post<NotificationsRoute.ReplyGroup.Seen> { route ->
            val request = call.receive<NotificationReplyGroupSeenRequest>()
            if (route.parent.roomId !in 1u..Int.MAX_VALUE.toUInt()) throw BadRequestException("roomId must be a positive int32")
            if (route.parent.threadNumber !in 1u..Int.MAX_VALUE.toUInt()) {
                throw BadRequestException("threadNumber must be a positive int32")
            }
            if (request.fromId !in 1u..Int.MAX_VALUE.toUInt()) throw BadRequestException("fromId must be a positive int32")
            if (request.throughId !in 1u..Int.MAX_VALUE.toUInt()) throw BadRequestException("throughId must be a positive int32")
            if (request.fromId > request.throughId) throw BadRequestException("fromId must not exceed throughId")
            notifications.markReplyGroupSeen(
                call.caller().userId,
                route.parent.roomId,
                route.parent.threadNumber.toInt(),
                request.fromId,
                request.throughId,
                nowMillis(),
            )
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The repeatable `type` filter: every distinct kind named, at most one of each; an unknown name is a `400` (never echoed back). */
private fun Parameters.notificationTypes(allowed: Set<NotificationType>): Set<NotificationType> =
    repeatedStrings("type", allowed.size).map { name ->
        allowed.firstOrNull { it.name == name } ?: throw BadRequestException("Unknown notification type")
    }.toSet()

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

/**
 * `POST /notifications/seen-all`: every unseen notice of the caller that shows in the app, nobody else's, on the same bucket; `204`
 * even if none was unseen.
 */
private fun Route.notificationSeenAll(notifications: NotificationService) {
    rateLimit(RateLimitName(RateLimits.READ_STATE)) {
        post<NotificationsRoute.SeenAll> {
            notifications.markAllSeen(call.caller().userId, nowMillis())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
