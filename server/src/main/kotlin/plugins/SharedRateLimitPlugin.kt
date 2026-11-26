package ch.nokillswit.plugins

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.clientAddressKey
import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.ResponseHeaders
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val rateLimitLog = LoggerFactory.getLogger("ch.nokillswit.plugins.RateLimits")
private const val RATE_LIMIT_DIGEST_LABEL = "adder.http-rate-limit-key.v1"
private const val RATE_LIMIT_CLEANUP_INTERVAL_MILLIS = 10_000L
private const val SHARED_RATE_LIMIT_REGISTRY_KEY = "shared"
private const val X_RATE_LIMIT_LIMIT = "X-RateLimit-Limit"
private const val X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining"
private const val X_RATE_LIMIT_RESET = "X-RateLimit-Reset"
private val SharedRateLimitIdentityKey = AttributeKey<String>("SharedRateLimitIdentity")
private val SharedRateLimitDecisionKey = AttributeKey<SharedRateLimitDecision>("SharedRateLimitDecision")

/** Installs process-local readiness and PostgreSQL-shared action/auth quotas before feature routes. */
internal fun Application.installRateLimits() {
    val sharedIpBuckets = with(RateLimits) {
        mapOf(
            LOGIN to loginLimit(),
            REFRESH to refreshLimit(),
            PASSWORD_RESET to passwordResetLimit(),
            MFA to mfaLimit(),
            PASSWORD_RESET_CONFIRM to passwordResetConfirmLimit(),
            LIVE_CONNECT to liveConnectLimit(),
        )
    }
    val perUserBuckets = with(RateLimits) {
        mapOf(
            TELEMETRY to telemetryLimit(),
            DIRECTORY to directoryLimit(),
            ROOM_NAME to roomNameLimit(),
            ROOM_MEMBERS to roomMembersLimit(),
            CHAT_POST to chatPostLimit(),
            CHAT_WRITE to chatWriteLimit(),
            CHAT_READ to chatReadLimit(),
            READ_STATE to readStateLimit(),
            THREAD_WATCH to threadWatchLimit(),
            NOTIFICATION_PREFS to notificationPrefsLimit(),
            NOTIFICATION_READ to notificationReadLimit(),
            LIVE_TICKET to liveTicketLimit(),
            SEARCH to searchLimit(),
            LINK_PREVIEW to linkPreviewLimit(),
            PASSWORD_CHANGE to passwordChangeLimit(),
        )
    }
    val digest = DeploymentKeyedDigest(attributes[JwtConfigKey].secret, RATE_LIMIT_DIGEST_LABEL)
    val sharedStore = lazy { SharedRateLimitStore(attributes[R2dbcDatabaseKey]) }
    val capacityAudit = RateLimitAuditThrottle()
    install(RateLimit) {
        registerLocalReadiness(with(RateLimits) { this@installRateLimits.readyLimit() })
        sharedIpBuckets.forEach { (name, limit) -> registerSharedIp(name, limit, sharedStore, capacityAudit, digest) }
        perUserBuckets.forEach { (name, limit) -> registerSharedUser(name, limit, sharedStore, capacityAudit, digest) }
    }
    launchRateLimitCleanup(sharedStore)
}

private fun ResponseHeaders.appendIfAbsent(name: String, value: String) {
    if (!contains(name)) append(name, value)
}

private fun io.ktor.server.plugins.ratelimit.RateLimitConfig.registerLocalReadiness(limit: Int) {
    register(RateLimitName(RateLimits.READY)) {
        rateLimiter(limit = limit, refillPeriod = 60.seconds)
        requestKey { call ->
            call.attributes.put(RateLimitBucketKey, RateLimits.READY)
            call.clientAddressKey()
        }
    }
}

private fun io.ktor.server.plugins.ratelimit.RateLimitConfig.registerSharedIp(
    name: String,
    limit: Int,
    store: Lazy<SharedRateLimitStore>,
    capacityAudit: RateLimitAuditThrottle,
    digest: DeploymentKeyedDigest,
) {
    register(RateLimitName(name)) {
        rateLimiter { _, _ -> SharedRateLimiter }
        requestKey { call ->
            call.attributes.put(RateLimitBucketKey, name)
            val namespace = call.application.attributes.getOrNull(RateLimitNamespaceOverrideKey).orEmpty()
            call.attributes.put(
                SharedRateLimitIdentityKey,
                digest.ofCanonical("$namespace\u0000ip\u0000$name\u0000${call.clientAddressKey()}"),
            )
            SHARED_RATE_LIMIT_REGISTRY_KEY
        }
        sharedDecision(store, capacityAudit, name, limit)
    }
}

private fun io.ktor.server.plugins.ratelimit.RateLimitConfig.registerSharedUser(
    name: String,
    limit: Int,
    store: Lazy<SharedRateLimitStore>,
    capacityAudit: RateLimitAuditThrottle,
    digest: DeploymentKeyedDigest,
) {
    register(RateLimitName(name)) {
        rateLimiter { _, _ -> SharedRateLimiter }
        requestKey { call ->
            call.attributes.put(RateLimitBucketKey, name)
            val userId = call.caller().userId
            call.attributes.put(RateLimitUserKey, userId)
            val namespace = call.application.attributes.getOrNull(RateLimitNamespaceOverrideKey).orEmpty()
            call.attributes.put(
                SharedRateLimitIdentityKey,
                digest.ofCanonical("$namespace\u0000user\u0000$name\u0000$userId"),
            )
            SHARED_RATE_LIMIT_REGISTRY_KEY
        }
        sharedDecision(store, capacityAudit, name, limit)
    }
}

private fun Application.launchRateLimitCleanup(store: Lazy<SharedRateLimitStore>) {
    backgroundScope("adder-rate-limit-cleanup").launch {
        while (true) {
            delay(RATE_LIMIT_CLEANUP_INTERVAL_MILLIS)
            try {
                store.value.pruneExpired()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                rateLimitLog.warn("shared rate-limit cleanup failed ({})", failure.javaClass.simpleName)
            }
        }
    }
}

private fun io.ktor.server.plugins.ratelimit.RateLimitProviderConfig.sharedDecision(
    store: Lazy<SharedRateLimitStore>,
    capacityAudit: RateLimitAuditThrottle,
    name: String,
    limit: Int,
) {
    requestWeight { call, _ ->
        val decision = store.value.consume(name, call.attributes[SharedRateLimitIdentityKey], limit)
        call.attributes.put(SharedRateLimitDecisionKey, decision)
        if (decision.capacityRefusal) {
            capacityAudit.admit(name)?.let { suppressed ->
                audit("rate_limit.saturated", "bucket" to name, "limit" to RATE_LIMIT_MAX_ACTIVE_KEYS, "suppressed" to suppressed)
            }
        }
        if (decision.admitted) SHARED_RATE_LIMIT_ADMITTED_WEIGHT else SHARED_RATE_LIMIT_REFUSED_WEIGHT
    }
    modifyResponse { call, _ ->
        val decision = call.attributes[SharedRateLimitDecisionKey]
        call.response.headers.appendIfAbsent(X_RATE_LIMIT_LIMIT, decision.limit.toString())
        call.response.headers.appendIfAbsent(X_RATE_LIMIT_REMAINING, decision.remaining.toString())
        call.response.headers.appendIfAbsent(X_RATE_LIMIT_RESET, ((decision.refillAtMillis + 999L) / 1_000L).toString())
        if (!decision.admitted) {
            val retryAfter = ((decision.refillAtMillis - decision.serverNowMillis).coerceAtLeast(1L) + 999L) / 1_000L
            call.response.headers.appendIfAbsent(HttpHeaders.RetryAfter, retryAfter.toString())
        }
    }
}
