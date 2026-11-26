package ch.nokillswit.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val LIVE_TICKET_EXPIRY_CLEANUP_BATCH = 256

private const val LOCK_CAPACITY_GUARD = "SELECT singleton FROM live_ticket_capacity_guard WHERE singleton FOR UPDATE"

private const val ISSUE = """
WITH clock AS MATERIALIZED (
    SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_ms
), expired AS MATERIALIZED (
    SELECT ticket_hash FROM live_tickets, clock
    WHERE expires_at <= clock.now_ms
    ORDER BY expires_at, ticket_hash
    LIMIT CAST(? AS int4)
    FOR UPDATE SKIP LOCKED
), purged AS (
    DELETE FROM live_tickets AS ticket USING expired
    WHERE ticket.ticket_hash = expired.ticket_hash
), active AS MATERIALIZED (
    SELECT count(*)::int AS n FROM live_tickets, clock WHERE expires_at > clock.now_ms
)
INSERT INTO live_tickets (
    ticket_hash, user_id, is_admin, roles, access_jti, credential_revision, token_expires_at, expires_at
)
SELECT CAST(? AS char(64)), CAST(? AS int8), CAST(? AS boolean), CAST(? AS text[]), ?, CAST(? AS int8), CAST(? AS int8),
       LEAST(CAST(? AS int8), clock.now_ms + CAST(? AS int8))
FROM clock, active
WHERE active.n < CAST(? AS int4)
RETURNING expires_at
"""

private const val REDEEM = """
WITH clock AS MATERIALIZED (
    SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_ms
), consumed AS (
    DELETE FROM live_tickets WHERE ticket_hash = CAST(? AS char(64))
    RETURNING user_id, is_admin, roles, access_jti, credential_revision, token_expires_at, expires_at
)
SELECT user_id, is_admin, roles, access_jti, credential_revision, token_expires_at, expires_at
FROM consumed, clock
WHERE consumed.expires_at > clock.now_ms
"""

/** Shared single-use store. Every method is one short transaction; failures never fall back to process memory. */
internal class LiveTicketPostgresStore(
    private val database: R2dbcDatabase,
    private val maxTickets: Int,
    private val ttlMillis: Long = LIVE_TICKET_TTL_MILLIS,
    private val cleanupBatch: Int = LIVE_TICKET_EXPIRY_CLEANUP_BATCH,
) : LiveTicketStore {
    init {
        require(maxTickets > 0) { "maxTickets must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
        require(cleanupBatch > 0) { "cleanupBatch must be positive" }
    }

    override suspend fun issue(digest: String, draft: LiveTicketDraft): Long = storeCall {
        suspendTransaction(database) {
            // A statement takes its MVCC snapshot before waiting on a row lock. Lock in its own statement so the count below gets a
            // fresh snapshot after every earlier mint committed; otherwise concurrent issuers could all observe the same free slot.
            TransactionManager.current().exec(LOCK_CAPACITY_GUARD, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Boolean::class.javaObjectType)!!
            }?.firstOrNull() ?: error("Live ticket capacity guard is missing")
            val arguments = listOf(
                IntegerColumnType() to cleanupBatch,
                TextColumnType() to digest,
                LongColumnType() to draft.userId.toLong(),
                BooleanColumnType() to draft.isAdmin,
                ArrayColumnType<String, List<String>>(TextColumnType()) to draft.roles.sorted(),
                TextColumnType() to draft.jti,
                LongColumnType() to draft.credentialRevision,
                LongColumnType() to draft.tokenExpiresAtMillis,
                LongColumnType() to draft.tokenExpiresAtMillis,
                LongColumnType() to ttlMillis,
                IntegerColumnType() to maxTickets,
            )
            TransactionManager.current().exec(ISSUE, arguments, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)!!
            }?.firstOrNull() ?: throw LiveTicketCapacityExceededException()
        }
    }

    override suspend fun redeem(digest: String): LiveTicketBinding? = storeCall {
        suspendTransaction(database) {
            TransactionManager.current().exec(
                REDEEM,
                listOf(TextColumnType() to digest),
                explicitStatementType = StatementType.SELECT,
            ) { row ->
                LiveTicketBinding(
                    userId = row.get(0, Long::class.javaObjectType)!!.toUInt(),
                    isAdmin = row.get(1, Boolean::class.javaObjectType)!!,
                    roles = row.get(2, Array<String>::class.java)!!.toSet(),
                    jti = row.get(3, String::class.java)!!,
                    credentialRevision = row.get(4, Long::class.javaObjectType)!!,
                    tokenExpiresAtMillis = row.get(5, Long::class.javaObjectType)!!,
                    expiresAtMillis = row.get(6, Long::class.javaObjectType)!!,
                )
            }?.firstOrNull()
        }
    }

    private suspend fun <T> storeCall(block: suspend () -> T): T = try {
        block()
    } catch (e: LiveTicketCapacityExceededException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw LiveTicketStoreUnavailableException(e)
    }
}
