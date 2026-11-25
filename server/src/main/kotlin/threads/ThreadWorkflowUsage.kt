package ch.nokillswit.threads

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.WorkflowUsage
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The thread aggregate's side of the [WorkflowUsage] port (`chat-model.md` "The mapping contract 0.3 plugs into"): `rooms/` declares the
 * seam and never imports `threads/`, `Database.kt` registers this under `WorkflowUsageKey`. Both calls run INSIDE the room's transaction
 * (the workflow GET's, or the PUT's under the room lock), so the numbers and the moves are one consistent state; every thread write holds
 * the same room lock, so a count cannot move under a PUT. Room data (the status labels) comes through [RoomWorkflowService] only.
 */
class ThreadWorkflowUsage(
    private val workflows: RoomWorkflowService,
    private val events: ThreadEventService,
) : WorkflowUsage {
    /** One `GROUP BY status_key` over `idx_threads_room_status`, for the [keys] asked; a status with no thread is absent. */
    override suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long> {
        val threads = ThreadService.Threads
        val total = threads.id.count()
        return threads.select(threads.statusKey, total)
            .where { (threads.roomId eq roomId) and (threads.statusKey inList keys) and threads.canonical() }
            .groupBy(threads.statusKey)
            .map { it[threads.statusKey] to it[total] }
            .toList()
            .toMap()
    }

    /**
     * Moves every thread of [roomId] in status [from] to [to] and records one `STATUS_REPLACED` event per thread, by [byUserId], on ONE
     * instant. The PUT calls this AFTER its status upsert: [to] may be a status that very PUT added or revived, and [from] is already
     * retired (its row, label included, is still there). A replacement has the source's category, so `closed_at` is left alone (a
     * CLOSED to CLOSED move keeps the original close time, as the status route's does) and the thread's `revision` (the details token) is
     * not touched either; a workflow move is not activity, so `last_activity_at` and the room's rail order stay put. Only `updated_at`
     * moves.
     */
    override suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long {
        val threads = ThreadService.Threads
        val moving = (threads.roomId eq roomId) and (threads.statusKey eq from) and threads.canonical()
        val ids = threads.select(threads.id).where { moving }.map { it[threads.id].value }.toList()
        val stamp = nowMillis()
        threads.update({ moving }) {
            it[statusKey] = to
            it[updatedAt] = stamp
        }
        val statuses = workflows.snapshot(roomId).statuses.associateBy { it.key }
        val event = threadStatusReplacedEvent(from, statuses.getValue(from).label, to, statuses.getValue(to).label)
        events.recordAll(ids, byUserId, event, stamp)
        return ids.size.toLong()
    }
}
