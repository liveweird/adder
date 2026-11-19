package ch.nokillswit

import ch.nokillswit.infra.db.FTS_CONFIG
import ch.nokillswit.infra.db.FTS_HIT_START
import ch.nokillswit.infra.db.FTS_HIT_STOP
import ch.nokillswit.infra.db.FTS_WHOLE_OPTIONS
import ch.nokillswit.infra.db.FtsSegment
import ch.nokillswit.infra.db.ftsHeadlineSql
import ch.nokillswit.infra.db.ftsQueryText
import ch.nokillswit.infra.db.splitHeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The full-text kernel (`infra/db/Fts.kt`, V16): the tsquery built from `to_tsvector`'s own lexemes - the hostile-input table, the
 * prefix rule, the empty query - and the headline splitter (markers into typed segments, whitespace collapsed, markup left as text).
 * The query text is built by the database, so those cases run against the shared container; the splitter is pure.
 */
class FtsTest {
    private fun queryText(input: String): String? = runBlocking {
        suspendTransaction(sharedDatabaseForTests()) { ftsQueryText(input) }
    }

    /** Whether the document matches the query text built from [input], by the database's own operator. */
    private fun matches(document: String, input: String): Boolean = runBlocking {
        suspendTransaction(sharedDatabaseForTests()) {
            val query = ftsQueryText(input) ?: return@suspendTransaction false
            exec(
                "SELECT to_tsvector('$FTS_CONFIG'::regconfig, ?) @@ CAST(? AS tsquery)",
                listOf(TextColumnType() to document, TextColumnType() to query),
                explicitStatementType = StatementType.SELECT,
            ) { it.get(0, java.lang.Boolean::class.java)!!.booleanValue() }?.toList()?.single() == true
        }
    }

    /** The headline of [text] for [input] with the whole-text options, split into segments. */
    private fun headline(text: String, input: String): List<FtsSegment> = runBlocking {
        suspendTransaction(sharedDatabaseForTests()) {
            val query = checkNotNull(ftsQueryText(input)) { "no lexeme in the input" }
            val raw = exec(
                "SELECT ${ftsHeadlineSql("?")}",
                listOf(TextColumnType() to text, TextColumnType() to query, TextColumnType() to FTS_WHOLE_OPTIONS),
                explicitStatementType = StatementType.SELECT,
            ) { it.get(0, String::class.java) }?.toList()?.single().orEmpty()
            splitHeadline(raw)
        }
    }

    @Test
    fun `hostile input is plain terms and never a syntax error`() {
        val table = listOf(
            "awaria w bazie" to "'awaria':* & 'bazie':* & 'w'",
            "'; DROP TABLE messages; --" to "'drop':* & 'messages':* & 'table':*",
            "foo:* | bar" to "'bar':* & 'foo':*",
            "a & b | !c <-> (d)" to "'a' & 'b' & 'c' & 'd'",
            "O'Brien" to "'brien':* & 'o'",
            "back\\slash \"quoted\" ''" to "'back':* & 'quoted':* & 'slash':*",
            "PROD-123" to "'-123':* & 'prod':*",
            "Żółw" to "'zolw':*",
            "ÀÉÎÕÜ ß" to "'aeiou':* & 'ss'",
        )
        for ((input, expected) in table) assertEquals(expected, queryText(input), input)
    }

    @Test
    fun `an input with no lexeme is no query at all`() {
        listOf("!!!", "<script>", "   ", "-- ;", "&|!:*()", "\\", "'").forEach { assertNull(queryText(it), it) }
    }

    @Test
    fun `a term of three characters or more is a prefix and a shorter one is exact`() {
        assertEquals("'ab'", queryText("ab"))
        assertEquals("'abc':*", queryText("abc"))
        assertEquals("'ab' & 'abc':* & 'wd'", queryText("wd ab abc"))
        assertTrue(matches("Awaria i awarii", "awari"), "a prefix finds both inflections")
        assertTrue(matches("outages", "outage"))
        assertTrue(!matches("wdrozenie", "wd"), "a two-character term is exact")
        assertTrue(!matches("baza", "bazy"), "no stemming: bazy does not find baza")
        assertTrue(!matches("alpha", "alpha beta"), "every term must match")
    }

    @Test
    fun `case and accents fold on both sides`() {
        assertTrue(matches("Żółw i żółwie", "zolw"))
        assertTrue(matches("zolw", "ŻÓŁW"))
        assertTrue(matches("Zażółć gęślą jaźń", "ZAZOLC gesla JAZN"))
    }

    @Test
    fun `markers split a headline into segments and the hits keep their diacritics`() {
        assertEquals(
            listOf(FtsSegment("Żółw", true), FtsSegment(" i ", false), FtsSegment("żółwie", true)),
            headline("Żółw i żółwie", "zol"),
        )
        val plain = headline("Plain title", "zol")
        assertEquals(listOf(FtsSegment("Plain title", false)), plain, "a title that does not match is one plain segment")
    }

    @Test
    fun `markup in the source stays text`() {
        val source = "<img src=x onerror=alert(1)> outage <b>bold</b>"
        val segments = headline(source, "outage")
        assertEquals(source, segments.joinToString("") { it.text })
        assertEquals(listOf("outage"), segments.filter { it.hit }.map { it.text })
        assertTrue(segments.none { FTS_HIT_START in it.text || FTS_HIT_STOP in it.text }, "no marker survives")
    }

    @Test
    fun `whitespace collapses across segments and the ends are trimmed`() {
        val split = splitHeadline("  first \n\n line$FTS_HIT_START  hit \t here $FTS_HIT_STOP\n tail  ")
        assertEquals(
            listOf(FtsSegment("first line", false), FtsSegment(" hit here ", true), FtsSegment("tail", false)),
            split,
        )
        assertEquals(emptyList(), splitHeadline(""))
        assertEquals(emptyList(), splitHeadline("  \n$FTS_HIT_START $FTS_HIT_STOP "))
    }

    @Test
    fun `unbalanced markers are tolerated and neighbours of one kind merge`() {
        assertEquals(listOf(FtsSegment("a", false), FtsSegment("b", true)), splitHeadline("a${FTS_HIT_START}b"))
        assertEquals(listOf(FtsSegment("ab", false)), splitHeadline("a${FTS_HIT_STOP}b"))
        assertEquals(listOf(FtsSegment("xy", true)), splitHeadline("${FTS_HIT_START}x${FTS_HIT_STOP}${FTS_HIT_START}y${FTS_HIT_STOP}"))
    }
}
