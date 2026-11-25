package ch.nokillswit.threads

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.threads.ThreadMuteService.ThreadMutes
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal val ThreadMuteServiceKey = AttributeKey<ThreadMuteService>("ThreadMuteService")

@Serializable
data class ThreadMuteRequest(val muted: Boolean)

/** A member's own notification setting. Only the membership lock is needed, even in an archived room or closed thread. */
internal class ThreadMuteService(private val database: R2dbcDatabase, private val members: RoomMemberService) {
    object ThreadMutes : Table("thread_mutes") {
        val threadId = reference("thread_id", ThreadService.Threads)
        val userId = reference("user_id", UserService.Users)
        val roomId = reference("room_id", RoomService.Rooms)
        override val primaryKey = PrimaryKey(threadId, userId)
    }

    suspend fun setMuted(roomId: UInt, number: Int, userId: UInt, muted: Boolean): UInt? = suspendTransaction(database) {
        if (!members.holdMembership(roomId, userId)) throw NotFoundException("Room not found")
        val threads = ThreadService.Threads
        val thread = threads.select(threads.id).where { (threads.roomId eq roomId) and (threads.number eq number) }
            .toList().singleOrNull()?.get(threads.id)?.value ?: throw NotFoundException("Thread not found")
        val changed = if (muted) {
            ThreadMutes.insertIgnore {
                it[threadId] = thread
                it[ThreadMutes.userId] = userId
                it[ThreadMutes.roomId] = roomId
            }.insertedCount
        } else {
            ThreadMutes.deleteWhere { (threadId eq thread) and (ThreadMutes.userId eq userId) }
        }
        thread.takeIf { changed > 0 }
    }
}

internal suspend fun mutedInThread(threadId: UInt): Set<UInt> = ThreadMutes.select(ThreadMutes.userId)
    .where { ThreadMutes.threadId eq threadId }.map { it[ThreadMutes.userId].value }.toList().toSet()

internal suspend fun isThreadMuted(threadId: UInt, userId: UInt): Boolean = ThreadMutes.select(ThreadMutes.userId)
    .where { (ThreadMutes.threadId eq threadId) and (ThreadMutes.userId eq userId) }.limit(1).toList().isNotEmpty()

internal fun threadNotMutedBy(threadId: Column<EntityID<UInt>>, userId: UInt): Op<Boolean> = notExists(
    ThreadMutes.select(ThreadMutes.threadId).where { (ThreadMutes.threadId eq threadId) and (ThreadMutes.userId eq userId) },
)
