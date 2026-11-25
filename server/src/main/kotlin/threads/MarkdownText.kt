package ch.nokillswit.threads

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.SourceSpan
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

// The Markdown pre-pass shared by the body parsers (hashtags and mentions): the text of a message body OUTSIDE the regions a token
// must not be read from. PURE (no database, no Ktor), and every pass is linear in the body: no nested quantifier, no unbounded
// backtracking, and the one bounded scan is a link destination. The SPA's parsers read the Markdown tree instead; the shared case tables
// pin the two sides together.

/** A bare URL (`http://`, `https://`, `www.`) up to the next whitespace, not glued to a word. */
private val BARE_URL = Regex("(?<![\\p{L}\\p{N}])(?:(?i:https?://)|(?i:www\\.))\\S*")

/** An `<autolink>`: a scheme and everything up to the closing `>` (an address or any other angle-bracket text stays as it is). */
private val AUTOLINK = Regex("<[A-Za-z][A-Za-z0-9+.-]{1,31}:[^<>\\s]*>")

/** A link reference definition line: `[label]: destination`. */
private val DEFINITION = Regex("(?m)^ {0,3}\\[[^\\]\\n]{1,200}]:[ \\t]*\\S*")

/** The longest destination that is blanked after `](`: it ends at the first `)` or whitespace, so the scan is bounded by it. */
private const val MAX_DESTINATION = 2_000

private val MARKDOWN_STRUCTURE = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

/**
 * [body] with every region a token (a hashtag or mention) must not be read from replaced by one space: code, raw HTML, image labels,
 * `<autolinks>`, link destinations, link reference definitions and bare URLs. CommonMark source spans identify the constructs whose nesting
 * cannot be recovered correctly with delimiters alone; the bounded single-purpose passes handle the remaining regions.
 */
internal fun textOutsideCodeAndLinks(body: String): String {
    var text = withoutNonProseNodes(body)
    text = withoutFencedCode(text)
    text = withoutInlineCode(text)
    text = AUTOLINK.replace(text, " ")
    text = withoutLinkDestinations(text)
    text = DEFINITION.replace(text, " ")
    return BARE_URL.replace(text, " ")
}

/** Indented code, raw HTML and complete image nodes, using the same CommonMark structure as the server's other Markdown reads. */
private fun withoutNonProseNodes(body: String): String {
    if (!body.hasPotentialHtml() && !body.contains("![") && !body.contains("    ") && !body.contains('\t')) return body
    val spans = ArrayList<SourceSpan>()
    MARKDOWN_STRUCTURE.parse(body).accept(
        object : AbstractVisitor() {
            override fun visit(block: IndentedCodeBlock) {
                spans.addAll(block.sourceSpans)
            }

            override fun visit(block: HtmlBlock) {
                spans.addAll(block.sourceSpans)
            }

            override fun visit(inline: HtmlInline) {
                spans.addAll(inline.sourceSpans)
            }

            override fun visit(image: Image) {
                spans.addAll(image.sourceSpans)
            }
        },
    )
    if (spans.isEmpty()) return body
    val out = StringBuilder(body.length)
    var cursor = 0
    for (span in spans.sortedBy(SourceSpan::getInputIndex)) {
        val start = span.inputIndex.coerceIn(cursor, body.length)
        val end = (span.inputIndex + span.length).coerceIn(start, body.length)
        out.append(body, cursor, start).append(' ')
        cursor = end
    }
    return out.append(body, cursor, body.length).toString()
}

/** A mention token starts `<@`; skipping those avoids parsing the large, hostile mention inputs just to learn that they are prose. */
private fun String.hasPotentialHtml(): Boolean {
    var at = indexOf('<')
    while (at >= 0) {
        if (getOrNull(at + 1) != '@') return true
        at = indexOf('<', at + 1)
    }
    return false
}

/** A fenced code block (three or more backticks or tildes, up to three spaces of indent) to its closing fence or the end, as one space. */
private fun withoutFencedCode(body: String): String {
    if (!body.contains("```") && !body.contains("~~~")) return body
    val out = StringBuilder(body.length)
    var fence: Fence? = null
    for (line in body.split('\n')) {
        val open = fence
        if (open == null) {
            val opened = fenceOf(line)
            if (opened == null) out.append(line).append('\n') else fence = opened
        } else if (closes(line, open)) {
            fence = null
            out.append(' ').append('\n')
        }
    }
    if (fence != null) out.append(' ')
    return out.toString()
}

private class Fence(val marker: Char, val length: Int)

/** The fence [line] opens, or null: a backtick fence's info string cannot hold a backtick (that line is an inline code span). */
private fun fenceOf(line: String): Fence? {
    val start = line.indexOfFirst { it != ' ' }
    if (start !in 0..3) return null
    val marker = line[start]
    if (marker != '`' && marker != '~') return null
    val length = line.drop(start).takeWhile { it == marker }.length
    if (length < 3) return null
    if (marker == '`' && line.indexOf('`', start + length) >= 0) return null
    return Fence(marker, length)
}

/** A closing fence: the same marker, at least as long as the opener, and nothing but spaces after it. */
private fun closes(line: String, fence: Fence): Boolean {
    val start = line.indexOfFirst { it != ' ' }
    if (start !in 0..3 || line[start] != fence.marker) return false
    val length = line.drop(start).takeWhile { it == fence.marker }.length
    return length >= fence.length && line.substring(start + length).isBlank()
}

/**
 * An inline code span: a run of N backticks to the next run of exactly N backticks (an unmatched run is literal). One pass collects the
 * runs and one pass right to left finds each run's nearest later run of the same length, so the scan is linear however many runs there are.
 */
private fun withoutInlineCode(text: String): String {
    if (!text.contains('`')) return text
    val starts = ArrayList<Int>()
    val lengths = ArrayList<Int>()
    var i = 0
    while (i < text.length) {
        if (text[i] == '`') {
            val begin = i
            while (i < text.length && text[i] == '`') i++
            starts += begin
            lengths += i - begin
        } else {
            i++
        }
    }
    val closer = IntArray(starts.size) { -1 }
    val nearest = HashMap<Int, Int>()
    for (run in starts.indices.reversed()) {
        closer[run] = nearest[lengths[run]] ?: -1
        nearest[lengths[run]] = run
    }
    val out = StringBuilder(text.length)
    var cursor = 0
    var run = 0
    while (run < starts.size) {
        val end = closer[run]
        if (end < 0) {
            run++
        } else {
            out.append(text, cursor, starts[run]).append(' ')
            cursor = starts[end] + lengths[end]
            run = end + 1
        }
    }
    return out.append(text, cursor, text.length).toString()
}

/** The destination of an inline link or image: `](` up to the first `)` or whitespace (a title is plain text), bounded. */
private fun withoutLinkDestinations(text: String): String {
    var at = text.indexOf("](")
    if (at < 0) return text
    val out = StringBuilder(text.length)
    var cursor = 0
    while (at >= 0) {
        val from = at + 2
        var end = from
        val limit = minOf(text.length, from + MAX_DESTINATION)
        while (end < limit && text[end] != ')' && !text[end].isWhitespace()) end++
        if (end > from) out.append(text, cursor, from).append(' ')
        if (end > from) cursor = end
        at = text.indexOf("](", maxOf(end, from))
    }
    return out.append(text, cursor, text.length).toString()
}
