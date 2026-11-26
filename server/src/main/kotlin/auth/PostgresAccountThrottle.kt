package ch.nokillswit.auth

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
private const val ACTIVE_STATE_COUNT = """
SELECT count(*)::int FROM account_login_attempts WHERE state_expires_at > CAST(? AS int8)
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
    identity_hash, failures, locked_until, state_expires_at, code_failed, escalation_level,
    escalation_anchor, escalation_expires_at, code_guesses, code_expires_at
) VALUES (CAST(? AS char(64)), CAST(? AS int4), CAST(? AS int8), CAST(? AS int8), CAST(? AS boolean),
          CAST(? AS int4), CAST(? AS int8), CAST(? AS int8), CAST(? AS int4), CAST(? AS int8))
ON CONFLICT (identity_hash) DO UPDATE SET
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

/** PostgreSQL implementation of the existing lockout policy. Each operation serializes only one identity. */
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

    override suspend fun preflight(email: String, knownAccount: Boolean): LoginThrottle.Preflight = transaction(email) { hash, now ->
        val state = read(hash).normalized(now)
        when {
            policy.isLocked(state, now) -> LoginThrottle.Preflight.LOCKED
            state.stateExpiresAt > now || knownAccount -> LoginThrottle.Preflight.ALLOWED
            else -> {
                lockCapacity()
                prune(now)
                if (activeCount(now) < maxTracked) LoginThrottle.Preflight.ALLOWED else LoginThrottle.Preflight.CAPACITY_EXCEEDED
            }
        }
    }

    override suspend fun recordFailure(
        email: String,
        knownAccount: Boolean,
        mfaCode: Boolean,
    ): LoginThrottle.FailureOutcome {
        require(knownAccount || !mfaCode) { "an MFA code failure belongs to a known account" }
        return transaction(email) { hash, now ->
            val persisted = read(hash)
            val state = persisted.normalized(now)
            if (state.stateExpiresAt == 0L && !knownAccount) {
                lockCapacity()
                prune(now)
                if (activeCount(now) >= maxTracked) {
                    return@transaction LoginThrottle.FailureOutcome(LoginThrottle.FailureResult.CAPACITY_EXCEEDED)
                }
            }
            val transition = policy.failure(state, now, mfaCode)
            write(hash, transition.state)
            transition.outcome
        }
    }

    override suspend fun recordSuccess(email: String): LoginThrottle.SuccessResult = transaction(email) { hash, now ->
        if (policy.isLocked(read(hash), now)) {
            LoginThrottle.SuccessResult.LOCKED
        } else {
            delete(hash)
            LoginThrottle.SuccessResult.CLEARED
        }
    }

    override suspend fun clear(email: String): Boolean = suspendTransaction(database) { clearInCurrentTransaction(email) }

    override suspend fun clearInCurrentTransaction(email: String): Boolean {
        val hash = digest.of(email)
        lockIdentity(hash)
        val heldLiveState = !read(hash).normalized(databaseNow()).isEmpty()
        delete(hash)
        return heldLiveState
    }

    private suspend fun <T> transaction(email: String, block: suspend (String, Long) -> T): T = suspendTransaction(database) {
        val hash = digest.of(email)
        lockIdentity(hash)
        block(hash, databaseNow())
    }

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

    private suspend fun write(hash: String, state: LoginAttemptState) {
        TransactionManager.current().exec(
            WRITE_STATE,
            listOf(
                TextColumnType() to hash,
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

    private suspend fun activeCount(now: Long): Int = TransactionManager.current().exec(
        ACTIVE_STATE_COUNT,
        listOf(LongColumnType() to now),
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
