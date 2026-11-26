package ch.nokillswit.notifications

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val ACTOR_RECIPIENT_INTERVAL_MILLIS = 3_600_000L
internal const val PACING_PURGE_BATCH = 500

internal data class ActorRecipientPair(val actorId: UInt, val recipientId: UInt) : Comparable<ActorRecipientPair> {
    override fun compareTo(other: ActorRecipientPair): Int =
        compareValuesBy(this, other, ActorRecipientPair::actorId, ActorRecipientPair::recipientId)
}

internal data class DigestClaim(val notices: List<ClaimedNotice>, val settled: Int)

private const val REQUESTED_PAIRS = """
SELECT actor_id, recipient_id
FROM unnest(CAST(? AS int8[]), CAST(? AS int8[])) AS pair(actor_id, recipient_id)"""

private const val ENSURE_PAIRS = """
INSERT INTO notification_email_pacing (actor_id, recipient_id, attempted_at)
SELECT actor_id, recipient_id, -9223372036854775808 FROM ($REQUESTED_PAIRS) AS requested
ORDER BY actor_id, recipient_id
ON CONFLICT (actor_id, recipient_id) DO NOTHING"""

private const val LOCK_PAIRS = """
WITH requested AS ($REQUESTED_PAIRS)
SELECT pacing.actor_id
FROM notification_email_pacing AS pacing
JOIN requested USING (actor_id, recipient_id)
ORDER BY pacing.actor_id, pacing.recipient_id
FOR UPDATE OF pacing"""

private const val RESERVE_PAIRS = """
WITH requested AS ($REQUESTED_PAIRS)
UPDATE notification_email_pacing AS pacing SET attempted_at = CAST(? AS int8)
FROM requested
WHERE pacing.actor_id = requested.actor_id AND pacing.recipient_id = requested.recipient_id
  AND pacing.attempted_at <= CAST(? AS int8)
RETURNING pacing.actor_id, pacing.recipient_id"""

private const val RELEASE_CLAIMS = """
WITH picked AS MATERIALIZED (
    SELECT id FROM notifications
    WHERE id = ANY(CAST(? AS int4[])) AND emailed_at = CAST(? AS int8)
    ORDER BY id FOR UPDATE)
UPDATE notifications AS notice SET emailed_at = NULL
FROM picked WHERE notice.id = picked.id
RETURNING notice.id"""

private const val PURGE_EXPIRED_PACING = """
WITH expired AS MATERIALIZED (
    SELECT actor_id, recipient_id FROM notification_email_pacing
    WHERE attempted_at <= CAST(? AS int8)
    ORDER BY attempted_at, actor_id, recipient_id
    LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED)
DELETE FROM notification_email_pacing AS pacing
USING expired
WHERE pacing.actor_id = expired.actor_id AND pacing.recipient_id = expired.recipient_id
RETURNING pacing.actor_id"""

/** SQL/storage seam for digest claims and persisted SMTP pacing. No method holds a transaction across rendering or SMTP. */
internal class NotificationDigestStore(
    private val database: R2dbcDatabase,
    private val delayMillis: Long,
    private val bounds: DigestBounds,
) {
    suspend fun claim(
        now: Long,
        recipientsInsideInterval: Set<UInt>,
        batchSize: Int,
        maxPerRun: Int,
        actorRecipientIntervalMillis: Long,
    ): DigestClaim = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        val cutoff = LongColumnType() to now - delayMillis
        val floor = LongColumnType() to now - MILLIS_PER_DAY
        val pacingCutoff = LongColumnType() to now - actorRecipientIntervalMillis
        val offByDefault = ArrayColumnType<String, List<String>>(TextColumnType()) to
            NotificationType.entries.filter { !it.defaultOn(NotificationChannel.EMAIL) }.map { it.name }
        val settled = TransactionManager.current().exec(
            SETTLE,
            listOf(cutoff, floor, IntegerColumnType() to bounds.scanCap, offByDefault, LongColumnType() to now),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType) }?.toList().orEmpty().size
        val arguments = listOf(
            cutoff,
            floor,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipientsInsideInterval.map { it.toLong() },
            pacingCutoff,
            offByDefault,
            IntegerColumnType() to bounds.scanCap,
            IntegerColumnType() to peopleToClaim(batchSize, maxPerRun),
            LongColumnType() to now,
        )
        val claimed = TransactionManager.current().exec(CLAIM, arguments, explicitStatementType = StatementType.SELECT) { row ->
            val type = NotificationType.entries.firstOrNull { it.name == row.get(5, String::class.java) }
            type?.let {
                ClaimedNotice(
                    id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                    recipientId = row.get(1, Long::class.javaObjectType)!!.toUInt(),
                    roomId = row.get(2, Int::class.javaObjectType)!!.toUInt(),
                    threadId = row.get(3, Int::class.javaObjectType)?.toUInt(),
                    messageId = row.get(4, Int::class.javaObjectType)?.toUInt(),
                    type = it,
                    actorId = row.get(6, Long::class.javaObjectType)!!.toUInt(),
                    rank = row.get(7, Long::class.javaObjectType)!!,
                )
            }
        }?.toList().orEmpty().filterNotNull().sortedBy { it.id }
        DigestClaim(claimed, settled)
    }

    /** Locks the requested pairs in a shared order before starting the pacing interval. */
    suspend fun reservePairs(
        pairs: Set<ActorRecipientPair>,
        intervalMillis: Long,
        clock: () -> Long,
    ): Set<ActorRecipientPair> {
        if (pairs.isEmpty()) return emptySet()
        val ordered = pairs.sorted()
        return suspendTransaction(database) {
            queryTimeout = bounds.statementTimeoutSeconds
            val array = ArrayColumnType<Long, List<Long>>(LongColumnType())
            val pairArgs = listOf(array to ordered.map { it.actorId.toLong() }, array to ordered.map { it.recipientId.toLong() })
            val transaction = TransactionManager.current()
            transaction.exec(ENSURE_PAIRS, pairArgs, explicitStatementType = StatementType.INSERT)
            transaction.exec(LOCK_PAIRS, pairArgs, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)
            }?.toList()
            val reservedAt = clock()
            transaction.exec(
                RESERVE_PAIRS,
                pairArgs + listOf(LongColumnType() to reservedAt, LongColumnType() to reservedAt - intervalMillis),
                explicitStatementType = StatementType.SELECT,
            ) { row ->
                ActorRecipientPair(
                    row.get(0, Long::class.javaObjectType)!!.toUInt(),
                    row.get(1, Long::class.javaObjectType)!!.toUInt(),
                )
            }?.toList().orEmpty().filterNotNull().toSet()
        }
    }

    /** Releases only this run's claims, after locking them in ascending notification-id order. */
    suspend fun release(ids: Collection<UInt>, stamp: Long): Int {
        if (ids.isEmpty()) return 0
        val ordered = ids.sorted()
        return suspendTransaction(database) {
            queryTimeout = bounds.statementTimeoutSeconds
            TransactionManager.current().exec(
                RELEASE_CLAIMS,
                listOf(
                    ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ordered.map { it.toInt() },
                    LongColumnType() to stamp,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, Int::class.javaObjectType) }?.toList().orEmpty().size
        }
    }

    /**
     * Sweep integration seam: deletes at most [batchSize] expired pairs in its own transaction. The caller failure-isolates this
     * independently from notice retention; `SKIP LOCKED` leaves racing reservations for a later hourly pass.
     */
    suspend fun purgeExpiredPacing(cutoffMillis: Long, batchSize: Int = PACING_PURGE_BATCH): Int = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        TransactionManager.current().exec(
            PURGE_EXPIRED_PACING,
            listOf(LongColumnType() to cutoffMillis, IntegerColumnType() to batchSize),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType) }?.toList().orEmpty().size
    }
}
