package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.accessToken
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.db.NotifyConnectionFactoryKey
import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.infra.notify.LiveStats
import ch.nokillswit.infra.notify.LiveStatsKey
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomServiceKey
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.post
import io.ktor.server.resources.resource
import io.ktor.server.response.respond
import io.ktor.server.routing.method
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/live/tickets")
class LiveTicketsRoute

@Serializable
@Resource("/api/v1/live")
class LiveConnectRoute

/** The hub (and through it the listener and the counters), published by `configureLive`; a test seam and the telemetry's reader. */
val LiveHubKey = AttributeKey<LiveHub>("LiveHub")

/** How long a `429 live-socket-limit` asks the client to wait: the SPA pauses a tab that was refused that long. */
private const val SOCKET_LIMIT_RETRY_SECONDS = 60

/**
 * `POST /api/v1/live/tickets`: the ticket (sent in the socket's first frame, never in a URL) and its expiry as Unix epoch
 * milliseconds (API-DATA-001).
 */
@Serializable
data class LiveTicketResponse(val ticket: String, val expiresAt: Long) {
    override fun toString(): String = "LiveTicketResponse(ticket=${maskedSecret(ticket)}, expiresAt=$expiresAt)"
}

/**
 * The live-update channel's HTTP side (`.claude/docs/live.md`): the ticket mint `POST /api/v1/live/tickets` (any authenticated caller,
 * inside `authenticate {}` so the per-USER `live-ticket` bucket is keyed by the verified user) and the socket `GET /api/v1/live`
 * (`security: []`, authenticated in-band by its first frame; the per-IP `live-connect` bucket, then `installLiveAdmission`: Origin,
 * stopping, capacity, per address and in total). The ticket binds the caller and the access token that authenticated the mint
 * (`authz/AccessToken.kt`) and
 * proves nothing about any room: subscriptions are judged per room when they are made. The hub's LISTEN connection starts lazily (the
 * first mint), and everything ends with the application (`ApplicationStopPreparing` closes every socket 1001 within 2 s,
 * `ApplicationStopping` then stops the listener and cancels and joins the live scope).
 * Registered with the feature routes in `application.yaml`.
 */
fun Application.configureLive() {
    val config = readLiveConfig()
    val tickets = LiveTickets(maxTickets = config.maxTickets)
    attributes.put(LiveTicketsKey, tickets)
    // The hub (actor, listener, hints, deaf watcher), the sweep and the recheck loop all run in this ONE scope. On ApplicationStopping it
    // is cancelled AND joined (bounded), after the hub closed the LISTEN connection and before the pool is disposed on ApplicationStopped.
    var hubToStop: LiveHub? = null
    val scope = backgroundScope("adder-live", Dispatchers.Default) { hubToStop?.stop() }
    val hub = LiveHub(scope, config.maxSockets, config.maxSocketsPerUser, attributes[NotifyConnectionFactoryKey]).also { hubToStop = it }
    attributes.put(LiveHubKey, hub)
    attributes.put(LiveStatsKey) { LiveStats(hub.socketCount, hub.listener.let { if (it.isStarted) it.isUp else null }) }
    monitor.subscribe(ApplicationStopPreparing) { runBlocking { hub.shutdown() } }
    val audits = LiveAudit()
    val sweep = LiveSweep(hub, attributes[TokenBlocklistServiceKey], attributes[RoomServiceKey], audits = audits)
    attributes.put(LiveSweepKey, sweep)
    sweep.start(scope)
    val deps = LiveSessionDeps(config, tickets, hub, attributes[TokenBlocklistServiceKey], attributes[RoomServiceKey], audits)
    // `live.ticket_saturated` is coalesced per user (one line a minute with a `suppressed` count): a full store answers every mint, and the
    // per-user bucket alone would still let many accounts write a line each per request.
    val saturationAudit = RateLimitAuditThrottle()

    routing {
        authenticate {
            rateLimit(RateLimitName(RateLimits.LIVE_TICKET)) {
                post<LiveTicketsRoute> {
                    val caller = call.caller()
                    val token = call.accessToken()
                    // A deaf hub (the LISTEN session down for long) cannot deliver a hint, so a ticket would buy a silent socket.
                    if (hub.isStopping || hub.socketCount >= config.maxSockets || hub.isDeaf) {
                        when {
                            hub.isStopping -> audits.refused("stopping", caller.userId)
                            hub.isDeaf -> audits.refused("listener-down", caller.userId)
                            else -> audits.refused("capacity", caller.userId, "limit" to "sockets")
                        }
                        call.respondLiveUnavailable()
                        return@post
                    }
                    if (hub.socketsOf(caller.userId) >= config.maxSocketsPerUser) {
                        audits.refused("socket-cap", caller.userId, "limit" to config.maxSocketsPerUser)
                        throw TooManyRequestsException(
                            "Too many live-update sockets are open for this account - close a tab",
                            problemType = LIVE_SOCKET_LIMIT_PROBLEM_TYPE,
                            retryAfterSeconds = SOCKET_LIMIT_RETRY_SECONDS,
                        )
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
                    // The LISTEN connection starts with the first mint (idempotent): a socket follows within seconds.
                    hub.ensureStarted()
                    call.respond(HttpStatusCode.OK, LiveTicketResponse(issued.ticket, issued.expiresAtMillis))
                }
            }
        }
        // Per-IP bucket first (429), then admission (Origin 403, stopping/capacity 503); a plain GET passes the same gates, so 403
        // wins over 400.
        rateLimit(RateLimitName(RateLimits.LIVE_CONNECT)) {
            resource<LiveConnectRoute> {
                method(HttpMethod.Get) {
                    installLiveAdmission(config, hub, audits)
                    webSocket { liveSession(deps) }
                    handle { throw BadRequestException("Expected a WebSocket upgrade") }
                }
            }
        }
    }
}
