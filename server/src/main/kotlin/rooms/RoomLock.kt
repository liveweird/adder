package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.infra.db.active
import ch.nokillswit.plugins.isLockNotAvailable
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.toList
import java.util.Collections
import java.util.WeakHashMap
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The default bound on a room write's wait for the room lock (`rooms.lockWaitMillis`, `ROOM_LOCK_WAIT_MILLIS`): see [withRoomWrite]. */
const val DEFAULT_ROOM_LOCK_WAIT_MILLIS = 3000

// The configured wait per database (one per application instance: `configureDatabase` records it, a test app may override it). A service
// holds the `R2dbcDatabase` it was built with, never the application, so the setting travels with the database; weak keys let a stopped
// test application's database go. A database nobody recorded (the harness's direct one) waits the default.
private val lockWaitMillisByDatabase: MutableMap<R2dbcDatabase, Int> = Collections.synchronizedMap(WeakHashMap())

/** Records how long a room write of this database waits for the room lock (boot-validated by the caller). */
fun R2dbcDatabase.useRoomLockWait(millis: Int) {
    lockWaitMillisByDatabase[this] = millis
}

private fun R2dbcDatabase.roomLockWaitMillis(): Int = lockWaitMillisByDatabase[this] ?: DEFAULT_ROOM_LOCK_WAIT_MILLIS

/**
 * The caller's [RoomStanding] in an ACTIVE room, or null when the room is missing or deleted. With [lock] the room row is taken
 * `FOR NO KEY UPDATE` first (the room write's lock); the member's role is read AFTER the lock is held, so a role change committed while
 * the caller waited is visible (every statement of a READ COMMITTED transaction takes its own snapshot). Must run inside a transaction.
 */
internal suspend fun readStanding(roomId: UInt, userId: UInt, lock: Boolean): RoomStanding? {
    val rooms = RoomService.Rooms
    val query = rooms.select(rooms.archivedAt, rooms.accessType).where { (rooms.id eq roomId) and rooms.active() }
    val row = (if (lock) query.forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate) else query).toList().singleOrNull() ?: return null
    val members = RoomService.RoomMembers
    // Joined to ACTIVE users: a soft-deleted user's membership row is inert and never reads as a role.
    val role = members.innerJoin(UserService.Users).select(members.role)
        .where { (members.roomId eq roomId) and (members.userId eq userId) and UserService.Users.active() }
        .toList()
        .singleOrNull()
        ?.let { RoomRole.valueOf(it[members.role]) }
    return RoomStanding(
        role = role,
        archived = row[rooms.archivedAt] != null,
        accessType = RoomAccessType.valueOf(row[rooms.accessType]),
    )
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
internal suspend fun freshCaller(caller: CallerPrincipal): CallerPrincipal? {
    val users = UserService.Users
    val role = users.select(users.role).where { (users.id eq caller.userId) and users.active() }
        .toList().singleOrNull()?.let { UserRole.valueOf(it[users.role]) } ?: return null
    return caller.copy(roles = setOf(role))
}

/**
 * Locks every distinct active room in ascending id order, then re-reads the caller and each standing. The quote write is the first
 * cross-room consumer. One deterministic order prevents opposite-direction quotes from deadlocking, while the shared bounded lock wait
 * keeps the connection pool protected like [withRoomWrite]. Guards are applied by the caller because source and destination have
 * different authorization rules.
 */
internal suspend fun <T> R2dbcDatabase.withRoomWrites(
    roomIds: Collection<UInt>,
    caller: CallerPrincipal,
    block: suspend (fresh: CallerPrincipal, standings: Map<UInt, RoomStanding?>) -> T,
): T = suspendTransaction(this) {
    val ordered = roomIds.distinct().sorted()
    exec("SET LOCAL lock_timeout = ${roomLockWaitMillis()}")
    val standings = linkedMapOf<UInt, RoomStanding?>()
    var locking = ordered.firstOrNull()
    try {
        for (roomId in ordered) {
            locking = roomId
            standings[roomId] = readStanding(roomId, caller.userId, lock = true)
        }
    } catch (failure: Exception) {
        if (!failure.isLockNotAvailable()) throw failure
        audit("room.busy", "byUserId" to caller.userId.toLong(), "roomId" to locking?.toLong())
        throw roomBusy()
    }
    exec("SET LOCAL lock_timeout TO DEFAULT")
    val fresh = freshCaller(caller) ?: throw NotFoundException("Room not found")
    block(fresh, standings)
}

/**
 * Runs [block] as ONE room write (`chat-model.md` "Locking and the room write", after Astrolabe's `withPlanWrite`): in one transaction it
 * (1) selects the ACTIVE room row `FOR NO KEY UPDATE` - the mode that conflicts with itself and with `UPDATE`/`DELETE` of the row but NOT
 * with the `FOR KEY SHARE` a child insert's FK check takes (`persistence.md` "Row locks vs. FK locks"), so writers of one room serialize
 * without stalling the inserts that reference it - (2) re-reads the CALLER's `users` row and membership and re-runs [guard] on them: a
 * caller whose account was deleted while the write waited is the uniform `404`, an ADMIN demoted meanwhile is judged as a plain user,
 * a caller demoted or removed from the room is refused (a missing room is a null standing, which the guards turn into the `404`): only
 * THIS verdict
 * authorises the write, and (3) runs [block] with the re-read caller and that standing (a thread write's own guard judges the fresh
 * caller against the fresh thread row, so it needs both). Every room-scoped write goes through it (metadata, archive, members,
 * roles, workflow), which makes the last-OWNER count, the revision compare and the archived check race-free without locking member rows.
 * A [block] that throws rolls the whole transaction back.
 *
 * **The wait for the lock is bounded** (`persistence.md` "Rooms (V13)", "The room lock"): a request queued behind a busy room holds one
 * of the pool's connections while it waits, so unbounded waiters could take the whole pool (20 connections, a 10 s acquire timeout) and
 * stall every other request. The lock statement runs under `SET LOCAL lock_timeout` ([roomLockWaitMillis], 3 s by default), and a wait
 * that outruns it (SQLSTATE `55P03`) is the typed `429 room-busy` with `Retry-After: 1`, audited `room.busy`. `SET LOCAL` is
 * transaction-scoped, so it ends with the commit or rollback and can leak nothing to the next borrower of the pooled connection
 * (`lock_timeout` is not `statement_timeout`: the "only `queryTimeout`" rule of `StatementTimeoutCache.kt` is untouched). The setting is
 * put back to the session default as soon as the lock is held, so no other lock wait of the write (a unique-index wait, an FK key
 * share) ever runs under it and none can raise `55P03` outside this one place.
 */
internal suspend fun <T> R2dbcDatabase.withRoomWrite(
    roomId: UInt,
    caller: CallerPrincipal,
    guard: RoomGuard,
    block: suspend (fresh: CallerPrincipal, standing: RoomStanding) -> T,
): T = suspendTransaction(this) {
    exec("SET LOCAL lock_timeout = ${roomLockWaitMillis()}")
    val standing = try {
        readStanding(roomId, caller.userId, lock = true)
    } catch (failure: Exception) {
        if (!failure.isLockNotAvailable()) throw failure
        audit("room.busy", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
        throw roomBusy()
    }
    exec("SET LOCAL lock_timeout TO DEFAULT")
    val fresh = freshCaller(caller) ?: throw NotFoundException("Room not found")
    guard(fresh, standing)
    block(fresh, standing ?: throw NotFoundException("Room not found"))
}
