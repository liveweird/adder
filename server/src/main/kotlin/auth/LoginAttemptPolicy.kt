package ch.nokillswit.auth

/** Immutable storage shape shared by the in-memory policy tests and the PostgreSQL adapter. Zero deadlines mean absent state. */
internal data class LoginAttemptState(
    val failures: Int = 0,
    val lockedUntil: Long = 0,
    val stateExpiresAt: Long = 0,
    val codeFailed: Boolean = false,
    val escalationLevel: Int = 0,
    val escalationAnchor: Long = 0,
    val escalationExpiresAt: Long = 0,
    val codeGuesses: Int = 0,
    val codeExpiresAt: Long = 0,
) {
    fun normalized(now: Long): LoginAttemptState = copy(
        failures = failures.takeIf { stateExpiresAt > now } ?: 0,
        lockedUntil = lockedUntil.takeIf { stateExpiresAt > now } ?: 0,
        stateExpiresAt = stateExpiresAt.takeIf { it > now } ?: 0,
        codeFailed = codeFailed && stateExpiresAt > now,
        escalationLevel = escalationLevel.takeIf { escalationExpiresAt > now } ?: 0,
        escalationAnchor = escalationAnchor.takeIf { escalationExpiresAt > now } ?: 0,
        escalationExpiresAt = escalationExpiresAt.takeIf { it > now } ?: 0,
        codeGuesses = codeGuesses.takeIf { codeExpiresAt > now } ?: 0,
        codeExpiresAt = codeExpiresAt.takeIf { it > now } ?: 0,
    )

    fun isEmpty() = stateExpiresAt == 0L && escalationExpiresAt == 0L && codeExpiresAt == 0L
}

internal data class LoginFailureTransition(
    val outcome: LoginThrottle.FailureOutcome,
    val state: LoginAttemptState,
)

/** The single credential-failure transition kernel. Storage adapters own serialization, capacity and expiration cleanup. */
internal class LoginAttemptPolicy(
    private val threshold: Int,
    private val lockoutMillis: Long,
    mfaMaxLockoutMillis: Long,
    private val mfaDecayMillis: Long,
) {
    private val mfaCapMillis = maxOf(mfaMaxLockoutMillis, lockoutMillis)

    fun isLocked(state: LoginAttemptState, now: Long): Boolean = state.normalized(now).lockedUntil > now

    fun failure(stored: LoginAttemptState, now: Long, mfaCode: Boolean): LoginFailureTransition {
        val state = stored.normalized(now)
        if (state.lockedUntil > now) {
            return LoginFailureTransition(
                LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.ALREADY_LOCKED),
                state,
            )
        }
        val failures = saturatingAdd(state.failures, 1)
        val codeFailed = mfaCode || state.codeFailed
        val codeCount = saturatingAdd(state.codeGuesses, if (mfaCode) 1 else 0)
        val codeExpiresAt = when {
            !mfaCode -> state.codeExpiresAt
            state.codeExpiresAt > now -> state.codeExpiresAt
            else -> now + mfaDecayMillis
        }
        if (failures < threshold && (!mfaCode || codeCount < threshold)) {
            return LoginFailureTransition(
                LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.RECORDED),
                state.copy(
                    failures = failures,
                    lockedUntil = 0,
                    stateExpiresAt = now + lockoutMillis,
                    codeFailed = codeFailed,
                    codeGuesses = codeCount,
                    codeExpiresAt = codeExpiresAt,
                ),
            )
        }
        val level = if (codeFailed) minOf(levelAt(state, now) + 1, MAX_ESCALATION_LEVEL) else 0
        val duration = if (codeFailed) escalatedLockMillis(level) else lockoutMillis
        val lockedUntil = now + duration
        return LoginFailureTransition(
            LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.LOCKED_NOW, duration, level),
            state.copy(
                failures = 0,
                lockedUntil = lockedUntil,
                stateExpiresAt = lockedUntil,
                codeFailed = false,
                escalationLevel = if (codeFailed) level else state.escalationLevel,
                escalationAnchor = if (codeFailed) lockedUntil else state.escalationAnchor,
                escalationExpiresAt = if (codeFailed) lockedUntil + level * mfaDecayMillis else state.escalationExpiresAt,
                codeGuesses = if (codeFailed) 0 else codeCount,
                codeExpiresAt = if (codeFailed) 0 else codeExpiresAt,
            ),
        )
    }

    fun levelAt(stored: LoginAttemptState, now: Long): Int {
        val state = stored.normalized(now)
        if (state.escalationExpiresAt == 0L) return 0
        val steps = if (now > state.escalationAnchor) (now - state.escalationAnchor) / mfaDecayMillis else 0
        return maxOf(0L, state.escalationLevel - steps).toInt()
    }

    private fun escalatedLockMillis(level: Int): Long {
        var millis = lockoutMillis
        repeat(level - 1) { millis = minOf(millis * 2, mfaCapMillis) }
        return millis
    }

    private companion object {
        const val MAX_ESCALATION_LEVEL = 32
    }
}

internal fun saturatingAdd(first: Int, second: Int): Int =
    if (Int.MAX_VALUE - first < second) Int.MAX_VALUE else first + second
