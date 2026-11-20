package ch.nokillswit

import ch.nokillswit.threads.MAX_REACTION_CODE_POINTS
import ch.nokillswit.threads.MAX_REACTION_UNITS
import ch.nokillswit.threads.isSingleEmoji
import ch.nokillswit.threads.requireReactionEmoji
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The pure "one emoji" rule (`threads/Reactions.kt`, `chat-model.md` "Reactions (V20)"). The case table `reaction-cases.json` is the
 * statement of the rule (the SPA picks from a list and validates nothing, so only this test reads it): the accepted and refused values
 * are written as code points, so the file shows no emoji a font might not draw.
 */
class ReactionsTest {
    @Serializable
    private class Case(val name: String, val emoji: String, val accepted: Boolean)

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.getResource("/reaction-cases.json")) { "reaction-cases.json is not on the classpath" }.readText()
        Json.decodeFromString<List<Case>>(text)
    }

    @Test
    fun `the case table pins the rule`() {
        assertTrue(cases.size >= 30, "the table is the contract: ${cases.size} cases")
        assertEquals(cases.map { it.name }.distinct(), cases.map { it.name }, "case names are unique")
        assertTrue(cases.any { it.accepted } && cases.any { !it.accepted })
        val failures = cases.filter { isSingleEmoji(it.emoji) != it.accepted }
            .map { "${it.name}: expected ${if (it.accepted) "accepted" else "refused"}" }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `no accepted value is over the two bounds, so the column holds every one`() {
        cases.filter { it.accepted }.forEach {
            assertTrue(it.emoji.length <= MAX_REACTION_UNITS, it.name)
            assertTrue(it.emoji.codePointCount(0, it.emoji.length) <= MAX_REACTION_CODE_POINTS, it.name)
        }
    }

    @Test
    fun `an emoji has one spelling - no joiner, selector, tone, keycap or tag can be added to a thumbs up at any place or in any number`() {
        val thumbsUp = 0x1F44D
        val extras = listOf(0xFE0F, 0x200D, 0x20E3, 0x1F3FB, 0x1F3FD, 0xE0067, 0xE007F, 0x1F44D, 0x1F1F5, 0x23)
        assertTrue(isSingleEmoji(String(Character.toChars(thumbsUp))))
        for (extra in extras) {
            for (count in 1..5) {
                val added = IntArray(count) { extra }
                val before = String(added, 0, count) + String(Character.toChars(thumbsUp))
                val after = String(Character.toChars(thumbsUp)) + String(added, 0, count)
                assertFalse(isSingleEmoji(before), "U+${extra.toString(16)} x$count before")
                // The only legitimate suffix is ONE skin tone.
                val legitimate = count == 1 && (extra == 0x1F3FB || extra == 0x1F3FD)
                assertEquals(legitimate, isSingleEmoji(after), "U+${extra.toString(16)} x$count after")
            }
        }
    }

    @Test
    fun `a value over 32 units or over 10 code points is refused before any other rule`() {
        assertFalse(isSingleEmoji("👍".repeat(MAX_REACTION_UNITS)))
        assertFalse(isSingleEmoji("a".repeat(MAX_REACTION_UNITS + 1)))
        assertFalse(isSingleEmoji("x".repeat(10_000)))
    }

    @Test
    fun `the validator answers a 400 with a fixed detail that never echoes the value`() {
        requireReactionEmoji("👍")
        val sent = "hunter2-the-password"
        val failure = assertFailsWith<BadRequestException> { requireReactionEmoji(sent) }
        assertEquals("emoji must be a single emoji", failure.message)
        assertFalse(sent in failure.message.orEmpty())
    }
}
