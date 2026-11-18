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
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
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
 * only through the `rooms/` services.
 */
class MessageService(private val database: R2dbcDatabase, private val rooms: RoomService) {
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
    }

    /** Inserts a message inside the caller's transaction (the thread write under the room lock) and returns its id. */
    suspend fun insertInTransaction(threadId: UInt, authorId: UInt, body: String, at: Long): UInt = Messages.insert {
        it[Messages.threadId] = threadId
        it[Messages.authorId] = authorId
        it[Messages.body] = body
        it[createdAt] = at
    }[Messages.id].value

    /**
     * One page of thread [threadId]'s messages (its per-room [number] rides on every row), tombstones included. [idLt] keeps only the
     * messages older than that id (keyset paging inside the envelope): the count and the rows share ONE predicate in ONE transaction, so
     * `total` is counted AFTER the filter and `total > items.size` means older messages exist.
     */
    suspend fun list(threadId: UInt, number: Int, idLt: UInt?, paging: PageRequest): MessageListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = Messages.threadId eq threadId
        idLt?.let { predicate = predicate and (Messages.id less it) }
        val total = Messages.selectAll().where { predicate }.count()
        val rows = Messages.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val names = userNames(rows.map { it[Messages.authorId].value })
        MessageListResult(rows.map { it.toResponse(number, names.getValue(it[Messages.authorId].value)) }, total)
    }

    /** Message [messageId] of room [roomId], or null when it is unknown, DELETED or belongs to another room (the single read's `404`). */
    suspend fun read(roomId: UInt, messageId: UInt): MessageResponse? = suspendTransaction(database) {
        val threads = ThreadService.Threads
        val row = Messages.innerJoin(threads).selectAll()
            .where { (Messages.id eq messageId) and (threads.roomId eq roomId) and Messages.active() }
            .toList().singleOrNull() ?: return@suspendTransaction null
        row.toResponse(row[threads.number], userNames(listOf(row[Messages.authorId].value)).getValue(row[Messages.authorId].value))
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
        return database.withThreadWrite(roomId, number, caller, memberGuard) { write ->
            write.requireRoomActive()
            write.requireThreadOpen()
            val authorId = write.caller.userId
            val stamp = nowMillis()
            val id = insertInTransaction(write.thread.id, authorId, request.body, stamp)
            val threads = ThreadService.Threads
            threads.update({ threads.id eq write.thread.id }) {
                it[messageCount] = messageCount + 1
                it[lastActivityAt] = stamp
            }
            rooms.touchActivity(roomId, stamp)
            notifyLive(LiveHint(LiveKind.MESSAGE_POSTED, roomId, threadNumber = number, messageId = id))
            val name = userNames(listOf(authorId)).getValue(authorId)
            PostedMessage(write.thread.id, MessageResponse(id, number, authorId, name, request.body, false, false, null, stamp))
        }
    }

    /**
     * Edits message [messageId] in ONE message write: the AUTHOR while a member ([requireMessageAuthor] on the fresh caller and row), an
     * archived room `409`, a closed thread `409`; the same body again (after sanitising) is null, a `204` with no write; otherwise the
     * body is overwritten (no versions kept) and `edited_at` set. An edit never bumps the activity or `message_count`.
     */
    suspend fun edit(roomId: UInt, messageId: UInt, caller: CallerPrincipal, raw: MessageRequest): MessageEdit? {
        val request = sanitizedMessage(raw)
        validateMessage(request)
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
            }
            notifyLive(LiveHint(LiveKind.MESSAGE_EDITED, roomId, threadNumber = write.thread.number, messageId = messageId))
            MessageEdit(write.thread.id, messageId)
        }
    }

    /**
     * Deletes message [messageId] in ONE message write (D2): the AUTHOR while a member, the room OWNER or an ADMIN
     * ([requireMessageRemover]), an archived room `409`, a closed thread `409` (a moderator's delete included: a steward reopens, deletes
     * and closes again). The body is scrubbed to '', the row marked and `deleted_by` set to the caller; `message_count` and the activity
     * stay as they are.
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
            notifyLive(LiveHint(LiveKind.MESSAGE_DELETED, roomId, threadNumber = write.thread.number, messageId = messageId))
            MessageRemoval(write.thread.id, messageId, write.message.authorId, moderated = remover != write.message.authorId)
        }

    private fun ResultRow.toResponse(threadNumber: Int, authorName: String): MessageResponse {
        val deleted = this[Messages.markedAsDeleted]
        val authorId = this[Messages.authorId].value
        return MessageResponse(
            id = this[Messages.id].value,
            threadNumber = threadNumber,
            authorId = authorId,
            authorName = authorName,
            body = if (deleted) null else this[Messages.body],
            deleted = deleted,
            deletedByModerator = this[Messages.deletedBy]?.value.let { it != null && it != authorId },
            editedAt = if (deleted) null else this[Messages.editedAt],
            createdAt = this[Messages.createdAt],
        )
    }
}
