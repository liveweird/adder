package ch.nokillswit.notifications

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** The hard per-recipient bound over every unseen notice, across rooms and delivery channels. */
internal const val MAX_UNSEEN_NOTICES = 5_000

/** One foreground operation or legacy-normalization pass deletes at most this many old rows across its locked audience. */
internal const val UNSEEN_PRUNE_BATCH = 1_000

/** One sweep pass visits at most this many legacy recipients. */
internal const val LEGACY_RECIPIENT_BATCH = 100

/** One row made by a notice operation, before the retention decision has re-read whether it survived. */
internal data class PersistedNotice(
    val userId: UInt,
    val notificationId: UInt,
    val type: NotificationType,
    val inApp: Boolean,
    /** The replacement removed an in-app row; a discarded or mail-only replacement must still invalidate the bell. */
    val replacedInApp: Boolean = false,
)

internal data class RetainedNotices(
    val survivors: List<PersistedNotice>,
    val bellChanged: Set<UInt>,
    val deleted: Int,
)

/**
 * Serializes and bounds unseen notices by recipient. A room lock is deliberately insufficient: the same person can receive concurrent
 * events from different rooms. Every producer calls [lockRecipients] once at its public entry, before an actor budget or notice mutation,
 * and calls [trimLocked] only after its whole operation. Reads and deletes can still hold notice rows; the trim skips those locks and
 * removes the next-oldest available rows, avoiding a lock-order cycle.
 */
internal class NotificationRetention(private val unseenLimit: Int = MAX_UNSEEN_NOTICES) {
    init {
        require(unseenLimit > 0)
    }

    /** Creates missing guards, then locks the distinct recipients in one ascending order. */
    suspend fun lockRecipients(recipients: Collection<UInt>) {
        val ids = recipients.distinct().sorted().map(UInt::toLong)
        if (ids.isEmpty()) return
        val transaction = TransactionManager.current()
        val array = ArrayColumnType<Long, List<Long>>(LongColumnType())
        transaction.exec(ENSURE_RECIPIENT_GUARDS, listOf(array to ids), explicitStatementType = StatementType.INSERT)
        transaction.exec(LOCK_RECIPIENT_GUARDS, listOf(array to ids), explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Long::class.javaObjectType)
        }?.toList()
    }

    /**
     * Enforces the bound for already-locked [recipients]. At most [pruneLimit] old available rows are removed in total. When a
     * legacy excess or locked old rows still leave the person oversized, this operation's new/replacement rows are discarded as well.
     * The returned survivors are re-read from storage and are the only rows callers may count, hint as creations or enqueue for push.
     */
    suspend fun trimLocked(
        recipients: Collection<UInt>,
        created: List<PersistedNotice>,
        pruneLimit: Int = UNSEEN_PRUNE_BATCH,
        clearNormalizationForCompliant: Boolean = false,
    ): RetainedNotices {
        require(pruneLimit > 0)
        val byRecipient = created.groupBy(PersistedNotice::userId)
        val bellChanged = linkedSetOf<UInt>()
        var deleted = 0
        val checked = recipients.distinct().sorted()
        val oversized = oversizedRecipients(checked)
        val expired = deleteOldestAvailable(oversized, pruneLimit)
        deleted += expired.size
        expired.filter { it.inApp }.forEach { bellChanged += it.recipient }
        val stillOversized = oversizedRecipients(oversized.toList())
        val currentIds = stillOversized.flatMap { recipient -> byRecipient[recipient].orEmpty().map(PersistedNotice::notificationId) }
        val discarded = deleteCreated(currentIds)
        deleted += discarded.size
        discarded.filter { it.inApp }.forEach { bellChanged += it.recipient }
        val finalOversized = oversizedRecipients(stillOversized.toList())
        val stateRecipients = if (clearNormalizationForCompliant) checked else oversized
        if (stateRecipients.isNotEmpty()) {
            setNormalizationState(stateRecipients, finalOversized)
        }
        val survivorIds = existingIds(created.map { it.notificationId })
        val survivors = created.filter { it.notificationId in survivorIds }
        survivors.filter { it.replacedInApp && !it.inApp }.forEach { bellChanged += it.userId }
        created.filter { it.replacedInApp && it.notificationId !in survivorIds }.forEach { bellChanged += it.userId }
        return RetainedNotices(survivors, bellChanged, deleted)
    }

    /** Locks at most one legacy guard after [after], for a bounded normalization transaction. */
    suspend fun nextLegacyRecipient(after: UInt): UInt? =
        TransactionManager.current().exec(
            NEXT_LEGACY_RECIPIENT,
            listOf(LongColumnType() to after.toLong()),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType)?.toUInt() }?.toList().orEmpty().singleOrNull()

    /**
     * Deletes one departing membership's notices in ascending locked batches before the membership's cascade. Both operations stay in
     * the caller's transaction, so rollback restores every row; the eventual cascade has no unbounded notification DML left to do.
     */
    suspend fun deleteMembershipNotices(roomId: UInt, recipientId: UInt, batchSize: Int = UNSEEN_PRUNE_BATCH): Int {
        require(batchSize > 0)
        var after = 0u
        var deleted = 0
        do {
            val ids = TransactionManager.current().exec(
                LOCK_MEMBERSHIP_NOTICE_BATCH,
                listOf(
                    LongColumnType() to recipientId.toLong(),
                    IntegerColumnType() to roomId.toInt(),
                    IntegerColumnType() to after.toInt(),
                    IntegerColumnType() to batchSize,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, Int::class.javaObjectType)?.toUInt() }?.toList().orEmpty().filterNotNull()
            if (ids.isNotEmpty()) {
                after = ids.last()
                deleted += deleteIds(recipientId, ids).size
            }
        } while (ids.size == batchSize)
        return deleted
    }

    /** One set-oriented bound probe for the whole audience; compliant broadcasts take no per-recipient statements or guard writes. */
    private suspend fun oversizedRecipients(recipients: List<UInt>): Set<UInt> {
        if (recipients.isEmpty()) return emptySet()
        return TransactionManager.current().exec(
            OVERSIZED_RECIPIENTS,
            listOf(
                ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients.map(UInt::toLong),
                IntegerColumnType() to unseenLimit,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType)?.toUInt() }?.toList().orEmpty().filterNotNull().toSet()
    }

    private suspend fun deleteOldestAvailable(recipients: Set<UInt>, prune: Int): List<DeletedNotice> {
        if (recipients.isEmpty()) return emptyList()
        return TransactionManager.current().exec(
            DELETE_OLDEST_AVAILABLE,
            listOf(
                ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients.sorted().map(UInt::toLong),
                IntegerColumnType() to (unseenLimit + prune + 1),
                IntegerColumnType() to unseenLimit,
                IntegerColumnType() to prune,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            DeletedNotice(
                row.get(0, Long::class.javaObjectType)!!.toUInt(),
                row.get(1, Int::class.javaObjectType)!!.toUInt(),
                row.get(2, Boolean::class.javaObjectType) == true,
            )
        }?.toList().orEmpty().filterNotNull()
    }

    private suspend fun deleteCreated(ids: Collection<UInt>): List<DeletedNotice> {
        if (ids.isEmpty()) return emptyList()
        return TransactionManager.current().exec(
            DELETE_CREATED_NOTICES,
            listOf(ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ids.distinct().map(UInt::toInt)),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            DeletedNotice(
                row.get(0, Long::class.javaObjectType)!!.toUInt(),
                row.get(1, Int::class.javaObjectType)!!.toUInt(),
                row.get(2, Boolean::class.javaObjectType) == true,
            )
        }?.toList().orEmpty().filterNotNull()
    }

    private suspend fun deleteIds(recipient: UInt, ids: Collection<UInt>): List<Pair<UInt, Boolean>> {
        if (ids.isEmpty()) return emptyList()
        return TransactionManager.current().exec(
            DELETE_NOTICE_IDS,
            listOf(
                LongColumnType() to recipient.toLong(),
                ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ids.distinct().map(UInt::toInt),
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            row.get(0, Int::class.javaObjectType)!!.toUInt() to (row.get(1, Boolean::class.javaObjectType) == true)
        }?.toList().orEmpty().filterNotNull()
    }

    private suspend fun existingIds(ids: Collection<UInt>): Set<UInt> {
        if (ids.isEmpty()) return emptySet()
        return TransactionManager.current().exec(
            EXISTING_NOTICE_IDS,
            listOf(ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ids.distinct().map(UInt::toInt)),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)?.toUInt() }?.toList().orEmpty().filterNotNull().toSet()
    }

    private suspend fun setNormalizationState(recipients: Collection<UInt>, needed: Collection<UInt>) {
        if (recipients.isEmpty()) return
        TransactionManager.current().exec(
            SET_NORMALIZATION_STATE,
            listOf(
                ArrayColumnType<Long, List<Long>>(LongColumnType()) to needed.map(UInt::toLong),
                ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients.map(UInt::toLong),
            ),
            explicitStatementType = StatementType.UPDATE,
        )
    }

    private data class DeletedNotice(val recipient: UInt, val id: UInt, val inApp: Boolean)
}

private const val ENSURE_RECIPIENT_GUARDS = """
INSERT INTO notification_recipient_guards (recipient_id)
SELECT recipient_id FROM unnest(CAST(? AS int8[])) AS recipients(recipient_id) ORDER BY recipient_id
ON CONFLICT (recipient_id) DO NOTHING"""

private const val LOCK_RECIPIENT_GUARDS = """
SELECT recipient_id FROM notification_recipient_guards
WHERE recipient_id = ANY(CAST(? AS int8[]))
ORDER BY recipient_id
FOR UPDATE"""

private const val OVERSIZED_RECIPIENTS = """
SELECT r.recipient_id
FROM unnest(CAST(? AS int8[])) AS r(recipient_id)
WHERE EXISTS (
  SELECT 1 FROM notifications n
  WHERE n.recipient_id = r.recipient_id AND n.seen_at IS NULL
  OFFSET CAST(? AS int4) LIMIT 1
)
ORDER BY r.recipient_id"""

private const val DELETE_OLDEST_AVAILABLE = """
WITH audience AS MATERIALIZED (
  SELECT recipient_id, count(*) OVER ()::int AS audience_size
  FROM unnest(CAST(? AS int8[])) AS recipients(recipient_id)
), targets AS MATERIALIZED (
  SELECT r.recipient_id, oldest.id
  FROM audience r
  CROSS JOIN LATERAL (
    SELECT count(*)::int AS total FROM (
      SELECT n.id FROM notifications n
      WHERE n.recipient_id = r.recipient_id AND n.seen_at IS NULL
      LIMIT CAST(? AS int4)
    ) bounded
  ) counts
  CROSS JOIN LATERAL (
    SELECT n.id FROM notifications n
    WHERE n.recipient_id = r.recipient_id AND n.seen_at IS NULL
    ORDER BY n.id
    LIMIT LEAST(GREATEST(counts.total - CAST(? AS int4), 0), CAST(? AS int4) / r.audience_size)
    FOR UPDATE SKIP LOCKED
  ) oldest
  ORDER BY r.recipient_id, oldest.id
), deleted AS (
  DELETE FROM notifications n USING targets t
  WHERE n.recipient_id = t.recipient_id AND n.id = t.id
  RETURNING n.recipient_id, n.id, n.in_app
)
SELECT recipient_id, id, in_app FROM deleted"""

private const val DELETE_CREATED_NOTICES = """
DELETE FROM notifications
WHERE id = ANY(CAST(? AS int4[]))
RETURNING recipient_id, id, in_app"""

private const val DELETE_NOTICE_IDS = """
DELETE FROM notifications
WHERE recipient_id = CAST(? AS int8) AND id = ANY(CAST(? AS int4[]))
RETURNING id, in_app"""

private const val EXISTING_NOTICE_IDS = """
SELECT id FROM notifications WHERE id = ANY(CAST(? AS int4[]))"""

private const val SET_NORMALIZATION_STATE = """
UPDATE notification_recipient_guards
SET needs_normalization = recipient_id = ANY(CAST(? AS int8[]))
WHERE recipient_id = ANY(CAST(? AS int8[]))"""

private const val NEXT_LEGACY_RECIPIENT = """
SELECT recipient_id FROM notification_recipient_guards
WHERE needs_normalization AND recipient_id > CAST(? AS int8)
ORDER BY recipient_id LIMIT 1
FOR UPDATE SKIP LOCKED"""

private const val LOCK_MEMBERSHIP_NOTICE_BATCH = """
SELECT id FROM notifications
WHERE recipient_id = CAST(? AS int8) AND room_id = CAST(? AS int4) AND id > CAST(? AS int4)
ORDER BY id LIMIT CAST(? AS int4)
FOR NO KEY UPDATE"""
