package ch.nokillswit.notifications

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/users/{id}/notification-preferences`: a person's in-app and email switches (one table, so the route lives here). */
@Serializable
@Resource("/api/v1/users/{id}/notification-preferences")
class UserNotificationPreferencesRoute(val id: UInt)

/**
 * `GET` and `PUT /users/{id}/notification-preferences`. Both: `requireSelfOrAdmin` FIRST (`403` before anything is read), then the body
 * (`400`: an unknown kind or channel fails to decode, a repeated pair), then the target (`404` for an unknown or soft-deleted user, so an
 * ADMIN asking for an id learns the same as for any other). The PUT is a wholesale replace (`disabled` pairs are off, `enabled` pairs are
 * on, a pair in neither takes its default, a pair twice or in both is a `400`) and a `204` whether or not it changed anything; it is
 * audited only when the person's DEVIATIONS from the defaults really changed, with the set before and after as `TYPE:CHANNEL=on|off`
 * (`from` is the one read under the row lock). The PUT rides the per-USER `notification-prefs` bucket, 30 a minute (before the
 * guards, so a refused write
 * spends a token); the GET is unbucketed: a person's own small row. A switch applies to notices made (in-app) or mailed (email) from
 * then on; it never rewrites a notice that exists.
 */
fun Application.configureNotificationPreferenceRoutes() {
    // Built here, not in `Database.kt`: nothing else needs the instance (the digest and the notice writer read the switches through
    // `effectivePreferences`).
    val preferences = NotificationPreferenceService(attributes[R2dbcDatabaseKey])
    val emailAvailable = mailer() != null
    routing {
        authenticate {
            get<UserNotificationPreferencesRoute> { route ->
                requireSelfOrAdmin(call.caller(), route.id)
                val effective = preferences.read(route.id).orNotFound("User")
                call.respond(HttpStatusCode.OK, preferencesResponse(effective, emailAvailable))
            }
            rateLimit(RateLimitName(RateLimits.NOTIFICATION_PREFS)) {
                put<UserNotificationPreferencesRoute> { route ->
                    val caller = call.caller()
                    requireSelfOrAdmin(caller, route.id)
                    val request = call.receive<NotificationPreferencesUpdateRequest>()
                    // Validated here (a 400 wins over the target's 404) and again by the service, which takes the raw lists.
                    val wanted = validateNotificationPreferences(request.disabled, request.enabled, request.roomMembership).deviations()
                    val before = preferences.replace(route.id, request.disabled, request.enabled, request.roomMembership)
                        .orNotFound("User").deviations
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
}
