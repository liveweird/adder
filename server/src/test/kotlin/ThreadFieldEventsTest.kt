package ch.nokillswit

import ch.nokillswit.rooms.RoomOptionDef
import ch.nokillswit.rooms.SnapshotField
import ch.nokillswit.threads.FieldChange
import ch.nokillswit.threads.ThreadEventType
import ch.nokillswit.threads.threadFieldChangedEvent
import ch.nokillswit.threads.threadTitleChangedEvent
import ch.nokillswit.threads.userIdsOfChanges
import ch.nokillswit.workflows.FieldType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The pure `FIELD_CHANGED` and `TITLE_CHANGED` descriptors (`threads/ThreadFieldEvents.kt`): the display text of every recorded side. */
class ThreadFieldEventsTest {
    private val options = listOf(RoomOptionDef("low", "Low"), RoomOptionDef("high", "High"))

    private fun field(key: String, type: FieldType) = SnapshotField(
        key, key.replaceFirstChar { it.uppercase() }, type, required = false,
        options = if (type.takesOptions) options else emptyList(), retired = false,
    )

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun params(type: FieldType, from: JsonElement?, to: JsonElement?, names: Map<UInt, String> = emptyMap()): Map<String, String> {
        val event = threadFieldChangedEvent(field("f", type), FieldChange("f", from, to), names)
        assertEquals(ThreadEventType.FIELD_CHANGED, event.type)
        return event.params - setOf("field", "fieldLabel")
    }

    @Test
    fun `the title event records that the title changed and neither title`() {
        val event = threadTitleChangedEvent()
        assertEquals(ThreadEventType.TITLE_CHANGED, event.type)
        assertEquals(emptyMap(), event.params)
    }

    @Test
    fun `the field and its label are recorded and an absent side means unset`() {
        val event = threadFieldChangedEvent(field("lead", FieldType.USER), FieldChange("lead", null, JsonPrimitive(7)), mapOf(7u to "Ann"))
        assertEquals(mapOf("field" to "lead", "fieldLabel" to "Lead", "to" to "Ann"), event.params)
        assertEquals(mapOf("from" to "Ann"), params(FieldType.USER, JsonPrimitive(7), null, mapOf(7u to "Ann")))
    }

    @Test
    fun `a TEXT or URL change records the field and that it changed, never the text`() {
        for (type in listOf(FieldType.TEXT, FieldType.URL)) {
            val secret = "hunter2 https://example.org/?token=abc"
            for ((from, to) in listOf(text(secret) to text("other"), null to text(secret), text(secret) to null)) {
                val event = threadFieldChangedEvent(field("note", type), FieldChange("note", from, to), emptyMap())
                assertEquals(ThreadEventType.FIELD_CHANGED, event.type)
                assertEquals(mapOf("field" to "note", "fieldLabel" to "Note"), event.params, "$type: $from -> $to")
            }
        }
    }

    @Test
    fun `the types that record their values are the SELECT, MULTI_SELECT, USER, DATE and NUMBER ones`() {
        fun valueOf(type: FieldType): JsonElement = when (type) {
            FieldType.MULTI_SELECT -> JsonArray(listOf(text("low")))
            FieldType.USER -> JsonPrimitive(1)
            else -> text("low")
        }
        val recorded = FieldType.entries.filter {
            threadFieldChangedEvent(field("f", it), FieldChange("f", null, valueOf(it)), mapOf(1u to "Ann")).params.containsKey("to")
        }
        assertEquals(
            setOf(FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.USER, FieldType.DATE, FieldType.NUMBER),
            recorded.toSet(),
            "an allow-list: a type added later records nothing until it is listed",
        )
    }

    @Test
    fun `a select shows the option label, falling back to the key of a removed option`() {
        assertEquals(mapOf("from" to "Low", "to" to "High"), params(FieldType.SELECT, text("low"), text("high")))
        assertEquals(mapOf("from" to "gone", "to" to "High"), params(FieldType.SELECT, text("gone"), text("high")))
    }

    @Test
    fun `a multi-select joins the labels with a comma`() {
        val low = JsonArray(listOf(text("low")))
        val both = JsonArray(listOf(text("low"), text("high"), text("gone")))
        assertEquals(mapOf("from" to "Low", "to" to "Low, High, gone"), params(FieldType.MULTI_SELECT, low, both))
    }

    @Test
    fun `a USER shows the name, or the id when the name is unknown`() {
        assertEquals(mapOf("from" to "Ann", "to" to "9"), params(FieldType.USER, JsonPrimitive(7), JsonPrimitive(9), mapOf(7u to "Ann")))
    }

    @Test
    fun `a number and a date show raw`() {
        assertEquals(mapOf("from" to "7", "to" to "7.5"), params(FieldType.NUMBER, JsonPrimitive(7), JsonPrimitive(7.5)))
        assertEquals(mapOf("to" to "2026-12-24"), params(FieldType.DATE, null, text("2026-12-24")))
    }

    @Test
    fun `the user ids to look up are those held by USER fields, either side, once`() {
        val fields = listOf(field("lead", FieldType.USER), field("note", FieldType.TEXT))
        val changes = listOf(
            FieldChange("lead", JsonPrimitive(7), JsonPrimitive(9)),
            FieldChange("lead", null, JsonPrimitive(7)),
            FieldChange("note", text("7"), null),
        )
        assertEquals(setOf(7u, 9u), userIdsOfChanges(fields, changes))
        assertEquals(emptySet(), userIdsOfChanges(fields, changes.drop(2)))
    }
}
