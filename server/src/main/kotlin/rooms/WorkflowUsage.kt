package ch.nokillswit.rooms

import io.ktor.util.AttributeKey

/**
 * The seam a room's workflow reads and edits thread usage through (`chat-model.md` "The mapping contract 0.3 plugs into"). 0.2 has no
 * threads, so [NoThreadsYet] answers every question with "none"; 0.3 implements this over its thread rows and registers it under
 * [WorkflowUsageKey], changing nothing else. Both calls run INSIDE the caller's transaction (the room's own, under the room lock for a
 * write), so a count and a move see one consistent state.
 */
interface WorkflowUsage {
    /** The number of threads of room [roomId] currently in each of [keys] (a key with none may be absent from the map). */
    suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long>

    /** Moves every thread of [roomId] in status [from] to status [to] on behalf of [byUserId]; returns how many moved. */
    suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long
}

/** 0.2: no thread exists, so nothing is counted and nothing moves. */
object NoThreadsYet : WorkflowUsage {
    override suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long> = emptyMap()

    override suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long = 0L
}

/** The attribute a later increment (or a test) registers its [WorkflowUsage] under; absent = [NoThreadsYet] (the telemetry seam idiom). */
val WorkflowUsageKey = AttributeKey<WorkflowUsage>("WorkflowUsage")
