package ch.nokillswit.notifications

import ch.nokillswit.infra.db.equalsAnyInt
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.threads.ReadLimiter
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.userNames
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
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

/** The ONE sortable whitelist of the notification list: `id` (time order) and `createdAt`. */
val NOTIFICATION_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The READ side of the notices of a member (V18 `notifications`, `chat-model.md` "Notifications (V18)"): the list, the "seen" writes and
 * retention. A notice is a POINTER, ids only; it is made INSIDE the chat's write by [NoticeWriter] (the `ThreadNotices` port). Rows are
 * hard-deleted: with their message ([NoticeWriter.messageRemoved], and an edit through [NoticeWriter.reviseMessage]), with the
 * membership they were made for
 * (the table's foreign key to `room_members`: a leaver's notices go, a rejoiner starts clean), and by retention ([purgeOlderThan]).
 *
 * The list is the caller's own rows in the ACTIVE rooms they are an ACTIVE member of (an ADMIN outside a room has none of its notices,
 * and a deleted room's are hidden), of a kind this build knows. Names are read for the page only: the room's from
 * [RoomService.memberRooms], the thread's number and title from `threads` and the actor's from `users` (soft-deleted accounts
 * included), all in the list's own transaction. No title, name or body is stored and none is ever logged.
 */
internal class NotificationService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    /** The per-actor digest budget (V24); the default is the config default, a test or the composition root passes its own. */
    private val emailBudget: NotificationEmailBudget = NotificationEmailBudget(DEFAULT_EMAIL_BUDGET_PER_ACTOR, ::nowMillis),
    /** The list's deadline and in-flight caps: the SAME limiter the thread and tag lists use (`threads.readTimeoutSeconds`). */
    private val readLimiter: ReadLimiter = ReadLimiter(),
) {
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

        /**
         * False for a mail-only notice (V27): the recipient's in-app switch for its kind was off when it was made; the bell never
         * shows it.
         */
        val inApp = bool("in_app").default(true)
    }

    /**
     * One page of the caller's notices, newest first by default: ONE predicate for the count and the rows in ONE transaction. The
     * `total` is CAPPED at [MAX_COUNTED_NOTICES] (the count reads at most that many plus one row), and a page starting at or past the cap
     * is empty without the rows being read (no OFFSET over a flooded recipient's history).
     * [seen] null is both; [types] (any-of; empty is every kind) narrows to those kinds. Only notices that show in the app are listed
     * (`in_app`, V27). The caller's active rooms come first as one array argument (the global search's shape), and no rooms is an
     * empty page.
     */
    suspend fun list(callerUserId: UInt, seen: Boolean?, types: Set<NotificationType>, paging: PageRequest): NotificationListResult =
        readLimiter.run(database, callerUserId, null, "notifications") { budget ->
            budget.arm(this)
            val memberRooms = rooms.memberRooms(callerUserId)
            if (memberRooms.isEmpty()) return@run NotificationListResult(emptyList(), 0)
            val n = Notifications
            var predicate: Op<Boolean> = (n.recipientId eq callerUserId) and
                n.roomId.equalsAnyInt(memberRooms.map { it.id.toInt() }) and
                n.inApp and
                (n.type inList (types.ifEmpty { NotificationType.entries.toSet() }).map { it.name })
            seen?.let { predicate = predicate and (if (it) n.seenAt.isNotNull() else n.seenAt.isNull()) }
            // Capped: the count reads at most MAX_COUNTED_NOTICES + 1 rows, so it never grows with a recipient's history.
            budget.arm(this)
            val counted = n.select(n.id).where { predicate }.limit(MAX_COUNTED_NOTICES + 1).count()
            val total = counted.coerceAtMost(MAX_COUNTED_NOTICES.toLong())
            // Nothing counted, or a page that starts at or past the cap (nothing the capped count admits): the page statement never runs.
            if (counted == 0L || (paging.page - 1).toLong() * paging.pageSize >= MAX_COUNTED_NOTICES) {
                return@run NotificationListResult(emptyList(), total)
            }
            budget.arm(this)
            val rows = n.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            if (rows.isEmpty()) return@run NotificationListResult(emptyList(), total)
            budget.arm(this)

            val threads = ThreadService.Threads
            val threadRows = threads.select(threads.id, threads.number, threads.title)
                .where { threads.id inList rows.map { it[n.threadId] }.toSet() }
                .map { it[threads.id].value to (it[threads.number] to it[threads.title]) }.toList().toMap()
            budget.arm(this)
            val actors = userNames(rows.map { it[n.actorId] })
            val roomById = memberRooms.associateBy { it.id }
            NotificationListResult(
                rows.map { row ->
                    val (number, title) = threadRows.getValue(row[n.threadId])
                    NotificationResponse(
                        id = row[n.id].value,
                        type = NotificationType.valueOf(row[n.type]),
                        seen = row[n.seenAt] != null,
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
     * Marks every unseen notice of the caller that SHOWS in the app seen at [at] (a mail-only notice, `in_app = false`, is not the
     * bell's to clear: the digest still owns it), in chunks of [SEEN_BATCH] rows each in its own short transaction (one
     * `UPDATE` over a flooded recipient's thousands of rows would hold its locks and its connection for as long); answers how many.
     * Notices made while it runs (a higher id than the newest at the start) stay unseen, as they would after one statement. Nobody
     * else's row is touched.
     */
    suspend fun markAllSeen(
        callerUserId: UInt,
        at: Long,
        chunk: Int = SEEN_BATCH,
        /** A TEST seam: runs between a chunk's select and its update (the gap a concurrent "seen" or delete falls into). */
        afterSelect: suspend (List<UInt>) -> Unit = {},
    ): Int {
        val n = Notifications
        val ceiling = suspendTransaction(database) {
            n.select(n.id).where { (n.recipientId eq callerUserId) and n.seenAt.isNull() and n.inApp }
                .orderBy(n.id, SortOrder.DESC).limit(1).map { it[n.id].value }.toList().singleOrNull()
        } ?: return 0
        var total = 0
        do {
            // The loop runs while the chunk SELECTED was full, not while it UPDATED a full chunk: a row seen or deleted by someone
            // else between the two statements makes the update short, and stopping there would leave the rest unseen.
            val (selected, moved) = suspendTransaction(database) {
                val ids = n.select(n.id)
                    .where { (n.recipientId eq callerUserId) and n.seenAt.isNull() and n.inApp and (n.id lessEq ceiling) }
                    .orderBy(n.id).limit(chunk).map { it[n.id].value }.toList()
                afterSelect(ids)
                ids.size to if (ids.isEmpty()) 0 else n.update({ (n.id inList ids) and n.seenAt.isNull() }) { it[seenAt] = at }
            }
            total += moved
        } while (selected == chunk)
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
        // The digest budget's days before the cutoff's (V24): a few tiny rows per actor and day, one short transaction.
        suspendTransaction(database) { emailBudget.purgeBefore(cutoffMillis) }
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
