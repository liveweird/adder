package ch.nokillswit.auth

import ch.nokillswit.infra.notify.notifyLiveAccount
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val MAX_ROTATION_RECORDS = 10_000
internal const val MAX_GRACE_REUSES = 2
private const val PRUNE_BATCH = 256

internal enum class RefreshPrecheck { AVAILABLE, GRACE, REPLAYED, REVOKED }

internal class RefreshTokenRevocation(val userId: UInt, val jti: String, val expiresAtMillis: Long)

private const val ENSURE_GUARD = """
INSERT INTO refresh_rotation_user_guards (user_id) VALUES (CAST(? AS int8)) ON CONFLICT DO NOTHING
"""

private const val LOCK_GUARD = """
SELECT user_id FROM refresh_rotation_user_guards WHERE user_id = CAST(? AS int8) FOR UPDATE
"""

private const val PRUNE_REVOKED = """
WITH expired AS (
    SELECT jti FROM revoked_tokens
    WHERE expires_at <= floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ORDER BY expires_at, jti
    LIMIT CAST(? AS int4)
    FOR UPDATE SKIP LOCKED
)
DELETE FROM revoked_tokens AS token USING expired WHERE token.jti = expired.jti
"""

private const val INSERT_REVOCATION = """
INSERT INTO revoked_tokens (jti, expires_at) VALUES (?, CAST(? AS int8))
ON CONFLICT DO NOTHING RETURNING jti
"""

private const val FREE_SLOT = """
SELECT slot FROM refresh_rotation_slots AS slot
WHERE slot.slot <= CAST(? AS int4)
  AND NOT EXISTS (SELECT 1 FROM refresh_rotations AS rotation WHERE rotation.slot = slot.slot)
ORDER BY slot.slot
LIMIT 1
FOR UPDATE SKIP LOCKED
"""

private const val EVICT_STRICT_ROTATION = """
DELETE FROM refresh_rotations
WHERE jti = (
    SELECT jti FROM refresh_rotations
    WHERE slot <= CAST(? AS int4)
      AND grace_expires_at <= floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ORDER BY grace_expires_at, jti
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
RETURNING slot
"""

private const val INSERT_ROTATION = """
WITH clock AS (
    SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_ms
)
INSERT INTO refresh_rotations (jti, slot, user_id, grace_expires_at, token_expires_at)
SELECT ?, CAST(? AS int4), CAST(? AS int8), LEAST(CAST(? AS int8), now_ms + CAST(? AS int8)), CAST(? AS int8)
FROM clock
"""

private const val CONSUME_GRACE = """
UPDATE refresh_rotations
SET grace_reuses = grace_reuses + 1
WHERE jti = ?
  AND grace_expires_at > floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
  AND grace_reuses < CAST(? AS int4)
RETURNING jti
"""

private const val ROTATION_EXISTS = "SELECT jti FROM refresh_rotations WHERE jti = ?"

private const val PRECHECK = """
SELECT token.jti IS NOT NULL,
       rotation.jti IS NOT NULL,
       rotation.grace_expires_at > floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
           AND rotation.grace_reuses < CAST(? AS int4)
FROM (SELECT CAST(? AS varchar(36)) AS jti) AS wanted
LEFT JOIN revoked_tokens AS token ON token.jti = wanted.jti
LEFT JOIN refresh_rotations AS rotation ON rotation.jti = wanted.jti
"""

private const val DELETE_USER_ROTATIONS = "DELETE FROM refresh_rotations WHERE user_id = CAST(? AS int8)"

/** Durable refresh rotation and logout transaction boundary. No raw JWT enters this store. */
internal class RefreshRotationStore(
    private val database: R2dbcDatabase,
    private val replayGraceMillis: Long,
    private val maxRecords: Int = MAX_ROTATION_RECORDS,
) {
    init {
        require(replayGraceMillis >= 0) { "replayGraceMillis must not be negative" }
        require(maxRecords in 1..MAX_ROTATION_RECORDS) { "maxRecords must be between 1 and $MAX_ROTATION_RECORDS" }
    }

    suspend fun precheck(jti: String): RefreshPrecheck = suspendTransaction(database) {
        TransactionManager.current().exec(
            PRECHECK,
            listOf(IntegerColumnType() to MAX_GRACE_REUSES, TextColumnType() to jti),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            val revoked = row.get(0, Boolean::class.javaObjectType)!!
            val rotated = row.get(1, Boolean::class.javaObjectType)!!
            val grace = row.get(2, Boolean::class.javaObjectType) ?: false
            when {
                !revoked -> RefreshPrecheck.AVAILABLE
                !rotated -> RefreshPrecheck.REVOKED
                grace -> RefreshPrecheck.GRACE
                else -> RefreshPrecheck.REPLAYED
            }
        }?.firstOrNull()!!
    }

    suspend fun claim(jti: String, expiresAtMillis: Long, userId: UInt): RefreshClaim = suspendTransaction(database) {
        lockUser(userId)
        pruneRevocations()
        if (insertRevocation(jti, expiresAtMillis)) {
            recordRotation(jti, expiresAtMillis, userId)
            RefreshClaim.CLAIMED
        } else if (consumeGrace(jti)) {
            RefreshClaim.GRACE_REPLAY
        } else if (rotationExists(jti)) {
            RefreshClaim.REPLAYED
        } else {
            RefreshClaim.REVOKED
        }
    }

    suspend fun logout(
        accessUserId: UInt,
        accessJti: String,
        accessExpiresAtMillis: Long,
        refresh: RefreshTokenRevocation?,
    ): Boolean = suspendTransaction(database) {
        val users = buildSet {
            add(accessUserId)
            refresh?.let { add(it.userId) }
        }.sortedBy(UInt::toLong)
        users.forEach { ensureGuard(it) }
        users.forEach { lockExistingGuard(it) }
        pruneRevocations()

        val revocations = buildList {
            add(RefreshTokenRevocation(accessUserId, accessJti, accessExpiresAtMillis))
            refresh?.let(::add)
        }.distinctBy { it.jti }.sortedBy { it.jti }
        var accessInserted = false
        for (revocation in revocations) {
            val inserted = insertRevocation(revocation.jti, revocation.expiresAtMillis)
            if (revocation.jti == accessJti) accessInserted = inserted
        }
        users.forEach { userId ->
            TransactionManager.current().exec(
                DELETE_USER_ROTATIONS,
                listOf(LongColumnType() to userId.toLong()),
                explicitStatementType = StatementType.DELETE,
            )
        }
        if (accessInserted) notifyLiveAccount(accessUserId)
        accessInserted
    }

    suspend fun revokeStrict(jti: String, expiresAtMillis: Long): Boolean = suspendTransaction(database) {
        pruneRevocations()
        insertRevocation(jti, expiresAtMillis)
    }

    private suspend fun lockUser(userId: UInt) {
        ensureGuard(userId)
        lockExistingGuard(userId)
    }

    private suspend fun ensureGuard(userId: UInt) {
        TransactionManager.current().exec(
            ENSURE_GUARD,
            listOf(LongColumnType() to userId.toLong()),
            explicitStatementType = StatementType.INSERT,
        )
    }

    private suspend fun lockExistingGuard(userId: UInt) {
        firstColumn(
            LOCK_GUARD,
            listOf(LongColumnType() to userId.toLong()),
            Long::class.javaObjectType,
        ) ?: error("Refresh rotation user guard is missing")
    }

    private suspend fun pruneRevocations() {
        TransactionManager.current().exec(
            PRUNE_REVOKED,
            listOf(IntegerColumnType() to PRUNE_BATCH),
            explicitStatementType = StatementType.DELETE,
        )
    }

    private suspend fun insertRevocation(jti: String, expiresAtMillis: Long): Boolean =
        firstColumn(
            INSERT_REVOCATION,
            listOf(TextColumnType() to jti, LongColumnType() to expiresAtMillis),
            String::class.java,
        ) != null

    private suspend fun recordRotation(jti: String, expiresAtMillis: Long, userId: UInt) {
        val slot = freeSlot() ?: evictStrictRotation() ?: return
        TransactionManager.current().exec(
            INSERT_ROTATION,
            listOf(
                TextColumnType() to jti,
                IntegerColumnType() to slot,
                LongColumnType() to userId.toLong(),
                LongColumnType() to expiresAtMillis,
                LongColumnType() to replayGraceMillis,
                LongColumnType() to expiresAtMillis,
            ),
            explicitStatementType = StatementType.INSERT,
        )
    }

    private suspend fun freeSlot(): Int? = firstColumn(
        FREE_SLOT,
        listOf(IntegerColumnType() to maxRecords),
        Int::class.javaObjectType,
    )

    private suspend fun evictStrictRotation(): Int? = firstColumn(
        EVICT_STRICT_ROTATION,
        listOf(IntegerColumnType() to maxRecords),
        Int::class.javaObjectType,
    )

    private suspend fun consumeGrace(jti: String): Boolean = firstColumn(
        CONSUME_GRACE,
        listOf(TextColumnType() to jti, IntegerColumnType() to MAX_GRACE_REUSES),
        String::class.java,
    ) != null

    private suspend fun rotationExists(jti: String): Boolean = firstColumn(
        ROTATION_EXISTS,
        listOf(TextColumnType() to jti),
        String::class.java,
    ) != null

    private suspend fun <T : Any> firstColumn(
        sql: String,
        arguments: List<Pair<IColumnType<*>, Any?>>,
        type: Class<T>,
    ): T? = TransactionManager.current().exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        row.get(0, type)!!
    }?.firstOrNull()
}
