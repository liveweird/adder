package ch.nokillswit.threads

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/** The most pins a room holds (`chat-model.md` "Pins"): a 51st is a `409 pin-limit`. */
const val MAX_ROOM_PINS = 50

/** What a pin points at: a whole thread, or one message of it. */
enum class PinKind { THREAD, MESSAGE }

/**
 * One pin of a room. [kind] says what it points at; a thread pin has `message: null`, a message pin carries the ordinary
 * [MessageResponse] as it reads now. The thread's title and status come with it for the panel's row, the status as its KEY.
 */
@Serializable
data class RoomPinResponse(
    val id: UInt,
    val kind: PinKind,
    val threadNumber: Int,
    val threadTitle: String,
    val threadStatus: String,
    val threadClosed: Boolean,
    val message: MessageResponse?,
    val pinnedById: UInt,
    val pinnedByName: String,
    val pinnedAt: Long,
)

typealias RoomPinPageResponse = PageResponse<RoomPinResponse>

/** One page of a room's pins plus the pre-pagination total (the list-endpoint envelope). */
data class RoomPinListResult(val items: List<RoomPinResponse>, val total: Long)

/** `PUT .../threads/{number}/pin` and `PUT /rooms/{id}/messages/{messageId}/pin`: whether the thread or message is pinned. */
@Serializable
data class PinRequest(val pinned: Boolean)

/** What a pin write changed, for the route's audit line: the thread's row id and the message (null for a thread pin). */
data class PinChange(val threadId: UInt, val messageId: UInt?, val pinned: Boolean)
