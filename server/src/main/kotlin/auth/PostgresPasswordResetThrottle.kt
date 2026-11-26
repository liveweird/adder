package ch.nokillswit.auth

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.validation.canonicalEmail
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val RESET_DIGEST_LABEL = "adder.password-reset-cooldown.v1"
private const val LOCK_RESET_CAPACITY =
    "SELECT singleton FROM password_reset_cooldown_capacity_guard WHERE singleton FOR UPDATE"
private const val PRUNE_RESET_COOLDOWNS = """
WITH expired AS (
    SELECT identity_hash FROM password_reset_cooldowns
    WHERE expires_at <= CAST(? AS int8)
    ORDER BY expires_at, identity_hash LIMIT CAST(? AS int4)
    FOR UPDATE SKIP LOCKED
)
DELETE FROM password_reset_cooldowns AS cooldown USING expired
WHERE cooldown.identity_hash = expired.identity_hash
"""
private const val READ_RESET_COOLDOWN =
    "SELECT expires_at FROM password_reset_cooldowns WHERE identity_hash = CAST(? AS char(64)) FOR UPDATE"
private const val COUNT_RESET_COOLDOWNS =
    "SELECT count(*)::int FROM password_reset_cooldowns WHERE expires_at > CAST(? AS int8)"
private const val WRITE_RESET_COOLDOWN = """
INSERT INTO password_reset_cooldowns (identity_hash, expires_at) VALUES (CAST(? AS char(64)), CAST(? AS int8))
ON CONFLICT (identity_hash) DO UPDATE SET expires_at = EXCLUDED.expires_at
"""

internal interface ResetRequestThrottle {
    suspend fun tryAcquire(email: String): PasswordResetThrottle.AcquireResult
}

/** One short guarded transaction keeps the low-rate reset interval and its capacity cluster-wide. */
internal class PostgresPasswordResetThrottle(
    private val database: R2dbcDatabase,
    jwtSecret: String,
    private val minIntervalMillis: Long,
    private val maxTracked: Int,
) : ResetRequestThrottle {
    private val digest = DeploymentKeyedDigest(jwtSecret, RESET_DIGEST_LABEL)

    override suspend fun tryAcquire(email: String): PasswordResetThrottle.AcquireResult = suspendTransaction(database) {
        TransactionManager.current().exec(LOCK_RESET_CAPACITY, explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Boolean::class.javaObjectType)!!
        }?.firstOrNull() ?: error("Password-reset cooldown capacity guard is missing")
        val now = TransactionManager.current().exec(
            AUTH_DATABASE_NOW,
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType)!! }!!.firstOrNull()!!
        TransactionManager.current().exec(
            PRUNE_RESET_COOLDOWNS,
            listOf(LongColumnType() to now, IntegerColumnType() to AUTH_STATE_EXPIRY_CLEANUP_BATCH),
            explicitStatementType = StatementType.DELETE,
        )
        val hash = digest.ofCanonical(canonicalEmail(email))
        val expiresAt = TransactionManager.current().exec(
            READ_RESET_COOLDOWN,
            listOf(TextColumnType() to hash),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType)!! }?.firstOrNull()
        if (expiresAt != null && expiresAt > now) return@suspendTransaction PasswordResetThrottle.AcquireResult.COOLDOWN
        val active = TransactionManager.current().exec(
            COUNT_RESET_COOLDOWNS,
            listOf(LongColumnType() to now),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!! }!!.firstOrNull()!!
        if (active >= maxTracked) return@suspendTransaction PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED
        TransactionManager.current().exec(
            WRITE_RESET_COOLDOWN,
            listOf(TextColumnType() to hash, LongColumnType() to now + minIntervalMillis),
            explicitStatementType = StatementType.INSERT,
        )
        PasswordResetThrottle.AcquireResult.ACQUIRED
    }
}
