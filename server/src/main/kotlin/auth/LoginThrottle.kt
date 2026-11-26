package ch.nokillswit.auth

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.sha256Hex

/**
 * Deterministic in-memory adapter around [LoginAttemptPolicy]. Production routes use [PostgresAccountThrottle], while these methods
 * keep the full credential-guess policy cheap to exercise on an injected clock.
 */
class LoginThrottle(
    private val threshold: Int,
    private val lockoutMillis: Long,
    private val maxTracked: Int = 10_000,
    mfaMaxLockoutMillis: Long = DEFAULT_MFA_MAX_LOCKOUT_MILLIS,
    private val mfaDecayMillis: Long = DEFAULT_MFA_DECAY_MILLIS,
    private val clock: () -> Long = ::nowMillis,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
        require(mfaDecayMillis > 0) { "mfaDecayMillis must be positive" }
    }

    enum class Preflight { ALLOWED, LOCKED, CAPACITY_EXCEEDED }
    enum class FailureResult { RECORDED, LOCKED_NOW, ALREADY_LOCKED, CAPACITY_EXCEEDED }
    enum class SuccessResult { CLEARED, LOCKED }

    data class FailureOutcome(val result: FailureResult, val lockMillis: Long = 0, val level: Int = 0)

    private val policy = LoginAttemptPolicy(threshold, lockoutMillis, mfaMaxLockoutMillis, mfaDecayMillis)
    private val states = mutableMapOf<String, LoginAttemptState>()
    private val lock = Any()

    fun preflight(email: String, knownAccount: Boolean = false): Preflight = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        val state = states[key(email)]
        when {
            state != null && policy.isLocked(state, now) -> Preflight.LOCKED
            state?.stateExpiresAt?.let { it > now } == true || knownAccount || activeStateCount(now) < maxTracked -> Preflight.ALLOWED
            else -> Preflight.CAPACITY_EXCEEDED
        }
    }

    fun recordFailure(email: String, knownAccount: Boolean = false, mfaCode: Boolean = false): FailureResult =
        recordFailureOutcome(email, knownAccount, mfaCode).result

    fun recordFailureOutcome(email: String, knownAccount: Boolean = false, mfaCode: Boolean = false): FailureOutcome {
        require(knownAccount || !mfaCode) { "an MFA code failure belongs to a known account" }
        return synchronized(lock) {
            val now = clock()
            pruneExpired(now)
            val key = key(email)
            val current = states[key] ?: LoginAttemptState()
            if (current.stateExpiresAt <= now && !knownAccount && activeStateCount(now) >= maxTracked) {
                return@synchronized FailureOutcome(FailureResult.CAPACITY_EXCEEDED)
            }
            policy.failure(current, now, mfaCode).also { states[key] = it.state }.outcome
        }
    }

    fun recordSuccess(email: String): SuccessResult = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        val key = key(email)
        if (states[key]?.let { policy.isLocked(it, now) } == true) {
            SuccessResult.LOCKED
        } else {
            states.remove(key)
            SuccessResult.CLEARED
        }
    }

    fun clear(email: String): Boolean = synchronized(lock) {
        pruneExpired(clock())
        states.remove(key(email)) != null
    }

    private fun activeStateCount(now: Long) = states.values.count { it.stateExpiresAt > now }

    private fun pruneExpired(now: Long) {
        states.replaceAll { _, state -> state.normalized(now) }
        states.values.removeIf { it.isEmpty() }
    }

    private fun key(email: String) = loginIdentityKey(email)

    internal fun escalationCount(): Int = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        states.values.count { it.escalationExpiresAt > now }
    }

    internal fun escalationInstants(): Int = escalationCount()

    internal fun codeGuessCount(email: String): Int = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        states[key(email)]?.codeGuesses ?: 0
    }

    internal fun codeGuessAccounts(): Int = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        states.values.count { it.codeExpiresAt > now }
    }

    internal fun codeGuessInstants(): Int = codeGuessAccounts()

    companion object {
        const val DEFAULT_MFA_MAX_LOCKOUT_MILLIS = 24L * 3600 * 1000
        const val DEFAULT_MFA_DECAY_MILLIS = 24L * 3600 * 1000
    }
}

/** Fixed-size test-adapter key. Production uses a deployment-keyed HMAC. */
internal fun loginIdentityKey(email: String): String = sha256Hex(email.trim().lowercase())
