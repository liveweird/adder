package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.activeForPartialIndex
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.threads.PinService.RoomPins
import ch.nokillswit.threads.ThreadService.Threads
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update

internal val ThreadLifecycleServiceKey = AttributeKey<ThreadLifecycleService>("ThreadLifecycleService")

internal fun threadAddress(roomId: UInt, number: Int) =
    (Threads.roomId eq roomId) and (Threads.number eq number) and Threads.active()

internal fun mergedSources(roomId: UInt, number: Int) =
    (Threads.roomId eq roomId) and (Threads.mergedIntoThreadNumber eq number) and Threads.active()

/** Normal lists and workflow operations enumerate canonical, active threads. */
internal fun ThreadService.Threads.canonical() = activeForPartialIndex() and mergedIntoThreadNumber.isNull()

private val steward: ThreadGuard = { caller, standing, thread -> requireThreadSteward(caller, standing, thread.ownerId) }

internal fun requireDistinctMergeTarget(number: Int, targetNumber: Int) {
    if (targetNumber < 1 || targetNumber == number) throw BadRequestException("Choose another thread in this room")
}

/** Both operations change a thread's lifecycle atomically under the existing room lock. */
internal class ThreadLifecycleService(
    private val database: R2dbcDatabase,
    private val events: ThreadEventService,
    private val notices: ThreadNotices,
    private val revisions: MessageRevisionService,
) {
    suspend fun delete(roomId: UInt, number: Int, caller: CallerPrincipal): UInt =
        database.withThreadWrite(roomId, number, caller, readerGuard, steward, allowMerged = true) { write ->
            write.requireRoomActive()
            val children = mergedSources(roomId, number)
            val threadIds = listOf(write.thread.id) + Threads.select(Threads.id).where { children }.map { it[Threads.id].value }.toList()
            Threads.update({ Threads.id inList threadIds }) {
                it[markedAsDeleted] = true
                it[updatedAt] = nowMillis()
            }
            revisions.purgeThreadsInTransaction(threadIds)
            RoomPins.deleteWhere { threadId inList threadIds }
            notices.threadsRemoved(roomId, threadIds)
            notifyLive(LiveHint(LiveKind.THREAD_DELETED, roomId, threadNumber = number))
            write.thread.id
        }

    suspend fun merge(roomId: UInt, number: Int, targetNumber: Int, caller: CallerPrincipal): UInt? {
        requireDistinctMergeTarget(number, targetNumber)
        return database.withThreadWrite(roomId, number, caller, readerGuard, steward, allowMerged = true) { write ->
            write.requireRoomActive()
            val target = threadRow(roomId, targetNumber)
            requireThreadSteward(write.caller, write.standing, target.ownerId)
            target.requireUnmerged()
            if (write.thread.mergedIntoThreadNumber == targetNumber) return@withThreadWrite null
            write.thread.requireUnmerged()

            val stamp = nowMillis()
            val changed = (Threads.id eq write.thread.id) or mergedSources(roomId, number)
            // Reparent existing sources to keep old links one hop from the canonical conversation.
            Threads.update({ changed }) {
                it[mergedIntoThreadNumber] = targetNumber
                it[updatedAt] = stamp
            }
            events.record(write.thread.id, write.caller.userId, mergeEvent(ThreadEventType.MERGED_INTO, targetNumber), stamp)
            events.record(target.id, write.caller.userId, mergeEvent(ThreadEventType.MERGED_FROM, number), stamp)
            notifyLive(LiveHint(LiveKind.THREAD_MERGED, roomId, threadNumber = number))
            write.thread.id
        }
    }
}

private fun mergeEvent(type: ThreadEventType, relatedNumber: Int) =
    ThreadEventDescriptor(type, mapOf("threadNumber" to relatedNumber.toString()))
