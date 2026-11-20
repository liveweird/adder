package ch.nokillswit.notifications

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * The kinds of notice, PERSISTED by name and frozen (`notifications.notification_type` has no CHECK: this enum is the whitelist, the V5
 * rule). A stored name this build does not know is invisible to every read, never fatal. A new kind is a new constant; none is renamed.
 * `threads/ThreadNotices.kt` names the two the chat creates as constants (a test pins them equal to these).
 */
@Serializable
enum class NotificationType {
    /** The recipient was mentioned in a message (`<@id>`). */
    MENTIONED,

    /** The recipient was made the owner of a thread by someone else. */
    THREAD_ASSIGNED,
}

/**
 * One notice of the caller, for the bell. The row holds ids only: the actor's and the room's names and the thread's number and title are
 * read when the page is read, so a rename shows at once and nothing stored can outlive a leave or a delete (`chat-model.md`
 * "Notifications (V18)"). [messageId] is null for [NotificationType.THREAD_ASSIGNED].
 */
@Serializable
data class NotificationResponse(
    val id: UInt,
    val type: NotificationType,
    val seen: Boolean,
    val createdAt: Long,
    val actorId: UInt,
    val actorName: String,
    val roomId: UInt,
    val roomName: String,
    val threadNumber: Int,
    val threadTitle: String,
    val messageId: UInt?,
)

typealias NotificationPageResponse = PageResponse<NotificationResponse>

/** One page of the caller's notices plus the pre-pagination total (the list-endpoint envelope). */
data class NotificationListResult(val items: List<NotificationResponse>, val total: Long)
