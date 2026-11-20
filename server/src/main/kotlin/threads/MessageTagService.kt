package ch.nokillswit.threads

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.deleted
import ch.nokillswit.infra.db.unaccent
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val MessageTagServiceKey = AttributeKey<MessageTagService>("MessageTagService")

private val TAG_SORT_FIELDS_COLUMNS: Map<String, Expression<*>> = mapOf(
    "tag" to MessageTagService.MessageTags.tag,
    "threadCount" to MessageTagService.MessageTags.threadId.countDistinct(),
    // The paging helper's `id` tiebreaker: a tag is its own identity within a room.
    "id" to MessageTagService.MessageTags.tag,
)

/** The ONE sortable whitelist of the room tag list, derived from the column map: `tag`, `threadCount` (not the `id` tiebreaker). */
val ROOM_TAG_SORT_FIELDS: Set<String> = TAG_SORT_FIELDS_COLUMNS.keys - "id"

/** The room tag list's default order: the most used tags first. */
val ROOM_TAG_DEFAULT_SORT: List<SortField> = listOf(SortField("threadCount", descending = true))

/** How many stale messages one reindex transaction takes (and holds row locks on) at a time. */
private const val REINDEX_BATCH = 200

/** How many deleted messages' leftover tag rows one purge statement removes at a time. */
private const val PURGE_BATCH = 1_000

/**
 * The write: four parallel arrays (message, thread, room, already-lower-cased tag), folded in SQL by the SAME expression [foldedTagKey]
 * renders for the filter. The WHERE keeps a key that folded to nothing out (none exists today; the column's CHECK must never fail a post).
 */
private const val INSERT_TAGS = """
INSERT INTO message_tags (message_id, thread_id, room_id, tag)
SELECT f.m, f.t, f.r, f.k
FROM (SELECT u.m, u.t, u.r, btrim(left(lower(public.unaccent(u.x)), $MAX_STORED_TAG_LENGTH)) AS k
      FROM unnest(CAST(? AS int4[]), CAST(? AS int4[]), CAST(? AS int4[]), CAST(? AS text[])) AS u(m, t, r, x)) f
WHERE char_length(f.k) >= 1
ON CONFLICT DO NOTHING"""

/** A message whose body is to be (re)indexed: where it lives and what it says. */
private class TaggedMessage(val messageId: UInt, val threadId: UInt, val roomId: UInt, val tags: List<String>)

/**
 * The derived hashtag index (V15 `message_tags`, `chat-model.md` "Hashtags (V15)"): what the thread list's `tag` filter and the room's
 * tag list read. A tag belongs to a MESSAGE; a thread has it while one of its LIVE messages does. The three writes run INSIDE the
 * message's own write transaction under the room lock ([insertInTransaction], [replaceInTransaction], [clearInTransaction], called by
 * `MessageService`), so a body and its tags commit or roll back together and a delete cannot leave a tag behind a scrubbed body. The
 * table is hard-deleted (it is rebuilt from the bodies; `persistence.md` "Hashtags (V15)"). [reindexStale] indexes the messages older
 * than the index at boot and [purgeDeletedTags] removes the rows a deleted message left. Tags are never logged.
 */
class MessageTagService(
    private val database: R2dbcDatabase,
    private val readLimiter: ReadLimiter = ReadLimiter(),
) {
    /** The derived index. A tag is stored folded; there is no soft delete and no id of its own. */
    object MessageTags : Table("message_tags") {
        val messageId = reference("message_id", MessageService.Messages)
        val threadId = reference("thread_id", ThreadService.Threads)

        /** Copied from the message's thread (a thread never changes room): both reads start at one room's rows. */
        val roomId = reference("room_id", RoomService.Rooms)
        val tag = varchar("tag", length = MAX_STORED_TAG_LENGTH)
        override val primaryKey = PrimaryKey(messageId, tag)
    }

    /**
     * Indexes [tags] (the body's hashtags, from [parseHashtags], parsed by the caller BEFORE it took the room lock) of a NEW message
     * [messageId] of thread [threadId] in room [roomId] (no row exists yet, so none is deleted).
     */
    suspend fun insertInTransaction(messageId: UInt, threadId: UInt, roomId: UInt, tags: List<String>) =
        insertTags(listOf(TaggedMessage(messageId, threadId, roomId, tags)))

    /** Replaces the tags of message [messageId] with [tags] (parsed by the caller before the lock): an edit whose tag set changed. */
    suspend fun replaceInTransaction(messageId: UInt, threadId: UInt, roomId: UInt, tags: List<String>) {
        MessageTags.deleteWhere { MessageTags.messageId eq messageId }
        insertTags(listOf(TaggedMessage(messageId, threadId, roomId, tags)))
    }

    /** Removes every tag of message [messageId]: its delete, a moderator's included. Run AFTER the message row's own update (the lock). */
    suspend fun clearInTransaction(messageId: UInt) {
        MessageTags.deleteWhere { MessageTags.messageId eq messageId }
    }

    /**
     * ONE `INSERT ... SELECT ... FROM unnest(...) ON CONFLICT DO NOTHING` for every tag of [messages] (a live write passes one message, a
     * reindex batch up to [REINDEX_BATCH]): the arrays keep the statement text constant and a batch is one round trip however many tags it
     * holds, so a reindex batch holds its row locks for one insert, not thousands. Two spellings can fold to one key (the conflict is
     * skipped). A message without a hashtag writes nothing.
     */
    private suspend fun insertTags(messages: List<TaggedMessage>) {
        val rows = messages.flatMap { message -> message.tags.map { message to foldedTag(it) } }
        if (rows.isEmpty()) return
        val ints = ArrayColumnType<Int, List<Int>>(IntegerColumnType())
        val texts = ArrayColumnType<String, List<String>>(TextColumnType())
        TransactionManager.current().exec(
            INSERT_TAGS,
            listOf(
                ints to rows.map { it.first.messageId.toInt() },
                ints to rows.map { it.first.threadId.toInt() },
                ints to rows.map { it.first.roomId.toInt() },
                texts to rows.map { it.second },
            ),
        )
    }

    /**
     * One page of the room's tags: one predicate for the count and the rows, in one transaction, over `idx_message_tags_room_tag` (the
     * room's rows only, an index-only scan). A tag's [RoomTagResponse.threadCount] counts distinct threads. Deleted messages hold no
     * rows, so every row is a live one. Both statements run through the [readLimiter]: ONE
     * deadline (`threads.readTimeoutSeconds`, past it the audited `429` `read-timeout`) and the in-flight caps (`read-busy`).
     */
    suspend fun list(callerUserId: UInt, roomId: UInt, filter: RoomTagListFilter, paging: PageRequest): RoomTagListResult =
        readLimiter.run(database, callerUserId, roomId, "tags") { budget ->
            val tags = MessageTags
            var predicate: Op<Boolean> = tags.roomId eq roomId
            filter.q?.takeIf { it.isNotBlank() }?.let { predicate = predicate and tags.tag.containsNormalized(it) }
            val threadCount = tags.threadId.countDistinct()
            budget.arm(this)
            val total = tags.select(tags.tag).where { predicate }.groupBy(tags.tag).count()
            budget.arm(this)
            val rows = tags.select(tags.tag, threadCount).where { predicate }.groupBy(tags.tag)
                .applyPaging(paging, TAG_SORT_FIELDS_COLUMNS)
                .map { RoomTagResponse(it[tags.tag], it[threadCount]) }
                .toList()
            RoomTagListResult(rows, total)
        }

    /**
     * Indexes every LIVE message whose `tags_version` is below [TAGS_VERSION] (the messages older than the index, a grammar bump, or a
     * runbook reset), in batches of [REINDEX_BATCH] under their own transaction each, and answers how many it indexed. A batch selects
     * its message rows `FOR NO KEY UPDATE SKIP LOCKED` (`OF messages`: the thread rows stay free for the writers): a message a writer holds
     * is skipped (the writer sets the version itself) and found by the next run, and a writer that arrives while a batch holds its row
     * waits for the commit. A batch is one delete and ONE insert statement. Row locks and ids only: no body or tag leaves this method
     * except through the caller's failure.
     */
    suspend fun reindexStale(): Int {
        var indexed = 0
        var after = 0u
        while (true) {
            val batch = reindexBatch(after)
            if (batch.isEmpty()) return indexed
            indexed += batch.size
            after = batch.last()
        }
    }

    /** One batch: the ids it indexed, ascending. */
    private suspend fun reindexBatch(after: UInt): List<UInt> = suspendTransaction(database) {
        val messages = MessageService.Messages
        val threads = ThreadService.Threads
        val stale = messages.join(threads, JoinType.INNER, messages.threadId, threads.id)
            .select(messages.id, messages.threadId, threads.roomId, messages.body)
            .where { messages.active() and (messages.tagsVersion less TAGS_VERSION) and (messages.id greater after) }
            .orderBy(messages.id)
            .limit(REINDEX_BATCH)
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED, messages))
            .map {
                val body = it[messages.body]
                TaggedMessage(it[messages.id].value, it[messages.threadId].value, it[threads.roomId].value, parseHashtags(body))
            }
            .toList()
        val ids = stale.map { it.messageId }
        if (ids.isNotEmpty()) {
            // One delete for the batch (a stale message normally has no row), then ONE insert for every tag the bodies carry.
            MessageTags.deleteWhere { MessageTags.messageId inList ids }
            insertTags(stale)
            messages.update({ messages.id inList ids }) { it[tagsVersion] = TAGS_VERSION }
        }
        ids
    }

    /**
     * Removes the tag rows of DELETED messages and answers how many rows it removed. A 0.5.0 write never leaves one, but a 0.4.0 binary
     * (a rollback) deletes a message without knowing the index, and the reindex only looks at LIVE messages below the version; this is
     * the boot-time net for that case. Batches of [PURGE_BATCH] messages, one transaction each; a message deleted while a batch runs is
     * just removed twice.
     */
    suspend fun purgeDeletedTags(): Int {
        var removed = 0
        while (true) {
            val purged = suspendTransaction(database) {
                val tags = MessageTags
                val messages = MessageService.Messages
                val ids = tags.join(messages, JoinType.INNER, tags.messageId, messages.id)
                    .select(tags.messageId)
                    .where { messages.deleted() }
                    .withDistinct()
                    .limit(PURGE_BATCH)
                    .map { it[tags.messageId].value }
                    .toList()
                if (ids.isEmpty()) 0 else tags.deleteWhere { tags.messageId inList ids }
            }
            if (purged == 0) return removed
            removed += purged
        }
    }
}

/**
 * The stored form of [tag] as a SQL expression: `btrim(left(lower(public.unaccent(?)), 150))` over the Kotlin-lower-cased text. Kotlin
 * lowers (so Greek, Cyrillic and the like fold), `unaccent` strips the accents, SQL `lower` catches the capitals `unaccent` itself
 * produces (`ℌ` becomes `H`), `left` keeps a folding that lengthened the tag within the column, and `btrim` removes the space `unaccent`
 * puts in front of a vulgar fraction (`½` becomes `" 1/2"`) that a key would otherwise start with, or that the cut can leave at its
 * end. The write ([INSERT_TAGS]) and the filter both fold with this, so a key the tag list returns (a stored key) folds to itself and a
 * truncated key still matches itself.
 */
internal fun foldedTagKey(tag: String): Expression<String> {
    val unaccented = unaccent(stringParam(foldedTag(tag)))
    val lowered = CustomFunction("lower", TextColumnType(), unaccented)
    val cut = CustomFunction("left", TextColumnType(), lowered, intLiteral(MAX_STORED_TAG_LENGTH))
    return CustomFunction("btrim", TextColumnType(), cut)
}

/**
 * `EXISTS (SELECT 1 FROM message_tags WHERE room_id = <roomId> AND thread_id = <threadId> AND tag IN (<folded>, ...))`: the thread has a
 * live message that carries any of [tags] (already validated by [requireTagFilter]). Correlated on [threadId], the outer list's
 * `threads.id`; the room predicate lets PostgreSQL probe `idx_message_tags_room_tag` for the room's rows alone and semi-join them.
 */
internal fun threadCarriesAnyTag(roomId: UInt, threadId: Column<EntityID<UInt>>, tags: List<String>): Op<Boolean> {
    val index = MessageTagService.MessageTags
    return exists(
        index.select(index.messageId).where {
            (index.roomId eq roomId) and (index.threadId eq threadId) and tags.map { index.tag eq foldedTagKey(it) }.compoundOr()
        },
    )
}
