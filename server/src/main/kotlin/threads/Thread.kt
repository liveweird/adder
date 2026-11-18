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
)

typealias ThreadPageResponse = PageResponse<ThreadListItem>

/** One page of a room's threads plus the pre-pagination total (the list-endpoint envelope). */
data class ThreadListResult(val items: List<ThreadListItem>, val total: Long)

/** What a create wrote: the new thread's row id and per-room [number], and its opening message's id (the audit line names all three). */
data class CreatedThread(val id: UInt, val number: Int, val messageId: UInt)

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

/** The create's own rules; the field values are the kernel's, under the room lock. Run by the route AND the service. */
fun validateThreadCreate(request: ThreadCreateRequest) {
    requireThreadTitle(request.title)
    requireMessageBody(request.body)
}
