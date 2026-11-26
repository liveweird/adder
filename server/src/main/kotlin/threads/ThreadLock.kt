package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.infra.db.active
import ch.nokillswit.rooms.RoomGuard
import ch.nokillswit.rooms.roomArchived
import ch.nokillswit.rooms.withRoomWrite
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** The thread row as a write under the room lock sees it (the stored `fields` include the retired fields' hidden values). */
internal data class ThreadRow(
    val id: UInt,
    /** The per-room number (`#42`): the key the SPA and the live hints name a thread by. */
    val number: Int,
    val title: String,
    val statusKey: String,
    val ownerId: UInt,
    val fields: Map<String, JsonElement>,
    val revision: Long,
    val closedAt: Long?,
    val mergedIntoThreadNumber: Int? = null,
) {
    /** `closed_at` mirrors the status category (V14), so it IS the closed flag. */
    val closed: Boolean get() = closedAt != null
}

/** What a thread write's block is handed: the caller and standing as RE-READ under the room lock, and the thread row read under it. */
internal class ThreadWrite(val caller: CallerPrincipal, val standing: RoomStanding, val thread: ThreadRow)

/** The act guard of a thread write: it judges the FRESH caller against the FRESH standing and thread row (a `403`). */
internal typealias ThreadGuard = (CallerPrincipal, RoomStanding, ThreadRow) -> Unit

/**
 * Runs [block] as ONE thread write (`chat-model.md` "Guard order and locking"): [withRoomWrite] takes the room lock (the room row
 * `FOR NO KEY UPDATE`, which IS the thread lock) and re-runs [roomGuard] on the re-read caller and standing, then thread [number] of the
 * room is read under that lock (a missing one is the `404`), [actGuard] re-runs on the fresh row (`403`), and [block] gets the lot. The
 * state checks (archived, closed, revision) are the block's, in the documented order, because a status move is allowed on a closed
 * thread and the others are not. Nothing but the room row is locked: no thread-row or status-row lock exists.
 */
internal suspend fun <T> R2dbcDatabase.withThreadWrite(
    roomId: UInt,
    number: Int,
    caller: CallerPrincipal,
    roomGuard: RoomGuard,
    actGuard: ThreadGuard? = null,
    allowMerged: Boolean = false,
    block: suspend (ThreadWrite) -> T,
): T = withRoomWrite(roomId, caller, roomGuard) { fresh, standing ->
    val thread = threadRow(roomId, number)
    actGuard?.invoke(fresh, standing, thread)
    if (!allowMerged) thread.requireUnmerged()
    block(ThreadWrite(fresh, standing, thread))
}

internal suspend fun threadRow(roomId: UInt, number: Int): ThreadRow =
    ThreadService.Threads.selectAll().where { threadAddress(roomId, number) }.toList().singleOrNull()?.toThreadRow()
        ?: throw NotFoundException("Thread not found")

internal fun ThreadRow.requireUnmerged() {
    if (mergedIntoThreadNumber != null) throw threadMerged()
}

/**
 * The message row as a write under the room lock sees it: author, current body and its current-since stamp, plus the grammar version
 * its hashtags were indexed under (a deleted message is never read here).
 */
internal data class MessageRow(
    val authorId: UInt,
    val body: String,
    val tagsVersion: Short,
    val createdAt: Long,
    val editedAt: Long?,
)

/** What a message write's block is handed: the thread write (caller, standing and thread row, as under the lock) and the message row. */
internal class MessageWrite(val threadWrite: ThreadWrite, val message: MessageRow) {
    val caller: CallerPrincipal get() = threadWrite.caller
    val thread: ThreadRow get() = threadWrite.thread
}

/** The act guard of a message write: it judges the FRESH caller against the FRESH standing and message row (a `403`). */
internal typealias MessageGuard = (CallerPrincipal, RoomStanding, MessageRow) -> Unit

/**
 * Runs [block] as ONE message write: [withRoomWrite] takes the room lock and re-runs [roomGuard], then the message is read under it,
 * joined to its thread with the thread's room being [roomId] - a message of another room, an unknown one and a DELETED one are the same
 * `404` - [actGuard] re-runs on the fresh row (`403`), and [block] gets the lot. The state checks (archived, closed) are the block's.
 */
internal suspend fun <T> R2dbcDatabase.withMessageWrite(
    roomId: UInt,
    messageId: UInt,
    caller: CallerPrincipal,
    roomGuard: RoomGuard,
    actGuard: MessageGuard,
    allowMerged: Boolean = false,
    block: suspend (MessageWrite) -> T,
): T = withRoomWrite(roomId, caller, roomGuard) { fresh, standing ->
    val messages = MessageService.Messages
    val threads = ThreadService.Threads
    val row = messages.innerJoin(threads).selectAll()
        .where { (messages.id eq messageId) and (threads.roomId eq roomId) and messages.active() and threads.active() }
        .toList().singleOrNull() ?: throw NotFoundException("Message not found")
    val message = MessageRow(
        authorId = row[messages.authorId].value,
        body = row[messages.body],
        tagsVersion = row[messages.tagsVersion],
        createdAt = row[messages.createdAt],
        editedAt = row[messages.editedAt],
    )
    actGuard(fresh, standing, message)
    val thread = row.toThreadRow()
    if (!allowMerged) thread.requireUnmerged()
    block(MessageWrite(ThreadWrite(fresh, standing, thread), message))
}

/** An archived room refuses every content write (`409 room-archived`). */
internal fun ThreadWrite.requireRoomActive() {
    if (standing.archived) throw roomArchived()
}

/** A closed thread refuses the content writes (`409 thread-closed`); a status move, a reopen included, does not call this. */
internal fun ThreadWrite.requireThreadOpen() {
    if (thread.closed) throw threadClosed()
}

/**
 * Display names by user id, soft-deleted accounts included (their recorded name is still the name). One query, empty in, no query. Runs
 * INSIDE the caller's transaction (the read and the thread writes both use it).
 */
internal suspend fun userNames(ids: Collection<UInt>): Map<UInt, String> {
    if (ids.isEmpty()) return emptyMap()
    val users = UserService.Users
    return users.select(users.id, users.name).where { users.id inList ids.toSet() }
        .map { it[users.id].value to it[users.name] }.toList().toMap()
}

private fun ResultRow.toThreadRow(): ThreadRow {
    val threads = ThreadService.Threads
    return ThreadRow(
        id = this[threads.id].value,
        number = this[threads.number],
        title = this[threads.title],
        statusKey = this[threads.statusKey],
        ownerId = this[threads.ownerId].value,
        fields = (Json.parseToJsonElement(this[threads.fieldValues]) as JsonObject),
        revision = this[threads.revision],
        closedAt = this[threads.closedAt],
        mergedIntoThreadNumber = this[threads.mergedIntoThreadNumber],
    )
}
