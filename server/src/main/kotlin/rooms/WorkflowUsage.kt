package ch.nokillswit.rooms

import io.ktor.util.AttributeKey

/**
 * The seam a room's workflow reads and edits thread usage through (`chat-model.md` "The mapping contract 0.3 plugs into"): `rooms/` never
 * imports `threads/`, so the thread aggregate implements this (`threads/ThreadWorkflowUsage.kt`) and `Database.kt` registers it under
 * [WorkflowUsageKey]. Both calls run INSIDE the caller's transaction (the room's own, under the room lock for a write), so a count and a
 * move see one consistent state.
 */
interface WorkflowUsage {
    /** The number of threads of room [roomId] currently in each of [keys] (a key with none may be absent from the map). */
    suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long>

    /** Moves every thread of [roomId] in status [from] to status [to] on behalf of [byUserId]; returns how many moved. */
    suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long
}

/** The attribute the composition root registers the [WorkflowUsage] under (a test may overwrite it with a fake: `test-fixtures.md`). */
val WorkflowUsageKey = AttributeKey<WorkflowUsage>("WorkflowUsage")
