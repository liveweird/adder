package ch.nokillswit

import ch.nokillswit.threads.textOutsideCodeAndLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * The Markdown pre-pass shared by the body parsers (`threads/MarkdownText.kt`): the text outside code and links, with each skipped
 * region replaced by one space. The hashtag grammar on top of it is `HashtagsTest`'s; these are the cases of the pass itself.
 */
class MarkdownTextTest {
    private fun words(body: String): List<String> = textOutsideCodeAndLinks(body).split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test
    fun `fenced and inline code are dropped and the text around them is kept`() {
        assertEquals(listOf("see", "and", "then", "<@3>"), words("see `<@1>` and\n```\n<@2>\n```\nthen <@3>"))
        assertEquals(listOf("a", "b"), words("a ``x ` y`` b"), "a double-backtick span holds a single backtick")
        assertEquals(listOf("a", "b"), words("a\n~~~sh\n<@1>\n~~~\nb"), "a tilde fence is a fence too")
    }

    @Test
    fun `link destinations, autolinks, definitions and bare URLs are dropped, link text is kept`() {
        val text = textOutsideCodeAndLinks(
            "[<@4> text](http://x.y/p) <https://a.b/c> www.d.e/<@5> http://f.g <@6>\n[ref]: http://h.i\n![alt](img.png)",
        )
        assertTrue("<@4>" in text, "the text of a link is read")
        assertTrue("<@6>" in text, "plain text after the skipped regions is read")
        listOf("<@5>", "http", "www", "[ref]", "img.png").forEach { assertFalse(it in text, "$it should have been dropped: $text") }
        assertEquals("kept", words("kept").single())
        assertEquals("", textOutsideCodeAndLinks(""))
    }

    @Test
    fun `an unmatched fence runs to the end, an unmatched span is literal, and hostile bodies stay linear`() {
        assertEquals(listOf("before"), words("before\n```\n<@2> never closed"))
        assertEquals(listOf("a", "`", "b", "<@3>"), words("a ` b <@3>"))
        val hostile = mapOf(
            "backticks" to "`".repeat(400_000),
            "alternating runs" to "` `` ``` ".repeat(50_000),
            "destinations" to "](".repeat(200_000),
            "mixed" to "`](<a:b ```\n~~~ www http:// [a]: ".repeat(10_000),
        )
        for ((name, body) in hostile) {
            val took = measureTime { textOutsideCodeAndLinks(body) }
            assertTrue(took < 5.seconds, "$name (${body.length} characters) took $took")
        }
    }
}
