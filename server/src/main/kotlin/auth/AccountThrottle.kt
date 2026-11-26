package ch.nokillswit.auth

/** The credential-guess policy port used by routes. Production state is PostgreSQL-shared; the pure kernel backs unit tests. */
internal interface AccountThrottle {
    suspend fun preflight(email: String, knownUserId: UInt? = null): LoginThrottle.Preflight
    suspend fun recordFailure(
        email: String,
        knownUserId: UInt? = null,
        mfaCode: Boolean = false,
    ): LoginThrottle.FailureOutcome
    suspend fun recordSuccess(email: String, knownUserId: UInt? = null): LoginThrottle.SuccessResult
    suspend fun clear(email: String, knownUserId: UInt? = null): Boolean
    /** Called from the password-reset transaction. Production implementations must use its current database transaction. */
    suspend fun clearInCurrentTransaction(email: String, knownUserId: UInt? = null): Boolean = clear(email, knownUserId)
}

/** Keeps the existing deterministic policy kernel available to focused tests without a database. */
internal class InMemoryAccountThrottle(private val policy: LoginThrottle) : AccountThrottle {
    override suspend fun preflight(email: String, knownUserId: UInt?) = policy.preflight(email, knownUserId != null)

    override suspend fun recordFailure(email: String, knownUserId: UInt?, mfaCode: Boolean) =
        policy.recordFailureOutcome(email, knownUserId != null, mfaCode)

    override suspend fun recordSuccess(email: String, knownUserId: UInt?) = policy.recordSuccess(email)

    override suspend fun clear(email: String, knownUserId: UInt?) = policy.clear(email)
}
