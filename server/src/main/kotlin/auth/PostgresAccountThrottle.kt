package ch.nokillswit.auth

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.validation.canonicalEmail
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val LOGIN_DIGEST_LABEL = "adder.account-login-attempt.v1"
internal const val AUTH_STATE_EXPIRY_CLEANUP_BATCH = 64
private const val LOCK_IDENTITY = "SELECT pg_advisory_xact_lock(CAST(? AS int8))"
private const val LOCK_CAPACITY = "SELECT singleton FROM account_login_attempt_capacity_guard WHERE singleton FOR UPDATE"
internal const val AUTH_DATABASE_NOW = "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint"
private const val READ_STATE = """
SELECT failures, locked_until, state_expires_at, code_failed, escalation_level, escalation_anchor,
       escalation_expires_at, code_guesses, code_expires_at
FROM account_login_attempts WHERE identity_hash = CAST(? AS char(64)) FOR UPDATE
"""
private const val ORDINARY_STATE_COUNT = "SELECT count(*)::int FROM account_login_attempts WHERE user_id IS NULL"
private const val LOCK_USER = """
SELECT email, credential_revision FROM users WHERE id = CAST(? AS int8) AND NOT marked_as_deleted FOR NO KEY UPDATE
"""
private const val READ_BOUND_HASH = """
SELECT identity_hash FROM account_login_attempts WHERE user_id = CAST(? AS int8)
"""
private const val READ_BOUND_USER = """
SELECT user_id FROM account_login_attempts WHERE identity_hash = CAST(? AS char(64))
"""
private const val PRUNE_EXPIRED = """
WITH expired AS (
    SELECT identity_hash FROM account_login_attempts
    WHERE GREATEST(state_expires_at, escalation_expires_at, code_expires_at) <= CAST(? AS int8)
    ORDER BY GREATEST(state_expires_at, escalation_expires_at, code_expires_at), identity_hash
    LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED
)
DELETE FROM account_login_attempts AS attempts USING expired
WHERE attempts.identity_hash = expired.identity_hash
"""
private const val WRITE_STATE = """
INSERT INTO account_login_attempts (
    identity_hash, user_id, failures, locked_until, state_expires_at, code_failed, escalation_level,
    escalation_anchor, escalation_expires_at, code_guesses, code_expires_at
) VALUES (CAST(? AS char(64)), CAST(? AS int8), CAST(? AS int4), CAST(? AS int8), CAST(? AS int8), CAST(? AS boolean),
          CAST(? AS int4), CAST(? AS int8), CAST(? AS int8), CAST(? AS int4), CAST(? AS int8))
ON CONFLICT (identity_hash) DO UPDATE SET
    user_id = COALESCE(account_login_attempts.user_id, EXCLUDED.user_id),
    failures = EXCLUDED.failures,
    locked_until = EXCLUDED.locked_until,
    state_expires_at = EXCLUDED.state_expires_at,
    code_failed = EXCLUDED.code_failed,
    escalation_level = EXCLUDED.escalation_level,
    escalation_anchor = EXCLUDED.escalation_anchor,
    escalation_expires_at = EXCLUDED.escalation_expires_at,
    code_guesses = EXCLUDED.code_guesses,
    code_expires_at = EXCLUDED.code_expires_at
"""
private const val DELETE_STATE =
    "DELETE FROM account_login_attempts WHERE identity_hash = CAST(? AS char(64)) RETURNING identity_hash"

/** PostgreSQL implementation of the lockout policy. Proved-account operations lock the user, then old/current identity keys. */
internal class PostgresAccountThrottle(
    private val database: R2dbcDatabase,
    jwtSecret: String,
    threshold: Int,
    lockoutMillis: Long,
    private val maxTracked: Int,
    mfaMaxLockoutMillis: Long,
    mfaDecayMillis: Long,
) : AccountThrottle {
    private val digest = DeploymentKeyedDigest(jwtSecret, LOGIN_DIGEST_LABEL)
    private val policy = LoginAttemptPolicy(threshold, lockoutMillis, mfaMaxLockoutMillis, mfaDecayMillis)

    override suspend fun preflight(email: String, knownUserId: UInt?): LoginThrottle.Preflight =
        identityTransaction(email, knownUserId, LoginThrottle.Preflight.LOCKED) { identity, now ->
            val (_, state) = identity
            when {
                policy.isLocked(state, now) -> LoginThrottle.Preflight.LOCKED
                state.stateExpiresAt > now || knownUserId != null -> LoginThrottle.Preflight.ALLOWED
                else -> {
                    lockCapacity()
                    prune(now)
                    if (ordinaryCount() < maxTracked) LoginThrottle.Preflight.ALLOWED else LoginThrottle.Preflight.CAPACITY_EXCEEDED
                }
            }
        }

    override suspend fun recordFailure(
        email: String,
        knownUserId: UInt?,
        mfaCode: Boolean,
    ): LoginThrottle.FailureOutcome {
        require(knownUserId != null || !mfaCode) { "an MFA code failure belongs to a known account" }
        val stale = LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.ALREADY_LOCKED)
        return identityTransaction(email, knownUserId, stale) { identity, now -> failLocked(identity, now, knownUserId, mfaCode) }
    }

    suspend fun recordMfaFailure(subject: MfaChallenges.Subject, revision: Long): MfaAttempt<LoginThrottle.FailureOutcome> =
        withMfaCredential(subject, revision) { identity, now -> failLocked(identity, now, subject.userId, mfaCode = true) }

    suspend fun recordMfaSuccess(subject: MfaChallenges.Subject, revision: Long): MfaAttempt<LoginThrottle.SuccessResult> =
        withMfaCredential(subject, revision, ::successLocked)

    /** The credential fence and the known-account reservation both use the same locked user row. */
    private suspend fun <T> withMfaCredential(
        subject: MfaChallenges.Subject,
        revision: Long,
        record: suspend (Pair<String, LoginAttemptState>, Long) -> T,
    ): MfaAttempt<T> = suspendTransaction(database) {
        val user = lockUser(subject.userId) ?: return@suspendTransaction MfaAttempt.Stale.USER_GONE
        if (user.revision != revision) return@suspendTransaction MfaAttempt.Stale.CREDENTIAL_CHANGED
        val currentEmail = canonicalEmail(user.email)
        if (canonicalEmail(subject.email) != currentEmail) return@suspendTransaction MfaAttempt.Stale.EMAIL_CHANGED
        MfaAttempt.Current(boundIdentity(currentEmail, subject.userId, record))
    }

    private suspend fun failLocked(
        identity: Pair<String, LoginAttemptState>,
        now: Long,
        knownUserId: UInt?,
        mfaCode: Boolean,
    ): LoginThrottle.FailureOutcome {
        val (hash, state) = identity
        if (state.stateExpiresAt == 0L && knownUserId == null) {
            lockCapacity()
            prune(now)
            if (ordinaryCount() >= maxTracked) return LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.CAPACITY_EXCEEDED)
        }
        val transition = policy.failure(state, now, mfaCode)
        write(hash, knownUserId, transition.state)
        return transition.outcome
    }

    override suspend fun recordSuccess(email: String, knownUserId: UInt?): LoginThrottle.SuccessResult =
        identityTransaction(email, knownUserId, LoginThrottle.SuccessResult.LOCKED, ::successLocked)

    private suspend fun successLocked(identity: Pair<String, LoginAttemptState>, now: Long): LoginThrottle.SuccessResult {
        val (hash, state) = identity
        if (policy.isLocked(state, now)) return LoginThrottle.SuccessResult.LOCKED
        delete(hash)
        return LoginThrottle.SuccessResult.CLEARED
    }

    override suspend fun clear(email: String, knownUserId: UInt?): Boolean =
        suspendTransaction(database) { clearInCurrentTransaction(email, knownUserId) }

    override suspend fun clearInCurrentTransaction(email: String, knownUserId: UInt?): Boolean =
        identityTransactionInCurrent(email, knownUserId, false) { identity, _ ->
            val (hash, state) = identity
            delete(hash)
            !state.isEmpty()
        }

    private suspend fun <T> identityTransaction(
        email: String,
        knownUserId: UInt?,
        stale: T,
        block: suspend (Pair<String, LoginAttemptState>, Long) -> T,
    ): T = suspendTransaction(database) { identityTransactionInCurrent(email, knownUserId, stale, block) }

    private suspend fun <T> identityTransactionInCurrent(
        email: String,
        knownUserId: UInt?,
        stale: T,
        block: suspend (Pair<String, LoginAttemptState>, Long) -> T,
    ): T {
        if (knownUserId != null) return knownIdentity(email, knownUserId, stale, block)
        val hash = digest.ofCanonical(canonicalEmail(email))
        lockIdentity(hash)
        val now = databaseNow()
        return block(hash to read(hash).normalized(now), now)
    }

    private suspend fun <T> knownIdentity(
        capturedEmail: String,
        userId: UInt,
        stale: T,
        block: suspend (Pair<String, LoginAttemptState>, Long) -> T,
    ): T {
        val user = lockUser(userId) ?: return stale
        val currentEmail = canonicalEmail(user.email)
        if (canonicalEmail(capturedEmail) != currentEmail) return stale
        return boundIdentity(currentEmail, userId, block)
    }

    private suspend fun <T> boundIdentity(
        currentEmail: String,
        userId: UInt,
        block: suspend (Pair<String, LoginAttemptState>, Long) -> T,
    ): T {
        val currentHash = digest.ofCanonical(currentEmail)
        val oldHash = boundHash(userId)
        listOfNotNull(oldHash, currentHash).distinct().sorted().forEach { lockIdentity(it) }
        val validOldHash = oldHash?.takeIf { boundUser(it) == userId }
        val currentOwner = boundUser(currentHash)
        val now = databaseNow()
        // A row bound to another durable user belongs to that former owner, not to this address forever. The locked active user row
        // proves this user owns the canonical address now; discard (rather than inherit) the former owner's lock and MFA ladder.
        val currentState = if (currentOwner != null && currentOwner != userId) LoginAttemptState() else read(currentHash).normalized(now)
        val oldState = validOldHash?.takeIf { it != currentHash }?.let { read(it).normalized(now) }
        val state = mergeStates(currentState, oldState)
        if (validOldHash != null && validOldHash != currentHash) delete(validOldHash)
        if (state.isEmpty()) {
            delete(currentHash)
        } else if (validOldHash != currentHash || currentOwner != userId) {
            // An active user row at this canonical email proves another user's old digest binding stale: reclaim it under the
            // identity lock. The partial unique active-email index means two live accounts cannot legitimately own this digest.
            if (currentOwner != null && currentOwner != userId) delete(currentHash)
            write(currentHash, userId, state)
        }
        return block(currentHash to state, now)
    }

    private data class LockedUser(val email: String, val revision: Long)

    private suspend fun lockUser(userId: UInt): LockedUser? = TransactionManager.current().exec(
        LOCK_USER,
        listOf(LongColumnType() to userId.toLong()),
        explicitStatementType = StatementType.SELECT,
    ) { row -> LockedUser(row.get(0, String::class.java)!!, row.get(1, Long::class.javaObjectType)!!) }?.firstOrNull()

    private suspend fun boundHash(userId: UInt): String? = TransactionManager.current().exec(
        READ_BOUND_HASH,
        listOf(LongColumnType() to userId.toLong()),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, String::class.java)!!.trim() }?.firstOrNull()

    private suspend fun boundUser(hash: String): UInt? = TransactionManager.current().exec(
        READ_BOUND_USER,
        listOf(TextColumnType() to hash),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Long::class.javaObjectType)?.toUInt() }?.firstOrNull()

    private suspend fun lockIdentity(hash: String) {
        TransactionManager.current().exec(
            LOCK_IDENTITY,
            listOf(LongColumnType() to advisoryLockKey(hash)),
            explicitStatementType = StatementType.SELECT,
        ) { Unit }?.firstOrNull()
    }

    private suspend fun databaseNow(): Long = TransactionManager.current().exec(
        AUTH_DATABASE_NOW,
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Long::class.javaObjectType)!! }!!.firstOrNull()!!

    private suspend fun read(hash: String): LoginAttemptState = TransactionManager.current().exec(
        READ_STATE,
        listOf(TextColumnType() to hash),
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        LoginAttemptState(
            failures = row.get(0, Int::class.javaObjectType)!!,
            lockedUntil = row.get(1, Long::class.javaObjectType)!!,
            stateExpiresAt = row.get(2, Long::class.javaObjectType)!!,
            codeFailed = row.get(3, Boolean::class.javaObjectType)!!,
            escalationLevel = row.get(4, Int::class.javaObjectType)!!,
            escalationAnchor = row.get(5, Long::class.javaObjectType)!!,
            escalationExpiresAt = row.get(6, Long::class.javaObjectType)!!,
            codeGuesses = row.get(7, Int::class.javaObjectType)!!,
            codeExpiresAt = row.get(8, Long::class.javaObjectType)!!,
        )
    }?.firstOrNull() ?: LoginAttemptState()

    private suspend fun write(hash: String, userId: UInt?, state: LoginAttemptState) {
        TransactionManager.current().exec(
            WRITE_STATE,
            listOf(
                TextColumnType() to hash,
                LongColumnType() to userId?.toLong(),
                IntegerColumnType() to state.failures,
                LongColumnType() to state.lockedUntil,
                LongColumnType() to state.stateExpiresAt,
                BooleanColumnType() to state.codeFailed,
                IntegerColumnType() to state.escalationLevel,
                LongColumnType() to state.escalationAnchor,
                LongColumnType() to state.escalationExpiresAt,
                IntegerColumnType() to state.codeGuesses,
                LongColumnType() to state.codeExpiresAt,
            ),
            explicitStatementType = StatementType.INSERT,
        )
    }

    private suspend fun delete(hash: String): Boolean = TransactionManager.current().exec(
        DELETE_STATE,
        listOf(TextColumnType() to hash),
        explicitStatementType = StatementType.SELECT,
    ) { Unit }?.firstOrNull() != null

    private suspend fun lockCapacity() {
        TransactionManager.current().exec(LOCK_CAPACITY, explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Boolean::class.javaObjectType)!!
        }?.firstOrNull() ?: error("Account login-attempt capacity guard is missing")
    }

    private suspend fun ordinaryCount(): Int = TransactionManager.current().exec(
        ORDINARY_STATE_COUNT,
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Int::class.javaObjectType)!! }!!.firstOrNull()!!

    private suspend fun prune(now: Long) {
        TransactionManager.current().exec(
            PRUNE_EXPIRED,
            listOf(LongColumnType() to now, IntegerColumnType() to AUTH_STATE_EXPIRY_CLEANUP_BATCH),
            explicitStatementType = StatementType.DELETE,
        )
    }
}

private fun advisoryLockKey(hash: String): Long = java.lang.Long.parseUnsignedLong(hash.take(16), 16)

private fun mergeStates(first: LoginAttemptState, second: LoginAttemptState?): LoginAttemptState {
    if (second == null) return first
    val escalation = maxOf(first, second, compareBy<LoginAttemptState> { it.escalationLevel }.thenBy { it.escalationExpiresAt })
    return LoginAttemptState(
        failures = saturatingAdd(first.failures, second.failures),
        lockedUntil = maxOf(first.lockedUntil, second.lockedUntil),
        stateExpiresAt = maxOf(first.stateExpiresAt, second.stateExpiresAt),
        codeFailed = first.codeFailed || second.codeFailed,
        escalationLevel = escalation.escalationLevel,
        escalationAnchor = escalation.escalationAnchor,
        escalationExpiresAt = escalation.escalationExpiresAt,
        codeGuesses = saturatingAdd(first.codeGuesses, second.codeGuesses),
        codeExpiresAt = maxOf(first.codeExpiresAt, second.codeExpiresAt),
    )
}

internal sealed interface MfaAttempt<out T> {
    data class Current<T>(val outcome: T) : MfaAttempt<T>

    enum class Stale(val auditReason: String) : MfaAttempt<Nothing> {
        USER_GONE("user_gone"),
        CREDENTIAL_CHANGED("credential_changed"),
        EMAIL_CHANGED("email_changed"),
    }
}
