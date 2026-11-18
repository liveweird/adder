package ch.nokillswit.threads

import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.users.UserService
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.insert

/**
 * The messages of a thread (V14): the `messages` table and the insert the thread's create uses for its opening message. Soft-deleted
 * (`marked_as_deleted`) to a listed tombstone whose body is scrubbed to '' and whose `deleted_by` records who removed it; the
 * list, read, post, edit and delete arrive with the message routes (0.3.0 S5).
 */
class MessageService {
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
}
