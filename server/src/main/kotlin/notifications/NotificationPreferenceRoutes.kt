package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.mail.mailer
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/users/{id}/notification-preferences`: a person's switches for the digest email (one table, so the route lives here). */
@Serializable
@Resource("/api/v1/users/{id}/notification-preferences")
class UserNotificationPreferencesRoute(val id: UInt)

/** Every `(kind, channel)` pair there is: a longer list repeats one (and is refused as that). */
private val MAX_DISABLED_PAIRS = NotificationType.entries.size * NotificationChannel.entries.size

/**
 * `GET` and `PUT /users/{id}/notification-preferences`. Both: `requireSelfOrAdmin` FIRST (`403` before anything is read), then the body
 * (`400`: an unknown kind or channel fails to decode, a repeated pair), then the target (`404` for an unknown or soft-deleted user, so an
 * ADMIN asking for an id learns the same as for any other). The PUT is a wholesale replace and a `204` whether or not it changed
 * anything; it is audited only when the stored set really changed, with the set before and after (`from` is the one read under the
 * row lock). Unbucketed: a person's own small row. The email switch changes what the next digest does, never what the bell shows.
 */
fun Application.configureNotificationPreferenceRoutes() {
    // Built here, not in `Database.kt`: nothing else needs the instance (the digest reads the switches through `emailDisabledKinds`).
    val preferences = NotificationPreferenceService(attributes[R2dbcDatabaseKey])
    val emailAvailable = mailer() != null
    routing {
        authenticate {
            get<UserNotificationPreferencesRoute> { route ->
                requireSelfOrAdmin(call.caller(), route.id)
                val disabled = preferences.read(route.id).orNotFound("User")
                call.respond(HttpStatusCode.OK, preferencesResponse(disabled, emailAvailable))
            }
            put<UserNotificationPreferencesRoute> { route ->
                val caller = call.caller()
                requireSelfOrAdmin(caller, route.id)
                val requested = call.receive<NotificationPreferencesUpdateRequest>().disabled
                val wanted = requested.toSet()
                if (requested.size > MAX_DISABLED_PAIRS || wanted.size != requested.size) {
                    throw BadRequestException("A notification preference is listed more than once")
                }
                val before = preferences.replace(route.id, wanted).orNotFound("User")
                if (before != wanted) {
                    audit(
                        "user.notification_preferences_changed",
                        "byUserId" to caller.userId.toLong(),
                        "targetUserId" to route.id.toLong(),
                        "from" to before.joinedForAudit(),
                        "to" to wanted.joinedForAudit(),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
