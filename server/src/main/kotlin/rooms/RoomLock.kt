package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The caller's [RoomStanding] in an ACTIVE room, or null when the room is missing or deleted. With [lock] the room row is taken
 * `FOR NO KEY UPDATE` first (the room write's lock); the member's role is read AFTER the lock is held, so a role change committed while
 * the caller waited is visible (every statement of a READ COMMITTED transaction takes its own snapshot). Must run inside a transaction.
 */
internal suspend fun readStanding(roomId: UInt, userId: UInt, lock: Boolean): RoomStanding? {
    val rooms = RoomService.Rooms
    val query = rooms.select(rooms.archivedAt).where { (rooms.id eq roomId) and rooms.active() }
    val row = (if (lock) query.forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate) else query).toList().singleOrNull() ?: return null
    val members = RoomService.RoomMembers
    val role = members.select(members.role)
        .where { (members.roomId eq roomId) and (members.userId eq userId) }
        .toList()
        .singleOrNull()
        ?.let { RoomRole.valueOf(it[members.role]) }
    return RoomStanding(role = role, archived = row[rooms.archivedAt] != null)
}

/**
 * Runs [block] as ONE room write (`chat-model.md` "Locking and the room write", after Astrolabe's `withPlanWrite`): in one transaction it
 * (1) selects the ACTIVE room row `FOR NO KEY UPDATE` - the mode that conflicts with itself and with `UPDATE`/`DELETE` of the row but NOT
 * with the `FOR KEY SHARE` a child insert's FK check takes (`persistence.md` "Row locks vs. FK locks"), so writers of one room serialize
 * without stalling the inserts that reference it - (2) re-reads the caller's membership and re-runs [guard] on that locked standing (a
 * missing room is a null standing, which the guards turn into the `404`): only THIS verdict authorises the write, so a caller demoted or
 * removed while the write waited for the lock is refused, and (3) runs [block] with that standing. Every room-scoped write goes through it
 * (metadata, archive, members, roles, workflow), which makes the last-OWNER count, the revision compare and the archived check race-free
 * without locking member rows. A [block] that throws rolls the whole transaction back.
 */
internal suspend fun <T> R2dbcDatabase.withRoomWrite(
    roomId: UInt,
    callerUserId: UInt,
    guard: (RoomStanding?) -> Unit,
    block: suspend (RoomStanding) -> T,
): T = suspendTransaction(this) {
    val standing = readStanding(roomId, callerUserId, lock = true)
    guard(standing)
    block(standing ?: throw NotFoundException("Room not found"))
}
