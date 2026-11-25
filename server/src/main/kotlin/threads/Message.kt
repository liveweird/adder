package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeMultiLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * One message of a thread (V14 `messages`). A deleted message is a TOMBSTONE: `body` is null, [deleted] is true and
 * [deletedByModerator] says whose hand removed it (a room OWNER or an ADMIN, not the author), for the "Removed by a room owner or an
 * administrator" line. [editedAt] is null while the body is as posted. `chat-model.md` "Threads and messages (0.3)" holds the rules.
 */
@Serializable
data class MessageResponse(
    val id: UInt,
    /** The per-room number of the thread the message belongs to. */
    val threadNumber: Int,
    val authorId: UInt,
    val authorName: String,
    /** Markdown, stored raw; null on a tombstone. */
    val body: String?,
    val deleted: Boolean,
    /** Always a boolean: `deleted_by` differs from the author (an author's own delete is false). */
    val deletedByModerator: Boolean,
    val editedAt: Long?,
    val createdAt: Long,
    /** The room members the body mentions with `<@id>`, by current name (at most 20, by user id; empty on a tombstone). */
    val mentions: List<MentionedUser>,
    /**
     * The group mentions of the body (`<@team:id>`, `<@watchers>`, `<@everyone>`) that took effect, at most 7 (5 teams, 2 keywords), teams
     * by id then the keywords; empty on a tombstone. A group token not listed here is plain text.
     */
    val groupMentions: List<GroupMention>,
    /** At most [MAX_DISTINCT_REACTIONS], in the order each emoji first appeared; empty on a tombstone (`chat-model.md`). */
    val reactions: List<ReactionSummary>,
    /** The message of the same thread this one quotes, as it reads NOW; null when it quotes nothing and on a tombstone ("Quotes (1.3)"). */
    val quote: MessageQuote?,
)

/**
 * The quoted message as it reads now (1.3; API-STRUCT-003's read-only summary of a related resource, the write refers to it by id).
 * [deleted]: it is a tombstone now, and then [body] is null, [truncated] false and both lists empty. [body] is its CURRENT Markdown, at
 * most [MAX_QUOTED_BODY] code points ([truncated]: it was cut); [editedAt] is when it was last edited (null while as posted and when
 * deleted), so a client can mark a quote whose text changed after it was quoted; [mentions] and [groupMentions] are the quoted
 * message's own, so the excerpt can show names (empty when no `<@` token survived the cut).
 */
@Serializable
data class MessageQuote(
    val messageId: UInt,
    val authorId: UInt,
    val authorName: String,
    val deleted: Boolean,
    val body: String?,
    val truncated: Boolean,
    val editedAt: Long?,
    val mentions: List<MentionedUser>,
    val groupMentions: List<GroupMention>,
)

/** A person a message mentions: the id as written in the token and their CURRENT display name (a mention of someone who left keeps it). */
@Serializable
data class MentionedUser(val userId: UInt, val name: String)

/**
 * One emoji on a message: how many people chose it, whether the READER did, and the first three of them by time ([names]: display
 * names, soft-deleted accounts included). [key] opens the separately paged full roster without putting the emoji in a URL.
 */
@Serializable
data class ReactionSummary(val emoji: String, val key: String, val count: Int, val reacted: Boolean, val names: List<String>)

/** One person in the paged full roster for a message reaction. */
@Serializable
data class ReactorResponse(val userId: UInt, val name: String)

typealias ReactorPageResponse = PageResponse<ReactorResponse>

data class ReactorListResult(val items: List<ReactorResponse>, val total: Long)

/** `PUT /rooms/{id}/messages/{messageId}/reaction`: the caller's own state of ONE [emoji] ([reacted] true adds, false removes). */
@Serializable
data class ReactionRequest(val emoji: String, val reacted: Boolean)

typealias MessagePageResponse = PageResponse<MessageResponse>

/**
 * A bounded chronological window around [messageId]. [older] and [newer] say that more messages exist outside [items], so a client can
 * replace this window by asking for context around its first or last item. The target is included even when it is a tombstone.
 */
@Serializable
data class MessageContextResponse(
    val messageId: UInt,
    val items: List<MessageResponse>,
    val older: Boolean,
    val newer: Boolean,
)

/** One page of a thread's messages plus the total AFTER the `id[lt]` filter (the list-endpoint envelope, keyset inside it). */
data class MessageListResult(val items: List<MessageResponse>, val total: Long)

/** `PUT /rooms/{id}/messages/{messageId}` (the edit): the Markdown [body]. The edit cannot name a quote: an unknown member is a `400`. */
@Serializable
data class MessageRequest(val body: String)

/**
 * `POST .../threads/{number}/messages`: the Markdown [body] and, optionally, the message of the SAME thread it quotes
 * ([quotedMessageId], 1 to 2147483647; `chat-model.md` "Quotes (1.3)"). A separate body from the edit's [MessageRequest] keeps a quote
 * out of every edit with no code.
 */
@Serializable
data class MessagePostRequest(val body: String, val quotedMessageId: UInt? = null) {
    /** The body half, for the shared message rules and the sanitiser. */
    fun message(): MessageRequest = MessageRequest(body)
}

/**
 * What a post wrote, for the route's `Location` header and audit line: the new message and its thread's row id. [notices] is what
 * the message's followers told about it ([MessageFollower]); never part of a response body.
 */
data class PostedMessage(val threadId: UInt, val message: MessageResponse, val notices: NoticeOutcome = NoticeOutcome.NONE)

/** What an edit changed, for the route's audit line (null when the body was unchanged); [notices]: see [PostedMessage]. */
data class MessageEdit(val threadId: UInt, val messageId: UInt, val notices: NoticeOutcome = NoticeOutcome.NONE)

/** What a delete wrote, for the route's audit line: whose message it was and whether a moderator (not the author) removed it. */
data class MessageRemoval(val threadId: UInt, val messageId: UInt, val authorId: UInt, val moderated: Boolean)

/** Normalises the body (CRLF to LF, a control or hidden direction character is a 400, `sanitizeMultiLine`). */
fun sanitizedMessage(request: MessageRequest): MessageRequest = request.copy(body = sanitizeMultiLine(request.body, "Body"))

/** The message rules shared by the post and the edit; run by the route AND the service. */
fun validateMessage(request: MessageRequest) = requireMessageBody(request.body)

/** A quoted id outside 1 to 2147483647 (message ids are int32 sequences) is a plain `400`, the read marker's shape; route AND service. */
fun validateQuotedMessageId(quotedMessageId: UInt?) {
    if (quotedMessageId != null && (quotedMessageId == 0u || quotedMessageId > Int.MAX_VALUE.toUInt())) {
        throw BadRequestException("quotedMessageId must be a message id")
    }
}
