package ch.nokillswit.threads

import ch.nokillswit.infra.db.FtsSegment
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalBoolean
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Full-text search over message bodies and thread titles (V16, 0.5): the result is a page of THREADS, each with its newest matching
 * messages. `chat-model.md` "Search (V16)" holds the rules.
 */

/** `q` is 2 to this many CODE POINTS after trimming (an emoji is one, though two UTF-16 units). */
const val MIN_SEARCH_QUERY_LENGTH = 2
const val MAX_SEARCH_QUERY_LENGTH = 100

/** The page ceiling of a search (the shared maximum is 100): a hit carries up to three snippets. */
const val MAX_SEARCH_PAGE_SIZE = 20

/** How many of a thread's newest matching messages a hit carries; `matchCount` counts them all. */
const val SEARCH_MESSAGES_PER_THREAD = 3

/** The sortable whitelist of a search and its default: the most recently active thread first (ties by `id`). */
val SEARCH_SORT_FIELDS: Set<String> = setOf("lastActivityAt", "createdAt", "id")
val SEARCH_DEFAULT_SORT: List<SortField> = listOf(SortField("lastActivityAt", descending = true))

/** One run of a snippet: plain [text], never markup (the client renders it as text), and [hit] when it matched the query. */
@Serializable
data class SnippetSegment(val text: String, val hit: Boolean)

/** One matching live message of a thread: who, when, and a fragment of its Markdown source as plain text. */
@Serializable
data class MessageSearchHit(
    val id: UInt,
    val authorId: UInt,
    val authorName: String,
    val createdAt: Long,
    val snippet: List<SnippetSegment>,
)

/**
 * One matching thread. [status] is the status KEY (the SPA draws the pill where it holds the workflow). [messages] holds the
 * [SEARCH_MESSAGES_PER_THREAD] newest matching live messages, newest first, and [matchCount] counts them all; a title-only hit has no
 * messages and a count of 0. [titleSnippet] is the whole title, its hits marked.
 */
@Serializable
data class ThreadSearchHit(
    val roomId: UInt,
    val roomName: String,
    val roomArchived: Boolean,
    val threadNumber: Int,
    val title: String,
    val titleSnippet: List<SnippetSegment>,
    val status: String,
    val closed: Boolean,
    val ownerId: UInt,
    val ownerName: String,
    val messageCount: Int,
    val lastActivityAt: Long,
    val matchCount: Long,
    val messages: List<MessageSearchHit>,
)

typealias ThreadSearchPageResponse = PageResponse<ThreadSearchHit>

/** One page of matching threads plus the total of matching threads. */
data class ThreadSearchResult(val items: List<ThreadSearchHit>, val total: Long)

internal fun FtsSegment.toWire() = SnippetSegment(text, hit)

/**
 * The validated `q`: trimmed, a control character is a `400` ([sanitizeSingleLine]), 2 to 100 code points, and at least one letter or
 * digit (`!!!` has no lexeme and could match nothing). The `400` details never carry the term: a search term reaches no log line, no
 * audit line and no problem body beyond the requester's own.
 */
fun validateSearchQuery(raw: String?): String {
    val q = sanitizeSingleLine(raw ?: throw BadRequestException("q is required"), "q")
    val length = q.codePointCount(0, q.length)
    if (length < MIN_SEARCH_QUERY_LENGTH || length > MAX_SEARCH_QUERY_LENGTH) {
        throw BadRequestException("q must be $MIN_SEARCH_QUERY_LENGTH to $MAX_SEARCH_QUERY_LENGTH characters after trimming")
    }
    if (q.codePoints().noneMatch { Character.isLetterOrDigit(it) }) throw BadRequestException("q must contain a letter or a digit")
    return q
}

/** The parameters of a search request, validated: the query, the strict `closed` boolean (null = both) and the page. */
class SearchParams(val q: String, val closed: Boolean?, val paging: PageRequest)

/**
 * Reads and validates `q`, `closed`, `page`, `pageSize` (at most [MAX_SEARCH_PAGE_SIZE]) and `sort` of a search; every violation is a `400`
 * and none of them names the term. A repeated key is a `400` (`singleValue`).
 */
fun ApplicationCall.parseSearchParams(): SearchParams {
    val paging = parsePaging(sortable = SEARCH_SORT_FIELDS, defaultSort = SEARCH_DEFAULT_SORT)
    if (paging.pageSize > MAX_SEARCH_PAGE_SIZE) throw BadRequestException("pageSize must be between 1 and $MAX_SEARCH_PAGE_SIZE")
    val params = request.queryParameters
    return SearchParams(
        q = validateSearchQuery(params.optionalString("q")),
        closed = params.optionalBoolean("closed"),
        paging = paging,
    )
}
