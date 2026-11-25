package ch.nokillswit.threads

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One batch's statement budget, whole seconds (the transaction's `queryTimeout`: the only way a statement timeout is set,
 * `persistence.md`).
 * The candidate SQL is a UNION of the message ids of the six derived tables (each read in full: the statement is only as cheap as
 * those tables are small) joined to `messages` and filtered on its deleted flag (no index on that flag, so `messages` is probed by
 * primary key per candidate, or scanned if the planner prefers it). That is milliseconds at today's sizes; a statement past this is
 * cancelled and the boot task logs it scrubbed ([bootRun]: class names and the SQLSTATE) and leaves the rows to the next boot.
 */
private const val STALE_STATEMENT_TIMEOUT_SECONDS = 30

/** Messages a purge batch handles (the [MessageTagService.purgeDeletedTags] batch). */
private const val STALE_BATCH = 1_000

/**
 * Every table this pass purges, by name. `StaleDerivedRowsTest` compares it with the catalog (every table with a foreign key to
 * `messages`), so a table a later migration adds fails there until it is purged here or named as handled elsewhere.
 */
val STALE_DERIVED_TABLES: Set<String> =
    setOf("message_mentions", "message_group_mentions", "message_here_mentions", "message_reactions", "room_pins", "notifications")

/**
 * ONE batch: up to [batch] (an Int, never input) DELETED messages that still carry a derived row, locked `FOR NO KEY UPDATE OF m SKIP
 * LOCKED` (a writer holding a message is skipped and found by the next boot), and every mention, group-mention, reaction, pin and notice
 * row of them removed by data-modifying CTEs; the answer is the number of rows removed. The candidates are a UNION over the six derived
 * tables, then checked against `messages` for the deleted flag (which has no index; see the budget above). Constant text per batch size.
 */
private fun purgeStaleSql(batch: Int) = """
WITH stale AS (
    SELECT m.id FROM messages m
    WHERE m.marked_as_deleted AND m.id IN (
        SELECT message_id FROM message_mentions
        UNION SELECT message_id FROM message_group_mentions
        UNION SELECT message_id FROM message_here_mentions
        UNION SELECT message_id FROM message_reactions
        UNION SELECT message_id FROM room_pins WHERE message_id IS NOT NULL
        UNION SELECT message_id FROM notifications WHERE message_id IS NOT NULL
    )
    ORDER BY m.id LIMIT $batch FOR NO KEY UPDATE OF m SKIP LOCKED
),
mentions AS (DELETE FROM message_mentions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
groups AS (DELETE FROM message_group_mentions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
here_mentions AS (DELETE FROM message_here_mentions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
reactions AS (DELETE FROM message_reactions WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
pins AS (DELETE FROM room_pins WHERE message_id IN (SELECT id FROM stale) RETURNING 1),
notices AS (DELETE FROM notifications WHERE message_id IN (SELECT id FROM stale) RETURNING 1)
SELECT (SELECT count(*) FROM mentions) + (SELECT count(*) FROM groups) + (SELECT count(*) FROM here_mentions) + (SELECT count(*) FROM reactions)
    + (SELECT count(*) FROM pins) + (SELECT count(*) FROM notices)
"""

/**
 * The boot-time net for the derived rows of DELETED messages other than hashtags (V17 mentions, V30 group mentions, V18 notices, V20
 * reactions, V21 pins; [MessageTagService.purgeDeletedTags] owns the tags). A 0.6.0 delete clears them in its own transaction (the
 * message followers), but a
 * 0.5.x binary run against a V17+ database (a rollback) deletes a message without knowing them, and nothing else would ever remove the
 * rows (`persistence.md` "Mentions (V17)", "Rollback below 0.6.0"). Batched, one transaction each. It touches `notifications` directly:
 * the `ThreadNotices` port deletes per message it is told about, and this net exists for messages nobody told it of (the cross-feature
 * list in `persistence.md` names it). A pin of a thread and a thread-level notice have no message and are not its business. Counts
 * only; no id, name or body is read out. [batchSize] is a constructor parameter so a test can prove more than one round.
 */
class StaleDerivedRows(private val database: R2dbcDatabase, batchSize: Int = STALE_BATCH) {
    private val sql = purgeStaleSql(batchSize)

    /** Removes the rows of deleted messages and answers how many rows it removed (0 when there is nothing to do). */
    suspend fun purge(): Int = purgeRounds().sum()

    /** [purge], as the rows removed by each round (a round is one batch in one transaction; the final empty round is not listed). */
    internal suspend fun purgeRounds(): List<Int> {
        val rounds = mutableListOf<Int>()
        while (true) {
            val purged = suspendTransaction(database) {
                queryTimeout = STALE_STATEMENT_TIMEOUT_SECONDS
                TransactionManager.current().exec(
                    sql,
                    emptyList(),
                    explicitStatementType = StatementType.SELECT,
                ) { row -> row.get(0, Long::class.javaObjectType)!!.toInt() }?.toList().orEmpty().firstOrNull() ?: 0
            }
            if (purged == 0) return rounds
            rounds += purged
        }
    }
}
