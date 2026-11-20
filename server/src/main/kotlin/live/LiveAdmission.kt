package ch.nokillswit.live

import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.clientAddressKey
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
const val LIVE_UNAVAILABLE_PROBLEM_TYPE = ProblemTypes.LIVE_UNAVAILABLE

/** The problem type of the `429` a mint answers when the caller already holds [LiveConfig.maxSocketsPerUser] sockets. */
const val LIVE_SOCKET_LIMIT_PROBLEM_TYPE = ProblemTypes.LIVE_SOCKET_LIMIT

/**
 * What `Retry-After` says on a `503` (seconds): a full table frees up as sockets close, a stopping server is back soon.
 */
const val LIVE_UNAVAILABLE_RETRY_SECONDS = 5

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65_535
private const val MAX_LOGGED_ORIGIN = 100
private val ORIGIN = Regex("""^(https?)://(\[[0-9a-fA-F:.]+]|[^:/?#@\[\]\s]+)(?::(\d{1,5}))?$""", RegexOption.IGNORE_CASE)
private val CORS_HOST = Regex("""^(\[[0-9a-fA-F:.]+]|[^:/?#@\[\]\s]+)(?::(\d{1,5}))?$""")

/** A host as compared: lower case, an IPv6 literal without its brackets. */
private fun normaliseHost(host: String): String = host.lowercase().removePrefix("[").removeSuffix("]")

private fun defaultPort(scheme: String): Int = if (scheme.equals("https", ignoreCase = true)) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT

/** Why an upgrade's `Origin` was or was not accepted; [detail] refines the audit line of a refusal. */
internal enum class OriginVerdict(val detail: String?) {
    ALLOWED(null),

    /** Some other authority: a foreign site, a missing or malformed header. */
    FOREIGN("authority"),

    /** The authority is this server's but the scheme is not (a TLS front door that does not say so, `X-Forwarded-Proto` missing). */
    SCHEME("scheme"),

    /** The request's own `Host` could not be read (a non-numeric port): nothing can be compared with it. */
    HOST("host"),
}

/**
 * The CSWSH defence (`live.md` "Origin rule"): whether the `Origin` header of a WebSocket upgrade names this very server or a host the
 * deployment lists in `http.corsHosts`. [origin] is the header (null = missing); [serverScheme], [serverHost] and [serverPort] are the
 * request's own origin as Ktor resolves it (`XForwardedHeaders` rewrites all three behind a proxy: `X-Forwarded-Proto` and
 * `X-Forwarded-Host`, port included). The scheme must be `http` or `https` AND equal the server's (a plain-`http` page is not the
 * `https` site's origin, and `http://x:443` is not `https://x:443`); the header must be a bare `scheme://host[:port]` (no path, query,
 * user info); host comparison is case-insensitive, an absent port is the scheme's default and an IPv6 literal is compared without its
 * brackets. A [corsHosts] entry is `host[:port]` and matches any `http`/`https` origin of that host: with a port, exactly that port;
 * without one, the scheme's default port; with [listedHttpsOnly] (production) only an `https` origin matches a listed host, the rule
 * CORS applies (`plugins/Http.kt`). Anything else, a missing header and `null` included, is refused.
 */
internal fun originVerdict(
    origin: String?,
    serverScheme: String,
    serverHost: String,
    serverPort: Int,
    corsHosts: List<String>,
    listedHttpsOnly: Boolean = false,
): OriginVerdict {
    val match = origin?.let { ORIGIN.matchEntire(it) } ?: return OriginVerdict.FOREIGN
    val scheme = match.groupValues[1].lowercase()
    val host = normaliseHost(match.groupValues[2])
    val port = match.groupValues[3].ifEmpty { null }?.toInt() ?: defaultPort(scheme)
    if (port > MAX_PORT) return OriginVerdict.FOREIGN
    val sameHost = host == normaliseHost(serverHost)
    val sameScheme = scheme == serverScheme.lowercase()
    val listed = corsHosts.any { entry ->
        val parts = CORS_HOST.matchEntire(entry.trim()) ?: return@any false
        val entryPort = parts.groupValues[2].ifEmpty { null }?.toInt() ?: defaultPort(scheme)
        host == normaliseHost(parts.groupValues[1]) && port == entryPort && (!listedHttpsOnly || scheme == "https")
    }
    return when {
        sameHost && sameScheme && port == serverPort -> OriginVerdict.ALLOWED
        listed -> OriginVerdict.ALLOWED
        sameHost && !sameScheme -> OriginVerdict.SCHEME
        else -> OriginVerdict.FOREIGN
    }
}

internal fun originAllowed(
    origin: String?,
    serverScheme: String,
    serverHost: String,
    serverPort: Int,
    corsHosts: List<String>,
    listedHttpsOnly: Boolean = false,
): Boolean =
    originVerdict(origin, serverScheme, serverHost, serverPort, corsHosts, listedHttpsOnly) == OriginVerdict.ALLOWED

/** The printable ASCII of [origin], cut to a log-safe length: the header is client text, it names nothing but itself. */
private fun loggedOrigin(origin: String?): String? =
    origin?.take(MAX_LOGGED_ORIGIN)?.map { if (it in ' '..'~') it else '?' }?.joinToString("")

/**
 * Admission of a call to `GET /api/v1/live` (before the upgrade, and for a plain GET too, so 403 comes before 400): the Origin rule
 * (`403`), then a stopping server, a full socket table and too many unauthenticated sockets, in total or for the caller's address (`503`
 * `live-unavailable` with `Retry-After`). The caps are ADVISORY here (a handful of upgrades in flight can slip past); the hub's register
 * and the pending claim are what hold the numbers.
 *
 * It intercepts in the `Call` phase, AFTER the route's per-IP bucket (`rateLimit` runs in an earlier phase), so a refused request spends
 * a token too: a flood of foreign-Origin or over-capacity requests is a `429` once the bucket is empty.
 */
internal fun Route.installLiveAdmission(config: LiveConfig, hub: LiveHub, audits: LiveAudit) {
    val corsHosts = application.environment.config.propertyOrNull("http.corsHosts")?.getString()
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    // Production lets a listed host through over https only, like CORS (an http page can be rewritten in transit).
    val listedHttpsOnly = !application.developmentMode
    intercept(ApplicationCallPipeline.Call) {
        val refused = refusal(call, config, hub, audits, corsHosts, listedHttpsOnly)
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
private fun refusal(
    call: ApplicationCall,
    config: LiveConfig,
    hub: LiveHub,
    audits: LiveAudit,
    corsHosts: List<String>,
    listedHttpsOnly: Boolean,
): HttpStatusCode? {
    val origins = call.request.headers.getAll(HttpHeaders.Origin)
    val header = origins?.singleOrNull()
    val verdict = originVerdictOf(call, header, corsHosts, listedHttpsOnly)
    return when {
        verdict != OriginVerdict.ALLOWED -> {
            audits.refusedOrigin(loggedOrigin(header ?: origins?.firstOrNull()), verdict)
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
        hub.pending.held(call.clientAddressKey()) >= config.maxPendingPerAddress -> {
            audits.refused("capacity", null, "limit" to "pending-per-address")
            HttpStatusCode.ServiceUnavailable
        }
        hub.pendingAuth.get() >= config.maxPendingAuth -> {
            audits.refused("capacity", null, "limit" to "pending-auth")
            HttpStatusCode.ServiceUnavailable
        }
        else -> null
    }
}

/**
 * The Origin verdict against the request's own scheme, host and port. `origin.serverPort` parses the `Host` header's port, which the
 * client chooses: a malformed one (`localhost:abc`, a bare IPv6 literal) throws, and that must be a refusal, never an anonymous 500.
 */
private fun originVerdictOf(call: ApplicationCall, header: String?, corsHosts: List<String>, listedHttpsOnly: Boolean): OriginVerdict {
    val point = call.request.origin
    return try {
        originVerdict(header, point.scheme, point.serverHost, point.serverPort, corsHosts, listedHttpsOnly)
    } catch (_: IllegalArgumentException) {
        OriginVerdict.HOST
    }
}
