package ch.nokillswit

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.json.encodeParams
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The string-map codec behind `thread_events.params` (`infra/json/JsonParams.kt`). */
class JsonParamsTest {
    @Test
    fun `an empty map is an empty object`() {
        assertEquals("{}", encodeParams(emptyMap()))
        assertEquals(emptyMap(), decodeParams("{}"))
    }

    @Test
    fun `a map round trips with quotes, unicode and a newline intact`() {
        val params = mapOf("from" to "Say \"hi\"", "to" to "Zażółć gęślą jaźń 🐍", "note" to "a\nb")
        assertEquals(params, decodeParams(encodeParams(params)))
    }

    @Test
    fun `the encoding is a compact JSON object in insertion order`() {
        assertEquals("""{"b":"1","a":"2"}""", encodeParams(linkedMapOf("b" to "1", "a" to "2")))
    }

    @Test
    fun `a non-string value or a non-object is refused`() {
        assertFailsWith<SerializationException> { decodeParams("""{"a":1}""") }
        assertFailsWith<SerializationException> { decodeParams("[]") }
    }
}
