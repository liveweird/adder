package ch.nokillswit.threads

import ch.nokillswit.infra.JsonUInt
import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.rooms.RoomMemberService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
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
data class ReadMarkerRequest(val messageId: JsonUInt)

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
            val threadId = threads.select(threads.id).where { threadAddress(roomId, number) }
                .toList().singleOrNull()?.get(threads.id)?.value ?: throw NotFoundException("Thread not found")
            val messages = MessageService.Messages
            val ofThread = messages.select(messages.id).where { (messages.id eq messageId) and (messages.threadId eq threadId) }
                .toList().isNotEmpty()
            if (!ofThread) throw NotFoundException("Message not found")
            moveMarkerInTransaction(userId, threadId, roomId, messageId, at)
            notices.threadRead(threadId, userId, messageId, at)
        }
    }

    /**
     * "Mark all as read" (1.2): [userId] has read everything of room [roomId] written up to now. Answers how many notices it settled.
     * No room lock anywhere, and NO transaction that holds the member row while it settles notices:
     * 1. ONE short transaction: the caller's own membership row (`read_from` and `seen_at` raised to the request's instant, forward
     *    only; no row means they were removed meanwhile: `404`), then the newest id among their unseen notices of the room (the ceiling,
     *    one index probe). From here on every thread of the room, closed and unopened ones included, reads as read, and a message or
     *    thread written later is unread again; no `thread_reads` marker moves.
     * 2. Their unseen notices of the room up to the ceiling and made no later than the instant are marked seen in KEYSET chunks of
     *    [chunk] rows (each starts past the previous chunk's last id), each its own short transaction ([ThreadNotices.roomSeenChunk]),
     *    so nothing waits behind a long statement and a person with a huge pile holds one connection for one chunk at a time. A notice
     *    made after the instant stays unseen.
     *
     * One per person at a time: a second call while one runs is the typed `429` `read-busy` with `Retry-After` (the caller's other
     * requests are unaffected), so a script cannot queue calls that each hold a pooled connection. Idempotent. A failure after step 1
     * leaves a retryable state: the floor is raised, the notices not all settled, the slot is freed and the client saw an error;
     * calling again settles the rest (the ceiling is re-read, so only what is still unseen is worked on). The audit trail is bounded
     * by what the call DID, not by how often it was made (the bucket allows 240 a minute): ONE `room.read_all` line closes a call that
     * settled at least one notice (`complete=true` with the count) and every call that FAILED or was CANCELLED after step 1
     * (`complete=false` with the count so far; the line is written from a `finally`, a plain synchronous log call, so a cancelled
     * request cannot skip it); a call that found nothing to settle writes none. A refusal for the in-flight rule is the `read.busy`
     * line, COALESCED to one a minute per person (`suppressed` carries the refusals folded since the last line).
     */
    suspend fun markRoomRead(roomId: UInt, userId: UInt, chunk: Int = ROOM_SEEN_CHUNK): Int {
        if (!inFlight.add(userId)) {
            busyAudit.admit("read.busy:read-all:$userId")?.let { suppressed ->
                audit(
                    "read.busy",
                    "byUserId" to userId.toLong(),
                    "roomId" to roomId.toLong(),
                    "resource" to "read-all",
                    "limit" to "user",
                    "suppressed" to suppressed,
                )
            }
            throw readAllBusy()
        }
        try {
            return raiseAndSettle(roomId, userId, chunk)
        } finally {
            inFlight.remove(userId)
        }
    }

    /**
     * Both steps of [markRoomRead] and its audit line (see there): the settled count. Step 1 is INSIDE the `try`, so a cancellation that
     * strikes right after it commits still leaves the one `complete=false` line. `floorRaised` is the last statement of step 1's block,
     * so a `404` (no membership row) and a failure before the raise write nothing; a commit that then fails writes one spurious line.
     */
    private suspend fun raiseAndSettle(roomId: UInt, userId: UInt, chunk: Int): Int {
        var settled = 0
        var floorRaised = false
        var finished = false
        fun line(complete: Boolean) = audit(
            "room.read_all",
            "byUserId" to userId.toLong(),
            "roomId" to roomId.toLong(),
            "settled" to settled,
            "complete" to complete,
        )
        try {
            val (at, ceiling) = suspendTransaction(database) {
                val at = nowMillis()
                if (!members.markAllReadInTransaction(roomId, userId, at)) throw NotFoundException("Room not found")
                val ceiling = notices.roomUnseenCeiling(roomId, userId)
                floorRaised = true
                at to ceiling
            }
            settleChunks(roomId, userId, at, ceiling, chunk) { settled += it }
            finished = true
        } finally {
            // A failure AND a cancellation (the client went away mid-call, also right after step 1 committed) leave the floor raised and
            // the notices part-settled: the line is the only trace of it. `audit` is a plain synchronous log call, so it runs on a
            // cancelled coroutine too.
            if (floorRaised && !finished) line(complete = false)
        }
        if (settled > 0) line(complete = true)
        return settled
    }

    private suspend fun settleChunks(roomId: UInt, userId: UInt, at: Long, ceiling: UInt?, chunk: Int, onMoved: (Int) -> Unit) {
        var after = 0u
        do {
            // The loop runs while the chunk SELECTED was full (not while it UPDATED a full one): a row that is not moved
            // (seen or deleted by someone else) makes the update short, and stopping there would leave the rest unseen.
            val done = if (ceiling == null) {
                SeenChunk(0, 0)
            } else {
                suspendTransaction(database) { notices.roomSeenChunk(roomId, userId, at, after, ceiling, chunk) }
            }
            onMoved(done.moved)
            after = done.lastId ?: after
        } while (done.selected == chunk)
    }

    /**
     * The people with a [markRoomRead] running (the entry is removed when it ends, so the set never grows past the people in flight).
     * In-process, so "one per person" holds per replica: it relies on the one-replica rule (`live.md`, `ci.md`); a second replica would
     * allow one call per person each, still bounded by the `read-state` bucket and the pool.
     */
    private val inFlight: MutableSet<UInt> = ConcurrentHashMap.newKeySet()

    /** Coalesces the `read.busy` line of a refused second call: one a minute per person (the `rate_limit.exceeded` idiom). */
    private val busyAudit = RateLimitAuditThrottle()
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
