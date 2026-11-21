package ch.nokillswit.threads

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.rooms.RoomMemberService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ThreadReadServiceKey = AttributeKey<ThreadReadService>("ThreadReadService")

/** `PUT .../threads/{number}/read-marker`: the member has read the thread up to, and including, message [messageId]. */
@Serializable
data class ReadMarkerRequest(val messageId: UInt)

/** Message ids are int32 sequences: a wider value is a `400`, never a wrapped id (the `id[lt]` rule of the message list). */
internal fun requireReadMarkerMessageId(messageId: UInt) {
    if (messageId == 0u || messageId > Int.MAX_VALUE.toUInt()) throw BadRequestException("messageId must be a message id")
}

/**
 * A member's read markers (V19 `thread_reads`, `chat-model.md` "Unread (V19)"): view state of the caller alone. The marker write takes NO
 * room lock, emits no hint and writes no audit line. The row locks it takes are all the caller's own: their membership row (the
 * `seen_at` update, held to the commit), their marker row and their unseen notices of the one thread. So thirty readers acknowledging a
 * busy thread never queue behind the room's writers, and a person's writes queue only behind that same person's. It never holds a pooled
 * connection behind the room lock. A write moves only the caller's own marker. The unread COUNTS are [unreadCountsOf] and
 * [unreadOfThread] (`ThreadUnread.kt`). Cross-feature: the membership row only through [RoomMemberService]; the notices of the thread
 * through the [ThreadNotices] port.
 */
class ThreadReadService internal constructor(
    private val database: R2dbcDatabase,
    private val members: RoomMemberService,
    private val notices: ThreadNotices,
) {
    /**
     * Records that [userId] has read thread [number] of room [roomId] up to message [messageId], in ONE transaction without the room lock:
     * the caller's own membership row is updated first (`seen_at`: reading is looking, and the row stays locked to the commit, so a
     * removal waits and the marker's foreign key can never fail), then the thread (`404`), the message, which must be the thread's (a
     * tombstone counts, `404` otherwise), the forward-only marker and the thread's notices of the person up to that message. A person who
     * is no longer a member is the `404` too. The marker never moves down: reading an older message again changes nothing.
     */
    suspend fun markRead(roomId: UInt, number: Int, userId: UInt, messageId: UInt) {
        suspendTransaction(database) {
            val at = nowMillis()
            if (!members.markSeenInTransaction(roomId, userId, at)) throw NotFoundException("Room not found")
            val threads = ThreadService.Threads
            val threadId = threads.select(threads.id).where { (threads.roomId eq roomId) and (threads.number eq number) }
                .toList().singleOrNull()?.get(threads.id)?.value ?: throw NotFoundException("Thread not found")
            val messages = MessageService.Messages
            val ofThread = messages.select(messages.id).where { (messages.id eq messageId) and (messages.threadId eq threadId) }
                .toList().isNotEmpty()
            if (!ofThread) throw NotFoundException("Message not found")
            moveMarkerInTransaction(userId, threadId, roomId, messageId, at)
            notices.threadRead(threadId, userId, messageId, at)
        }
    }
}

/**
 * The author of a message has read it and everything before it: a post, and a thread's opening message, move THEIR marker to the new
 * message (a follower of the message write, so it runs under the room lock with the author's membership held by the member guard). The
 * edit and the delete change nothing here: a marker is a position, not a reference to the row.
 */
internal object AuthorReadMarker : MessageFollower {
    override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): NoticeOutcome {
        moveMarkerInTransaction(authorId, ref.threadId, ref.roomId, ref.messageId, at)
        return NoticeOutcome.NONE
    }
}
