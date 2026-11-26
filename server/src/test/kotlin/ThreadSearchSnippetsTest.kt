package ch.nokillswit

import ch.nokillswit.threads.ResolvedStructuredSearch
import ch.nokillswit.threads.parseStructuredSearch
import ch.nokillswit.threads.plainSnippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadSearchSnippetsTest {
    @Test
    fun `plain snippets are whitespace-normalized code-point bounded and never split emoji`() {
        assertEquals(emptyList(), plainSnippet(" \n\t ", resolved("owner:1")))

        val exact = "🚀".repeat(300)
        assertEquals(exact, plainSnippet(exact, resolved("owner:1")).single().text)

        val clipped = plainSnippet("🚀".repeat(301), resolved("owner:1")).single()
        assertEquals(302, clipped.text.codePointCount(0, clipped.text.length), "300 emoji, a space and the ellipsis")
        assertTrue(clipped.text.startsWith("🚀".repeat(300)))
        assertTrue(clipped.text.endsWith(" …"))
        assertFalse(clipped.hit)
    }

    @Test
    fun `link snippets distinguish no link and a link at the exact message edge`() {
        val noLink = plainSnippet("ordinary body", resolved("has:link"))
        assertEquals(listOf("ordinary body"), noLink.map { it.text })
        assertTrue(noLink.none { it.hit })

        val atEdge = plainSnippet("https://example.test/", resolved("has:link"))
        assertEquals(listOf("https://example.test/"), atEdge.map { it.text })
        assertTrue(atEdge.single().hit)
    }

    @Test
    fun `literal snippets clip both contexts by code points and mark only the phrase`() {
        val body = "🚀".repeat(121) + " target phrase " + "🌍".repeat(121)
        val snippet = plainSnippet(body, resolved("\"target phrase\""))

        assertEquals("target phrase", snippet.single { it.hit }.text)
        assertTrue(snippet.first().text.startsWith("… "))
        assertTrue(snippet.last().text.endsWith(" …"))
        val before = snippet.first().text.removePrefix("… ").trimEnd()
        val after = snippet.last().text.removeSuffix(" …").trimStart()
        assertEquals(119, before.codePointCount(0, before.length), "the separating space occupies one context point, then trims")
        assertEquals(119, after.codePointCount(0, after.length), "the separating space occupies one context point, then trims")
    }

    @Test
    fun `an absent literal falls back to the bounded unmarked excerpt`() {
        val snippet = plainSnippet("visible body", resolved("\"absent phrase\""))
        assertEquals(listOf("visible body"), snippet.map { it.text })
        assertTrue(snippet.none { it.hit })
    }

    private fun resolved(raw: String): ResolvedStructuredSearch = ResolvedStructuredSearch(
        query = parseStructuredSearch(raw, "UTC"),
        roomIds = listOf(1),
        authorIds = emptyList(),
        participantIds = emptyList(),
        ownerIds = emptyList(),
        textQueries = emptyMap(),
    )
}
