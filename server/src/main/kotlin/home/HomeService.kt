package ch.nokillswit.home

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.threads.MAX_QUOTED_BODY
import ch.nokillswit.threads.MAX_UNREAD_COUNT
import ch.nokillswit.threads.MessageMentionService
import ch.nokillswit.threads.ReadLimiter
import ch.nokillswit.threads.cutQuotedBody
import ch.nokillswit.workflows.StatusColour
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

val HomeServiceKey = AttributeKey<HomeService>("HomeService")

/** Every Home section is deliberately small; filtering and ordering happen before this database limit. */
internal const val HOME_SECTION_LIMIT = 5

/**
 * The caller's personal Home read. It is a top-layer projection over the chat tables: one bounded transaction, four source-wide queries
 * and (only when a selected quote's cut body contains a token) the two existing batched mention reads. No query fans out by room.
 */
class HomeService internal constructor(
    private val database: R2dbcDatabase,
    private val mentions: MessageMentionService,
    private val limiter: ReadLimiter,
) {
    suspend fun read(userId: UInt): HomeResponse = limiter.run(database, userId, roomId = null, resource = "home") { budget ->
        budget.arm(this)
        val quotes = quoteRows(userId)
        val tokenIds = quotes.filter { it.body.text.contains("<@") }.map { it.messageId }
        budget.arm(this)
        val direct = mentions.mentionsOf(tokenIds)
        budget.arm(this)
        val groups = mentions.groups.groupsOf(tokenIds)
        budget.arm(this)
        val ownedThreads = threadRows(OWNED_THREADS_SQL, userId)
        budget.arm(this)
        val watchedThreads = threadRows(WATCHED_THREADS_SQL, userId)
        budget.arm(this)
        val ownedRooms = roomRows(userId)
        HomeResponse(
            quotes = quotes.map { row ->
                HomeQuote(
                    roomId = row.roomId,
                    roomName = row.roomName,
                    roomArchived = row.roomArchived,
                    threadNumber = row.threadNumber,
                    threadTitle = row.threadTitle,
                    messageId = row.messageId,
                    authorName = row.authorName,
                    body = row.body.text,
                    truncated = row.body.truncated,
                    mentions = direct[row.messageId].orEmpty(),
                    groupMentions = groups[row.messageId].orEmpty(),
                    createdAt = row.createdAt,
                    unread = row.unread,
                )
            },
            ownedThreads = ownedThreads,
            watchedThreads = watchedThreads,
            ownedRooms = ownedRooms,
        )
    }
}

private data class QuoteRow(
    val roomId: UInt,
    val roomName: String,
    val roomArchived: Boolean,
    val threadNumber: Int,
    val threadTitle: String,
    val messageId: UInt,
    val authorName: String,
    val body: ch.nokillswit.threads.CutBody,
    val createdAt: Long,
    val unread: Boolean,
)

/**
 * The quoting reply and both threads must still be live. A cross-room result is included only while the caller can read the source room
 * too (membership, or ADMIN access to a non-hidden room), even though none of the original's text travels. Destination membership is the
 * output scope, and the marker/floor expression is the existing unread definition for this particular message.
 */
internal const val HOME_QUOTES_SQL = """
SELECT r.id, r.name, r.archived_at IS NOT NULL, t.number, t.title,
       reply.id, author.name, substring(reply.body FROM 1 FOR ${MAX_QUOTED_BODY + 1}), reply.created_at,
       reply.id > COALESCE(tr.last_read_message_id, 0)
           AND reply.created_at > GREATEST(rm.read_from, rm.added_at) AS unread
FROM messages reply
JOIN messages quoted ON quoted.id = reply.quoted_message_id
JOIN threads source_t ON source_t.id = quoted.thread_id AND NOT source_t.marked_as_deleted
JOIN rooms source_r ON source_r.id = source_t.room_id AND NOT source_r.marked_as_deleted
LEFT JOIN room_members source_rm ON source_rm.room_id = source_r.id AND source_rm.user_id = CAST(? AS int8)
JOIN threads t ON t.id = reply.thread_id AND NOT t.marked_as_deleted
JOIN rooms r ON r.id = t.room_id AND NOT r.marked_as_deleted
JOIN room_members rm ON rm.room_id = r.id AND rm.user_id = CAST(? AS int8)
JOIN users author ON author.id = reply.author_id
LEFT JOIN thread_reads tr ON tr.thread_id = t.id AND tr.user_id = rm.user_id
WHERE NOT reply.marked_as_deleted
  AND quoted.author_id = rm.user_id AND reply.author_id <> rm.user_id
  AND (source_rm.user_id IS NOT NULL OR (
      source_r.access_type <> 'RESTRICTED_HIDDEN'
      AND EXISTS (SELECT 1 FROM users viewer WHERE viewer.id = rm.user_id AND viewer.role = 'ADMIN' AND NOT viewer.marked_as_deleted)
  ))
ORDER BY reply.created_at DESC, reply.id DESC
LIMIT $HOME_SECTION_LIMIT"""

private suspend fun quoteRows(userId: UInt): List<QuoteRow> = TransactionManager.current().exec(
    HOME_QUOTES_SQL,
    listOf(LongColumnType() to userId.toLong(), LongColumnType() to userId.toLong()),
    explicitStatementType = StatementType.SELECT,
) { row ->
    QuoteRow(
        roomId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
        roomName = row.get(1, String::class.java)!!,
        roomArchived = row.get(2, Boolean::class.javaObjectType)!!,
        threadNumber = row.get(3, Int::class.javaObjectType)!!,
        threadTitle = row.get(4, String::class.java)!!,
        messageId = row.get(5, Int::class.javaObjectType)!!.toUInt(),
        authorName = row.get(6, String::class.java)!!,
        body = cutQuotedBody(row.get(7, String::class.java)!!),
        createdAt = row.get(8, Long::class.javaObjectType)!!,
        unread = row.get(9, Boolean::class.javaObjectType)!!,
    )
}?.toList().orEmpty().filterNotNull()

private const val THREAD_COLUMNS = """r.id, r.name, r.archived_at IS NOT NULL, selected.number, selected.title,
       selected.status_key, selected.status_label, selected.status_colour, selected.closed_at IS NOT NULL,
       selected.unread_count, selected.last_activity_at"""

/** Owned means the thread owner is still a member; archived rooms are intentionally absent from this action-oriented section. */
internal const val OWNED_THREADS_SQL = """
WITH selected AS (
    SELECT t.id, t.room_id, t.number, t.title, t.status_key, s.label AS status_label, s.colour AS status_colour,
           t.closed_at, t.last_activity_at, GREATEST(rm.read_from, rm.added_at) AS unread_floor, rm.user_id
    FROM threads t
    JOIN rooms r ON r.id = t.room_id AND NOT r.marked_as_deleted AND r.archived_at IS NULL
    JOIN room_members rm ON rm.room_id = r.id AND rm.user_id = CAST(? AS int8)
    JOIN room_statuses s ON s.room_id = t.room_id AND s.key = t.status_key AND NOT s.marked_as_deleted
    WHERE t.owner_id = rm.user_id AND t.closed_at IS NULL AND NOT t.marked_as_deleted AND t.merged_into_thread_number IS NULL
    ORDER BY t.last_activity_at DESC, t.id DESC
    LIMIT $HOME_SECTION_LIMIT
), counted AS (
    SELECT selected.*,
           (SELECT CAST(count(*) AS int4)
            FROM (SELECT m.created_at FROM messages m
                  WHERE m.thread_id = selected.id
                    AND m.id > COALESCE((SELECT tr.last_read_message_id FROM thread_reads tr
                                         WHERE tr.user_id = selected.user_id AND tr.thread_id = selected.id), 0)
                  ORDER BY m.id DESC LIMIT $MAX_UNREAD_COUNT) recent
            WHERE recent.created_at > selected.unread_floor) AS unread_count
    FROM selected
)
SELECT $THREAD_COLUMNS
FROM counted selected JOIN rooms r ON r.id = selected.room_id
ORDER BY selected.last_activity_at DESC, selected.id DESC"""

/** Watched includes closed threads and archived rooms; unread threads lead, then the newest activity. */
internal const val WATCHED_THREADS_SQL = """
WITH candidates AS (
    SELECT t.id, t.room_id, t.number, t.title, t.status_key, s.label AS status_label, s.colour AS status_colour,
           t.closed_at, t.last_activity_at, w.user_id, GREATEST(rm.read_from, rm.added_at) AS unread_floor,
           t.last_activity_at > GREATEST(rm.read_from, rm.added_at) AND EXISTS (
               SELECT 1 FROM (SELECT m.created_at FROM messages m
                              WHERE m.thread_id = t.id
                                AND m.id > COALESCE((SELECT tr.last_read_message_id FROM thread_reads tr
                                                     WHERE tr.user_id = w.user_id AND tr.thread_id = t.id), 0)
                              ORDER BY m.id DESC LIMIT $MAX_UNREAD_COUNT) recent
               WHERE recent.created_at > GREATEST(rm.read_from, rm.added_at)
           ) AS unread
    FROM thread_watches w
    JOIN threads t ON t.id = w.thread_id AND t.room_id = w.room_id
    JOIN rooms r ON r.id = t.room_id AND NOT r.marked_as_deleted
    JOIN room_members rm ON rm.room_id = r.id AND rm.user_id = w.user_id
    JOIN room_statuses s ON s.room_id = t.room_id AND s.key = t.status_key AND NOT s.marked_as_deleted
    WHERE w.user_id = CAST(? AS int8) AND w.watching AND NOT t.marked_as_deleted AND t.merged_into_thread_number IS NULL
), selected AS (
    SELECT * FROM candidates
    ORDER BY unread DESC, last_activity_at DESC, id DESC
    LIMIT $HOME_SECTION_LIMIT
), counted AS (
    SELECT selected.*,
           (SELECT CAST(count(*) AS int4)
            FROM (SELECT m.created_at FROM messages m
                  WHERE m.thread_id = selected.id
                    AND m.id > COALESCE((SELECT tr.last_read_message_id FROM thread_reads tr
                                         WHERE tr.user_id = selected.user_id AND tr.thread_id = selected.id), 0)
                  ORDER BY m.id DESC LIMIT $MAX_UNREAD_COUNT) recent
            WHERE recent.created_at > selected.unread_floor) AS unread_count
    FROM selected
)
SELECT $THREAD_COLUMNS
FROM counted selected JOIN rooms r ON r.id = selected.room_id
ORDER BY (selected.unread_count > 0) DESC, selected.last_activity_at DESC, selected.id DESC"""

private suspend fun threadRows(sql: String, userId: UInt): List<HomeThread> {
    val arguments = listOf(LongColumnType() to userId.toLong())
    return TransactionManager.current().exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        HomeThread(
            roomId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
            roomName = row.get(1, String::class.java)!!,
            roomArchived = row.get(2, Boolean::class.javaObjectType)!!,
            number = row.get(3, Int::class.javaObjectType)!!,
            title = row.get(4, String::class.java)!!,
            status = row.get(5, String::class.java)!!,
            statusLabel = row.get(6, String::class.java)!!,
            statusColour = StatusColour.valueOf(row.get(7, String::class.java)!!),
            closed = row.get(8, Boolean::class.javaObjectType)!!,
            unreadCount = row.get(9, Int::class.javaObjectType)!!,
            lastActivityAt = row.get(10, Long::class.javaObjectType)!!,
        )
    }?.toList().orEmpty().filterNotNull()
}

/** Pending work is a boolean priority; then active rooms lead archived ones, and activity decides within those groups. */
internal const val OWNED_ROOMS_SQL = """
SELECT r.id, r.name, r.access_type, r.archived_at IS NOT NULL,
       r.last_activity_at > GREATEST(rm.seen_at, rm.added_at) AS unread,
       queue.pending,
       r.last_activity_at
FROM room_members rm
JOIN rooms r ON r.id = rm.room_id AND NOT r.marked_as_deleted
JOIN LATERAL (
    SELECT count(*) AS pending FROM room_join_requests request
    JOIN users applicant ON applicant.id = request.user_id AND NOT applicant.marked_as_deleted
    WHERE request.room_id = r.id AND request.status = 'PENDING'
) queue ON TRUE
WHERE rm.user_id = CAST(? AS int8) AND rm.role = 'OWNER'
ORDER BY (queue.pending > 0) DESC,
         (r.archived_at IS NULL) DESC, r.last_activity_at DESC, r.id DESC
LIMIT $HOME_SECTION_LIMIT"""

private suspend fun roomRows(userId: UInt): List<HomeRoom> = TransactionManager.current().exec(
    OWNED_ROOMS_SQL,
    listOf(LongColumnType() to userId.toLong()),
    explicitStatementType = StatementType.SELECT,
) { row ->
    HomeRoom(
        id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
        name = row.get(1, String::class.java)!!,
        accessType = RoomAccessType.valueOf(row.get(2, String::class.java)!!),
        archived = row.get(3, Boolean::class.javaObjectType)!!,
        unread = row.get(4, Boolean::class.javaObjectType)!!,
        pendingJoinRequests = row.get(5, Long::class.javaObjectType)!!,
        lastActivityAt = row.get(6, Long::class.javaObjectType)!!,
    )
}?.toList().orEmpty().filterNotNull()
