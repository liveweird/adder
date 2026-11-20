package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeMultiLine
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
    /** At most [MAX_DISTINCT_REACTIONS], in the order each emoji first appeared; empty on a tombstone (`chat-model.md`). */
    val reactions: List<ReactionSummary>,
)

/** A person a message mentions: the id as written in the token and their CURRENT display name (a mention of someone who left keeps it). */
@Serializable
data class MentionedUser(val userId: UInt, val name: String)

/**
 * One emoji on a message: how many people chose it, whether the READER did, and the first three of them by time ([names]: display
 * names, soft-deleted accounts included). The full list of reactors is not part of the wire.
 */
@Serializable
data class ReactionSummary(val emoji: String, val count: Int, val reacted: Boolean, val names: List<String>)

/** `PUT /rooms/{id}/messages/{messageId}/reaction`: the caller's own state of ONE [emoji] ([reacted] true adds, false removes). */
@Serializable
data class ReactionRequest(val emoji: String, val reacted: Boolean)

typealias MessagePageResponse = PageResponse<MessageResponse>

/** One page of a thread's messages plus the total AFTER the `id[lt]` filter (the list-endpoint envelope, keyset inside it). */
data class MessageListResult(val items: List<MessageResponse>, val total: Long)

/** `POST .../threads/{number}/messages` and `PUT /rooms/{id}/messages/{messageId}`: the Markdown [body]. */
@Serializable
data class MessageRequest(val body: String)

/**
 * What a post wrote, for the route's `Location` header and audit line: the new message and its thread's row id. [notified] are the people
 * the message's followers told about it ([MessageFollower]); never part of a response body.
 */
data class PostedMessage(val threadId: UInt, val message: MessageResponse, val notified: List<UInt> = emptyList())

/** What an edit changed, for the route's audit line (null when the body was unchanged); [notified]: see [PostedMessage]. */
data class MessageEdit(val threadId: UInt, val messageId: UInt, val notified: List<UInt> = emptyList())

/** What a delete wrote, for the route's audit line: whose message it was and whether a moderator (not the author) removed it. */
data class MessageRemoval(val threadId: UInt, val messageId: UInt, val authorId: UInt, val moderated: Boolean)

/** Normalises the body (CRLF to LF, a control or hidden direction character is a 400, `sanitizeMultiLine`). */
fun sanitizedMessage(request: MessageRequest): MessageRequest = request.copy(body = sanitizeMultiLine(request.body, "Body"))

/** The message rules shared by the post and the edit; run by the route AND the service. */
fun validateMessage(request: MessageRequest) = requireMessageBody(request.body)
