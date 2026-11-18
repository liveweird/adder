package ch.nokillswit

import ch.nokillswit.threads.FieldChange
import ch.nokillswit.threads.FieldDefinition
import ch.nokillswit.threads.MAX_NUMBER_FIELD_MAGNITUDE
import ch.nokillswit.threads.MAX_TEXT_FIELD_LENGTH
import ch.nokillswit.threads.MAX_URL_FIELD_LENGTH
import ch.nokillswit.threads.applyFieldValues
import ch.nokillswit.threads.incomingUserIds
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_OPTIONS
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pure thread field kernel (`threads/ThreadFields.kt`): every type (valid, invalid, null, blank) and every cross-cutting rule. */
class ThreadFieldsTest {
    private val options = listOf("low", "high", "critical")
    private val members = setOf(7L, 8L)

    private fun def(
        key: String,
        type: FieldType,
        required: Boolean = false,
        optionKeys: List<String> = if (type.takesOptions) options else emptyList(),
        retired: Boolean = false,
    ) = FieldDefinition(key, type, required, optionKeys, retired)

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun many(vararg values: String): JsonElement = JsonArray(values.map(::JsonPrimitive))

    private fun apply(
        definitions: List<FieldDefinition>,
        incoming: Map<String, JsonElement>,
        current: Map<String, JsonElement> = emptyMap(),
        creating: Boolean = false,
    ) = applyFieldValues(definitions, current, incoming, members, creating)

    private fun applyOne(type: FieldType, value: JsonElement, current: JsonElement? = null) =
        apply(listOf(def("f", type)), mapOf("f" to value), listOfNotNull(current?.let { "f" to it }).toMap())

    private fun assertRejected(part: String, block: () -> Unit) {
        val failure = assertFailsWith<BadRequestException> { block() }
        assertTrue(part in failure.message.orEmpty(), "expected '$part' in: ${failure.message}")
    }

    private fun assertStored(type: FieldType, value: JsonElement, stored: JsonElement? = value) {
        val write = applyOne(type, value)
        assertEquals(if (stored == null) emptyMap() else mapOf("f" to stored), write.merged, "$type $value")
    }

    private fun assertInvalid(type: FieldType, value: JsonElement, part: String = "Field 'f'") =
        assertRejected(part) { applyOne(type, value) }

    @Test
    fun `TEXT is trimmed, single line, bounded, and blank means unset`() {
        assertStored(FieldType.TEXT, text("  hello  "), text("hello"))
        assertStored(FieldType.TEXT, text(" \t "), null)
        assertStored(FieldType.TEXT, text("x".repeat(MAX_TEXT_FIELD_LENGTH)))
        assertInvalid(FieldType.TEXT, text("x".repeat(MAX_TEXT_FIELD_LENGTH + 1)), "at most $MAX_TEXT_FIELD_LENGTH")
        assertInvalid(FieldType.TEXT, text("a\nb"), "control characters")
        for (hidden in listOf('\u202A', '\u202E', '\u2066', '\u2069', '\u2028', '\u2029')) {
            assertInvalid(FieldType.TEXT, text("a${hidden}b"), "control characters")
        }
        assertStored(FieldType.TEXT, text("\uD83D\uDC68\u200D\uD83D\uDCBB ok"))
        assertInvalid(FieldType.TEXT, JsonPrimitive(5), "must be a string")
        assertInvalid(FieldType.TEXT, many("a"), "must be a string")
    }

    // The server's real request parser (Ktor's lenient `DefaultJson`): `raw` is the JSON text of ONE field value, as a client sends it.
    private fun parsed(raw: String): JsonElement = (DefaultJson.parseToJsonElement("""{"f": $raw}""") as JsonObject).getValue("f")

    private fun storedFrom(type: FieldType, raw: String): JsonElement? = applyOne(type, parsed(raw)).merged["f"]

    @Test
    fun `NUMBER is a finite number within the bound`() {
        assertStored(FieldType.NUMBER, JsonPrimitive(42))
        assertStored(FieldType.NUMBER, JsonPrimitive(-3.5))
        assertStored(FieldType.NUMBER, JsonPrimitive(MAX_NUMBER_FIELD_MAGNITUDE), JsonPrimitive(1_000_000_000_000_000L))
        assertStored(FieldType.NUMBER, JsonPrimitive(-MAX_NUMBER_FIELD_MAGNITUDE), JsonPrimitive(-1_000_000_000_000_000L))
        assertInvalid(FieldType.NUMBER, JsonPrimitive(MAX_NUMBER_FIELD_MAGNITUDE * 10), "no larger than 1e15")
        assertInvalid(FieldType.NUMBER, JsonPrimitive(-MAX_NUMBER_FIELD_MAGNITUDE * 10), "no larger than 1e15")
        assertInvalid(FieldType.NUMBER, JsonPrimitive(Double.NaN), "JSON number")
        assertInvalid(FieldType.NUMBER, JsonPrimitive(Double.POSITIVE_INFINITY), "JSON number")
        assertInvalid(FieldType.NUMBER, JsonPrimitive("12"), "must be a number")
        assertInvalid(FieldType.NUMBER, JsonPrimitive(true), "JSON number")
        assertInvalid(FieldType.NUMBER, many("1"), "must be a number")
    }

    @Test
    fun `NUMBER is stored as one canonical number whatever the client's spelling`() {
        val canonicalSeven = JsonPrimitive(7)
        for (raw in listOf("7", "7.0", "7.000", "7e0", "0.7e1", "70e-1", "-0", "0")) {
            val expected = if (raw.startsWith("-0") || raw == "0") JsonPrimitive(0) else canonicalSeven
            assertEquals(expected, storedFrom(FieldType.NUMBER, raw), raw)
        }
        assertEquals(JsonPrimitive(0.5), storedFrom(FieldType.NUMBER, "0.5"))
        assertEquals(JsonPrimitive(-0.25), storedFrom(FieldType.NUMBER, "-2.5e-1"))
        // Tiny but well-formed: it underflows to zero rather than failing the jsonb write.
        assertEquals(JsonPrimitive(0), storedFrom(FieldType.NUMBER, "1e-2000"))
        // The canonical form is valid JSON text for any stored number (what the jsonb column receives).
        for (raw in listOf("0.00001", "123456789.5", "1e14", "999999999999999.5")) {
            val stored = storedFrom(FieldType.NUMBER, raw)!!
            assertEquals(stored, DefaultJson.parseToJsonElement(stored.toString()), raw)
        }
    }

    @Test
    fun `NUMBER refuses what the lenient parser lets through but the JSON grammar does not`() {
        val rejected = listOf(
            "+7", "007", "1d", "0x1p3", ".5", "5.", "1e", "1e+", "--1", "NaN", "Infinity", "-Infinity", "1_000", "abc", "true",
        )
        for (raw in rejected) {
            assertRejected("JSON number") { applyOne(FieldType.NUMBER, parsed(raw)) }
        }
        assertRejected("JSON number") { applyOne(FieldType.NUMBER, parsed("1e999")) }
        assertRejected("JSON number") { applyOne(FieldType.NUMBER, parsed("1" + "0".repeat(31))) }
        // 32 characters is the cap on the token itself, however small the value.
        assertEquals(JsonPrimitive(1), storedFrom(FieldType.NUMBER, "1." + "0".repeat(30)))
        assertRejected("JSON number") { applyOne(FieldType.NUMBER, parsed("1." + "0".repeat(31))) }
    }

    @Test
    fun `DATE is a real calendar day as YYYY-MM-DD`() {
        assertStored(FieldType.DATE, text("2026-02-28"))
        assertStored(FieldType.DATE, text("2028-02-29"))
        val badDates = listOf(
            "2026-02-30", "2027-02-29", "2026-13-01", "2026-2-3", "26-02-03", "2026/02/03", "", "tomorrow", "+12026-01-01",
        )
        for (bad in badDates) {
            assertInvalid(FieldType.DATE, text(bad), "calendar date")
        }
        assertInvalid(FieldType.DATE, JsonPrimitive(20260228), "must be a string")
    }

    @Test
    fun `SELECT is one option key`() {
        assertStored(FieldType.SELECT, text("high"))
        assertInvalid(FieldType.SELECT, text("urgent"), "one of its options")
        assertInvalid(FieldType.SELECT, text(""), "one of its options")
        assertInvalid(FieldType.SELECT, many("high"), "must be a string")
    }

    @Test
    fun `MULTI_SELECT is one to fifty distinct option keys and empty means unset`() {
        assertStored(FieldType.MULTI_SELECT, many("low", "critical"))
        assertStored(FieldType.MULTI_SELECT, many(), null)
        assertInvalid(FieldType.MULTI_SELECT, many("low", "low"), "must not repeat")
        assertInvalid(FieldType.MULTI_SELECT, many("low", "urgent"), "only its options")
        assertInvalid(FieldType.MULTI_SELECT, text("low"), "array of option keys")
        assertInvalid(FieldType.MULTI_SELECT, JsonArray(listOf(JsonPrimitive(1))), "must be a string")
        val many = (1..MAX_OPTIONS).map { "o$it" }
        val fifty = apply(listOf(def("f", FieldType.MULTI_SELECT, optionKeys = many)), mapOf("f" to JsonArray(many.map(::JsonPrimitive))))
        assertEquals(MAX_OPTIONS, (fifty.merged.getValue("f") as JsonArray).size)
        val tooMany = many + "extra"
        assertRejected("at most $MAX_OPTIONS") {
            apply(listOf(def("f", FieldType.MULTI_SELECT, optionKeys = tooMany)), mapOf("f" to JsonArray(tooMany.map(::JsonPrimitive))))
        }
    }

    @Test
    fun `MULTI_SELECT is stored in option order with kept removed options last, and a reorder is no change`() {
        val definitions = listOf(def("f", FieldType.MULTI_SELECT))
        assertEquals(many("low", "high", "critical"), apply(definitions, mapOf("f" to many("critical", "high", "low"))).merged["f"])
        // A removed option is kept after the live ones, in its stored order.
        val current = mapOf("f" to many("gone-b", "low", "gone-a"))
        val changed = apply(definitions, mapOf("f" to many("gone-a", "critical", "gone-b")), current)
        assertEquals(many("critical", "gone-b", "gone-a"), changed.merged["f"])
        // The same set in any order is no change, whichever order the stored value or the definition has.
        for (incoming in listOf(many("critical", "low"), many("low", "critical"))) {
            val write = apply(definitions, mapOf("f" to incoming), mapOf("f" to many("critical", "low")))
            assertEquals(mapOf("f" to many("critical", "low")), write.merged)
            assertEquals(emptyList(), write.changes)
        }
        assertEquals(1, apply(definitions, mapOf("f" to many("low")), mapOf("f" to many("critical", "low"))).changes.size)
    }

    @Test
    fun `USER is the id of an active member`() {
        assertStored(FieldType.USER, JsonPrimitive(7))
        assertInvalid(FieldType.USER, JsonPrimitive(99), "active room member")
        assertInvalid(FieldType.USER, JsonPrimitive(7.5), "active room member")
        assertInvalid(FieldType.USER, JsonPrimitive(-7), "active room member")
        assertInvalid(FieldType.USER, JsonPrimitive("7"), "a user id")
        assertInvalid(FieldType.USER, many("7"), "a user id")
    }

    @Test
    fun `USER is stored as the plain id and refuses every other spelling`() {
        assertEquals(JsonPrimitive(7), storedFrom(FieldType.USER, "7"))
        assertEquals(JsonPrimitive(7), storedFrom(FieldType.USER, "007"))
        for (raw in listOf("+7", "7e0", "7.0", "0x7", "1d", ".7", "seven", "1" + "0".repeat(18))) {
            assertRejected("active room member") { applyOne(FieldType.USER, parsed(raw)) }
        }
        // A respelling of the stored id is no change.
        val write = apply(listOf(def("f", FieldType.USER)), mapOf("f" to parsed("007")), mapOf("f" to JsonPrimitive(7)))
        assertEquals(emptyList(), write.changes)
    }

    @Test
    fun `URL is absolute http or https within the length`() {
        assertStored(FieldType.URL, text("https://example.com/a?b=c#d"))
        assertStored(FieldType.URL, text("HTTP://example.com"))
        assertStored(FieldType.URL, text("  https://example.com/a \t"), text("https://example.com/a"))
        val longest = "https://example.com/" + "a".repeat(MAX_URL_FIELD_LENGTH - "https://example.com/".length)
        assertStored(FieldType.URL, text(longest))
        assertInvalid(FieldType.URL, text(longest + "a"), "absolute http or https URL")
        assertStored(FieldType.URL, text(" $longest "), text(longest))
        val rejected = listOf(
            "ftp://example.com", "javascript:alert(1)", "example.com", "/relative", "https://", "http:foo", "https://exa mple.com", "",
        )
        for (bad in rejected) {
            assertInvalid(FieldType.URL, text(bad), "absolute http or https URL")
        }
        assertInvalid(FieldType.URL, JsonPrimitive(1), "must be a string")
    }

    @Test
    fun `null clears every type`() {
        for (type in FieldType.entries) {
            val stored: JsonElement = when (type) {
                FieldType.NUMBER, FieldType.USER -> JsonPrimitive(7)
                FieldType.MULTI_SELECT -> many("low")
                else -> text("low")
            }
            val write = applyOne(type, JsonNull, current = stored)
            assertEquals(emptyMap(), write.merged, "$type")
            assertEquals(listOf(FieldChange("f", stored, null)), write.changes, "$type")
        }
    }

    @Test
    fun `unknown keys are a 400, and so is a new value for a retired one`() {
        val definitions = listOf(def("severity", FieldType.SELECT), def("old", FieldType.TEXT, retired = true))
        assertRejected("Unknown field 'nope'") { apply(definitions, mapOf("nope" to text("x"))) }
        assertRejected("Unknown field 'nope'") { apply(definitions, mapOf("severity" to text("low"), "nope" to JsonNull)) }
        assertRejected("Field 'old' is retired") { apply(definitions, mapOf("old" to text("x"))) }
        val echoed = assertFailsWith<BadRequestException> { apply(definitions, mapOf("k".repeat(500) to text("x"))) }
        assertTrue("…" in echoed.message.orEmpty())
    }

    @Test
    fun `an unchanged value for a retired field is ignored, a changed or new one is refused`() {
        val definitions = listOf(
            def("old", FieldType.TEXT, retired = true),
            def("num", FieldType.NUMBER, retired = true),
            def("live", FieldType.TEXT),
        )
        val current = mapOf("old" to text("kept"), "num" to JsonPrimitive(7))
        // A stale form still carries the retired values it loaded: untouched, however the number is spelled.
        val stale = apply(definitions, mapOf("old" to text("kept"), "num" to parsed("7.0"), "live" to text("x")), current)
        assertEquals(current + ("live" to text("x")), stale.merged)
        assertEquals(listOf(FieldChange("live", null, text("x"))), stale.changes)
        assertRejected("Field 'old' is retired") { apply(definitions, mapOf("old" to text("changed")), current) }
        assertRejected("Field 'old' is retired") { apply(definitions, mapOf("old" to JsonNull), current) }
        assertRejected("Field 'num' is retired") { apply(definitions, mapOf("num" to JsonPrimitive(8)), current) }
        // Nothing stored, nothing to keep: a value is new, a null is no change.
        assertRejected("Field 'old' is retired") { apply(definitions, mapOf("old" to text("x"))) }
        assertEquals(emptyMap(), apply(definitions, mapOf("old" to JsonNull)).merged)
    }

    @Test
    fun `retired values are preserved and a write leaves them alone`() {
        val definitions = listOf(def("severity", FieldType.SELECT), def("old", FieldType.TEXT, retired = true))
        val write = apply(definitions, mapOf("severity" to text("high")), current = mapOf("old" to text("kept"), "severity" to text("low")))
        assertEquals(mapOf("old" to text("kept"), "severity" to text("high")), write.merged)
        assertEquals(listOf(FieldChange("severity", text("low"), text("high"))), write.changes)
    }

    @Test
    fun `merge semantics - an absent key is untouched and changes come in definition order`() {
        val definitions = listOf(def("a", FieldType.TEXT), def("b", FieldType.TEXT), def("c", FieldType.TEXT))
        val current = mapOf("a" to text("1"), "b" to text("2"))
        val write = apply(definitions, mapOf("c" to text("3"), "a" to text("9")), current)
        assertEquals(mapOf("a" to text("9"), "b" to text("2"), "c" to text("3")), write.merged)
        assertEquals(listOf(FieldChange("a", text("1"), text("9")), FieldChange("c", null, text("3"))), write.changes)
    }

    @Test
    fun `an unchanged value is no change, however it is spelled`() {
        val definitions = listOf(def("n", FieldType.NUMBER), def("t", FieldType.TEXT))
        val current = mapOf("n" to JsonPrimitive(1000), "t" to text("same"))
        val write = apply(definitions, mapOf("n" to JsonPrimitive(1000.0), "t" to text("  same  ")), current)
        assertEquals(current, write.merged)
        assertEquals(emptyList(), write.changes)
        assertEquals(emptyList(), apply(definitions, mapOf("n" to JsonNull), emptyMap()).changes)
    }

    @Test
    fun `an unchanged stale value skips validation`() {
        val stale = mapOf("severity" to text("removed"), "who" to JsonPrimitive(99), "tags" to many("low", "gone"))
        val definitions = listOf(def("severity", FieldType.SELECT), def("who", FieldType.USER), def("tags", FieldType.MULTI_SELECT))
        val write = apply(definitions, stale, stale)
        assertEquals(stale, write.merged)
        assertEquals(emptyList(), write.changes)
    }

    @Test
    fun `a removed option is kept when unchanged and refused when newly chosen`() {
        val definitions = listOf(def("severity", FieldType.SELECT), def("tags", FieldType.MULTI_SELECT))
        val current = mapOf("severity" to text("removed"), "tags" to many("low", "gone"))
        // A SELECT can only keep it by sending it again; any other value must be a live option.
        assertRejected("one of its options") { apply(definitions, mapOf("severity" to text("removed-too")), current) }
        assertEquals(text("high"), apply(definitions, mapOf("severity" to text("high")), current).merged["severity"])
        // A MULTI_SELECT may keep the removed option it holds while changing the rest, but not newly choose another one.
        val kept = apply(definitions, mapOf("tags" to many("gone", "critical")), current)
        assertEquals(many("critical", "gone"), kept.merged["tags"])
        assertRejected("only its options") { apply(definitions, mapOf("tags" to many("gone", "other")), current) }
    }

    @Test
    fun `required fields must be supplied on create`() {
        val definitions = listOf(
            def("severity", FieldType.SELECT, required = true),
            def("note", FieldType.TEXT),
            def("gone", FieldType.TEXT, required = true, retired = true),
        )
        assertRejected("Field 'severity' is required") { apply(definitions, emptyMap(), creating = true) }
        assertRejected("Field 'severity' is required") { apply(definitions, mapOf("severity" to JsonNull), creating = true) }
        val blankText = listOf(def("note", FieldType.TEXT, required = true))
        assertRejected("Field 'note' is required") { apply(blankText, mapOf("note" to text(" ")), creating = true) }
        val write = apply(definitions, mapOf("severity" to text("low")), creating = true)
        assertEquals(mapOf("severity" to text("low")), write.merged)
        assertEquals(listOf(FieldChange("severity", null, text("low"))), write.changes)
    }

    @Test
    fun `a required field cannot be cleared once set, and a never-set one may stay unset`() {
        val definitions = listOf(def("severity", FieldType.SELECT, required = true), def("tags", FieldType.MULTI_SELECT, required = true))
        val current = mapOf("severity" to text("low"))
        assertRejected("Field 'severity' is required") { apply(definitions, mapOf("severity" to JsonNull), current) }
        assertRejected("Field 'tags' is required") {
            apply(definitions, mapOf("tags" to many()), current + ("tags" to many("low")))
        }
        // Never set: untouched, or "cleared" again, stays unset without complaint.
        assertEquals(emptyList(), apply(definitions, emptyMap(), current).changes)
        assertEquals(emptyList(), apply(definitions, mapOf("tags" to JsonNull), current).changes)
        assertEquals(text("high"), apply(definitions, mapOf("severity" to text("high")), current).merged["severity"])
    }

    @Test
    fun `incomingUserIds collects the ids of active USER values and leaves everything else to the kernel`() {
        val definitions = listOf(
            def("lead", FieldType.USER),
            def("deputy", FieldType.USER),
            def("gone", FieldType.USER, retired = true),
            def("note", FieldType.TEXT),
        )
        val incoming = mapOf(
            "lead" to JsonPrimitive(7),
            "deputy" to JsonNull,
            "gone" to JsonPrimitive(9),
            "note" to JsonPrimitive(11),
        )
        assertEquals(setOf(7L), incomingUserIds(definitions, incoming), "a retired or non-USER field's value is not a lookup candidate")
        for (notAnId in listOf(JsonPrimitive("7"), JsonPrimitive(7.5), JsonPrimitive(-7), JsonPrimitive(1e30), many("7"))) {
            assertEquals(emptySet(), incomingUserIds(definitions, mapOf("lead" to notAnId)), "$notAnId")
        }
        assertEquals(setOf(7L, 8L), incomingUserIds(definitions, mapOf("lead" to JsonPrimitive(7), "deputy" to JsonPrimitive(8))))
        assertEquals(emptySet(), incomingUserIds(definitions, emptyMap()))
    }
}
