package ch.nokillswit.threads

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import kotlinx.coroutines.flow.toList

/**
 * How many of the newest messages after a member's marker one thread's count reads (`chat-model.md` "Unread (V19)"): the count is 0 to
 * this, and this many is "this many or more". It bounds the work of a count however long a thread is.
 */
const val MAX_UNREAD_COUNT = 100

/**
 * The unread counts of one list page, ONE statement for its threads. A message M of thread T is unread for a member when `M.id` is above
 * their marker in T (no marker row = 0) AND it was written after their floor (`GREATEST(read_from, added_at)`, the first argument): the
 * newest [MAX_UNREAD_COUNT] after the marker are read backwards through `idx_messages_thread` and the ones newer than the floor are
 * counted. A thread whose own `last_activity_at` is not past the floor cannot hold such a message and is skipped. Arguments: the floor,
 * the member, the thread ids, the floor again. Constant text (the arrays keep it so), the same plan custom and generic.
 */
internal const val UNREAD_COUNTS_SQL = """
SELECT t.id,
       (SELECT CAST(count(*) AS int4)
        FROM (SELECT m.created_at FROM messages m
              WHERE m.thread_id = t.id AND m.id > COALESCE(r.last_read_message_id, 0)
              ORDER BY m.id DESC LIMIT $MAX_UNREAD_COUNT) recent
        WHERE recent.created_at > CAST(? AS int8)) AS unread
FROM threads t
LEFT JOIN thread_reads r ON r.thread_id = t.id AND r.user_id = CAST(? AS int8)
WHERE t.id = ANY(CAST(? AS int4[])) AND t.last_activity_at > CAST(? AS int8)"""

/**
 * The count and the first unread message of ONE thread: the same inner read as [UNREAD_COUNTS_SQL], with the member's marker looked up
 * inside it. `min(id)` of the (at most [MAX_UNREAD_COUNT]) newest unread messages is the first of them; it is NULL when none is unread.
 * Arguments: the thread, the member, the thread again, the floor.
 */
internal const val THREAD_UNREAD_SQL = """
SELECT CAST(count(*) AS int4), min(recent.id)
FROM (SELECT m.id, m.created_at FROM messages m
      WHERE m.thread_id = CAST(? AS int4)
        AND m.id > COALESCE((SELECT r.last_read_message_id FROM thread_reads r
                             WHERE r.user_id = CAST(? AS int8) AND r.thread_id = CAST(? AS int4)), 0)
      ORDER BY m.id DESC LIMIT $MAX_UNREAD_COUNT) recent
WHERE recent.created_at > CAST(? AS int8)"""

/**
 * A member's marker moves FORWARD only: a conflict that would not raise `last_read_message_id` updates nothing. Arguments: the member,
 * the thread, the room, the message, the instant. The membership key refuses a non-member (23503): every caller holds the person's
 * membership row (the marker route's own update of it, or the room lock of a post).
 */
internal const val MOVE_MARKER_SQL = """
INSERT INTO thread_reads (user_id, thread_id, room_id, last_read_message_id, read_at)
VALUES (CAST(? AS int8), CAST(? AS int4), CAST(? AS int4), CAST(? AS int4), CAST(? AS int8))
ON CONFLICT (user_id, thread_id) DO UPDATE
    SET last_read_message_id = EXCLUDED.last_read_message_id, read_at = EXCLUDED.read_at
    WHERE thread_reads.last_read_message_id < EXCLUDED.last_read_message_id"""

/** What a member has not read of one thread: [count] (0 to [MAX_UNREAD_COUNT]) and the first of those messages, null for none. */
internal data class ThreadUnread(val count: Int, val firstMessageId: UInt?)

private val INTS = ArrayColumnType<Int, List<Int>>(IntegerColumnType())

/**
 * The unread counts of [threadIds] for member [userId] whose floor is [since], by thread (a thread with nothing unread may be absent).
 * Runs INSIDE the caller's transaction: one statement, no lock.
 */
internal suspend fun unreadCountsOf(userId: UInt, since: Long, threadIds: Collection<UInt>): Map<UInt, Int> {
    if (threadIds.isEmpty()) return emptyMap()
    return TransactionManager.current().exec(
        UNREAD_COUNTS_SQL,
        listOf(
            LongColumnType() to since,
            LongColumnType() to userId.toLong(),
            INTS to threadIds.map { it.toInt() },
            LongColumnType() to since,
        ),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Int::class.javaObjectType)!!.toUInt() to row.get(1, Int::class.javaObjectType)!! }
        ?.toList().orEmpty().filterNotNull().toMap()
}

/** What [userId], whose floor is [since], has not read of thread [threadId]. INSIDE the caller's transaction: one statement, no lock. */
internal suspend fun unreadOfThread(userId: UInt, threadId: UInt, since: Long): ThreadUnread {
    val int = IntegerColumnType()
    val rows = TransactionManager.current().exec(
        THREAD_UNREAD_SQL,
        listOf(int to threadId.toInt(), LongColumnType() to userId.toLong(), int to threadId.toInt(), LongColumnType() to since),
        explicitStatementType = StatementType.SELECT,
    ) { row -> ThreadUnread(row.get(0, Int::class.javaObjectType)!!, row.get(1, Int::class.javaObjectType)?.toUInt()) }
    return rows?.toList().orEmpty().filterNotNull().single()
}

/** Moves [userId]'s marker in thread [threadId] of room [roomId] up to [messageId] (never down), INSIDE the caller's transaction. */
internal suspend fun moveMarkerInTransaction(userId: UInt, threadId: UInt, roomId: UInt, messageId: UInt, at: Long) {
    val int = IntegerColumnType()
    TransactionManager.current().exec(
        MOVE_MARKER_SQL,
        listOf(
            LongColumnType() to userId.toLong(),
            int to threadId.toInt(),
            int to roomId.toInt(),
            int to messageId.toInt(),
            LongColumnType() to at,
        ),
    )
}
