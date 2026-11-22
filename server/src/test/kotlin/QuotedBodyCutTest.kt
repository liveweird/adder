package ch.nokillswit

import ch.nokillswit.threads.MAX_QUOTED_BODY
import ch.nokillswit.threads.cutQuotedBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ONE cut of a quoted body (`chat-model.md` "Quotes (1.3)" "The read"): at most 1,000 code points; when longer, back to the last
 * whitespace inside the final 100; with no whitespace there, exactly 1,000; never inside a surrogate pair. Pure: no database.
 */
class QuotedBodyCutTest {
    private val pair = String(Character.toChars(0x1F600)) // one code point, two UTF-16 units

    private fun codePoints(text: String) = text.codePointCount(0, text.length)

    @Test
    fun `1,000 code points are kept whole and 1,001 are cut`() {
        val exact = "a".repeat(MAX_QUOTED_BODY)
        assertEquals(exact to false, cutQuotedBody(exact).let { it.text to it.truncated })

        val over = "a".repeat(MAX_QUOTED_BODY + 1)
        val cut = cutQuotedBody(over)
        assertTrue(cut.truncated)
        assertEquals(MAX_QUOTED_BODY, codePoints(cut.text), "no whitespace anywhere: exactly 1,000")
    }

    @Test
    fun `a longer body is cut back to the last whitespace inside the final 100`() {
        // A word ends at 960, a space, then 60 characters of the next word that runs past the cap.
        val body = "a".repeat(960) + " " + "b".repeat(100)
        val cut = cutQuotedBody(body)
        assertTrue(cut.truncated)
        assertEquals("a".repeat(960), cut.text, "the cut word is dropped whole, and so is the whitespace")

        // The LAST whitespace of the window wins, a newline or a tab as well as a space; trailing whitespace before it goes too.
        val tabbed = "a".repeat(920) + " \t " + "b".repeat(30) + "\n" + "c".repeat(100)
        assertEquals("a".repeat(920) + " \t " + "b".repeat(30), cutQuotedBody(tabbed).text)
        val doubled = "a".repeat(940) + "  \n" + "c".repeat(100)
        assertEquals("a".repeat(940), cutQuotedBody(doubled).text, "the whitespace run before the cut is trimmed")
    }

    @Test
    fun `whitespace before the final 100 does not count - the cut is exactly 1,000`() {
        val body = "a".repeat(100) + " " + "b".repeat(1_200)
        val cut = cutQuotedBody(body)
        assertTrue(cut.truncated)
        assertEquals(MAX_QUOTED_BODY, codePoints(cut.text))
        assertEquals(body.substring(0, MAX_QUOTED_BODY), cut.text)
        // The window is [900, 1000): a space AT 900 counts, one at 899 does not.
        val atEdge = "a".repeat(900) + " " + "b".repeat(300)
        assertEquals("a".repeat(900), cutQuotedBody(atEdge).text)
        val justOutside = "a".repeat(899) + " " + "b".repeat(300)
        assertEquals(MAX_QUOTED_BODY, codePoints(cutQuotedBody(justOutside).text))
    }

    @Test
    fun `when the code point after the cap is whitespace the full 1,000 are kept, not backed off to an earlier word`() {
        // 999 letters of one word end exactly at the cap and a space follows: the cut is already on a word end.
        val onWordEnd = "a".repeat(MAX_QUOTED_BODY) + " " + "b".repeat(50)
        val cut = cutQuotedBody(onWordEnd)
        assertTrue(cut.truncated)
        assertEquals("a".repeat(MAX_QUOTED_BODY), cut.text)

        // The same with an earlier space in the window: it is NOT used, the whole word that ends at 1,000 stays.
        val withEarlier = "a".repeat(950) + " " + "w".repeat(49) + "\n" + "c".repeat(50)
        assertEquals(1_000, withEarlier.indexOf('\n'))
        assertEquals(withEarlier.substring(0, MAX_QUOTED_BODY), cutQuotedBody(withEarlier).text)
        // A newline or tab after the cap counts like a space.
        assertEquals("a".repeat(MAX_QUOTED_BODY), cutQuotedBody("a".repeat(MAX_QUOTED_BODY) + "\t" + "x".repeat(10)).text)
    }

    @Test
    fun `a surrogate pair at the edge is never halved, and the cap counts code points`() {
        // 999 letters then a pair (code points 999 and 1000 are 'a' and the pair): the pair ends exactly at the cap and survives whole.
        val whole = "a".repeat(MAX_QUOTED_BODY - 1) + pair + "tail"
        val kept = cutQuotedBody(whole)
        assertEquals("a".repeat(MAX_QUOTED_BODY - 1) + pair, kept.text, "the pair is the 1,000th code point")
        assertEquals(MAX_QUOTED_BODY, codePoints(kept.text))

        // 1,000 code points of pairs are 2,000 UTF-16 units and still fit; one more is cut at 1,000 code points, never inside a pair.
        val fits = pair.repeat(MAX_QUOTED_BODY)
        assertEquals(fits to false, cutQuotedBody(fits).let { it.text to it.truncated })
        val cut = cutQuotedBody(pair.repeat(MAX_QUOTED_BODY + 1))
        assertEquals(pair.repeat(MAX_QUOTED_BODY), cut.text)
        assertTrue(cut.truncated)
        assertFalse(cut.text.last().isHighSurrogate(), "no lone high surrogate at the end")
    }

    @Test
    fun `a mention token at the edge is dropped whole rather than halved`() {
        val token = "<@123456>"
        // The token starts at 995 and would end at 1,004: the whitespace before it is inside the window, so the token goes whole.
        val body = "a".repeat(994) + " " + token + " tail"
        val cut = cutQuotedBody(body)
        assertTrue(cut.truncated)
        assertEquals("a".repeat(994), cut.text)
        assertFalse("<@" in cut.text, "no half of a token travels")

        // A token that ENDS inside the cap travels whole.
        val inside = "a".repeat(980) + " " + token + " " + "b".repeat(100)
        val kept = cutQuotedBody(inside)
        assertTrue(token in kept.text, kept.text)
    }

    @Test
    fun `a tail with no whitespace in the window is cut at exactly 1,000, even through a token`() {
        // The rule's own corner: no whitespace in the final 100, so the cut is exact and may halve a token (a body of one 1,000+ character
        // run has no better place); the excerpt the SPA makes is plain text, so a half token costs nothing but a cut word.
        val body = "a".repeat(950) + "<@1234567>" + "b".repeat(100)
        val cut = cutQuotedBody(body)
        assertEquals(body.substring(0, MAX_QUOTED_BODY), cut.text)
        assertTrue(cut.truncated)
    }
}
