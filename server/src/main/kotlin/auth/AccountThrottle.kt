package ch.nokillswit.auth

/** The credential-guess policy port used by routes. Production state is PostgreSQL-shared; the pure kernel backs unit tests. */
internal interface AccountThrottle {
    suspend fun preflight(email: String, knownAccount: Boolean = false): LoginThrottle.Preflight
    suspend fun recordFailure(
        email: String,
        knownAccount: Boolean = false,
        mfaCode: Boolean = false,
    ): LoginThrottle.FailureOutcome
    suspend fun recordSuccess(email: String): LoginThrottle.SuccessResult
    suspend fun clear(email: String): Boolean
    /** Called from the password-reset transaction. Production implementations must use its current database transaction. */
    suspend fun clearInCurrentTransaction(email: String): Boolean = clear(email)
}

/** Keeps the existing deterministic policy kernel available to focused tests without a database. */
internal class InMemoryAccountThrottle(private val policy: LoginThrottle) : AccountThrottle {
    override suspend fun preflight(email: String, knownAccount: Boolean) = policy.preflight(email, knownAccount)

    override suspend fun recordFailure(email: String, knownAccount: Boolean, mfaCode: Boolean) =
        policy.recordFailureOutcome(email, knownAccount, mfaCode)

    override suspend fun recordSuccess(email: String) = policy.recordSuccess(email)

    override suspend fun clear(email: String) = policy.clear(email)
}
