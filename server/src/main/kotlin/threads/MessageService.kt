package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.requireMessageAuthor
import ch.nokillswit.authz.requireMessageRemover
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.memberGuard
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insertReturning
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val MessageServiceKey = AttributeKey<MessageService>("MessageService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf("id" to MessageService.Messages.id)

/** The ONE sortable whitelist of the message list: `id` only (ascending is time). */
val MESSAGE_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The messages of a thread (V14): the `messages` table, the list and the single read, and the three writes - post, edit, delete - each ONE
 * write under the room lock ([withThreadWrite], [withMessageWrite]; the room row lock is the thread lock, `chat-model.md` "Guard order and
 * locking"). A message is soft-deleted (`marked_as_deleted`) to a LISTED tombstone: the body is scrubbed to '' and `deleted_by` records who
 * removed it, the author's own delete included. Reads use `users` for display names only (soft-deleted accounts included) and room data
 * only through the `rooms/` services. Everything derived from a body or a delete that is not a hashtag (the mention index first, and
 * through it the notices) follows the write through the [followers] ([MessageFollower]): each runs inside the write, AFTER the row's own
 * statement, the counters and the message's own live hint, so a follower's own hint (`notification.created`) always follows
 * `message.posted` on the wire, and on an edit a follower sees only the NEW body (the row is already overwritten): it diffs against its
 * own derived rows.
 */
class MessageService internal constructor(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val tags: MessageTagService,
    private val mentions: MessageMentionService,
    followers: List<MessageFollower> = emptyList(),
    private val reactions: MessageReactionService = MessageReactionService(database),
) {
    /**
     * Every follower of a write, in order: the mention index first, then the [followers] the composition root adds, then the reactions'
     * own (always on, so a delete clears a message's reactions whoever built the service) and the author's read marker, which a post
     * moves to the new message (also always on).
     */
    private val followers: List<MessageFollower> = listOf<MessageFollower>(mentions) + followers + reactions.follower + AuthorReadMarker

    /** The ONE assembly of message rows into responses, shared by [list], [read] and [readInTransaction] (`MessageAssembly.kt`). */
    private val assembly = MessageAssembly(mentions, reactions)

    object Messages : UIntIdTable("messages"), SoftDeletable {
        val threadId = reference("thread_id", ThreadService.Threads)
        val authorId = reference("author_id", UserService.Users)

        /** Markdown, stored raw; '' only on a tombstone (a V14 CHECK). */
        val body = text("body")
        val createdAt = long("created_at")

        /** Set by an edit of the body; null while the message is as posted. */
        val editedAt = long("edited_at").nullable()
        override val markedAsDeleted = bool("marked_as_deleted").default(false)

        /** Who deleted it, the author's own delete included (a V14 CHECK: set exactly when the message is deleted). */
        val deletedBy = reference("deleted_by", UserService.Users).nullable()

        /** The hashtag grammar version the message's tags were indexed under (V15; 0 = not yet). Set with every write of its tags. */
        val tagsVersion = short("tags_version").default(0)
    }

    /**
     * Inserts a message inside the caller's transaction (the thread write under the room lock) and returns it. The ONE chokepoint of a
     * new body (a post and a thread's opening message both come here), so its hashtags are indexed here, in the same transaction.
     * [hashtags] is [parseHashtags] of [body], parsed by the caller BEFORE it took the room lock (a parse is work, and the lock is held).
     * [threadNumber] is the thread's per-room number, for the followers; [opening] says the message opens a new thread (the audience of
     * a thread's first message is the whole room, a reply's is its watchers). The followers do NOT run here: the caller runs them with
     * [followPosted] once it has written the counters and the write's own live hint, so a follower's hint comes after it.
     */
    internal suspend fun insertInTransaction(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        authorId: UInt,
        body: String,
        hashtags: List<String>,
        at: Long,
        opening: Boolean = false,
    ): InsertedMessage {
        // `insertReturning(id)`, not `insert`: a plain insert answers `RETURNING *`, which would send the generated `search` vector (about
        // as large as the body, V16) back on every post. The generated column is never in this table object, so only the id returns.
        val id = Messages.insertReturning(listOf(Messages.id)) {
            it[Messages.threadId] = threadId
            it[Messages.authorId] = authorId
            it[Messages.body] = body
            it[createdAt] = at
            it[tagsVersion] = TAGS_VERSION
        }.single()[Messages.id].value
        tags.insertInTransaction(id, threadId, roomId, hashtags)
        return InsertedMessage(id, MessageRef(roomId, threadId, threadNumber, id, opening), authorId, body, at)
    }

    /**
     * Runs every follower's `posted` for a message [insertInTransaction] wrote, INSIDE the same write and AFTER the caller's counters and
     * its own live hint (a follower may emit hints of its own, and the wire order is "the write, then what it caused"). Answers the
     * sum of what the followers notified. A post and a thread's opening message both call it.
     */
    internal suspend fun followPosted(message: InsertedMessage): NoticeOutcome =
        followers.fold(NoticeOutcome.NONE) { sum, f -> sum + f.posted(message.ref, message.authorId, message.body, message.at) }

    /**
     * One page of thread [threadId]'s messages (its per-room [number] rides on every row), tombstones included. [idLt] keeps only the
     * messages older than that id (keyset paging inside the envelope): the count and the rows share ONE predicate in ONE transaction, so
     * `total` is counted AFTER the filter and `total > items.size` means older messages exist.
     */
    suspend fun list(threadId: UInt, number: Int, idLt: UInt?, paging: PageRequest, viewerId: UInt): MessageListResult =
        suspendTransaction(database) {
            var predicate: Op<Boolean> = Messages.threadId eq threadId
            idLt?.let { predicate = predicate and (Messages.id less it) }
            val total = Messages.selectAll().where { predicate }.count()
            val rows = Messages.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            MessageListResult(assembly.responses(rows, viewerId) { number }, total)
        }

    /**
     * Message [messageId] of room [roomId] as [viewerId] sees it (their own reactions are marked), or null when it is unknown, DELETED or
     * belongs to another room (the single read's `404`).
     */
    suspend fun read(roomId: UInt, messageId: UInt, viewerId: UInt): MessageResponse? = suspendTransaction(database) {
        val threads = ThreadService.Threads
        val row = Messages.innerJoin(threads).selectAll()
            .where { (Messages.id eq messageId) and (threads.roomId eq roomId) and Messages.active() }
            .toList().singleOrNull() ?: return@suspendTransaction null
        assembly.responses(listOf(row), viewerId) { it[threads.number] }.single()
    }

    /**
     * The live messages [ids] of room [roomId] as they read now for [viewerId] (their own reactions are marked), by id, inside the
     * CALLER's transaction (a pin listing reads them beside its own rows); an id that is unknown, deleted or of another room is simply
     * absent. One query for the lot, plus the mentions' and the reactions' reads.
     */
    internal suspend fun readInTransaction(roomId: UInt, ids: Collection<UInt>, viewerId: UInt): Map<UInt, MessageResponse> {
        if (ids.isEmpty()) return emptyMap()
        val threads = ThreadService.Threads
        val rows = Messages.innerJoin(threads).selectAll()
            .where { (Messages.id inList ids.toSet()) and (threads.roomId eq roomId) and Messages.active() }.toList()
        return assembly.responses(rows, viewerId) { it[threads.number] }.associateBy { it.id }
    }

    /**
     * The author of message [messageId] of room [roomId] read WITHOUT a lock, or null when it is unknown, deleted or in another room: the
     * `403` pre-check of the edit and delete guards (a missing message is `404` there) that the write repeats under the room lock.
     */
    suspend fun authorOf(roomId: UInt, messageId: UInt): UInt? = suspendTransaction(database) {
        val threads = ThreadService.Threads
        Messages.innerJoin(threads).select(Messages.authorId)
            .where { (Messages.id eq messageId) and (threads.roomId eq roomId) and Messages.active() }
            .toList().singleOrNull()?.get(Messages.authorId)?.value
    }

    /**
     * Posts to thread [number] in ONE thread write ([withThreadWrite], the MEMBER guard on the re-read caller): an archived room is `409
     * room-archived`, a closed thread `409 thread-closed`, then the message, `message_count + 1` and the activity bump of the thread
     * and the room all carry ONE instant. The body is sanitised and validated here too (both idempotent after the route).
     */
    suspend fun post(roomId: UInt, number: Int, caller: CallerPrincipal, raw: MessageRequest): PostedMessage {
        val request = sanitizedMessage(raw)
        validateMessage(request)
        val hashtags = parseHashtags(request.body) // before the room lock
        return database.withThreadWrite(roomId, number, caller, memberGuard) { write ->
            write.requireRoomActive()
            write.requireThreadOpen()
            val authorId = write.caller.userId
            val stamp = nowMillis()
            val inserted = insertInTransaction(roomId, write.thread.id, number, authorId, request.body, hashtags, stamp)
            val id = inserted.id
            val threads = ThreadService.Threads
            threads.update({ threads.id eq write.thread.id }) {
                it[messageCount] = messageCount + 1
                it[lastActivityAt] = stamp
            }
            rooms.touchActivity(roomId, stamp, authorId)
            notifyLive(LiveHint(LiveKind.MESSAGE_POSTED, roomId, threadNumber = number, messageId = id))
            val notices = followPosted(inserted)
            val name = userNames(listOf(authorId)).getValue(authorId)
            val tokens = request.body.contains("<@")
            val mentioned = if (tokens) mentions.mentionsOf(listOf(id))[id].orEmpty() else emptyList()
            val grouped = if (tokens) mentions.groups.groupsOf(listOf(id))[id].orEmpty() else emptyList()
            val response = MessageResponse(
                id, number, authorId, name, request.body, false, false, null, stamp, mentioned, grouped, emptyList(),
            )
            PostedMessage(write.thread.id, response, notices)
        }
    }

    /**
     * Edits message [messageId] in ONE message write: the AUTHOR while a member ([requireMessageAuthor] on the fresh caller and row), an
     * archived room `409`, a closed thread `409`; the same body again (after sanitising) is null, a `204` with no write; otherwise the
     * body is overwritten (no versions kept) and `edited_at` set. An edit never bumps the activity or `message_count`. The hashtags follow
     * the body in the same transaction: rewritten when the folded set changed, or when the message was never indexed under this grammar.
     */
    suspend fun edit(roomId: UInt, messageId: UInt, caller: CallerPrincipal, raw: MessageRequest): MessageEdit? {
        val request = sanitizedMessage(raw)
        validateMessage(request)
        val newTags = parseHashtags(request.body) // the new body is parsed ONCE, before the room lock
        return database.withMessageWrite(
            roomId, messageId, caller, readerGuard,
            actGuard = { fresh, standing, message -> requireMessageAuthor(fresh, standing, message.authorId) },
        ) { write ->
            write.threadWrite.requireRoomActive()
            write.threadWrite.requireThreadOpen()
            if (request.body == write.message.body) return@withMessageWrite null
            Messages.update({ Messages.id eq messageId }) {
                it[body] = request.body
                it[editedAt] = nowMillis()
                it[tagsVersion] = TAGS_VERSION
            }
            // The old body is parsed only when the version is current (a stale one rewrites without comparing).
            if (write.message.tagsVersion < TAGS_VERSION || tagKeysDiffer(write.message.body, newTags)) {
                tags.replaceInTransaction(messageId, write.thread.id, roomId, newTags)
            }
            notifyLive(LiveHint(LiveKind.MESSAGE_EDITED, roomId, threadNumber = write.thread.number, messageId = messageId))
            val ref = MessageRef(roomId, write.thread.id, write.thread.number, messageId) { isOpening(write.thread.id, messageId) }
            val notices = followers.fold(NoticeOutcome.NONE) { sum, f -> sum + f.edited(ref, write.message.authorId, request.body) }
            MessageEdit(write.thread.id, messageId, notices)
        }
    }

    /**
     * Deletes message [messageId] in ONE message write (D2): the AUTHOR while a member, the room OWNER or an ADMIN
     * ([requireMessageRemover]), an archived room `409`, a closed thread `409` (a moderator's delete included: a steward reopens, deletes
     * and closes again). The body is scrubbed to '', the row marked and `deleted_by` set to the caller; `message_count` and the activity
     * stay as they are. The message's hashtags are deleted in the same transaction, AFTER the row update (which takes the row lock a boot
     * reindex may hold), so a scrubbed body never keeps a findable tag.
     */
    suspend fun delete(roomId: UInt, messageId: UInt, caller: CallerPrincipal): MessageRemoval =
        database.withMessageWrite(
            roomId, messageId, caller, readerGuard,
            actGuard = { fresh, standing, message -> requireMessageRemover(fresh, standing, message.authorId) },
        ) { write ->
            write.threadWrite.requireRoomActive()
            write.threadWrite.requireThreadOpen()
            val remover = write.caller.userId
            Messages.update({ (Messages.id eq messageId) and Messages.active() }) {
                it[body] = ""
                it[markedAsDeleted] = true
                it[deletedBy] = remover
            }
            tags.clearInTransaction(messageId)
            notifyLive(LiveHint(LiveKind.MESSAGE_DELETED, roomId, threadNumber = write.thread.number, messageId = messageId))
            // A delete does not read `opening`: nothing about a removed message's audience depends on it.
            val ref = MessageRef(roomId, write.thread.id, write.thread.number, messageId, opening = false)
            followers.forEach { it.deleted(ref) }
            MessageRemoval(write.thread.id, messageId, write.message.authorId, moderated = remover != write.message.authorId)
        }

    /** Whether message [messageId] is the first message ever written to thread [threadId] (its opening message; a tombstone counts). */
    private suspend fun isOpening(threadId: UInt, messageId: UInt): Boolean {
        val first = Messages.id.min()
        return Messages.select(first).where { Messages.threadId eq threadId }.toList().single()[first]?.value == messageId
    }
}

/** A message just inserted: its row [id] and what [MessageService.followPosted] needs to tell the followers about it. */
internal class InsertedMessage(val id: UInt, val ref: MessageRef, val authorId: UInt, val body: String, val at: Long)
