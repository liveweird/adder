package ch.nokillswit

import ch.nokillswit.threads.MAX_MENTIONS_PER_MESSAGE
import ch.nokillswit.threads.MAX_TEAM_MENTIONS_PER_MESSAGE
import ch.nokillswit.threads.ParsedMentions
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
    /** `teams`, `everyone` and `watchers` are optional in the table: absent means none (the first 37 cases are people only). */
    @Serializable
    private class Case(
        val name: String,
        val body: String,
        val ids: List<UInt>,
        val teams: List<UInt> = emptyList(),
        val everyone: Boolean = false,
        val watchers: Boolean = false,
    ) {
        val expected: ParsedMentions get() = ParsedMentions(ids, teams, watchers, everyone)
    }

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.getResource("/mention-cases.json")) { "mention-cases.json is not on the classpath" }.readText()
        Json.decodeFromString<List<Case>>(text)
    }

    @Test
    fun `the case table pins the grammar`() {
        assertTrue(cases.size >= 60, "the table is the shared contract: ${cases.size} cases")
        assertEquals(cases.map { it.name }.distinct(), cases.map { it.name }, "case names are unique")
        val failures = cases.filter { parseMentions(it.body) != it.expected }
            .map { "${it.name}: expected ${it.expected}, got ${parseMentions(it.body)}" }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `only the first twenty distinct ids are kept and the rest are ignored`() {
        val body = (1..30).joinToString(" ") { "<@$it>" }
        assertEquals((1..MAX_MENTIONS_PER_MESSAGE).map { it.toUInt() }, parseMentions(body).users)
        // A repeated id is one of the twenty, not one per occurrence.
        assertEquals(
            MAX_MENTIONS_PER_MESSAGE,
            parseMentions((1..25).joinToString(" ") { "<@1> <@2> <@${it + 2}>" }).users.size,
        )
        assertEquals(listOf(1u, 2u, 3u), parseMentions("<@1> <@2> <@1> <@3>").users)
    }

    @Test
    fun `only the first five distinct teams are kept and the people are counted apart`() {
        val teams = (1..9).joinToString(" ") { "<@team:$it>" }
        assertEquals((1..MAX_TEAM_MENTIONS_PER_MESSAGE).map { it.toUInt() }, parseMentions(teams).teams)
        // Twenty people do not use up the teams, nor the keywords: every kind has its own bound.
        val mixed = (1..30).joinToString(" ") { "<@$it>" } + " " + teams + " <@everyone> <@watchers>"
        val parsed = parseMentions(mixed)
        assertEquals(MAX_MENTIONS_PER_MESSAGE, parsed.users.size)
        assertEquals(MAX_TEAM_MENTIONS_PER_MESSAGE, parsed.teams.size)
        assertTrue(parsed.everyone && parsed.watchers)
        assertTrue(parseMentions("plain words").isEmpty)
    }

    @Test
    fun `group tokens in an indented code block or a raw HTML block are read by the server - the known disagreement with the SPA`() {
        // The pre-pass blanks fenced and inline code, link destinations and autolinks, not an INDENTED code block nor a raw HTML block (the
        // same gap as `<@id>`, `chat-model.md` "Mentions (V17)"): the server indexes and notifies, the SPA skips the node and shows the raw
        // token (`MarkdownView.test.tsx` pins that side). They are pinned here, not in the shared table, because the table is also what the
        // SPA must be able to show.
        val indented = parseMentions("para\n\n    <@everyone> <@watchers> <@team:4> indented\n\nafter")
        assertTrue(indented.everyone && indented.watchers)
        assertEquals(listOf(4u), indented.teams)
        val html = parseMentions("<div><@everyone> <@team:5> in html</div>\n\nafter")
        assertTrue(html.everyone)
        assertEquals(listOf(5u), html.teams)
    }

    @Test
    fun `a body of 40,000 tokens parses in linear time`() {
        val body = "<@1>".repeat(2_000) + "`" + "<@7>`".repeat(2_000) + " https://x.y/".repeat(2_000) + "\\<@9>".repeat(2_000)
        val tokens = "<@42>".repeat(40_000)
        val hostile = listOf(
            body,
            tokens,
            "<@".repeat(20_000),
            "<@team:9>".repeat(20_000) + "<@everyone>".repeat(20_000) + "<@team:".repeat(20_000),
            "<@1".repeat(20_000),
            "`".repeat(40_000),
            "```\n".repeat(10_000),
            "](".repeat(20_000),
        )
        val elapsed = measureTime { hostile.forEach { parseMentions(it) } }
        assertTrue(elapsed < 5.seconds, "hostile bodies took $elapsed")
        assertEquals(listOf(42u), parseMentions(tokens).users)
    }
}
