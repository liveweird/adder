package ch.nokillswit.infra.presence

import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ForegroundPresenceKey = AttributeKey<ForegroundPresence>("ForegroundPresence")

/** Transaction-aware lookup used by message writes; implementations return only fresh, visible sessions. */
fun interface ForegroundPresence {
    suspend fun eligibleUserIds(candidateUserIds: Collection<UInt>): Set<UInt>
}

/** PostgreSQL is the sole production source of foreground presence across all application instances. */
class PostgresForegroundPresence(private val database: R2dbcDatabase) : ForegroundPresence {
    override suspend fun eligibleUserIds(candidateUserIds: Collection<UInt>): Set<UInt> {
        val candidates = candidateUserIds.distinct()
        if (candidates.isEmpty()) return emptySet()
        return suspendTransaction(database) {
            TransactionManager.current().exec(
                PRESENT_USERS,
                listOf(ArrayColumnType<Long, List<Long>>(LongColumnType()) to candidates.map(UInt::toLong)),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, Long::class.javaObjectType)?.toUInt() }
                ?.filterNotNull()?.toList()?.toSet().orEmpty()
        }
    }
}

private const val PRESENT_USERS = """
WITH clock AS MATERIALIZED (
  SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
)
SELECT DISTINCT lease.user_id
FROM live_session_leases lease CROSS JOIN clock
WHERE lease.user_id = ANY(CAST(? AS int8[]))
  AND lease.socket_expires_at > clock.now_millis
  AND lease.token_expires_at > clock.now_millis
  AND lease.foreground_expires_at > clock.now_millis
ORDER BY lease.user_id"""
