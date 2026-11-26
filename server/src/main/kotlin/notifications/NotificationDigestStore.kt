package ch.nokillswit.notifications

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val ACTOR_RECIPIENT_INTERVAL_MILLIS = 3_600_000L
internal const val PACING_PURGE_BATCH = 500

internal data class DigestClaim(val notices: List<ClaimedNotice>, val settled: Int)
internal enum class DigestAttemptStatus { RESERVED, RECIPIENT_PACED, ACTORS_PACED, CAPACITY }
internal data class DigestAttemptReservation(
    val status: DigestAttemptStatus,
    val actorIds: Set<UInt> = emptySet(),
)

private const val LOCK_RECIPIENT = """
SELECT attempted_at FROM notification_digest_recipient_pacing
WHERE recipient_id = CAST(? AS int8)
FOR UPDATE"""

private const val ENSURE_RECIPIENT = """
WITH free_slot AS MATERIALIZED (
    SELECT slots.slot FROM notification_digest_recipient_slots AS slots
    WHERE slots.slot <= CAST(? AS int4)
      AND NOT EXISTS (SELECT 1 FROM notification_digest_recipient_pacing AS pacing WHERE pacing.slot = slots.slot)
    ORDER BY slots.slot LIMIT 1 FOR UPDATE OF slots SKIP LOCKED)
INSERT INTO notification_digest_recipient_pacing (recipient_id, slot, attempted_at)
SELECT CAST(? AS int8), slot, -9223372036854775808 FROM free_slot
ON CONFLICT (recipient_id) DO NOTHING"""

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
SELECT pacing.actor_id, pacing.attempted_at
FROM notification_email_pacing AS pacing
JOIN requested USING (actor_id, recipient_id)
ORDER BY pacing.actor_id, pacing.recipient_id
FOR UPDATE OF pacing"""

private const val RESERVE_RECIPIENT = """
UPDATE notification_digest_recipient_pacing SET attempted_at = CAST(? AS int8)
WHERE recipient_id = CAST(? AS int8)"""

private const val RELEASE_EMPTY_RECIPIENT = """
DELETE FROM notification_digest_recipient_pacing
WHERE recipient_id = CAST(? AS int8) AND attempted_at = -9223372036854775808"""

private const val RESERVE_PAIRS = """
UPDATE notification_email_pacing SET attempted_at = CAST(? AS int8)
WHERE recipient_id = CAST(? AS int8) AND actor_id = ANY(CAST(? AS int8[]))"""

private const val RELEASE_CLAIMS = """
WITH picked AS MATERIALIZED (
    SELECT id FROM notifications
    WHERE id = ANY(CAST(? AS int4[])) AND emailed_at = CAST(? AS int8)
    ORDER BY id FOR UPDATE)
UPDATE notifications AS notice SET emailed_at = NULL
FROM picked WHERE notice.id = picked.id
RETURNING notice.id"""

private const val PURGE_EXPIRED_PAIRS = """
WITH expired AS MATERIALIZED (
    SELECT actor_id, recipient_id FROM notification_email_pacing
    WHERE attempted_at <= CAST(? AS int8)
    ORDER BY attempted_at, actor_id, recipient_id
    LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED)
DELETE FROM notification_email_pacing AS pacing
USING expired
WHERE pacing.actor_id = expired.actor_id AND pacing.recipient_id = expired.recipient_id
RETURNING pacing.actor_id"""

private const val PURGE_EXPIRED_RECIPIENTS = """
WITH expired AS MATERIALIZED (
    SELECT recipient_id FROM notification_digest_recipient_pacing
    WHERE attempted_at <= CAST(? AS int8)
    ORDER BY attempted_at, recipient_id
    LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED)
DELETE FROM notification_digest_recipient_pacing AS pacing
USING expired
WHERE pacing.recipient_id = expired.recipient_id
RETURNING pacing.recipient_id"""

private const val DATABASE_NOW = "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint"

/** SQL/storage seam for digest claims and persisted SMTP pacing. No method holds a transaction across rendering or SMTP. */
internal class NotificationDigestStore(
    private val database: R2dbcDatabase,
    private val delayMillis: Long,
    private val bounds: DigestBounds,
) {
    suspend fun claim(
        now: Long,
        recipientIntervalMillis: Long,
        batchSize: Int,
        maxPerRun: Int,
        actorRecipientIntervalMillis: Long,
    ): DigestClaim = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        val cutoff = LongColumnType() to now - delayMillis
        val floor = LongColumnType() to now - MILLIS_PER_DAY
        val pacingNow = pacingNow()
        val recipientPacingCutoff = LongColumnType() to pacingNow - recipientIntervalMillis
        val actorPacingCutoff = LongColumnType() to pacingNow - actorRecipientIntervalMillis
        val offByDefault = ArrayColumnType<String, List<String>>(TextColumnType()) to
            NotificationType.entries.filter { !it.defaultOn(NotificationChannel.EMAIL) }.map { it.name }
        val transaction = TransactionManager.current()
        val settled = transaction.noticeRows(
            SETTLE,
            listOf(cutoff, floor, IntegerColumnType() to bounds.scanCap, offByDefault, LongColumnType() to now),
        ) { row -> row.noticeColumn(0, Int::class.javaObjectType) }.size
        val arguments = listOf(
            cutoff,
            floor,
            recipientPacingCutoff,
            actorPacingCutoff,
            offByDefault,
            IntegerColumnType() to bounds.scanCap,
            IntegerColumnType() to peopleToClaim(batchSize, maxPerRun),
            LongColumnType() to now,
        )
        val claimed = transaction.noticeRows(CLAIM, arguments) { row ->
            val type = NotificationType.entries.firstOrNull { it.name == row.noticeColumn(5, String::class.java) }
            type?.let {
                ClaimedNotice(
                    id = row.noticeColumn(0, Int::class.javaObjectType).toUInt(),
                    recipientId = row.noticeColumn(1, Long::class.javaObjectType).toUInt(),
                    roomId = row.noticeColumn(2, Int::class.javaObjectType).toUInt(),
                    threadId = row.get(3, Int::class.javaObjectType)?.toUInt(),
                    messageId = row.get(4, Int::class.javaObjectType)?.toUInt(),
                    type = it,
                    actorId = row.noticeColumn(6, Long::class.javaObjectType).toUInt(),
                    rank = row.noticeColumn(7, Long::class.javaObjectType),
                )
            }
        }.sortedBy { it.id }
        DigestClaim(claimed, settled)
    }

    /**
     * Authoritative pre-SMTP reservation. Locks one recipient first, then that recipient's actor pairs in actor order. Neither interval
     * starts when the recipient or every actor is paced. The transaction commits before this method returns; SMTP holds no connection.
     */
    suspend fun reserveAttempt(
        recipientId: UInt,
        actorIds: Set<UInt>,
        recipientIntervalMillis: Long,
        actorRecipientIntervalMillis: Long,
    ): DigestAttemptReservation {
        if (actorIds.isEmpty()) return DigestAttemptReservation(DigestAttemptStatus.ACTORS_PACED)
        val orderedActors = actorIds.sorted()
        return suspendTransaction(database) {
            queryTimeout = bounds.statementTimeoutSeconds
            val array = ArrayColumnType<Long, List<Long>>(LongColumnType())
            val transaction = TransactionManager.current()
            val recipientArgument = LongColumnType() to recipientId.toLong()
            var recipientAttemptedAt = transaction.lockRecipient(recipientArgument)
            val createdRecipient = recipientAttemptedAt == null
            if (recipientAttemptedAt == null) {
                transaction.exec(
                    ENSURE_RECIPIENT,
                    listOf(IntegerColumnType() to bounds.maxRecipientReservations, recipientArgument),
                    explicitStatementType = StatementType.INSERT,
                )
                recipientAttemptedAt = transaction.lockRecipient(recipientArgument)
                    ?: return@suspendTransaction DigestAttemptReservation(DigestAttemptStatus.CAPACITY)
            }
            var reservedAt = pacingNow()
            if (recipientAttemptedAt > reservedAt - recipientIntervalMillis) {
                return@suspendTransaction DigestAttemptReservation(DigestAttemptStatus.RECIPIENT_PACED)
            }
            val pairArgs = listOf(
                array to orderedActors.map { it.toLong() },
                array to List(orderedActors.size) { recipientId.toLong() },
            )
            transaction.exec(ENSURE_PAIRS, pairArgs, explicitStatementType = StatementType.INSERT)
            val attemptedByActor = transaction.noticeRows(LOCK_PAIRS, pairArgs) { row ->
                row.noticeColumn(0, Long::class.javaObjectType).toUInt() to
                    row.noticeColumn(1, Long::class.javaObjectType)
            }.toMap()
            reservedAt = pacingNow()
            val admitted = orderedActors.filterTo(linkedSetOf()) {
                attemptedByActor.getValue(it) <= reservedAt - actorRecipientIntervalMillis
            }
            if (admitted.isEmpty()) {
                if (createdRecipient) {
                    transaction.exec(
                        RELEASE_EMPTY_RECIPIENT,
                        listOf(recipientArgument),
                        explicitStatementType = StatementType.DELETE,
                    )
                }
                return@suspendTransaction DigestAttemptReservation(DigestAttemptStatus.ACTORS_PACED)
            }
            transaction.exec(
                RESERVE_RECIPIENT,
                listOf(LongColumnType() to reservedAt, recipientArgument),
                explicitStatementType = StatementType.UPDATE,
            )
            transaction.exec(
                RESERVE_PAIRS,
                listOf(
                    LongColumnType() to reservedAt,
                    recipientArgument,
                    array to admitted.map { it.toLong() },
                ),
                explicitStatementType = StatementType.UPDATE,
            )
            DigestAttemptReservation(DigestAttemptStatus.RESERVED, admitted)
        }
    }

    /** Releases only this run's claims, after locking them in ascending notification-id order. */
    suspend fun release(ids: Collection<UInt>, stamp: Long): Int {
        if (ids.isEmpty()) return 0
        val ordered = ids.sorted()
        return suspendTransaction(database) {
            queryTimeout = bounds.statementTimeoutSeconds
            TransactionManager.current().noticeRows(
                RELEASE_CLAIMS,
                listOf(
                    ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ordered.map { it.toInt() },
                    LongColumnType() to stamp,
                ),
            ) { row -> row.noticeColumn(0, Int::class.javaObjectType) }.size
        }
    }

    /**
     * Sweep integration seam: deletes at most [batchSize] expired pairs in its own transaction. The caller failure-isolates this
     * independently from notice retention; `SKIP LOCKED` leaves racing reservations for a later hourly pass.
     */
    suspend fun purgeExpiredPacing(
        recipientIntervalMillis: Long,
        actorRecipientIntervalMillis: Long,
        batchSize: Int = PACING_PURGE_BATCH,
    ): Int = suspendTransaction(database) {
        queryTimeout = bounds.statementTimeoutSeconds
        val now = pacingNow()
        val transaction = TransactionManager.current()
        val recipients = transaction.noticeRows(
            PURGE_EXPIRED_RECIPIENTS,
            listOf(LongColumnType() to now - recipientIntervalMillis, IntegerColumnType() to batchSize),
        ) { row -> row.noticeColumn(0, Long::class.javaObjectType) }.size
        val pairs = transaction.noticeRows(
            PURGE_EXPIRED_PAIRS,
            listOf(LongColumnType() to now - actorRecipientIntervalMillis, IntegerColumnType() to batchSize),
        ) { row -> row.noticeColumn(0, Long::class.javaObjectType) }.size
        recipients + pairs
    }

    private suspend fun pacingNow(): Long = bounds.pacingClock?.invoke() ?: TransactionManager.current().noticeRows(
        DATABASE_NOW,
        emptyList(),
    ) { row -> row.noticeColumn(0, Long::class.javaObjectType) }.single()

    private suspend fun R2dbcTransaction.lockRecipient(
        recipientArgument: Pair<LongColumnType, Long>,
    ): Long? = noticeRows(LOCK_RECIPIENT, listOf(recipientArgument)) {
        it.noticeColumn(0, Long::class.javaObjectType)
    }.singleOrNull()
}
