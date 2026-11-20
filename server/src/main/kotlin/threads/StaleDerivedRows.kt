package ch.nokillswit.threads

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Messages a purge batch handles (the [MessageTagService.purgeDeletedTags] batch). */
private const val STALE_BATCH = 1_000

/**
 * ONE batch: up to [STALE_BATCH] DELETED messages that still carry a derived row, locked `FOR NO KEY UPDATE OF m SKIP LOCKED` (a writer
 * holding a message is skipped and found by the next boot), and every mention, reaction, pin and notice row of them removed by
 * data-modifying CTEs; the answer is the number of rows removed. The candidates come from the derived tables (never a scan of
 * `messages` for its deleted flag, which has no index). Constant text.
 */
private const val PURGE_STALE_SQL = """
WITH stale AS (
    SELECT m.id FROM messages m
    WHERE m.marked_as_deleted AND m.id IN (
        SELECT message_id FROM message_mentions
        UNION SELECT message_id FROM message_reactions
        UNION SELECT message_id FROM room_pins WHERE message_id IS NOT NULL
        UNION SELECT message_id FROM notifications WHERE message_id IS NOT NULL
    )
    ORDER BY m.id LIMIT $STALE_BATCH FOR NO KEY UPDATE OF m SKIP LOCKED
),
mentions AS (DELETE FROM message_mentions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
reactions AS (DELETE FROM message_reactions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
pins AS (DELETE FROM room_pins WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
notices AS (DELETE FROM notifications WHERE message_id IN (SELECT id FROM stale) RETURNING 1)
SELECT (SELECT count(*) FROM mentions) + (SELECT count(*) FROM reactions)
    + (SELECT count(*) FROM pins) + (SELECT count(*) FROM notices)
"""

/**
 * The boot-time net for the derived rows of DELETED messages other than hashtags (V17 mentions, V18 notices, V20 reactions, V21 pins;
 * [MessageTagService.purgeDeletedTags] owns the tags). A 0.6.0 delete clears them in its own transaction (the message followers), but a
 * 0.5.x binary run against a V17+ database (a rollback) deletes a message without knowing them, and nothing else would ever remove the
 * rows (`persistence.md` "Mentions (V17)", "Rollback below 0.6.0"). Batched, one transaction each. It touches `notifications` directly:
 * the `ThreadNotices` port deletes per message it is told about, and this net exists for messages nobody told it of (the cross-feature
 * list in `persistence.md` names it). Counts only; no id, name or body is read out.
 */
class StaleDerivedRows(private val database: R2dbcDatabase) {
    /** Removes the rows of deleted messages and answers how many rows it removed (0 when there is nothing to do). */
    suspend fun purge(): Int {
        var removed = 0
        while (true) {
            val purged = suspendTransaction(database) {
                TransactionManager.current().exec(
                    PURGE_STALE_SQL,
                    emptyList(),
                    explicitStatementType = StatementType.SELECT,
                ) { row -> row.get(0, Long::class.javaObjectType)!!.toInt() }?.toList().orEmpty().firstOrNull() ?: 0
            }
            if (purged == 0) return removed
            removed += purged
        }
    }
}
