package ch.nokillswit.rooms

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.infra.db.active
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
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
    // Joined to ACTIVE users: a soft-deleted user's membership row is inert and never reads as a role.
    val role = members.innerJoin(UserService.Users).select(members.role)
        .where { (members.roomId eq roomId) and (members.userId eq userId) and UserService.Users.active() }
        .toList()
        .singleOrNull()
        ?.let { RoomRole.valueOf(it[members.role]) }
    return RoomStanding(role = role, archived = row[rooms.archivedAt] != null)
}

/** A room write's guard: it judges the caller as RE-READ under the room lock ([withRoomWrite]) and their standing in the room. */
internal typealias RoomGuard = (CallerPrincipal, RoomStanding?) -> Unit

internal val managerGuard: RoomGuard = { caller, standing -> requireRoomManager(caller, standing) }

/** The guard of a self-leave: the reader standing only (a caller removed meanwhile is the uniform `404`). */
internal val readerGuard: RoomGuard = { caller, standing -> requireRoomReader(caller, standing) }

/** The guard of a speech write (start a thread, post a message): a MEMBER of the room; an ADMIN outside it is `403`, not `404`. */
internal val memberGuard: RoomGuard = { caller, standing -> requireRoomMember(caller, standing) }

/**
 * The caller as the database knows them NOW: their roles from the `users` row (an ADMIN demoted since the token was minted is a plain
 * user), or null when the account is soft-deleted. Must run inside a transaction.
 */
private suspend fun freshCaller(caller: CallerPrincipal): CallerPrincipal? {
    val users = UserService.Users
    val role = users.select(users.role).where { (users.id eq caller.userId) and users.active() }
        .toList().singleOrNull()?.let { UserRole.valueOf(it[users.role]) } ?: return null
    return caller.copy(roles = setOf(role))
}

/**
 * Runs [block] as ONE room write (`chat-model.md` "Locking and the room write", after Astrolabe's `withPlanWrite`): in one transaction it
 * (1) selects the ACTIVE room row `FOR NO KEY UPDATE` - the mode that conflicts with itself and with `UPDATE`/`DELETE` of the row but NOT
 * with the `FOR KEY SHARE` a child insert's FK check takes (`persistence.md` "Row locks vs. FK locks"), so writers of one room serialize
 * without stalling the inserts that reference it - (2) re-reads the CALLER's `users` row and membership and re-runs [guard] on them: a
 * caller whose account was deleted while the write waited is the uniform `404`, an ADMIN demoted meanwhile is judged as a plain user,
 * a caller demoted or removed from the room is refused (a missing room is a null standing, which the guards turn into the `404`): only
 * THIS verdict
 * authorises the write, and (3) runs [block] with that standing. Every room-scoped write goes through it (metadata, archive, members,
 * roles, workflow), which makes the last-OWNER count, the revision compare and the archived check race-free without locking member rows.
 * A [block] that throws rolls the whole transaction back.
 */
internal suspend fun <T> R2dbcDatabase.withRoomWrite(
    roomId: UInt,
    caller: CallerPrincipal,
    guard: RoomGuard,
    block: suspend (RoomStanding) -> T,
): T = suspendTransaction(this) {
    val standing = readStanding(roomId, caller.userId, lock = true)
    val fresh = freshCaller(caller) ?: throw NotFoundException("Room not found")
    guard(fresh, standing)
    block(standing ?: throw NotFoundException("Room not found"))
}
