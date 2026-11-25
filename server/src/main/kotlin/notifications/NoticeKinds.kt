package ch.nokillswit.notifications

import ch.nokillswit.threads.NoticeReason

/*
 * The two exhaustive maps between the chat's `NoticeReason` and the persisted `NotificationType` (`chat-model.md` "Notice kinds
 * (V27, V28)").
 * No `else` on either side: a new reason or a new kind does not compile until both are mapped. Room notices have no thread reason.
 */

internal fun NoticeReason.toNotificationType(): NotificationType = when (this) {
    NoticeReason.MENTIONED -> NotificationType.MENTIONED
    NoticeReason.MESSAGE_QUOTED -> NotificationType.MESSAGE_QUOTED
    NoticeReason.TEAM_MENTIONED -> NotificationType.TEAM_MENTIONED
    NoticeReason.WATCHERS_MENTIONED -> NotificationType.WATCHERS_MENTIONED
    NoticeReason.EVERYONE_MENTIONED -> NotificationType.EVERYONE_MENTIONED
    NoticeReason.THREAD_REPLIED -> NotificationType.THREAD_REPLIED
    NoticeReason.THREAD_CREATED -> NotificationType.THREAD_CREATED
    NoticeReason.THREAD_ASSIGNED -> NotificationType.THREAD_ASSIGNED
    NoticeReason.THREAD_OWNER_CHANGED -> NotificationType.THREAD_OWNER_CHANGED
    NoticeReason.THREAD_STATUS_CHANGED -> NotificationType.THREAD_STATUS_CHANGED
    NoticeReason.THREAD_CLOSED -> NotificationType.THREAD_CLOSED
    NoticeReason.THREAD_REOPENED -> NotificationType.THREAD_REOPENED
}

internal fun NotificationType.toNoticeReason(): NoticeReason? = when (this) {
    NotificationType.MENTIONED -> NoticeReason.MENTIONED
    NotificationType.MESSAGE_QUOTED -> NoticeReason.MESSAGE_QUOTED
    NotificationType.TEAM_MENTIONED -> NoticeReason.TEAM_MENTIONED
    NotificationType.WATCHERS_MENTIONED -> NoticeReason.WATCHERS_MENTIONED
    NotificationType.EVERYONE_MENTIONED -> NoticeReason.EVERYONE_MENTIONED
    NotificationType.THREAD_REPLIED -> NoticeReason.THREAD_REPLIED
    NotificationType.THREAD_CREATED -> NoticeReason.THREAD_CREATED
    NotificationType.THREAD_ASSIGNED -> NoticeReason.THREAD_ASSIGNED
    NotificationType.THREAD_OWNER_CHANGED -> NoticeReason.THREAD_OWNER_CHANGED
    NotificationType.THREAD_STATUS_CHANGED -> NoticeReason.THREAD_STATUS_CHANGED
    NotificationType.THREAD_CLOSED -> NoticeReason.THREAD_CLOSED
    NotificationType.THREAD_REOPENED -> NoticeReason.THREAD_REOPENED
    NotificationType.ROOM_ADDED -> null
}
