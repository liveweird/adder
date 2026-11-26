package ch.nokillswit.threads

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** If a batch exceeds its statement budget, [bootRun] logs a scrubbed warning and resumes the pass next boot. */
private const val STALE_STATEMENT_TIMEOUT_SECONDS = 30

/** Messages a purge batch handles (the [MessageTagService.purgeDeletedTags] batch). */
private const val STALE_BATCH = 1_000

/**
 * Every table this pass purges, by name. `StaleDerivedRowsTest` compares it with the catalog (every table with a foreign key to
 * `messages`), so a table a later migration adds fails there until it is purged here or named as handled elsewhere.
 */
val STALE_DERIVED_TABLES: Set<String> =
    setOf("message_mentions", "message_group_mentions", "message_here_mentions", "message_reactions", "room_pins", "notifications")

// Keyset batches walk V50's partial index. An array from the batch lets each DELETE probe its message_id index.
// Separate the notice deletes along V45's disjoint partial-index predicates.
private const val PURGE_STALE_SQL = """
WITH stale AS (
    SELECT m.id FROM messages m
    WHERE m.marked_as_deleted AND m.id > ?
    ORDER BY m.id LIMIT ? FOR NO KEY UPDATE OF m SKIP LOCKED
),
batch AS (SELECT coalesce(max(id), 0) AS last_id, array(SELECT id FROM stale) AS ids FROM stale),
mentions AS (DELETE FROM message_mentions WHERE message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1),
groups AS (DELETE FROM message_group_mentions WHERE message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1),
here_mentions AS (DELETE FROM message_here_mentions WHERE message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1),
reactions AS (DELETE FROM message_reactions WHERE message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1),
pins AS (DELETE FROM room_pins WHERE message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1),
notices AS (
    DELETE FROM notifications WHERE message_id IS NOT NULL AND notification_type <> 'REACTION_ADDED'
      AND message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1
),
reaction_notices AS (
    DELETE FROM notifications WHERE message_id IS NOT NULL AND notification_type = 'REACTION_ADDED'
      AND message_id = ANY ((SELECT ids FROM batch)::integer[]) RETURNING 1
)
SELECT last_id, (SELECT count(*) FROM mentions) + (SELECT count(*) FROM groups) + (SELECT count(*) FROM here_mentions)
    + (SELECT count(*) FROM reactions) + (SELECT count(*) FROM pins) + (SELECT count(*) FROM notices)
    + (SELECT count(*) FROM reaction_notices)
FROM batch
"""

/**
 * The boot-time net for the derived rows of DELETED messages other than hashtags (V17 mentions, V30 group mentions, V18 notices, V20
 * reactions, V21 pins; [MessageTagService.purgeDeletedTags] owns the tags). A 0.6.0 delete clears them in its own transaction (the
 * message followers), but a
 * 0.5.x binary run against a V17+ database (a rollback) deletes a message without knowing them, and nothing else would ever remove the
 * rows (`persistence.md` "Mentions (V17)", "Rollback below 0.6.0"). Batched, one transaction each. It touches `notifications` directly:
 * the `ThreadNotices` port deletes per message it is told about, and this net exists for messages nobody told it of (the cross-feature
 * list in `persistence.md` names it). A pin of a thread and a thread-level notice have no message and are not its business. The cursor
 * stays internal and logs carry counts only. Locked tombstones are revisited on the next boot.
 */
class StaleDerivedRows(private val database: R2dbcDatabase, private val batchSize: Int = STALE_BATCH) {
    /** Removes the rows of deleted messages and answers how many rows it removed (0 when there is nothing to do). */
    suspend fun purge(): Int = purgeRounds().sum()

    /** [purge], as the counts for batches that removed rows, one transaction per batch. */
    internal suspend fun purgeRounds(): List<Int> {
        val rounds = mutableListOf<Int>()
        var cursor = 0
        while (true) {
            val (lastId, purged) = purgeBatch(cursor)
            if (lastId == 0) return rounds
            cursor = lastId
            if (purged > 0) rounds += purged
        }
    }

    private suspend fun purgeBatch(cursor: Int): Pair<Int, Int> = suspendTransaction(database) {
        queryTimeout = STALE_STATEMENT_TIMEOUT_SECONDS
        val result = TransactionManager.current().exec(
            PURGE_STALE_SQL,
            listOf(IntegerColumnType() to cursor, IntegerColumnType() to batchSize),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            row.get(0, Int::class.javaObjectType)!! to row.get(1, Long::class.javaObjectType)!!.toInt()
        }
        checkNotNull(result?.toList()?.single())
    }
}
