package ch.nokillswit.notifications

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable

/**
 * The kinds of notice, PERSISTED by name and frozen (`notifications.notification_type` has no CHECK: this enum is the whitelist, the V5
 * rule). A stored name this build does not know is invisible to every read, never fatal. A new kind is a new constant; none is renamed. The
 * kind IS the reason (there is no reason column): `chat-model.md` "Notice kinds (V27, V28)" says which event makes which and in
 * what priority
 * (`threads/ThreadNotices.kt` names the reasons; `NoticeKinds.kt` maps the two by an exhaustive `when`). The
 * DEFAULT of each channel lives here beside the name ([defaultOn]) and is frozen with it.
 */
@Serializable
enum class NotificationType {
    /** The recipient was mentioned in a message by name (`<@id>`). */
    MENTIONED,

    /** Somebody quoted a message of the recipient's in a reply of the same thread (1.3: the quoted message's author). */
    MESSAGE_QUOTED,

    /** The recipient is on a team a message mentions (`<@team:id>`). */
    TEAM_MENTIONED,

    /** The recipient watches the thread and a message in it mentions the watchers (`<@watchers>`). */
    WATCHERS_MENTIONED,

    /** The recipient is a member of the room and a message mentions everyone (`<@everyone>`). */
    EVERYONE_MENTIONED,

    /** The recipient watches the thread and somebody replied in it. */
    THREAD_REPLIED,

    /** Somebody started a thread in a room the recipient is a member of. */
    THREAD_CREATED,

    /** The recipient was made the owner of a thread by someone else. */
    THREAD_ASSIGNED,

    /** The recipient watches the thread and its owner changed (they are not the new owner). */
    THREAD_OWNER_CHANGED,

    /** The recipient watches the thread and it moved to another status of the same category. */
    THREAD_STATUS_CHANGED,

    /** The recipient watches the thread and it was closed. */
    THREAD_CLOSED,

    /** The recipient watches the thread and it was reopened. */
    THREAD_REOPENED,

    /** Another person added the recipient to a room. */
    ROOM_ADDED,
    ;

    /**
     * Whether a person who never touched their preferences gets this kind through [channel] (1.1 D7 as amended: the app for every kind,
     * email for none; a person's stored deviations override it). An exhaustive `when` over the kinds, no `else`: a new kind must choose
     * its defaults here, never inherit one, and the choice is frozen with the name (a stored row only ever says "different from this").
     */
    fun defaultOn(channel: NotificationChannel): Boolean = when (this) {
        MENTIONED, MESSAGE_QUOTED, TEAM_MENTIONED, WATCHERS_MENTIONED, EVERYONE_MENTIONED, THREAD_REPLIED, THREAD_CREATED,
        THREAD_ASSIGNED, THREAD_OWNER_CHANGED, THREAD_STATUS_CHANGED, THREAD_CLOSED, THREAD_REOPENED, ROOM_ADDED,
        -> channel == NotificationChannel.IN_APP
    }
}

/** The kinds exposed by the original thread notice list and preference pairs. */
internal val THREAD_NOTIFICATION_TYPES = NotificationType.entries.filterNot { it == NotificationType.ROOM_ADDED }.toSet()

/**
 * One notice of the caller, for the bell. The row holds ids only: the actor's and the room's names and the thread's number and title are
 * read when the page is read, so a rename shows at once and nothing stored can outlive a leave or a delete (`chat-model.md`
 * "Notifications (V18)"). [messageId] is null for every thread-level kind (a hand-over, a status move).
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

/** A feed item targets the room itself, a thread, or a message within a thread. */
@Serializable
data class NotificationFeedItem(
    val id: UInt,
    val type: NotificationType,
    val seen: Boolean,
    val createdAt: Long,
    val actorId: UInt,
    val actorName: String,
    val roomId: UInt,
    val roomName: String,
    val threadNumber: Int?,
    val threadTitle: String?,
    val messageId: UInt?,
) {
    internal fun asThreadNotice() = NotificationResponse(
        id, type, seen, createdAt, actorId, actorName, roomId, roomName,
        checkNotNull(threadNumber), checkNotNull(threadTitle), messageId,
    )
}

internal data class NotificationFeedResult(val items: List<NotificationFeedItem>, val total: Long)
