package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.memberGuard
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val PinServiceKey = AttributeKey<PinService>("PinService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to PinService.RoomPins.id,
    "pinnedAt" to PinService.RoomPins.pinnedAt,
)

/** The ONE sortable whitelist of the pin list: `id` and `pinnedAt`. */
val PIN_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The pins of a room (V21): the `room_pins` table, the list and the two single-value sets (a thread, a message), each ONE write under
 * the room lock ([withThreadWrite], [withMessageWrite]) by any MEMBER (`chat-model.md` "Pins"). A pin is a pointer, hard-deleted when
 * unpinned or when its message is deleted ([PinCleanup]); it is not activity (no rail bump, no history event). Reads use `users` for
 * display names only (soft-deleted accounts included) and messages through [MessageService].
 */
class PinService internal constructor(
    private val database: R2dbcDatabase,
    private val messages: MessageService,
) {
    object RoomPins : UIntIdTable("room_pins") {
        val roomId = reference("room_id", RoomService.Rooms)
        val threadId = reference("thread_id", ThreadService.Threads)

        /** Null for a pin of the whole thread. */
        val messageId = reference("message_id", MessageService.Messages).nullable()
        val pinnedBy = reference("pinned_by", UserService.Users)
        val pinnedAt = long("pinned_at")
    }

    /**
     * One page of room [roomId]'s pins as [viewerId] sees them (a pinned message carries the viewer's own reactions): the count, the
     * rows and the pinned messages (one query) share one predicate and one transaction,
     * but they are separate READ COMMITTED statements, not one snapshot. A message deleted between them leaves its pin out of the page
     * (the row is skipped) while `total` still counted it, so `total` can be one higher than the pins that exist for that one read.
     */
    suspend fun list(roomId: UInt, paging: PageRequest, viewerId: UInt): RoomPinListResult = suspendTransaction(database) {
        val threads = ThreadService.Threads
        val inRoom = RoomPins.roomId eq roomId
        val total = RoomPins.selectAll().where { inRoom }.count()
        val rows = RoomPins.innerJoin(threads)
            .select(
                RoomPins.id, RoomPins.messageId, RoomPins.pinnedBy, RoomPins.pinnedAt,
                threads.number, threads.title, threads.statusKey, threads.closedAt,
            )
            .where { inRoom }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val pinned = messages.readInTransaction(roomId, rows.mapNotNull { it[RoomPins.messageId]?.value }, viewerId)
        val names = userNames(rows.map { it[RoomPins.pinnedBy].value })
        val items = rows.mapNotNull { row ->
            val messageId = row[RoomPins.messageId]?.value
            val message = messageId?.let { pinned[it] }
            // A message pin whose message has just been deleted is gone with the delete's own commit: leave it out of this read.
            if (messageId != null && message == null) return@mapNotNull null
            RoomPinResponse(
                id = row[RoomPins.id].value,
                kind = if (messageId == null) PinKind.THREAD else PinKind.MESSAGE,
                threadNumber = row[threads.number],
                threadTitle = row[threads.title],
                threadStatus = row[threads.statusKey],
                threadClosed = row[threads.closedAt] != null,
                message = message,
                pinnedById = row[RoomPins.pinnedBy].value,
                pinnedByName = names.getValue(row[RoomPins.pinnedBy].value),
                pinnedAt = row[RoomPins.pinnedAt],
            )
        }
        RoomPinListResult(items, total)
    }

    /**
     * Pins or unpins thread [number] in ONE thread write (the MEMBER guard on the re-read caller): an archived room is `409
     * room-archived`; the state the thread already has is null (a `204` with no write, no hint); the 51st pin is `409 pin-limit`. A
     * closed thread is NOT refused: a pin is the room's. Answers what changed, for the route's audit line.
     */
    suspend fun setThreadPin(roomId: UInt, number: Int, caller: CallerPrincipal, pinned: Boolean): PinChange? =
        database.withThreadWrite(roomId, number, caller, memberGuard) { write ->
            write.requireRoomActive()
            if (!change(roomId, write.thread.id, null, write.caller.userId, pinned)) return@withThreadWrite null
            notifyLive(LiveHint(LiveKind.ROOM_PIN_CHANGED, roomId, threadNumber = number))
            PinChange(write.thread.id, null, pinned)
        }

    /**
     * Pins or unpins message [messageId] (404 unless it is a live message of the room; the same rules as [setThreadPin]). Any member
     * pins any message, so there is no act guard beyond the member guard.
     */
    suspend fun setMessagePin(roomId: UInt, messageId: UInt, caller: CallerPrincipal, pinned: Boolean): PinChange? =
        database.withMessageWrite(roomId, messageId, caller, memberGuard, actGuard = { _, _, _ -> }) { write ->
            write.threadWrite.requireRoomActive()
            if (!change(roomId, write.thread.id, messageId, write.caller.userId, pinned)) return@withMessageWrite null
            notifyLive(LiveHint(LiveKind.ROOM_PIN_CHANGED, roomId, threadNumber = write.thread.number, messageId = messageId))
            PinChange(write.thread.id, messageId, pinned)
        }

    /** Writes the pin of a thread ([messageId] null) or a message, under the room lock; false when it is already in the requested state. */
    private suspend fun change(roomId: UInt, threadId: UInt, messageId: UInt?, by: UInt, pinned: Boolean): Boolean {
        val target = if (messageId == null) {
            (RoomPins.threadId eq threadId) and RoomPins.messageId.isNull()
        } else {
            RoomPins.messageId eq messageId
        }
        val exists = RoomPins.select(RoomPins.id).where { target }.toList().isNotEmpty()
        if (exists == pinned) return false
        if (!pinned) {
            RoomPins.deleteWhere { target }
            return true
        }
        if (RoomPins.selectAll().where { RoomPins.roomId eq roomId }.count() >= MAX_ROOM_PINS) throw pinLimit()
        RoomPins.insert {
            it[RoomPins.roomId] = roomId
            it[RoomPins.threadId] = threadId
            it[RoomPins.messageId] = messageId
            it[pinnedBy] = by
            it[pinnedAt] = nowMillis()
        }
        return true
    }
}

/**
 * A deleted message loses its pin in the delete's own transaction, whoever deleted it ([MessageFollower]): the pin is a pointer, and a
 * scrubbed message must not stay listed in the room's pinned panel.
 */
internal class PinCleanup : MessageFollower {
    override suspend fun deleted(ref: MessageRef) {
        PinService.RoomPins.deleteWhere { PinService.RoomPins.messageId eq ref.messageId }
    }
}
