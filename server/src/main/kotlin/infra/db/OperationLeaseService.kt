package ch.nokillswit.infra.db

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val OPERATION_LEASE_MILLIS = 30_000L

internal enum class SharedOperation(val storedName: String, val lockNamespace: Int) {
    READ_ALL("read-all", 0x4f4c0001),
    LINK_PREVIEW("link-preview", 0x4f4c0002),
}

internal data class OperationLease(
    val operation: SharedOperation,
    val userId: UInt,
    val ownerId: UUID,
)

/** PostgreSQL-shared, DB-clock leases for the fixed per-user operation namespaces above. */
internal class OperationLeaseService(
    private val database: R2dbcDatabase,
    private val ttlMillis: Long = OPERATION_LEASE_MILLIS,
) {
    init {
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    /** Returns null immediately when this operation and user already have a live or concurrently-admitting owner. */
    suspend fun tryAcquire(operation: SharedOperation, userId: UInt): OperationLease? {
        val ownerId = UUID.randomUUID()
        try {
            return suspendTransaction(database) {
                if (!tryLock(operation, userId)) return@suspendTransaction null
                val now = databaseNow()
                val insertedOwner = TransactionManager.current().exec(
                    ACQUIRE,
                    listOf(
                        TextColumnType() to operation.storedName,
                        LongColumnType() to userId.toLong(),
                        UUIDColumnType() to ownerId,
                        LongColumnType() to now + ttlMillis,
                        LongColumnType() to now,
                    ),
                    explicitStatementType = StatementType.SELECT,
                ) { row -> row.get(0, UUID::class.java)!! }?.toList()?.singleOrNull() ?: return@suspendTransaction null
                check(insertedOwner == ownerId)
                OperationLease(operation, userId, ownerId)
            }
        } catch (failure: Throwable) {
            // A connection may fail after PostgreSQL committed but before the client observed the commit. The random owner is unique,
            // so expiring only that candidate cannot disturb a later owner even when RETURNING never reached us.
            withContext(NonCancellable) {
                runCatching { compensate(operation, userId, ownerId) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    /**
     * Fences [lease] and extends it using the DB clock. Call first inside the transaction that performs protected writes: the UPDATE
     * holds this lease row until commit, so an expiry takeover cannot pass it while those writes run.
     */
    suspend fun renewInCurrentTransaction(lease: OperationLease): Boolean {
        lock(lease.operation, lease.userId)
        val now = databaseNow()
        return TransactionManager.current().exec(
            RENEW,
            listOf(
                LongColumnType() to now + ttlMillis,
                TextColumnType() to lease.operation.storedName,
                LongColumnType() to lease.userId.toLong(),
                UUIDColumnType() to lease.ownerId,
                LongColumnType() to now,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, UUID::class.java) }?.toList()?.singleOrNull() == lease.ownerId
    }

    /** Short current-owner check and renewal, used after work that deliberately held no database connection. */
    suspend fun renew(lease: OperationLease): Boolean = suspendTransaction(database) { renewInCurrentTransaction(lease) }

    /** Expires exactly this owner token. Cancellation cannot skip cleanup and a stale token cannot release its successor. */
    suspend fun releaseNonCancellable(lease: OperationLease) {
        withContext(NonCancellable) {
            suspendTransaction(database) {
                lock(lease.operation, lease.userId)
                val now = databaseNow()
                TransactionManager.current().exec(
                    RELEASE,
                    listOf(
                        LongColumnType() to now,
                        TextColumnType() to lease.operation.storedName,
                        LongColumnType() to lease.userId.toLong(),
                        UUIDColumnType() to lease.ownerId,
                    ),
                    explicitStatementType = StatementType.UPDATE,
                )
            }
        }
    }

    private suspend fun tryLock(operation: SharedOperation, userId: UInt): Boolean =
        TransactionManager.current().exec(
            TRY_LOCK,
            listOf(IntegerColumnType() to operation.lockNamespace, IntegerColumnType() to userId.toInt()),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Boolean::class.javaObjectType)!! }?.toList()?.single() == true

    private suspend fun lock(operation: SharedOperation, userId: UInt) {
        TransactionManager.current().exec(
            LOCK,
            listOf(IntegerColumnType() to operation.lockNamespace, IntegerColumnType() to userId.toInt()),
            explicitStatementType = StatementType.SELECT,
        ) { Unit }?.toList()
    }

    private suspend fun databaseNow(): Long = TransactionManager.current().exec(
        OPERATION_DATABASE_NOW,
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Long::class.javaObjectType)!! }?.toList()?.single() ?: error("Database clock returned no row")

    private suspend fun compensate(operation: SharedOperation, userId: UInt, ownerId: UUID) {
        suspendTransaction(database) {
            lock(operation, userId)
            val now = databaseNow()
            TransactionManager.current().exec(
                COMPENSATE,
                listOf(
                    LongColumnType() to now,
                    TextColumnType() to operation.storedName,
                    LongColumnType() to userId.toLong(),
                    UUIDColumnType() to ownerId,
                ),
                explicitStatementType = StatementType.UPDATE,
            )
        }
    }
}

private const val OPERATION_DATABASE_NOW = "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint"

private const val TRY_LOCK = "SELECT pg_try_advisory_xact_lock(CAST(? AS int4), CAST(? AS int4))"
private const val LOCK = "SELECT pg_advisory_xact_lock(CAST(? AS int4), CAST(? AS int4))"

private const val ACQUIRE = """
INSERT INTO operation_leases (operation, user_id, owner_id, expires_at)
VALUES (CAST(? AS varchar), CAST(? AS int8), CAST(? AS uuid), CAST(? AS int8))
ON CONFLICT (operation, user_id) DO UPDATE SET
    owner_id = EXCLUDED.owner_id,
    expires_at = EXCLUDED.expires_at
WHERE operation_leases.expires_at <= CAST(? AS int8)
RETURNING owner_id
"""

private const val RENEW = """
UPDATE operation_leases SET expires_at = CAST(? AS int8)
WHERE operation = CAST(? AS varchar) AND user_id = CAST(? AS int8) AND owner_id = CAST(? AS uuid)
  AND expires_at > CAST(? AS int8)
RETURNING owner_id
"""

private const val RELEASE = """
UPDATE operation_leases SET expires_at = LEAST(expires_at, CAST(? AS int8))
WHERE operation = CAST(? AS varchar) AND user_id = CAST(? AS int8) AND owner_id = CAST(? AS uuid)
"""

private const val COMPENSATE = """
UPDATE operation_leases SET expires_at = LEAST(expires_at, CAST(? AS int8))
WHERE operation = CAST(? AS varchar) AND user_id = CAST(? AS int8) AND owner_id = CAST(? AS uuid)
"""
