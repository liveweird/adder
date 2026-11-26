package ch.nokillswit.plugins

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.clientAddressKey
import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val rateLimitLog = LoggerFactory.getLogger("ch.nokillswit.plugins.RateLimits")
private const val RATE_LIMIT_DIGEST_LABEL = "adder.http-rate-limit-key.v1"
private const val RATE_LIMIT_CLEANUP_INTERVAL_MILLIS = 10_000L

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
        rateLimiter { _, key -> sharedLimiter(store, capacityAudit, name, key, limit) }
        requestKey { call ->
            call.attributes.put(RateLimitBucketKey, name)
            val namespace = call.application.attributes.getOrNull(RateLimitNamespaceOverrideKey).orEmpty()
            digest.ofCanonical("$namespace\u0000ip\u0000$name\u0000${call.clientAddressKey()}")
        }
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
        rateLimiter { _, key -> sharedLimiter(store, capacityAudit, name, key, limit) }
        requestKey { call ->
            call.attributes.put(RateLimitBucketKey, name)
            val userId = call.caller().userId
            call.attributes.put(RateLimitUserKey, userId)
            val namespace = call.application.attributes.getOrNull(RateLimitNamespaceOverrideKey).orEmpty()
            digest.ofCanonical("$namespace\u0000user\u0000$name\u0000$userId")
        }
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

private fun sharedLimiter(
    store: Lazy<SharedRateLimitStore>,
    capacityAudit: RateLimitAuditThrottle,
    name: String,
    key: Any,
    limit: Int,
): SharedRateLimiter = SharedRateLimiter({ store.value }, name, key as String, limit) {
    capacityAudit.admit(name)?.let { suppressed ->
        audit("rate_limit.saturated", "bucket" to name, "limit" to RATE_LIMIT_MAX_ACTIVE_KEYS, "suppressed" to suppressed)
    }
}
