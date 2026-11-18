package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.authz.accessToken
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.plugins.RateLimits
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/live/tickets")
class LiveTicketsRoute

/**
 * `POST /api/v1/live/tickets`: the ticket (sent in the socket's first frame, never in a URL) and its expiry as Unix epoch
 * milliseconds (API-DATA-001).
 */
@Serializable
data class LiveTicketResponse(val ticket: String, val expiresAt: Long) {
    override fun toString(): String = "LiveTicketResponse(ticket=${maskedSecret(ticket)}, expiresAt=$expiresAt)"
}

/**
 * The live-update channel's HTTP side (`.claude/docs/live.md`). This step ships the ticket mint only: `POST /api/v1/live/tickets`,
 * any authenticated caller, inside `authenticate {}` so the per-USER `live-ticket` bucket (60/min) is keyed by the verified user. The
 * ticket binds the caller and the access token that authenticated the call (`authz/AccessToken.kt`); redeeming it, and the socket,
 * come with the next step. No guard beyond authentication: a ticket proves nothing about any room, subscriptions are judged
 * per room when they are made. Registered with the feature routes in `application.yaml`.
 */
fun Application.configureLive() {
    val config = readLiveConfig()
    val tickets = LiveTickets(maxTickets = config.maxTickets)
    attributes.put(LiveTicketsKey, tickets)
    // `live.ticket_saturated` is coalesced per user (one line a minute with a `suppressed` count): a full store answers every mint, and the
    // per-user bucket alone would still let many accounts write a line each per request.
    val saturationAudit = RateLimitAuditThrottle()

    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.LIVE_TICKET)) {
                post<LiveTicketsRoute> {
                    val caller = call.caller()
                    val token = call.accessToken()
                    // A token with no future `exp` can bind nothing a socket could live on.
                    if (token.expiresAtMillis <= System.currentTimeMillis()) {
                        throw UnauthorizedException("Access token is no longer valid - refresh or sign in again")
                    }
                    val issued = try {
                        tickets.issue(
                            userId = caller.userId,
                            isAdmin = caller.isAdmin(),
                            roles = caller.roles.map { it.name }.toSet(),
                            jti = token.jti,
                            credentialRevision = token.credentialRevision,
                            tokenExpiresAtMillis = token.expiresAtMillis,
                        )
                    } catch (_: LiveTickets.CapacityExceededException) {
                        saturationAudit.admit("live.ticket_saturated:${caller.userId}")?.let { suppressed ->
                            audit("live.ticket_saturated", "byUserId" to caller.userId.toLong(), "suppressed" to suppressed)
                        }
                        // 30 s is the ticket TTL: by then every ticket held now has expired or been redeemed.
                        throw TooManyRequestsException(
                            "Too many live-update tickets are pending - try again later",
                            retryAfterSeconds = (LIVE_TICKET_TTL_MILLIS / 1000).toInt(),
                        )
                    }
                    call.respond(HttpStatusCode.OK, LiveTicketResponse(issued.ticket, issued.expiresAtMillis))
                }
            }
        }
    }
}
