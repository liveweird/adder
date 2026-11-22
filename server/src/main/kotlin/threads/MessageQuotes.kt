package ch.nokillswit.threads

import ch.nokillswit.infra.db.active
import ch.nokillswit.threads.MessageService.Messages
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.substring
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * What a quote carries of the quoted message's body, at most (`chat-model.md` "Quotes (1.3)" "The read"): the cap of the wire, in code
 * points.
 */
const val MAX_QUOTED_BODY = 1_000

/** A longer body is cut back to the last whitespace inside the final this-many code points, so no word, link or mention token is halved. */
private const val QUOTE_CUT_WINDOW = 100

/** What the SQL brings of a body: one past the cap, so the cut can tell "exactly at the cap" from "longer" (the rule runs in Kotlin). */
private const val QUOTED_BODY_FETCH = MAX_QUOTED_BODY + 1

/** The cut of a quoted body: [text] is what travels, [truncated] says the body was longer. */
internal data class CutBody(val text: String, val truncated: Boolean)

/**
 * The ONE cut of a quoted body, whatever its source (a row of the page, or the first [QUOTED_BODY_FETCH] characters the SQL brought): at
 * most [MAX_QUOTED_BODY] code points; when longer and the code point after the cap is whitespace, exactly [MAX_QUOTED_BODY] (the cut is
 * already on a word end); otherwise back to the last whitespace inside the final [QUOTE_CUT_WINDOW] (the whitespace and what trails it
 * go); with no whitespace there, exactly [MAX_QUOTED_BODY]. Works on code points, so a surrogate pair is never halved.
 */
internal fun cutQuotedBody(body: String): CutBody {
    if (body.codePointCount(0, body.length) <= MAX_QUOTED_BODY) return CutBody(body, false)
    val end = body.offsetByCodePoints(0, MAX_QUOTED_BODY)
    // The cut already falls on a word end when the next code point is whitespace: keep the full 1,000.
    if (Character.isWhitespace(body.codePointAt(end))) return CutBody(body.substring(0, end), true)
    val windowStart = body.offsetByCodePoints(0, MAX_QUOTED_BODY - QUOTE_CUT_WINDOW)
    var at = end
    while (at > windowStart) {
        val cp = body.codePointBefore(at)
        at -= Character.charCount(cp)
        if (Character.isWhitespace(cp)) return CutBody(body.substring(0, at).trimEnd(), true)
    }
    return CutBody(body.substring(0, end), true)
}

/** A message as a quote of it reads it: its place, its author, whether it is a tombstone now and its body already cut. */
internal class QuotedMessage(
    val id: UInt,
    val threadId: UInt,
    val authorId: UInt,
    val deleted: Boolean,
    val body: CutBody,
    /** Epoch millis of its last edit; null while as posted and on a tombstone. */
    val editedAt: Long?,
) {
    /** Whether its (cut) body may hold mention tokens: only then are its mention lists read at all. */
    val hasTokens: Boolean get() = !deleted && body.text.contains("<@")

    /** The wire form; [mentions] and [groups] are used only when a `<@` token survived the cut, so every source answers alike. */
    fun toQuote(authorName: String, mentions: List<MentionedUser>, groups: List<GroupMention>): MessageQuote = MessageQuote(
        messageId = id,
        authorId = authorId,
        authorName = authorName,
        deleted = deleted,
        body = if (deleted) null else body.text,
        truncated = !deleted && body.truncated,
        editedAt = if (deleted) null else editedAt,
        mentions = if (hasTokens) mentions else emptyList(),
        groupMentions = if (hasTokens) groups else emptyList(),
    )
}

/** A row of [Messages] as a quoted message; [bodyOf] is the body column or the SQL's bounded head of it. */
private fun ResultRow.toQuoted(bodyOf: Expression<String> = Messages.body): QuotedMessage {
    val deleted = this[Messages.markedAsDeleted]
    return QuotedMessage(
        this[Messages.id].value,
        this[Messages.threadId].value,
        this[Messages.authorId].value,
        deleted,
        if (deleted) CutBody("", false) else cutQuotedBody(this[bodyOf]),
        if (deleted) null else this[Messages.editedAt],
    )
}

/**
 * The quote check of a post, ONE statement under the room lock: message [quotedId] of thread [threadId], live. An unknown id, a message
 * of another thread or room and a tombstone are the same empty answer, the typed `400` ([quoteUnavailable]); nothing is written. Only
 * the first [QUOTED_BODY_FETCH] characters of the body travel.
 */
internal suspend fun checkedQuote(threadId: UInt, quotedId: UInt): QuotedMessage {
    val head = Messages.body.substring(1, QUOTED_BODY_FETCH)
    val row = Messages.select(Messages.id, Messages.authorId, Messages.editedAt, head)
        .where { (Messages.id eq quotedId) and (Messages.threadId eq threadId) and Messages.active() }
        .toList().singleOrNull() ?: throw quoteUnavailable()
    return QuotedMessage(quotedId, threadId, row[Messages.authorId].value, false, cutQuotedBody(row[head]), row[Messages.editedAt])
}

/**
 * The quoted messages of a page of [rows] (rows of [Messages]), by the id of the QUOTING row, for the rows that have a quote that counts:
 * a live quoting row (a tombstone quotes nothing) whose quoted row is of the SAME thread (anything else reads as no quote: the leak rule
 * of the read). The quoted rows are taken from the page's own rows first; ONE `id IN (...)` read brings the rest and none is sent when
 * the page needs none. Runs INSIDE the caller's transaction.
 */
internal suspend fun quotedMessagesOf(rows: List<ResultRow>): Map<UInt, QuotedMessage> {
    val asking = rows.filter { !it[Messages.markedAsDeleted] && it[Messages.quotedMessageId] != null }
    if (asking.isEmpty()) return emptyMap()
    val wanted = asking.mapNotNull { it[Messages.quotedMessageId]?.value }.toSet()
    val known = rows.filter { it[Messages.id].value in wanted }.map { it.toQuoted() }.associateBy { it.id }.toMutableMap()
    val missing = wanted - known.keys
    if (missing.isNotEmpty()) {
        val head = Messages.body.substring(1, QUOTED_BODY_FETCH)
        // Scoped to the threads of the quoting rows in SQL too, so a planted foreign reference does not even bring its text over.
        val threads = asking.map { it[Messages.threadId].value }.toSet()
        Messages.select(Messages.id, Messages.threadId, Messages.authorId, Messages.markedAsDeleted, Messages.editedAt, head)
            .where { (Messages.id inList missing) and (Messages.threadId inList threads) }
            .toList().map { it.toQuoted(head) }.forEach { known[it.id] = it }
    }
    return asking.mapNotNull { row ->
        val quoted = known[row[Messages.quotedMessageId]?.value]?.takeIf { it.threadId == row[Messages.threadId].value }
        quoted?.let { row[Messages.id].value to it }
    }.toMap()
}

/**
 * The author of the message that message [messageId] quotes, or null when it quotes none: ONE joined read of the message's
 * `quoted_message_id` and that row's author, asked by an edit only past its early returns (an edit never changes a quote, but the author
 * of the quoted message is one of the people its new mentions are weighed against, `NoticeAudience.ofEdit`). A quoted message that was
 * deleted since still has its author. Runs INSIDE the caller's transaction.
 */
internal suspend fun authorQuotedBy(messageId: UInt): UInt? {
    val quoted = Messages.alias("quoted")
    return Messages.join(quoted, JoinType.INNER, Messages.quotedMessageId, quoted[Messages.id])
        .select(quoted[Messages.authorId])
        .where { Messages.id eq messageId }
        .toList().singleOrNull()?.get(quoted[Messages.authorId])?.value
}
