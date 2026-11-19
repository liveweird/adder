package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.plugins.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.intercept

/** The problem type of a `503` the live channel answers (a stopping server, a full socket table); the spec's `LiveUnavailable`. */
const val LIVE_UNAVAILABLE_PROBLEM_TYPE = "urn:adder:problem:live-unavailable"

/** The problem type of the `429` a mint answers when the caller already holds [LiveConfig.maxSocketsPerUser] sockets. */
const val LIVE_SOCKET_LIMIT_PROBLEM_TYPE = "urn:adder:problem:live-socket-limit"

/**
 * What `Retry-After` says on a `503` (seconds): a full table frees up as sockets close, a stopping server is back soon.
 */
const val LIVE_UNAVAILABLE_RETRY_SECONDS = 5

/** Room ids one `live.subscribe_denied` line lists. */
private const val MAX_DENIED_LISTED = 20
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65_535
private const val MAX_LOGGED_ORIGIN = 100
private val ORIGIN = Regex("""^(https?)://(\[[0-9a-fA-F:.]+]|[^:/?#@\[\]\s]+)(?::(\d{1,5}))?$""", RegexOption.IGNORE_CASE)
private val CORS_HOST = Regex("""^(\[[0-9a-fA-F:.]+]|[^:/?#@\[\]\s]+)(?::(\d{1,5}))?$""")

/** A host as compared: lower case, an IPv6 literal without its brackets. */
private fun normaliseHost(host: String): String = host.lowercase().removePrefix("[").removeSuffix("]")

private fun defaultPort(scheme: String): Int = if (scheme.equals("https", ignoreCase = true)) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT

/**
 * The CSWSH defence (`live.md` "Origin rule"): whether the `Origin` header of a WebSocket upgrade names this very server or a host the
 * deployment lists in `http.corsHosts`. [origin] is the header (null = missing); [serverHost] and [serverPort] are the request's own
 * authority as Ktor resolves it (`XForwardedHeaders` rewrites it behind a proxy). The scheme must be `http` or `https`; the header must
 * be a bare `scheme://host[:port]` (no path, query, user info); host comparison is case-insensitive, an absent port is the scheme's default
 * and an IPv6 literal is compared without its brackets. A [corsHosts] entry is `host[:port]` and matches any `http`/`https` origin of
 * that host: with a port, exactly that port; without one, the scheme's default port. Anything else, a missing header and `null` included,
 * is refused.
 */
internal fun originAllowed(origin: String?, serverHost: String, serverPort: Int, corsHosts: List<String>): Boolean {
    val match = origin?.let { ORIGIN.matchEntire(it) } ?: return false
    val scheme = match.groupValues[1].lowercase()
    val host = normaliseHost(match.groupValues[2])
    val port = match.groupValues[3].ifEmpty { null }?.toInt() ?: defaultPort(scheme)
    if (port > MAX_PORT) return false
    if (host == normaliseHost(serverHost) && port == serverPort) return true
    return corsHosts.any { entry ->
        val parts = CORS_HOST.matchEntire(entry.trim()) ?: return@any false
        val entryPort = parts.groupValues[2].ifEmpty { null }?.toInt() ?: defaultPort(scheme)
        host == normaliseHost(parts.groupValues[1]) && port == entryPort
    }
}

/**
 * The live channel's audit lines (`observability.md`): `live.refused` is COALESCED through [RateLimitAuditThrottle] (one line per reason a
 * minute before authentication, per reason and user after it, with the `suppressed` count), so a flood of refused sockets writes a
 * handful of
 * lines; `live.subscribe_denied` is coalesced per user the same way (the inbound frame rate alone would still allow 3 lines a second per
 * socket). The client address is deliberately NOT a
 * field (the `rate_limit.exceeded` rule: behind a proxy it is attacker-chosen text; the access log has it).
 */
internal class LiveAudit(
    private val throttle: RateLimitAuditThrottle = RateLimitAuditThrottle(),
    private val deniedThrottle: RateLimitAuditThrottle = RateLimitAuditThrottle(),
) {
    private val pendingDenied = java.util.concurrent.ConcurrentHashMap<UInt, java.util.TreeSet<UInt>>()

    fun refused(reason: String, userId: UInt? = null, vararg fields: Pair<String, Any?>) {
        val key = if (userId == null) "live.refused:$reason" else "live.refused:$reason:$userId"
        throttle.admit(key)?.let { suppressed ->
            val identity = if (userId == null) emptyArray() else arrayOf("byUserId" to userId.toLong())
            audit("live.refused", "reason" to reason, *identity, *fields, "suppressed" to suppressed)
        }
    }

    /**
     * COALESCED per user (one line a minute with the `suppressed` count): a client can send a subscribe per frame, and each denied one
     * would otherwise cost a log line. The line lists the UNION of the rooms denied since the last line, at most [MAX_DENIED_LISTED] ids
     * (the lowest first), so a rotating probe is not lost to the window.
     */
    fun subscribeDenied(userId: UInt, roomIds: Collection<UInt>) {
        val pending = pendingDenied.computeIfAbsent(userId) { java.util.TreeSet() }
        synchronized(pending) { roomIds.sorted().forEach { if (pending.size < MAX_DENIED_LISTED) pending.add(it) } }
        deniedThrottle.admit("live.subscribe_denied:$userId")?.let { suppressed ->
            val listed = synchronized(pending) { pending.toList().also { pending.clear() } }
            audit(
                "live.subscribe_denied", "byUserId" to userId.toLong(), "roomIds" to listed.map { it.toLong() },
                "suppressed" to suppressed,
            )
        }
    }
}

/** The printable ASCII of [origin], cut to a log-safe length: the header is client text, it names nothing but itself. */
private fun loggedOrigin(origin: String?): String? =
    origin?.take(MAX_LOGGED_ORIGIN)?.map { if (it in ' '..'~') it else '?' }?.joinToString("")

/**
 * Admission of a call to `GET /api/v1/live` (before the upgrade, and for a plain GET too, so 403 comes before 400): the Origin rule
 * (`403`), then a stopping server, a full socket table and too many unauthenticated sockets (`503` `live-unavailable` with
 * `Retry-After`). The caps are ADVISORY here (a handful of upgrades in flight can slip past); the hub's register and the pending
 * counter are what hold the numbers. The per-IP bucket sits before this (the route's `rateLimit`), so a flood is a `429` first.
 */
internal fun Route.installLiveAdmission(config: LiveConfig, hub: LiveHub, audits: LiveAudit) {
    val corsHosts = application.environment.config.propertyOrNull("http.corsHosts")?.getString()
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    intercept(ApplicationCallPipeline.Plugins) {
        val refused = refusal(call, config, hub, audits, corsHosts)
        if (refused != null) {
            if (refused == HttpStatusCode.Forbidden) {
                call.respondProblem(HttpStatusCode.Forbidden, "Origin not allowed")
            } else {
                call.respondLiveUnavailable()
            }
            finish()
        }
    }
}

/** The `503` of the live channel: `urn:adder:problem:live-unavailable` with `Retry-After` (a stopping server, a full table). */
internal suspend fun ApplicationCall.respondLiveUnavailable() {
    response.header(HttpHeaders.RetryAfter, LIVE_UNAVAILABLE_RETRY_SECONDS.toString())
    respondProblem(
        HttpStatusCode.ServiceUnavailable,
        "Live updates are unavailable right now - try again shortly",
        type = LIVE_UNAVAILABLE_PROBLEM_TYPE,
    )
}

/** The status the call is refused with (403 or 503) after its audit line, or null when it is admitted. */
private fun refusal(call: ApplicationCall, config: LiveConfig, hub: LiveHub, audits: LiveAudit, corsHosts: List<String>): HttpStatusCode? {
    val origins = call.request.headers.getAll(HttpHeaders.Origin)
    val header = origins?.singleOrNull()
    val point = call.request.origin
    return when {
        !originAllowed(header, point.serverHost, point.serverPort, corsHosts) -> {
            audits.refused("origin", null, "origin" to loggedOrigin(header ?: origins?.firstOrNull()))
            HttpStatusCode.Forbidden
        }
        hub.isStopping -> {
            audits.refused("stopping", null)
            HttpStatusCode.ServiceUnavailable
        }
        hub.socketCount >= config.maxSockets -> {
            audits.refused("capacity", null, "limit" to "sockets")
            HttpStatusCode.ServiceUnavailable
        }
        hub.pendingAuth.get() >= config.maxPendingAuth -> {
            audits.refused("capacity", null, "limit" to "pending-auth")
            HttpStatusCode.ServiceUnavailable
        }
        else -> null
    }
}
