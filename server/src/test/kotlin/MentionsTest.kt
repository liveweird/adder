package ch.nokillswit

import ch.nokillswit.threads.MAX_MENTIONS_PER_MESSAGE
import ch.nokillswit.threads.parseMentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The pure mention parser (`threads/Mentions.kt`, `chat-model.md` "Mentions (V17)"). The case table `mention-cases.json` is the ONE
 * statement of the grammar for both sides: this test reads it and so does the SPA parser's test. The rest is what a table cannot say: the
 * cap and the linear-time bound on hostile bodies.
 */
class MentionsTest {
    @Serializable
    private class Case(val name: String, val body: String, val ids: List<UInt>)

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.getResource("/mention-cases.json")) { "mention-cases.json is not on the classpath" }.readText()
        Json.decodeFromString<List<Case>>(text)
    }

    @Test
    fun `the case table pins the grammar`() {
        assertTrue(cases.size >= 30, "the table is the shared contract: ${cases.size} cases")
        assertEquals(cases.map { it.name }.distinct(), cases.map { it.name }, "case names are unique")
        val failures = cases.filter { parseMentions(it.body) != it.ids }
            .map { "${it.name}: expected ${it.ids}, got ${parseMentions(it.body)}" }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `only the first twenty distinct ids are kept and the rest are ignored`() {
        val body = (1..30).joinToString(" ") { "<@$it>" }
        assertEquals((1..MAX_MENTIONS_PER_MESSAGE).map { it.toUInt() }, parseMentions(body))
        // A repeated id is one of the twenty, not one per occurrence.
        assertEquals(
            MAX_MENTIONS_PER_MESSAGE,
            parseMentions((1..25).joinToString(" ") { "<@1> <@2> <@${it + 2}>" }).size,
        )
        assertEquals(listOf(1u, 2u, 3u), parseMentions("<@1> <@2> <@1> <@3>"))
    }

    @Test
    fun `a body of 40,000 tokens parses in linear time`() {
        val body = "<@1>".repeat(2_000) + "`" + "<@7>`".repeat(2_000) + " https://x.y/".repeat(2_000) + "\\<@9>".repeat(2_000)
        val tokens = "<@42>".repeat(40_000)
        val hostile = listOf(
            body,
            tokens,
            "<@".repeat(20_000),
            "<@1".repeat(20_000),
            "`".repeat(40_000),
            "```\n".repeat(10_000),
            "](".repeat(20_000),
        )
        val elapsed = measureTime { hostile.forEach { parseMentions(it) } }
        assertTrue(elapsed < 5.seconds, "hostile bodies took $elapsed")
        assertEquals(listOf(42u), parseMentions(tokens))
    }
}
