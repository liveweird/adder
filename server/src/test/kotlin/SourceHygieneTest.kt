package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Invisible characters never sit literally in main sources: a bidirectional control, a zero-width character or a line/paragraph
 * separator is invisible (or reorders the text around it) in review, so a change could hide in it ("Trojan Source"). Where code needs
 * one (the plain-text mail sanitiser, `plainName`), it is written as a unicode escape. The scan reads the files as UTF-8 text and
 * names the file, line and code point of every hit. The guarded set is spelled in code points here so this file holds none itself.
 */
class SourceHygieneTest {
    private fun mainSources(): List<File> {
        val root = listOf("src/main", "server/src/main").map(::File).firstOrNull { it.exists() }
            ?: error("server/src/main not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension in SCANNED }.toList()
    }

    @Test
    fun `main sources hold no literal bidi control, zero-width character or line separator`() {
        val files = mainSources()
        assertTrue(files.size > MIN_FILES, "expected the server sources, found ${files.size} files")
        val hits = files.flatMap { file ->
            file.readLines().flatMapIndexed { index, line ->
                line.filter { it.code in INVISIBLE }.map { "${file.name}:${index + 1} U+%04X".format(it.code) }
            }
        }
        assertTrue(hits.isEmpty(), "write these as unicode escapes instead of literal characters: $hits")
    }

    @Test
    fun `the guarded set covers bidi controls, zero-width characters and line separators`() {
        val expected = listOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C) +
            listOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF) + listOf(0x2028, 0x2029)
        assertTrue(expected.all { it in INVISIBLE }, "the guarded set lost a character")
        assertTrue(0x0041 !in INVISIBLE && 0x00E9 !in INVISIBLE, "the guarded set must not swallow ordinary text")
    }

    private companion object {
        const val MIN_FILES = 60
        val SCANNED = setOf("kt", "kts", "yaml", "yml", "sql", "xml", "properties")

        /**
         * Bidi embeddings, overrides and isolates (U+202A-202E, U+2066-2069) and marks (U+200E, U+200F, U+061C); zero-width space and
         * joiners, word joiner and BOM (U+200B-200D, U+2060, U+FEFF); line and paragraph separators (U+2028, U+2029).
         */
        val INVISIBLE: Set<Int> = (0x202A..0x202E).toSet() + (0x2066..0x2069) + setOf(0x200E, 0x200F, 0x061C) +
            setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF) + setOf(0x2028, 0x2029)
    }
}
