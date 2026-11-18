package ch.nokillswit

import ch.nokillswit.rooms.StoredField
import ch.nokillswit.rooms.StoredStatus
import ch.nokillswit.rooms.planRoomWorkflow
import ch.nokillswit.workflows.FieldShape
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusShape
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pure room-workflow plan (`rooms/RoomWorkflowPlan.kt`): what a PUT adds, revives, retires, re-categorises or re-types, and every
 * replacement rule.
 */
class RoomWorkflowPlanTest {
    private val open = StatusCategory.OPEN
    private val closed = StatusCategory.CLOSED

    private fun stored(key: String, category: StatusCategory = open, retired: Boolean = false) = StoredStatus(key, category, retired)

    private fun incoming(key: String, category: StatusCategory = open, initial: Boolean = false) = StatusShape(key, category, initial)

    /** A room of live `a` (OPEN), `b` (OPEN), `z` (CLOSED) and the retired `old` (OPEN). */
    private val storedStatuses = listOf(stored("a"), stored("b"), stored("z", closed), stored("old", retired = true))

    private fun plan(
        statuses: List<StatusShape>,
        fields: List<FieldShape> = emptyList(),
        storedFields: List<StoredField> = emptyList(),
        rows: List<StoredStatus> = storedStatuses,
    ) = planRoomWorkflow(rows, storedFields, statuses, fields)

    @Test
    fun `an unchanged document is an empty plan`() {
        val plan = plan(listOf(incoming("a", initial = true), incoming("b"), incoming("z", closed)))
        assertEquals(emptyList(), plan.statusesAdded + plan.statusesRevived + plan.statusesRetired + plan.statusesRecategorised)
        assertEquals(emptyList(), plan.statusesToCount)
        assertEquals(0, plan.statusesBecomingLive)
    }

    @Test
    fun `a key the room never had is added, and a retired key named again is revived`() {
        val plan = plan(listOf(incoming("a"), incoming("new"), incoming("old"), incoming("z", closed)))
        assertEquals(listOf("new"), plan.statusesAdded)
        assertEquals(listOf("old"), plan.statusesRevived)
        assertEquals(listOf("b"), plan.statusesRetired, "b is not named any more")
        assertEquals(2, plan.statusesBecomingLive)
    }

    @Test
    fun `a live key the document drops is retired - a retired one that stays dropped is not retired again`() {
        val plan = plan(listOf(incoming("a"), incoming("z", closed)))
        assertEquals(listOf("b"), plan.statusesRetired)
        assertEquals(listOf("b"), plan.statusesToCount)
    }

    @Test
    fun `only a live status that changes category is re-categorised - a revived one is free to change`() {
        val plan = plan(listOf(incoming("a", closed), incoming("b"), incoming("z", closed), incoming("old", closed)))
        assertEquals(listOf("a"), plan.statusesRecategorised)
        assertEquals(listOf("old"), plan.statusesRevived)
        assertEquals(listOf("a"), plan.statusesToCount, "the revived one has no threads, so it is not counted")
    }

    @Test
    fun `fields are added, revived, retired and re-typed by key`() {
        val storedFields = listOf(
            StoredField("keep", FieldType.TEXT, retired = false),
            StoredField("drop", FieldType.NUMBER, retired = false),
            StoredField("gone", FieldType.DATE, retired = true),
        )
        val plan = plan(
            statuses = listOf(incoming("a")),
            storedFields = storedFields,
            fields = listOf(
                FieldShape("keep", FieldType.TEXT),
                FieldShape("gone", FieldType.DATE),
                FieldShape("fresh", FieldType.URL),
            ),
        )
        assertEquals(listOf("fresh"), plan.fieldsAdded)
        assertEquals(listOf("gone"), plan.fieldsRevived)
        assertEquals(listOf("drop"), plan.fieldsRetired)
        assertEquals(emptyList(), plan.fieldsRetyped)
        assertEquals(2, plan.fieldsBecomingLive)
    }

    @Test
    fun `a type change is reported for a live key and for a retired one alike`() {
        val storedFields = listOf(
            StoredField("live", FieldType.TEXT, retired = false),
            StoredField("dead", FieldType.NUMBER, retired = true),
        )
        val plan = plan(
            statuses = listOf(incoming("a")),
            storedFields = storedFields,
            fields = listOf(FieldShape("live", FieldType.NUMBER), FieldShape("dead", FieldType.TEXT)),
        )
        assertEquals(listOf("live", "dead"), plan.fieldsRetyped)
    }

    @Test
    fun `a replacement must name a status this change retires`() {
        val plan = plan(listOf(incoming("a"), incoming("z", closed)))
        plan.requireValidReplacements(mapOf("b" to "a"))
        plan.requireValidReplacements(emptyMap())
        for (from in listOf("a", "z", "old", "nope")) {
            val failure = assertFailsWith<BadRequestException>(from) { plan.requireValidReplacements(mapOf(from to "a")) }
            assertTrue("not a status this change retires" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `a replacement target must be in the new document and in the same category`() {
        val plan = plan(listOf(incoming("a"), incoming("z", closed)))
        val absent = assertFailsWith<BadRequestException> { plan.requireValidReplacements(mapOf("b" to "ghost")) }
        assertTrue("not a status of the new workflow" in absent.message.orEmpty(), absent.message)
        // `old` is retired already and `b` goes in this change: neither is in the new document.
        assertFailsWith<BadRequestException> { plan.requireValidReplacements(mapOf("b" to "old")) }
        val crossed = assertFailsWith<BadRequestException> { plan.requireValidReplacements(mapOf("b" to "z")) }
        assertTrue("same category" in crossed.message.orEmpty(), crossed.message)
    }

    @Test
    fun `the category that counts is the target's in the NEW document`() {
        // `a` flips to CLOSED in this change, so it is a valid home for a retired CLOSED status and no longer for an OPEN one.
        val rows = listOf(stored("a"), stored("b"), stored("c", closed), stored("z", closed))
        val plan = plan(listOf(incoming("a", closed), incoming("z", closed)), rows = rows)
        plan.requireValidReplacements(mapOf("c" to "a"))
        assertFailsWith<BadRequestException> { plan.requireValidReplacements(mapOf("b" to "a")) }
    }

    @Test
    fun `an echoed replacement key is cut to the key limit`() {
        val plan = plan(listOf(incoming("a"), incoming("z", closed)))
        val failure = assertFailsWith<BadRequestException> { plan.requireValidReplacements(mapOf("x".repeat(5_000) to "a")) }
        assertTrue(failure.message.orEmpty().length < 200, "the message is bounded")
    }

    @Test
    fun `a status with threads needs a mapping to retire and can never be re-categorised`() {
        val plan = plan(listOf(incoming("a", closed), incoming("z", closed)))
        assertEquals(listOf("b"), plan.statusesRetired)
        assertEquals(listOf("a"), plan.statusesRecategorised)
        val counts = mapOf("a" to 2L, "b" to 3L)
        assertEquals(listOf("b", "a"), plan.statusesInUse(counts, emptyMap()))
        // A mapping frees the retired one; the re-categorised one has no way out.
        assertEquals(listOf("a"), plan.statusesInUse(counts, mapOf("b" to "a")))
        // No threads, no problem: the absent count is zero.
        assertEquals(emptyList(), plan.statusesInUse(mapOf("a" to 0L), emptyMap()))
        assertEquals(emptyList(), plan.statusesInUse(emptyMap(), emptyMap()))
    }

    @Test
    fun `moves are the retired statuses that have threads and a mapping`() {
        val plan = plan(listOf(incoming("z", closed)))
        assertEquals(listOf("a", "b"), plan.statusesRetired)
        val replacements = mapOf("a" to "z", "b" to "z")
        assertEquals(listOf("a" to "z"), plan.movesFor(mapOf("a" to 4L), replacements))
        assertEquals(emptyList(), plan.movesFor(mapOf("a" to 4L), emptyMap()), "no mapping, no move")
        assertEquals(emptyList(), plan.movesFor(emptyMap(), replacements), "no threads, no move")
        assertEquals(listOf("a" to "z", "b" to "z"), plan.movesFor(mapOf("a" to 1L, "b" to 1L), replacements))
    }
}
