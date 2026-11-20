package ch.nokillswit.notifications

import ch.nokillswit.infra.db.equalsAnyInt
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.ThreadNotices
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.userNames
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

internal val NotificationServiceKey = AttributeKey<NotificationService>("NotificationService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to NotificationService.Notifications.id,
    "createdAt" to NotificationService.Notifications.createdAt,
)

/**
 * The most notices a count reads: a list's `total` is capped here, so a recipient's count never grows with their history
 * (`security.md` "Notices").
 */
const val MAX_COUNTED_NOTICES = 1000

/** Rows one "mark everything seen" chunk updates, each chunk its own short transaction. */
internal const val SEEN_BATCH = 1000

/** Rows one retention batch deletes, each batch its own short transaction (the `MessageTagService.reindexStale` shape). */
internal const val PURGE_BATCH = 1000

/**
 * ONE statement for every recipient of a write, constant text: `unnest` of the recipient ids, `ON CONFLICT DO NOTHING` on
 * `uq_notifications_message`, the people a row was really made for come back. Every parameter is cast, so a null message id (a hand-over)
 * has a type.
 */
private const val INSERT_NOTICES = """
INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at)
SELECT u.r, CAST(? AS int4), CAST(? AS varchar), CAST(? AS int8), CAST(? AS int4), CAST(? AS int4), CAST(? AS int8)
FROM unnest(CAST(? AS int8[])) AS u(r)
ON CONFLICT DO NOTHING
RETURNING recipient_id"""

/**
 * The unseen notices of one person in one thread up to a message become seen (the read marker's write, run without the room lock): the
 * person and the thread are the two leading keys of the partial index `idx_notifications_unseen_thread` (V19), so the statement touches
 * only that person's unseen rows of that thread however many notices they hold. A hand-over has no message and is seen with the thread.
 * Arguments: the instant, the person, the thread, the message.
 */
internal const val MARK_THREAD_SEEN_SQL = """
UPDATE notifications SET seen_at = CAST(? AS int8)
WHERE recipient_id = CAST(? AS int8) AND thread_id = CAST(? AS int4) AND seen_at IS NULL
  AND (message_id IS NULL OR message_id <= CAST(? AS int4))"""

/** The ONE sortable whitelist of the notification list: `id` (time order) and `createdAt`. */
val NOTIFICATION_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The notices of a member (V18 `notifications`, `chat-model.md` "Notifications (V18)") and the chat's [ThreadNotices] port. A notice is
 * a POINTER, ids only: it is made INSIDE the write that caused it (a post, an edit or a hand-over, under the room lock), together with
 * its `notification.created` live hint, so a rolled-back write announces nothing. Rows are hard-deleted: with their message
 * ([messageRemoved], [mentionsDropped]), with the membership they were made for (the table's foreign key to `room_members`: a leaver's
 * notices go, a rejoiner starts clean), and by retention ([purgeOlderThan]).
 *
 * The list is the caller's own rows in the ACTIVE rooms they are an ACTIVE member of (an ADMIN outside a room has none of its notices,
 * and a deleted room's are hidden), of a kind this build knows. Names are read for the page only: the room's from
 * [RoomService.memberRooms], the thread's number and title from `threads` and the actor's from `users` (soft-deleted accounts
 * included), all in the list's own transaction. No title, name or body is stored and none is ever logged.
 */
internal class NotificationService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
) : ThreadNotices {
    /** The notices. Ids only: there is no text column and no soft delete. */
    object Notifications : UIntIdTable("notifications") {
        val recipientId = uinteger("recipient_id")
        val roomId = uinteger("room_id")
        val type = varchar("notification_type", length = 40)
        val actorId = uinteger("actor_id")
        val threadId = uinteger("thread_id")

        /** Null for a hand-over of a thread (a thread-level notice). */
        val messageId = uinteger("message_id").nullable()
        val createdAt = long("created_at")

        /** Null while unseen. */
        val seenAt = long("seen_at").nullable()

        /** Null until the email digest claims the notice (V22). */
        val emailedAt = long("emailed_at").nullable()
    }

    override suspend fun mentioned(at: MessageRef, byUserId: UInt, userIds: Collection<UInt>): List<UInt> {
        val recipients = userIds.filter { it != byUserId }.distinct()
        if (recipients.isEmpty()) return emptyList()
        val made = insertNotices(at.roomId, at.threadId, at.messageId, NotificationType.MENTIONED, byUserId, recipients)
        announce(made, at.roomId, at.threadNumber, at.messageId)
        return made
    }

    override suspend fun mentionsDropped(messageId: UInt, userIds: Collection<UInt>) {
        if (userIds.isEmpty()) return
        Notifications.deleteWhere {
            (Notifications.messageId eq messageId) and (Notifications.recipientId inList userIds) and
                (Notifications.type eq NotificationType.MENTIONED.name)
        }
    }

    override suspend fun messageRemoved(messageId: UInt) {
        Notifications.deleteWhere { Notifications.messageId eq messageId }
    }

    override suspend fun ownerAssigned(roomId: UInt, threadId: UInt, threadNumber: Int, byUserId: UInt, toUserId: UInt): Boolean {
        if (toUserId == byUserId) return false
        val made = insertNotices(roomId, threadId, null, NotificationType.THREAD_ASSIGNED, byUserId, listOf(toUserId))
        announce(made, roomId, threadNumber, null)
        return made.isNotEmpty()
    }

    override suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long) {
        val int = IntegerColumnType()
        val long = LongColumnType()
        TransactionManager.current().exec(
            MARK_THREAD_SEEN_SQL,
            listOf(long to at, long to userId.toLong(), int to threadId.toInt(), int to upToMessageId.toInt()),
        )
    }

    /**
     * ONE multi-row `INSERT ... SELECT FROM unnest(...) ON CONFLICT DO NOTHING RETURNING recipient_id` ([INSERT_NOTICES]) under the
     * caller's room lock: answers the people a row was made for (a notice of the same message and kind that already exists is not made
     * twice). `created_at` is the write's own instant ([nowMillis]).
     */
    private suspend fun insertNotices(
        roomId: UInt,
        threadId: UInt,
        messageId: UInt?,
        type: NotificationType,
        actorId: UInt,
        recipients: List<UInt>,
    ): List<UInt> {
        val arguments = listOf(
            IntegerColumnType() to roomId.toInt(),
            TextColumnType() to type.name,
            LongColumnType() to actorId.toLong(),
            IntegerColumnType() to threadId.toInt(),
            IntegerColumnType() to messageId?.toInt(),
            LongColumnType() to nowMillis(),
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients.map { it.toLong() },
        )
        return TransactionManager.current()
            .exec(INSERT_NOTICES, arguments, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)!!.toUInt()
            }?.toList().orEmpty().filterNotNull()
    }

    /** ONE `notification.created` hint to [recipients] (nothing for nobody), inside the write's own transaction. */
    private suspend fun announce(recipients: List<UInt>, roomId: UInt, threadNumber: Int, messageId: UInt?) {
        if (recipients.isEmpty()) return
        notifyLive(LiveHint(LiveKind.NOTIFICATION_CREATED, roomId, threadNumber, messageId, targetUserIds = recipients))
    }

    /**
     * One page of the caller's notices, newest first by default: ONE predicate for the count and the rows in ONE transaction. The
     * `total` is CAPPED at [MAX_COUNTED_NOTICES] (the count reads at most that many plus one row), and a page starting at or past the cap
     * is empty without the rows being read (no OFFSET over a flooded recipient's history).
     * [wasSeen] null is both. The caller's active rooms come first as one array argument (the global search's shape), and no rooms is an
     * empty page.
     */
    suspend fun list(callerUserId: UInt, wasSeen: Boolean?, paging: PageRequest): NotificationListResult =
        suspendTransaction(database) {
            val memberRooms = rooms.memberRooms(callerUserId)
            if (memberRooms.isEmpty()) return@suspendTransaction NotificationListResult(emptyList(), 0)
            val n = Notifications
            var predicate: Op<Boolean> = (n.recipientId eq callerUserId) and
                n.roomId.equalsAnyInt(memberRooms.map { it.id.toInt() }) and
                (n.type inList NotificationType.entries.map { it.name })
            wasSeen?.let { predicate = predicate and (if (it) n.seenAt.isNotNull() else n.seenAt.isNull()) }
            // Capped: the count reads at most MAX_COUNTED_NOTICES + 1 rows, so it never grows with a recipient's history.
            val counted = n.select(n.id).where { predicate }.limit(MAX_COUNTED_NOTICES + 1).count()
            val total = counted.coerceAtMost(MAX_COUNTED_NOTICES.toLong())
            // A page that starts at or past the cap holds nothing the capped count admits: the OFFSET never runs.
            if ((paging.page - 1).toLong() * paging.pageSize >= MAX_COUNTED_NOTICES) {
                return@suspendTransaction NotificationListResult(emptyList(), total)
            }
            val rows = n.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            if (rows.isEmpty()) return@suspendTransaction NotificationListResult(emptyList(), total)

            val threads = ThreadService.Threads
            val threadRows = threads.select(threads.id, threads.number, threads.title)
                .where { threads.id inList rows.map { it[n.threadId] }.toSet() }
                .map { it[threads.id].value to (it[threads.number] to it[threads.title]) }.toList().toMap()
            val actors = userNames(rows.map { it[n.actorId] })
            val roomById = memberRooms.associateBy { it.id }
            NotificationListResult(
                rows.map { row ->
                    val (number, title) = threadRows.getValue(row[n.threadId])
                    NotificationResponse(
                        id = row[n.id].value,
                        type = NotificationType.valueOf(row[n.type]),
                        wasSeen = row[n.seenAt] != null,
                        createdAt = row[n.createdAt],
                        actorId = row[n.actorId],
                        actorName = actors.getValue(row[n.actorId]),
                        roomId = row[n.roomId],
                        roomName = roomById.getValue(row[n.roomId]).name,
                        threadNumber = number,
                        threadTitle = title,
                        messageId = row[n.messageId],
                    )
                },
                total,
            )
        }

    /**
     * Marks the caller's notice [id] seen at [at]. False only when the caller has no such notice (it is someone else's or does not exist:
     * the same answer, so an id probes nothing); a notice already seen is true and unchanged.
     */
    suspend fun markSeen(callerUserId: UInt, id: UInt, at: Long): Boolean = suspendTransaction(database) {
        val n = Notifications
        val moved = n.update({ (n.id eq id) and (n.recipientId eq callerUserId) and n.seenAt.isNull() }) { it[seenAt] = at }
        moved > 0 || n.select(n.id).where { (n.id eq id) and (n.recipientId eq callerUserId) }.toList().isNotEmpty()
    }

    /**
     * Marks every unseen notice of the caller seen at [at], in chunks of [SEEN_BATCH] rows each in its own short transaction (one
     * `UPDATE` over a flooded recipient's thousands of rows would hold its locks and its connection for as long); answers how many.
     * Notices made while it runs (a higher id than the newest at the start) stay unseen, as they would after one statement. Nobody
     * else's row is touched.
     */
    suspend fun markAllSeen(callerUserId: UInt, at: Long, chunk: Int = SEEN_BATCH): Int {
        val n = Notifications
        val ceiling = suspendTransaction(database) {
            n.select(n.id).where { (n.recipientId eq callerUserId) and n.seenAt.isNull() }
                .orderBy(n.id, SortOrder.DESC).limit(1).map { it[n.id].value }.toList().singleOrNull()
        } ?: return 0
        var total = 0
        do {
            val moved = suspendTransaction(database) {
                val ids = n.select(n.id)
                    .where { (n.recipientId eq callerUserId) and n.seenAt.isNull() and (n.id lessEq ceiling) }
                    .limit(chunk).map { it[n.id].value }.toList()
                if (ids.isEmpty()) 0 else n.update({ (n.id inList ids) and n.seenAt.isNull() }) { it[seenAt] = at }
            }
            total += moved
        } while (moved == chunk)
        return total
    }

    /**
     * Deletes the notices created before [cutoffMillis] (retention), in batches of [batchSize] rows, each its own short transaction
     * (`ORDER BY created_at LIMIT n FOR UPDATE SKIP LOCKED`, the `MessageTagService.reindexStale` shape): never one unbounded `DELETE`
     * that would hold its locks while a member removal or a message delete (under the room lock) waits behind it, and a row a chat write
     * holds is left for the next pass. Answers how many went; [onBatch] sees each batch's size (a test seam).
     *
     * The batch is found through `idx_notifications_created` (V18): the oldest rows are read straight off the index, so a pass costs its
     * deletions and never a scan of the table. (An id bound, found by a binary search over the primary key on the strength of "ids follow
     * time", was dropped: it silently skips an old row that sits behind a younger one, and an index that every insert maintains is cheap
     * beside that.) Outside any chat write; the sweep runs it.
     */
    suspend fun purgeOlderThan(cutoffMillis: Long, batchSize: Int = PURGE_BATCH, onBatch: (Int) -> Unit = {}): Int {
        var total = 0
        do {
            val deleted = purgeBatch(cutoffMillis, batchSize)
            total += deleted
            if (deleted > 0) onBatch(deleted)
        } while (deleted == batchSize)
        return total
    }

    private suspend fun purgeBatch(cutoffMillis: Long, size: Int): Int = suspendTransaction(database) {
        val n = Notifications
        val ids = n.select(n.id).where { n.createdAt less cutoffMillis }
            .orderBy(n.createdAt)
            .limit(size)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[n.id].value }.toList()
        if (ids.isEmpty()) 0 else n.deleteWhere { n.id inList ids }
    }
}
