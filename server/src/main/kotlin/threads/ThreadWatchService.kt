package ch.nokillswit.threads

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ThreadWatchServiceKey = AttributeKey<ThreadWatchService>("ThreadWatchService")

/** `PUT .../threads/{number}/watch`: whether the CALLER watches the thread. The one field is a strict boolean (else a `400`). */
@Serializable
data class WatchRequest(val watching: Boolean)

/** What a watch write changed, for the route's audit line: the thread's row id. */
data class WatchChange(val threadId: UInt)

/**
 * One member's watch of one thread, inserted unless it exists; answers the thread id only when a row was written. The row's room is
 * the THREAD's own (`t.room_id`, never an argument), so a watch can never name a room its thread is not in. The membership key refuses
 * a non-member (23503): every caller holds the person's membership row ([RoomMemberService.holdMembership], or the room lock of a thread
 * write that has just judged them an active member). An unknown thread inserts nothing. Arguments: the person, the instant, the thread.
 */
internal const val ADD_WATCH_SQL = """
INSERT INTO thread_watches (thread_id, user_id, room_id, created_at)
SELECT t.id, CAST(? AS int8), t.room_id, CAST(? AS int8) FROM threads t WHERE t.id = CAST(? AS int4)
ON CONFLICT DO NOTHING
RETURNING thread_id"""

/**
 * Which of the threads watch the person, ONE statement for a list page: the person and the room lead the index
 * `idx_thread_watches_user_room` (V26), the thread ids ride the last column, so the read touches the person's watches of the room alone
 * (an index-only probe) however many threads the room has. Constant text (the array keeps it so), the same plan custom and generic.
 * Arguments: the person, the room, the thread ids.
 */
internal const val WATCHING_AMONG_SQL = """
SELECT w.thread_id FROM thread_watches w
WHERE w.user_id = CAST(? AS int8) AND w.room_id = CAST(? AS int4) AND w.thread_id = ANY(CAST(? AS int4[]))"""

/**
 * A member's watches of threads (V26 `thread_watches`, `chat-model.md` "Watching (V26)"): the caller's OWN state, like the read markers
 * of [ThreadReadService]. The toggle takes NO room lock, emits no live hint and is the caller's alone; the one row lock it takes is the
 * caller's own membership row (`FOR KEY SHARE`, [RoomMemberService.holdMembership]), so a removal waits and the composite foreign key
 * cannot fail under the insert, and thirty people watching a busy thread never queue behind the room's writers. The auto-watches (the
 * creator of a thread and each new owner) are [addInTransaction] calls INSIDE those writes, under the room lock. A watch is hard-deleted
 * (it goes with its thread and with the membership, V26); a soft-deleted user's watches are inert and never counted. Cross-feature: the
 * membership row only through [RoomMemberService], `users` for the active filter (`persistence.md`).
 */
class ThreadWatchService internal constructor(
    private val database: R2dbcDatabase,
    private val members: RoomMemberService,
) {
    /** The watches (V26). There is no soft delete and no id of its own. */
    object ThreadWatches : Table("thread_watches") {
        val threadId = reference("thread_id", ThreadService.Threads)
        val userId = reference("user_id", UserService.Users)

        /** Copied from the thread's room (a thread never changes room); with [userId] it is the membership key. */
        val roomId = reference("room_id", RoomService.Rooms)
        val createdAt = long("created_at")
        override val primaryKey = PrimaryKey(threadId, userId)
    }

    /**
     * Sets whether [userId] watches thread [number] of room [roomId], in ONE transaction without the room lock: the caller's own
     * membership row is held first (a person who is no longer a member is the `404`, never a `500` from the foreign key), then the
     * thread (`404`), then the state it already has (null: a `204` with nothing written) and the write. A closed thread and an
     * archived room are allowed: a watch is the member's own. Answers what changed, for the route's audit line.
     */
    suspend fun setWatching(roomId: UInt, number: Int, userId: UInt, watching: Boolean): WatchChange? =
        suspendTransaction(database) {
            if (!members.holdMembership(roomId, userId)) throw NotFoundException("Room not found")
            val threads = ThreadService.Threads
            val threadId = threads.select(threads.id).where { (threads.roomId eq roomId) and (threads.number eq number) }
                .toList().singleOrNull()?.get(threads.id)?.value ?: throw NotFoundException("Thread not found")
            val changed = if (watching) {
                addInTransaction(threadId, userId, nowMillis())
            } else {
                ThreadWatches.deleteWhere { (ThreadWatches.threadId eq threadId) and (ThreadWatches.userId eq userId) } > 0
            }
            if (changed) WatchChange(threadId) else null
        }

    /**
     * Makes [userId], an active member of the thread's room, a watcher of thread [threadId] at [at]; false when they already were (a
     * concurrent double toggle is a no-op, never a duplicate key). The room is read from the thread, not passed. Runs INSIDE the
     * caller's transaction and takes no lock of its own.
     */
    suspend fun addInTransaction(threadId: UInt, userId: UInt, at: Long): Boolean {
        val rows = TransactionManager.current().exec(
            ADD_WATCH_SQL,
            listOf(
                LongColumnType() to userId.toLong(),
                LongColumnType() to at,
                IntegerColumnType() to threadId.toInt(),
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!! }
        return rows?.toList().orEmpty().isNotEmpty()
    }

    /** The ACTIVE users who watch thread [threadId], by id. INSIDE the caller's transaction: one statement, no lock. */
    suspend fun watcherIds(threadId: UInt): List<UInt> {
        val users = UserService.Users
        return ThreadWatches.innerJoin(users).select(ThreadWatches.userId)
            .where { (ThreadWatches.threadId eq threadId) and users.active() }
            .orderBy(ThreadWatches.userId)
            .map { it[ThreadWatches.userId].value }
            .toList()
    }

    /** How many ACTIVE users watch thread [threadId]. INSIDE the caller's transaction: one statement, no lock. */
    suspend fun count(threadId: UInt): Int {
        val users = UserService.Users
        return ThreadWatches.innerJoin(users).select(ThreadWatches.userId)
            .where { (ThreadWatches.threadId eq threadId) and users.active() }
            .count().toInt()
    }

    /**
     * Which of [threadIds] (threads of room [roomId]) [userId] watches: ONE statement ([WATCHING_AMONG_SQL]) for a whole list page,
     * empty for nobody (a caller who is no member watches nothing). INSIDE the caller's transaction, no lock.
     */
    suspend fun watchingAmong(roomId: UInt, userId: UInt, threadIds: Collection<UInt>): Set<UInt> {
        if (threadIds.isEmpty()) return emptySet()
        val rows = TransactionManager.current().exec(
            WATCHING_AMONG_SQL,
            listOf(LongColumnType() to userId.toLong(), IntegerColumnType() to roomId.toInt(), INTS to threadIds.map { it.toInt() }),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!!.toUInt() }
        return rows?.toList().orEmpty().filterNotNull().toSet()
    }
}

/**
 * `EXISTS (SELECT 1 FROM thread_watches WHERE user_id = <userId> AND room_id = <roomId> AND thread_id = <threadId>)`: [userId] watches the
 * thread. Correlated on [threadId], the outer list's `threads.id`; the person and the room lead `idx_thread_watches_user_room`, so
 * PostgreSQL probes that person's watches of the room alone (the shape of [threadMentionsAny]).
 */
internal fun threadWatchedBy(roomId: UInt, userId: UInt, threadId: Column<EntityID<UInt>>): Op<Boolean> {
    val watches = ThreadWatchService.ThreadWatches
    return exists(
        watches.select(watches.threadId).where {
            (watches.userId eq userId) and (watches.roomId eq roomId) and (watches.threadId eq threadId)
        },
    )
}
