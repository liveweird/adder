package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeMultiLine
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Threads (V14): the unit of talk in a room. A thread has a per-room number, a title, a workflow status, an owner and typed field
 * values, and every message lives in one. `chat-model.md` "Threads and messages (0.3)" holds the rules. Never declare a class named
 * `Thread` in this package: it would shadow `java.lang.Thread`.
 */
const val MAX_THREAD_TITLE_LENGTH = 200

/** A message body is Markdown: 1 to this many UTF-16 units after [sanitizeMultiLine]. */
const val MAX_MESSAGE_BODY_LENGTH = 8000

/**
 * `POST /rooms/{id}/threads`: a title, the opening message and the field values (a `null` value means unset; every required field must
 * be supplied; the kernel, `ThreadFields.kt`, judges them against the room's field definitions).
 */
@Serializable
data class ThreadCreateRequest(
    val title: String,
    val body: String,
    val fields: Map<String, JsonElement> = emptyMap(),
)

/**
 * `PUT /rooms/{id}/threads/{number}` under `X-Expected-Revision`: the title and the field values to MERGE over the stored ones (a key
 * left out is untouched, a `null` value clears the field; the kernel, `ThreadFields.kt`, judges them). Nothing changed is a `204`
 * with no revision bump, no history entry and no activity bump.
 */
@Serializable
data class ThreadUpdateRequest(
    val title: String,
    val fields: Map<String, JsonElement> = emptyMap(),
)

/** One list row. The status label and colour come from the room workflow the SPA already holds. */
@Serializable
data class ThreadListItem(
    val id: UInt,
    val number: Int,
    val title: String,
    /** The status KEY. */
    val status: String,
    val closed: Boolean,
    val ownerId: UInt,
    val ownerName: String,
    val messageCount: Int,
    val lastActivityAt: Long,
    val createdAt: Long,
    /** Messages the CALLER has not read, 0 to [MAX_UNREAD_COUNT] (that many is "that many or more"); 0 for someone who is no member. */
    val unreadCount: Int,
    /** The CALLER watches the thread (`chat-model.md` "Watching (V26)"); false for someone who is no member. */
    val watching: Boolean,
    /** The thread's hashtags (V15 `message_tags`): the folded keys of its live messages, alphabetical, at most [MAX_THREAD_HASHTAGS]. */
    val hashtags: List<String>,
)

@Serializable
data class ThreadResponse(
    val id: UInt,
    val number: Int,
    val title: String,
    /** The status KEY. */
    val status: String,
    /** The status is in a CLOSED category: the thread takes no further activity until it is reopened. */
    val closed: Boolean,
    val closedAt: Long?,
    val ownerId: UInt,
    val ownerName: String,
    val createdById: UInt,
    val createdByName: String,
    /** ACTIVE fields only (a retired field keeps its stored value, hidden); values in their stored forms (`ThreadFields.kt`). */
    val fields: Map<String, JsonElement>,
    /** A read-only `{userId: name}` map for the USER values in [fields] (display names, API-STRUCT-003). */
    val fieldUserNames: Map<String, String>,
    /** Every message, the opener and tombstones included; it never goes down. */
    val messageCount: Int,
    /** The optimistic-concurrency token of the details PUT (`X-Expected-Revision`); starts at 1. */
    val revision: Long,
    val lastActivityAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** Messages the CALLER has not read, 0 to [MAX_UNREAD_COUNT]; 0 for someone who is no member. */
    val unreadCount: Int,
    /** The first of those messages (of the newest [MAX_UNREAD_COUNT] unread), null when none is unread or the caller is no member. */
    val firstUnreadMessageId: UInt?,
    /** The CALLER watches the thread; false for someone who is no member. */
    val watching: Boolean,
    /** How many active members watch the thread (the list of them is not on the wire). */
    val watcherCount: Int,
    /** The thread's hashtags, as on a list row ([ThreadListItem.hashtags]): the SPA's header and side panel show them as filter links. */
    val hashtags: List<String>,
)

typealias ThreadPageResponse = PageResponse<ThreadListItem>

/** One page of a room's threads plus the pre-pagination total (the list-endpoint envelope). */
data class ThreadListResult(val items: List<ThreadListItem>, val total: Long)

/**
 * What a create wrote: the new thread's row id and per-room [number], and its opening message's id (the audit line names all three).
 * [notices] is what the opening message's followers told people about it ([MessageFollower]).
 */
data class CreatedThread(val id: UInt, val number: Int, val messageId: UInt, val notices: NoticeOutcome = NoticeOutcome.NONE)

/** Trims the title and normalises the body (a control or hidden direction character is a 400, `sanitizeSingleLine`/`sanitizeMultiLine`). */
fun sanitizedThreadCreate(request: ThreadCreateRequest): ThreadCreateRequest = request.copy(
    title = sanitizeSingleLine(request.title, "Title"),
    body = sanitizeMultiLine(request.body, "Body"),
)

/** A thread title: 1 to [MAX_THREAD_TITLE_LENGTH] characters after trimming. */
fun requireThreadTitle(title: String) {
    if (title.isBlank() || title.length > MAX_THREAD_TITLE_LENGTH) {
        throw BadRequestException("Title must be 1-$MAX_THREAD_TITLE_LENGTH characters")
    }
}

/** A message body: 1 to [MAX_MESSAGE_BODY_LENGTH] UTF-16 units (already through [sanitizeMultiLine]). */
fun requireMessageBody(body: String) {
    if (body.isEmpty() || body.length > MAX_MESSAGE_BODY_LENGTH) {
        throw BadRequestException("Body must be 1-$MAX_MESSAGE_BODY_LENGTH characters")
    }
}

/**
 * The create's own rules plus the SHAPE of `fields` ([requireFieldShape]); the field values are the kernel's, under the room lock. Run by
 * the route AND the service, before the room lock.
 */
fun validateThreadCreate(request: ThreadCreateRequest) {
    requireThreadTitle(request.title)
    requireMessageBody(request.body)
    requireFieldShape(request.fields)
}

/** Trims the title (a control or hidden direction character is a 400, `sanitizeSingleLine`); the field values are the kernel's. */
fun sanitizedThreadUpdate(request: ThreadUpdateRequest): ThreadUpdateRequest =
    request.copy(title = sanitizeSingleLine(request.title, "Title"))

/** The details PUT's own rules plus the SHAPE of `fields`; the values are the kernel's. Run by the route AND the service. */
fun validateThreadUpdate(request: ThreadUpdateRequest) {
    requireThreadTitle(request.title)
    requireFieldShape(request.fields)
}

/** What a details write changed, for the route's audit line: the row id, the NEW [revision], the title flag and the changed field keys. */
data class ThreadChange(val threadId: UInt, val revision: Long, val titleChanged: Boolean, val fieldKeys: List<String>)

/** `PUT .../threads/{number}/status`: the KEY of an ACTIVE status of the room (an unknown or retired one is a `400`). */
@Serializable
data class ThreadStatusRequest(val status: String)

/** `PUT .../threads/{number}/owner`: the id of an ACTIVE member of the room (anyone else is a `400`, under the room lock). */
@Serializable
data class ThreadOwnerRequest(val ownerId: UInt)
