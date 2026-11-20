package ch.nokillswit

import ch.nokillswit.threads.MAX_STORED_TAG_LENGTH
import ch.nokillswit.threads.MAX_TAGS_PER_MESSAGE
import ch.nokillswit.threads.MAX_TAG_LENGTH
import ch.nokillswit.threads.parseHashtags
import ch.nokillswit.threads.requireTagFilter
import ch.nokillswit.threads.tagKeysDiffer
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The pure hashtag parser (`threads/Hashtags.kt`, `chat-model.md` "Hashtags (V15)"). The case table `hashtag-cases.json` is the ONE
 * statement of the grammar for both sides: this test reads it and so does the SPA parser's test. The rest is what a table cannot
 * say: the cap, the filter's validation and the linear-time bound on hostile bodies.
 */
class HashtagsTest {
    @Serializable
    private class Case(val name: String, val body: String, val tags: List<String>)

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.getResource("/hashtag-cases.json")) { "hashtag-cases.json is not on the classpath" }.readText()
        Json.decodeFromString<List<Case>>(text)
    }

    @Test
    fun `the case table pins the grammar`() {
        assertTrue(cases.size >= 40, "the table is the shared contract: ${cases.size} cases")
        assertEquals(cases.map { it.name }.distinct(), cases.map { it.name }, "case names are unique")
        val failures = cases.filter { parseHashtags(it.body) != it.tags }
            .map { "${it.name}: expected ${it.tags}, got ${parseHashtags(it.body)}" }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `only the first twenty distinct tags are kept and the rest are ignored`() {
        val body = (1..30).joinToString(" ") { "#tag$it" }
        val tags = parseHashtags(body)
        assertEquals(MAX_TAGS_PER_MESSAGE, tags.size)
        assertEquals((1..MAX_TAGS_PER_MESSAGE).map { "tag$it" }, tags)
        // A repeated tag is one of the twenty, not one per occurrence.
        assertEquals(MAX_TAGS_PER_MESSAGE, parseHashtags((1..25).joinToString(" ") { "#a #b #tag$it" }).size)
        assertEquals(listOf("a", "b", "tag1"), parseHashtags("#a #b #A #tag1 #B").take(3))
    }

    @Test
    fun `a run of more than fifty characters is no tag and a tag needs a letter`() {
        assertEquals(emptyList(), parseHashtags("#" + "a".repeat(MAX_TAG_LENGTH + 1)))
        assertEquals(listOf("a".repeat(MAX_TAG_LENGTH)), parseHashtags("#" + "a".repeat(MAX_TAG_LENGTH)))
        assertEquals(emptyList(), parseHashtags("#42 #-- #___ #1-2"))
        assertEquals(listOf("1-2a"), parseHashtags("#1-2a"))
    }

    @Test
    fun `two bodies differ in tags only when the folded sets differ`() {
        assertFalse(tagKeysDiffer("a #Foo b", "other words #foo"))
        assertFalse(tagKeysDiffer("#a #b", "#b #a"))
        assertTrue(tagKeysDiffer("#a", "#a #b"))
        assertTrue(tagKeysDiffer("#a", "no tag"))
        assertFalse(tagKeysDiffer("no tag", "still none"))
    }

    @Test
    fun `a tag filter takes a written tag or a stored key, folded, and refuses the rest with 400`() {
        assertEquals("wdro\u017cenie", requireTagFilter("Wdro\u017cENIE"))
        assertEquals("wdro\u017cenie", requireTagFilter("wdroz\u0307enie"), "NFC first")
        assertEquals("a-b_c1", requireTagFilter("A-B_C1"))
        // A STORED KEY (what the tag list returns) is shaped by folding: punctuation, spaces and up to 150 characters are fine.
        val keys = listOf("hawai'i", "a:b", "ss" + "a".repeat(MAX_TAG_LENGTH), "1/2", "a b", "42", "--", "x".repeat(MAX_STORED_TAG_LENGTH))
        for (key in keys) {
            assertEquals(key.lowercase(), requireTagFilter(key), "'$key' is a possible stored key")
        }
        for (bad in listOf("", "#a", "x#y", "a\u0000", "a\nb", "a\u007f", "x".repeat(MAX_STORED_TAG_LENGTH + 1))) {
            assertFailsWith<BadRequestException>("'$bad' must be refused") { requireTagFilter(bad) }
        }
    }

    @Test
    fun `a decomposed tag is normalised to NFC`() {
        assertEquals(listOf("\u00e9t\u00e9"), parseHashtags("#e\u0301te\u0301"))
        // The first mark composes into the letter and the rest are no tag characters, so the tag ends before them.
        val run = "\u0301\u0316".repeat(20)
        assertEquals(listOf("\u00e1"), parseHashtags("#a$run"))
        assertEquals(listOf("\u00e1", "b"), parseHashtags("#a$run #b"))
    }

    /**
     * Canonical reordering sorts a run of combining marks by insertion: quadratic in the run. The body cap (8,000 UTF-16 units, checked
     * before the parser) bounds it: measured 10 to 20 ms for the worst bodies AT the cap, so the bound here is generous for a busy CI.
     */
    @Test
    fun `worst-case runs of misordered combining marks at the body cap parse within the budget`() {
        val cap = 8_000
        val hostile = mapOf(
            "misordered marks" to "#a" + "\u0301\u0316".repeat(cap / 2),
            "reversed runs" to "#a" + "\u0301".repeat(cap / 2) + "\u0316".repeat(cap / 2),
            "marks between tags" to "#a\u0301\u0316 ".repeat(cap / 5),
        )
        for ((name, body) in hostile) {
            assertTrue(body.length <= cap + 2, "$name is at the cap (${body.length})")
            val took = measureTime { parseHashtags(body) }
            assertTrue(took < 500.milliseconds, "$name (${body.length} units) took $took")
        }
    }

    @Test
    fun `a tag filter value is bounded before it is normalised`() {
        val hostile = "a" + "\u0301\u0316".repeat(100_000)
        val took = measureTime { assertFailsWith<BadRequestException> { requireTagFilter(hostile) } }
        assertTrue(took < 500.milliseconds, "the over-long value was refused in $took")
        assertEquals("\u00e9t\u00e9", requireTagFilter("e\u0301te\u0301"))
    }

    @Test
    fun `a body of 4,000 hashes parses in linear time`() {
        assertEquals(emptyList(), parseHashtags("#".repeat(4_000)))
        assertEquals(listOf("a"), parseHashtags("#a".repeat(4_000)))
        // A quadratic or backtracking parser needs minutes on these; the linear one needs milliseconds. The budget is generous (a busy CI).
        val hostile = mapOf(
            "hashes" to "#".repeat(400_000),
            "tags" to "#a ".repeat(130_000),
            "long runs" to ("#" + "a".repeat(60) + " ").repeat(6_000),
            "backticks" to "`".repeat(400_000),
            "alternating backtick runs" to "`a``b```c````".repeat(30_000),
            "opening fences" to "```x\n".repeat(80_000),
            "link openers" to "](".repeat(200_000),
            "link destinations" to "[a](b#c ".repeat(50_000),
            "autolink openers" to "<abc:".repeat(80_000),
            "definitions" to "[a]:#b\n".repeat(60_000),
            "urls" to "http://".repeat(60_000),
            "www" to "www".repeat(130_000),
            "mixed" to "#a`b](c<d:e[f]: ```\n~~~ http://x ".repeat(10_000),
        )
        for ((name, body) in hostile) {
            val took = measureTime { parseHashtags(body) }
            assertTrue(took < 5.seconds, "$name (${body.length} characters) took $took")
        }
    }
}
