package ch.nokillswit.live

import java.util.UUID
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val LIVE_SESSION_LEASE_MILLIS = 60_000L
internal const val LIVE_SESSION_PRUNE_BATCH = 500

internal enum class LiveLeaseRefusal { CAPACITY, TOKEN_EXPIRED }

internal sealed interface LiveLeaseAdmission {
    val serverNowMillis: Long

    data class Accepted(
        override val serverNowMillis: Long,
        val expiresAtMillis: Long,
    ) : LiveLeaseAdmission

    data class Refused(
        override val serverNowMillis: Long,
        val reason: LiveLeaseRefusal,
    ) : LiveLeaseAdmission
}

internal data class LiveLeaseRenewal(
    val serverNowMillis: Long,
    val deadlines: Map<UUID, Long>,
)

internal data class LiveForegroundUpdate(
    val serverNowMillis: Long,
    val updatedSessions: Set<UUID>,
    val expiresAtMillis: Long?,
)

internal interface LiveLeaseStore {
    suspend fun admit(userId: UInt, sessionId: UUID, tokenExpiresAtMillis: Long, maxSessions: Int): LiveLeaseAdmission
    suspend fun hasCapacity(userId: UInt, maxSessions: Int): Boolean
    suspend fun renew(sessionIds: Collection<UUID>): LiveLeaseRenewal
    suspend fun setForeground(sessionIds: Collection<UUID>, visible: Boolean): LiveForegroundUpdate
    suspend fun release(sessionIds: Collection<UUID>): Int
    suspend fun pruneExpired(batchSize: Int = LIVE_SESSION_PRUNE_BATCH): Int
}

/** PostgreSQL-backed live socket leases shared by every application instance. */
internal class LiveSessionLeaseStore(
    private val database: R2dbcDatabase,
    private val instanceId: UUID,
) : LiveLeaseStore {
    /** Serializes one user's admission and atomically enforces the configured shared live-session cap. */
    override suspend fun admit(userId: UInt, sessionId: UUID, tokenExpiresAtMillis: Long, maxSessions: Int): LiveLeaseAdmission =
        suspendTransaction(database) {
            val transaction = TransactionManager.current()
            val user = LongColumnType() to userId.toLong()
            transaction.exec(ENSURE_USER_GUARD, listOf(user), explicitStatementType = StatementType.INSERT)
            transaction.exec(LOCK_USER_GUARD, listOf(user), explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)
            }?.toList()
            val row = checkNotNull(
                transaction.exec(
                    ADMIT_LEASE,
                    listOf(
                        UUIDColumnType() to sessionId,
                        UUIDColumnType() to instanceId,
                        user,
                        LongColumnType() to tokenExpiresAtMillis,
                        LongColumnType() to LIVE_SESSION_LEASE_MILLIS,
                        IntegerColumnType() to maxSessions,
                    ),
                    explicitStatementType = StatementType.SELECT,
                ) { result ->
                    AdmissionRow(
                        checkNotNull(result.get(0, Long::class.javaObjectType)),
                        result.get(1, Boolean::class.javaObjectType) == true,
                        result.get(2, Boolean::class.javaObjectType) == true,
                        result.get(3, Long::class.javaObjectType),
                    )
                },
            ).filterNotNull().toList().single()
            when {
                row.expiresAt != null -> LiveLeaseAdmission.Accepted(row.serverNow, row.expiresAt)
                !row.tokenLive -> LiveLeaseAdmission.Refused(row.serverNow, LiveLeaseRefusal.TOKEN_EXPIRED)
                else -> {
                    check(!row.capacityAvailable)
                    LiveLeaseAdmission.Refused(row.serverNow, LiveLeaseRefusal.CAPACITY)
                }
            }
        }

    /** Non-consuming capacity hint for ticket minting; admission remains the authoritative serialized decision. */
    override suspend fun hasCapacity(userId: UInt, maxSessions: Int): Boolean = suspendTransaction(database) {
        checkNotNull(
            TransactionManager.current().exec(
                CAPACITY_HINT,
                listOf(IntegerColumnType() to maxSessions, LongColumnType() to userId.toLong()),
                explicitStatementType = StatementType.SELECT,
            ) { row -> checkNotNull(row.get(0, Boolean::class.javaObjectType)) },
        ).filterNotNull().toList().single()
    }

    /** Renews only this instance's still-live sessions and reports the exact rows and DB-clock deadlines that moved. */
    override suspend fun renew(sessionIds: Collection<UUID>): LiveLeaseRenewal = suspendTransaction(database) {
        val rows = timedRows(RENEW_LEASES, sessionIds, visible = null)
        LiveLeaseRenewal(rows.first().serverNow, rows.mapNotNull { row -> row.sessionId?.let { it to checkNotNull(row.deadline) } }.toMap())
    }

    /** Sets or clears foreground state without extending the socket lease. Expired leases are never changed. */
    override suspend fun setForeground(sessionIds: Collection<UUID>, visible: Boolean): LiveForegroundUpdate =
        suspendTransaction(database) {
            val rows = timedRows(SET_FOREGROUND, sessionIds, visible)
            LiveForegroundUpdate(
                rows.first().serverNow,
                rows.mapNotNull(TimedRow::sessionId).toSet(),
                rows.firstNotNullOfOrNull(TimedRow::deadline),
            )
        }

    /** Releases only session ids owned by this store's instance. */
    override suspend fun release(sessionIds: Collection<UUID>): Int {
        val ids = sessionIds.distinct()
        if (ids.isEmpty()) return 0
        return suspendTransaction(database) {
            TransactionManager.current().exec(
                RELEASE_LEASES,
                listOf(
                    UUIDColumnType() to instanceId,
                    ArrayColumnType<UUID, List<UUID>>(UUIDColumnType()) to ids,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, UUID::class.java) }?.toList().orEmpty().size
        }
    }

    /** Deletes at most [batchSize] expired leases in deadline/id order, skipping rows held by another transaction. */
    override suspend fun pruneExpired(batchSize: Int): Int {
        require(batchSize > 0)
        return suspendTransaction(database) {
            TransactionManager.current().exec(
                PRUNE_EXPIRED,
                listOf(IntegerColumnType() to batchSize),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, UUID::class.java) }?.toList().orEmpty().size
        }
    }

    private suspend fun timedRows(sql: String, sessionIds: Collection<UUID>, visible: Boolean?): List<TimedRow> {
        val ids = sessionIds.distinct()
        val arguments = buildList {
            add(UUIDColumnType() to instanceId)
            add(ArrayColumnType<UUID, List<UUID>>(UUIDColumnType()) to ids)
            if (visible != null) add(BooleanColumnType() to visible)
            add(LongColumnType() to LIVE_SESSION_LEASE_MILLIS)
        }
        return checkNotNull(
            TransactionManager.current().exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
                TimedRow(
                    checkNotNull(row.get(0, Long::class.javaObjectType)),
                    row.get(1, UUID::class.java),
                    row.get(2, Long::class.javaObjectType),
                )
            },
        ).filterNotNull().toList()
    }

    private data class AdmissionRow(
        val serverNow: Long,
        val tokenLive: Boolean,
        val capacityAvailable: Boolean,
        val expiresAt: Long?,
    )

    private data class TimedRow(val serverNow: Long, val sessionId: UUID?, val deadline: Long?)
}

private const val ENSURE_USER_GUARD = """
INSERT INTO live_session_user_guards (user_id) VALUES (CAST(? AS int8))
ON CONFLICT (user_id) DO NOTHING"""

private const val LOCK_USER_GUARD = """
SELECT user_id FROM live_session_user_guards WHERE user_id = CAST(? AS int8) FOR UPDATE"""

private const val CAPACITY_HINT = """
WITH clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
)
SELECT count(*) < CAST(? AS int4)
FROM live_session_leases lease CROSS JOIN clock
WHERE lease.user_id = CAST(? AS int8) AND lease.socket_expires_at > clock.now_millis"""

private const val ADMIT_LEASE = """
WITH input AS MATERIALIZED (
  SELECT CAST(? AS uuid) AS session_id, CAST(? AS uuid) AS instance_id, CAST(? AS int8) AS user_id,
         CAST(? AS int8) AS token_expires_at, CAST(? AS int8) AS lease_millis, CAST(? AS int4) AS max_sessions
), clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), state AS MATERIALIZED (
  SELECT count(lease.session_id)::int AS live_count
  FROM input CROSS JOIN clock
  LEFT JOIN live_session_leases lease
    ON lease.user_id = input.user_id AND lease.socket_expires_at > clock.now_millis
), inserted AS (
  INSERT INTO live_session_leases
    (session_id, instance_id, user_id, token_expires_at, socket_expires_at, foreground_expires_at)
  SELECT input.session_id, input.instance_id, input.user_id, input.token_expires_at,
         LEAST(input.token_expires_at, clock.now_millis + input.lease_millis), NULL
  FROM input CROSS JOIN clock CROSS JOIN state
  WHERE input.token_expires_at > clock.now_millis AND state.live_count < input.max_sessions
  RETURNING socket_expires_at
)
SELECT clock.now_millis, input.token_expires_at > clock.now_millis, state.live_count < input.max_sessions, inserted.socket_expires_at
FROM input CROSS JOIN clock CROSS JOIN state LEFT JOIN inserted ON TRUE"""

private const val RENEW_LEASES = """
WITH input AS MATERIALIZED (
  SELECT CAST(? AS uuid) AS instance_id, CAST(? AS uuid[]) AS session_ids, CAST(? AS int8) AS lease_millis
), clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), renewed AS (
  UPDATE live_session_leases lease
  SET socket_expires_at = LEAST(lease.token_expires_at, clock.now_millis + input.lease_millis)
  FROM clock CROSS JOIN input
  WHERE lease.instance_id = input.instance_id AND lease.session_id = ANY(input.session_ids)
    AND lease.socket_expires_at > clock.now_millis AND lease.token_expires_at > clock.now_millis
  RETURNING lease.session_id, lease.socket_expires_at
)
SELECT clock.now_millis, renewed.session_id, renewed.socket_expires_at
FROM clock LEFT JOIN renewed ON TRUE"""

private const val SET_FOREGROUND = """
WITH input AS MATERIALIZED (
  SELECT CAST(? AS uuid) AS instance_id, CAST(? AS uuid[]) AS session_ids, CAST(? AS bool) AS visible,
         CAST(? AS int8) AS lease_millis
), clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), changed AS (
  UPDATE live_session_leases lease
  SET foreground_expires_at = CASE WHEN input.visible THEN clock.now_millis + input.lease_millis ELSE NULL END
  FROM clock CROSS JOIN input
  WHERE lease.instance_id = input.instance_id AND lease.session_id = ANY(input.session_ids)
    AND lease.socket_expires_at > clock.now_millis AND lease.token_expires_at > clock.now_millis
  RETURNING lease.session_id, lease.foreground_expires_at
)
SELECT clock.now_millis, changed.session_id, changed.foreground_expires_at
FROM clock LEFT JOIN changed ON TRUE"""

private const val RELEASE_LEASES = """
DELETE FROM live_session_leases
WHERE instance_id = CAST(? AS uuid) AND session_id = ANY(CAST(? AS uuid[]))
RETURNING session_id"""

private const val PRUNE_EXPIRED = """
WITH clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), expired AS MATERIALIZED (
  SELECT lease.session_id
  FROM live_session_leases lease CROSS JOIN clock
  WHERE lease.socket_expires_at <= clock.now_millis
  ORDER BY lease.socket_expires_at, lease.session_id
  LIMIT CAST(? AS int4)
  FOR UPDATE OF lease SKIP LOCKED
)
DELETE FROM live_session_leases lease
USING expired
WHERE lease.session_id = expired.session_id
RETURNING lease.session_id"""
