package ch.nokillswit

import ch.nokillswit.threads.SearchReference
import ch.nokillswit.threads.SearchTextClause
import ch.nokillswit.threads.SearchTextKind
import ch.nokillswit.threads.parseStructuredSearch
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parser for the structured search query language. SQL matching is covered by the search service tests. */
class StructuredSearchTest {
    @Test
    fun `words phrases prefixes exclusions and escapes become typed clauses`() {
        val parsed = parseStructuredSearch(
            "outage -noise \"database timeout\" -\"old incident\" deploy* -legacy* " +
                "\"say \\\"hello\\\" at C:\\\\tmp\" \"-leading words\" \"  exact spacing  \" \"!!!\"",
            null,
        )

        assertEquals(
            listOf(
                SearchTextClause("outage", SearchTextKind.WORD, excluded = false),
                SearchTextClause("noise", SearchTextKind.WORD, excluded = true),
                SearchTextClause("database timeout", SearchTextKind.PHRASE, excluded = false),
                SearchTextClause("old incident", SearchTextKind.PHRASE, excluded = true),
                SearchTextClause("deploy", SearchTextKind.PREFIX, excluded = false),
                SearchTextClause("legacy", SearchTextKind.PREFIX, excluded = true),
                SearchTextClause("say \"hello\" at C:\\tmp", SearchTextKind.PHRASE, excluded = false),
                SearchTextClause("-leading words", SearchTextKind.PHRASE, excluded = false),
                SearchTextClause("  exact spacing  ", SearchTextKind.PHRASE, excluded = false),
                SearchTextClause("!!!", SearchTextKind.PHRASE, excluded = false),
            ),
            parsed.text,
        )
    }

    @Test
    fun `words and prefixes count supplementary letters as code points`() {
        val parsed = parseStructuredSearch("𐐀 𐐀𐐁*", null)
        assertEquals(
            listOf(
                SearchTextClause("𐐀", SearchTextKind.WORD, excluded = false),
                SearchTextClause("𐐀𐐁", SearchTextKind.PREFIX, excluded = false),
            ),
            parsed.text,
        )
        assertFailsWith<BadRequestException> { parseStructuredSearch("𐐀*", null) }
    }

    @Test
    fun `room and user filters accept quoted names and canonical ids`() {
        val parsed = parseStructuredSearch(
            "in:\"Incident Command\" in:#42 from:Alice from:7 with:\"Bob Smith\" owner:#2147483647 owner:\"#support\"",
            "UTC",
        )

        assertEquals(listOf(SearchReference(name = "Incident Command"), SearchReference(id = 42u)), parsed.rooms)
        assertEquals(listOf(SearchReference(name = "Alice"), SearchReference(id = 7u)), parsed.authors)
        assertEquals(listOf(SearchReference(name = "Bob Smith")), parsed.participants)
        assertEquals(
            listOf(SearchReference(id = Int.MAX_VALUE.toUInt()), SearchReference(name = "#support")),
            parsed.owners,
        )
    }

    @Test
    fun `has filters distinguish any and specific reactions and preserve emoji sequences`() {
        val family = "👩‍👩‍👧‍👦"
        val heart = "❤️"
        val parsed = parseStructuredSearch(
            "has:pin has:link has:reaction has:reaction:👍 has:emoji:$family has:emoji:$heart",
            null,
        )

        assertTrue(parsed.pinned)
        assertTrue(parsed.link)
        assertEquals(listOf(null, "👍"), parsed.reactions)
        assertEquals(listOf(family, heart), parsed.emojis)
    }

    @Test
    fun `status and hashtag filters become canonical typed clauses`() {
        val parsed = parseStructuredSearch(
            "status:investigating status:waiting-on-customer hashtag:Incident hashtag:#Zażółć",
            null,
        )

        assertEquals(listOf("investigating", "waiting-on-customer"), parsed.statuses)
        assertEquals(listOf("incident", "zażółć"), parsed.hashtags)
    }

    @Test
    fun `date filters form half open intersections in the requested time zone`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val parsed = parseStructuredSearch(
            "after:2026-03-27 on:2026-03-29 before:2026-04-01 during:2026-03",
            zone.id,
        )

        assertEquals(LocalDate.parse("2026-03-29").atStartOfDay(zone).toInstant().toEpochMilli(), parsed.startMillis)
        assertEquals(LocalDate.parse("2026-03-30").atStartOfDay(zone).toInstant().toEpochMilli(), parsed.endMillis)
        assertEquals(23 * 60 * 60 * 1_000L, parsed.endMillis!! - parsed.startMillis!!, "DST day has 23 hours")
    }

    @Test
    fun `during accepts a month or year and defaults to UTC`() {
        val month = parseStructuredSearch("during:2026-02", null)
        assertEquals(utc("2026-02-01"), month.startMillis)
        assertEquals(utc("2026-03-01"), month.endMillis)

        val year = parseStructuredSearch("during:2024", null)
        assertEquals(utc("2024-01-01"), year.startMillis)
        assertEquals(utc("2025-01-01"), year.endMillis)
        assertEquals(year, parseStructuredSearch("  during:2024  ", " "))
    }

    @Test
    fun `null and blank input produce an empty search`() {
        for (raw in listOf<String?>(null, "", "   ")) {
            val parsed = parseStructuredSearch(raw, null)
            assertTrue(parsed.text.isEmpty())
            assertTrue(parsed.rooms.isEmpty())
            assertNull(parsed.startMillis)
            assertNull(parsed.endMillis)
            assertFalse(parsed.pinned)
            assertFalse(parsed.link)
        }
    }

    @Test
    fun `invalid syntax is a fixed 400 that never echoes the query`() {
        val invalid = listOf(
            "\"unclosed secret",
            "\"\"",
            "\" \"",
            "\"phrase\"suffix",
            "prefix\"phrase\"",
            "unknown:hunter2",
            "in:",
            "in:\" \"",
            "in:${"a".repeat(101)}",
            "in:#0",
            "from:#2147483648",
            "has:reaction:not-an-emoji",
            "has:emoji:👍👍",
            "has:missing",
            "status:",
            "status:Investigating",
            "status:two--hyphens",
            "status:${"secret".repeat(8)}",
            "hashtag:",
            "hashtag:#",
            "hashtag:##secret",
            "hashtag:bad#tag",
            "hashtag:${"hunter2".repeat(22)}",
            "on:2026-02-30",
            "on:2026-02",
            "during:2026-13",
            "during:26",
            "a*bc",
            "x*",
            "ab-*",
            "-",
            "\\x",
        )
        for (query in invalid) {
            val failure = assertFailsWith<BadRequestException>(query) { parseStructuredSearch(query, null) }
            assertEquals("Invalid structured search query", failure.message)
            assertFalse(query in failure.message.orEmpty())
        }
    }

    @Test
    fun `input bounds controls and bidi formatting are refused before parsing`() {
        val tooLong = "😀".repeat(513)
        val tooMany = (1..25).joinToString(" ") { "word$it" }
        val forbidden = listOf("a\u0000b", "a\u0085b", "a\u061Cb", "a\u200Fb", "a\u2028b", "a\u2029b", "a\u202Eb", "a\u2067b")

        for (query in listOf(tooLong, tooMany) + forbidden) {
            assertFailsWith<BadRequestException> { parseStructuredSearch(query, null) }
        }
        assertEquals("a".repeat(512), parseStructuredSearch("a".repeat(512), null).text.single().value)
        assertFailsWith<BadRequestException> { parseStructuredSearch("\tword", null) }
    }

    @Test
    fun `an invalid time zone is a fixed 400`() {
        val sent = "Europe/Secret-Hunter2"
        val failure = assertFailsWith<BadRequestException> { parseStructuredSearch("on:2026-01-01", sent) }
        assertEquals("Invalid search time zone", failure.message)
        assertFalse(sent in failure.message.orEmpty())
        assertFailsWith<BadRequestException> { parseStructuredSearch("on:2026-01-01", "+01:00") }
    }

    @Test
    fun `reference invariant requires exactly one representation`() {
        assertFailsWith<IllegalArgumentException> { SearchReference() }
        assertFailsWith<IllegalArgumentException> { SearchReference(id = 1u, name = "one") }
    }

    private fun utc(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}
