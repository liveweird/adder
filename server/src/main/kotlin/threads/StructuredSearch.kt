package ch.nokillswit.threads

import ch.nokillswit.infra.paging.positiveId
import ch.nokillswit.workflows.requireKey
import io.ktor.server.plugins.BadRequestException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException

internal const val MAX_STRUCTURED_SEARCH_CODE_POINTS = 512
internal const val MAX_STRUCTURED_SEARCH_CLAUSES = 24

internal enum class SearchTextKind { WORD, PHRASE, PREFIX }

internal data class SearchTextClause(val value: String, val kind: SearchTextKind, val excluded: Boolean)

internal data class SearchReference(val id: UInt? = null, val name: String? = null) {
    init {
        require((id == null) != (name == null)) { "A search reference must have exactly one representation" }
    }
}

internal data class StructuredSearch(
    val text: List<SearchTextClause>,
    val rooms: List<SearchReference>,
    val authors: List<SearchReference>,
    val participants: List<SearchReference>,
    val owners: List<SearchReference>,
    val statuses: List<String>,
    val hashtags: List<String>,
    val startMillis: Long?,
    val endMillis: Long?,
    val pinned: Boolean,
    val link: Boolean,
    val reactions: List<String?>,
    val emojis: List<String>,
)

private const val INVALID_QUERY = "Invalid structured search query"
private const val INVALID_TIME_ZONE = "Invalid search time zone"
private const val MAX_REFERENCE_CODE_POINTS = 100

private data class Lexeme(val value: String, val quoteStart: Int?)

private data class MutableStructuredSearch(
    val text: MutableList<SearchTextClause> = mutableListOf(),
    val rooms: MutableList<SearchReference> = mutableListOf(),
    val authors: MutableList<SearchReference> = mutableListOf(),
    val participants: MutableList<SearchReference> = mutableListOf(),
    val owners: MutableList<SearchReference> = mutableListOf(),
    val statuses: MutableList<String> = mutableListOf(),
    val hashtags: MutableList<String> = mutableListOf(),
    var startMillis: Long? = null,
    var endMillis: Long? = null,
    var pinned: Boolean = false,
    var link: Boolean = false,
    val reactions: MutableList<String?> = mutableListOf(),
    val emojis: MutableList<String> = mutableListOf(),
) {
    fun immutable() = StructuredSearch(
        text = text,
        rooms = rooms,
        authors = authors,
        participants = participants,
        owners = owners,
        statuses = statuses,
        hashtags = hashtags,
        startMillis = startMillis,
        endMillis = endMillis,
        pinned = pinned,
        link = link,
        reactions = reactions,
        emojis = emojis,
    )
}

/**
 * Parses the bounded query language into values the search service can bind without interpreting raw user text. Every rejection is a
 * fixed-detail `400`: a query is user content and must not appear in a problem body (`security.md` "Search").
 */
internal fun parseStructuredSearch(raw: String?, timeZone: String?): StructuredSearch {
    val zone = parseZone(timeZone)
    val sent = raw.orEmpty()
    if (sent.hasForbiddenCodePoint()) invalidQuery()
    val query = sent.trim()
    if (query.codePointCount(0, query.length) > MAX_STRUCTURED_SEARCH_CODE_POINTS) invalidQuery()

    val lexemes = tokenize(query)
    if (lexemes.size > MAX_STRUCTURED_SEARCH_CLAUSES) invalidQuery()

    val result = MutableStructuredSearch()
    lexemes.forEach { result.add(it, zone) }
    return result.immutable()
}

private fun parseZone(raw: String?): ZoneId = try {
    val value = raw?.takeIf { it.isNotBlank() } ?: "UTC"
    if (value != "UTC" && value !in ZoneId.getAvailableZoneIds()) throw DateTimeException("Not an IANA zone")
    ZoneId.of(value)
} catch (_: DateTimeException) {
    throw BadRequestException(INVALID_TIME_ZONE)
}

private fun tokenize(query: String): List<Lexeme> {
    if (query.isEmpty()) return emptyList()
    val tokens = mutableListOf<Lexeme>()
    var index = 0
    while (index < query.length) {
        while (index < query.length && query[index].isWhitespace()) index++
        val (lexeme, nextIndex) = readLexeme(query, index)
        tokens += lexeme
        index = nextIndex
    }
    return tokens
}

private fun readLexeme(query: String, start: Int): Pair<Lexeme, Int> {
    val value = StringBuilder()
    var quoteStart: Int? = null
    var inQuote = false
    var index = start
    while (index < query.length && (inQuote || !query[index].isWhitespace())) {
        val char = query[index]
        when {
            char == '\\' -> {
                val escaped = query.getOrNull(index + 1)
                if (escaped != '\\' && escaped != '"') invalidQuery()
                value.append(escaped)
                index += 2
            }
            char == '"' && !inQuote -> {
                if (!canOpenQuote(value)) invalidQuery()
                quoteStart = value.length
                inQuote = true
                index++
            }
            char == '"' -> {
                index++
                if (index < query.length && !query[index].isWhitespace()) invalidQuery()
                return Lexeme(value.toString(), quoteStart) to index
            }
            else -> {
                value.append(char)
                index++
            }
        }
    }
    if (inQuote) invalidQuery()
    return Lexeme(value.toString(), quoteStart) to index
}

private fun canOpenQuote(value: StringBuilder): Boolean =
    value.isEmpty() || value.contentEquals("-") || value.lastOrNull() == ':'

private fun MutableStructuredSearch.add(lexeme: Lexeme, zone: ZoneId) {
    val phrase = lexeme.quoteStart == 0 || lexeme.quoteStart == 1 && lexeme.value.startsWith('-')
    if (phrase) {
        val excluded = lexeme.quoteStart == 1
        val value = if (excluded) lexeme.value.drop(1) else lexeme.value
        if (value.isBlank()) invalidQuery()
        text += SearchTextClause(value, SearchTextKind.PHRASE, excluded)
        return
    }

    val separator = lexeme.value.indexOf(':')
    if (separator < 0) {
        text += textClause(lexeme.value)
        return
    }

    val operator = lexeme.value.substring(0, separator).lowercase()
    val operand = lexeme.value.substring(separator + 1)
    val quotedOperand = lexeme.quoteStart == separator + 1
    when (operator) {
        "in" -> rooms += reference(operand, quotedOperand)
        "from" -> authors += reference(operand, quotedOperand)
        "with" -> participants += reference(operand, quotedOperand)
        "owner" -> owners += reference(operand, quotedOperand)
        "status" -> statuses += statusKey(operand)
        "hashtag" -> hashtags += hashtagKey(operand)
        "on" -> dateRange(operand, zone).also { intersect(it.first, it.second) }
        "before" -> intersect(null, dateStart(operand, zone))
        "after" -> intersect(dateStart(operand, zone, plusDays = 1), null)
        "during" -> duringRange(operand, zone).also { intersect(it.first, it.second) }
        "has" -> addHas(operand)
        else -> invalidQuery()
    }
}

private fun statusKey(value: String): String = try {
    requireKey(value, "Status key")
    value
} catch (_: BadRequestException) {
    invalidQuery()
}

private fun hashtagKey(value: String): String = try {
    requireTagFilter(value.removePrefix("#"))
} catch (_: BadRequestException) {
    invalidQuery()
}

private fun textClause(raw: String): SearchTextClause {
    val excluded = raw.startsWith('-')
    val term = if (excluded) raw.drop(1) else raw
    if ('*' !in term) {
        if (term.codePoints().noneMatch(Character::isLetterOrDigit)) invalidQuery()
        return SearchTextClause(term, SearchTextKind.WORD, excluded)
    }
    if (!term.endsWith('*')) invalidQuery()
    val prefix = term.dropLast(1)
    if (prefix.codePointCount(0, prefix.length) < 2 || !prefix.codePoints().allMatch(Character::isLetterOrDigit)) invalidQuery()
    return SearchTextClause(prefix, SearchTextKind.PREFIX, excluded)
}

private fun reference(value: String, quoted: Boolean): SearchReference {
    if (value.isEmpty() || value.codePointCount(0, value.length) > MAX_REFERENCE_CODE_POINTS) invalidQuery()
    if (!quoted) {
        val numeric = value.removePrefix("#")
        if (value.startsWith('#') || value.all { it in '0'..'9' }) {
            val id = positiveId(numeric)?.toUInt() ?: invalidQuery()
            return SearchReference(id = id)
        }
    }
    if (value.isBlank()) invalidQuery()
    return SearchReference(name = value)
}

private fun MutableStructuredSearch.addHas(value: String) {
    when {
        value == "pin" -> pinned = true
        value == "link" -> link = true
        value == "reaction" -> reactions += null
        value.startsWith("reaction:") -> reactions += requiredEmoji(value.substringAfter(':'))
        value.startsWith("emoji:") -> emojis += requiredEmoji(value.substringAfter(':'))
        else -> invalidQuery()
    }
}

private fun requiredEmoji(value: String): String {
    if (!isSingleEmoji(value)) invalidQuery()
    return value
}

private fun MutableStructuredSearch.intersect(start: Long?, end: Long?) {
    if (start != null) startMillis = maxOf(startMillis ?: start, start)
    if (end != null) endMillis = minOf(endMillis ?: end, end)
}

private fun dateRange(value: String, zone: ZoneId): Pair<Long, Long> {
    val start = dateStart(value, zone)
    val end = dateStart(value, zone, plusDays = 1)
    return start to end
}

private fun dateStart(value: String, zone: ZoneId, plusDays: Long = 0): Long {
    if (!DATE.matches(value)) invalidQuery()
    return try {
        LocalDate.parse(value).plusDays(plusDays).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        invalidQuery()
    }
}

private fun duringRange(value: String, zone: ZoneId): Pair<Long, Long> = try {
    when {
        MONTH.matches(value) -> YearMonth.parse(value).let {
            it.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                it.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        YEAR.matches(value) -> Year.parse(value).let {
            it.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
                it.plusYears(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        else -> invalidQuery()
    }
} catch (_: DateTimeException) {
    invalidQuery()
}

private fun String.hasForbiddenCodePoint(): Boolean = codePoints().anyMatch { codePoint ->
    codePoint in 0x00..0x1F || codePoint in 0x7F..0x9F || codePoint in HIDDEN_FORMATTING
}

private fun invalidQuery(): Nothing = throw BadRequestException(INVALID_QUERY)

private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val MONTH = Regex("[0-9]{4}-[0-9]{2}")
private val YEAR = Regex("[0-9]{4}")
private val HIDDEN_FORMATTING = setOf(0x061C, 0x200E, 0x200F, 0x2028, 0x2029) + (0x202A..0x202E) + (0x2066..0x2069)
