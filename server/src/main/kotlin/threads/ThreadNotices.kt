package ch.nokillswit.threads

/** The persisted name of a notice for a mention (`notifications/` owns the enum and a test pins the two equal). */
internal const val NOTICE_MENTIONED = "MENTIONED"

/** The persisted name of a notice for a hand-over of a thread (see [NOTICE_MENTIONED]). */
internal const val NOTICE_THREAD_ASSIGNED = "THREAD_ASSIGNED"

/**
 * What the chat tells the notification feature (`chat-model.md` "Notifications (V18)"): `threads/` declares this port and
 * `notifications/NotificationService` implements it, so the chat never imports the feature that sits above it (the `WorkflowUsage`
 * precedent in `rooms/`); `infra/db/Database.kt` hands the implementation to the services. EVERY call runs INSIDE the caller's
 * transaction (a post, an edit, a delete or a hand-over, under the room lock), so a notice and its live hint commit with the write that
 * caused them and a rolled-back write announces nothing. A notice is ids only. The one exception to "under the room lock" is
 * [threadRead]: it belongs to the read marker's own short transaction, which takes no room lock (`chat-model.md` "Unread (V19)").
 */
internal interface ThreadNotices {
    /**
     * [userIds] were newly mentioned in message [at], by [byUserId]. Answers the people a notice was made for: never [byUserId] (a mention
     * of oneself is shown and never notified), and nobody a notice of this message already exists for. Emits ONE `notification.created`
     * hint to them when there are any.
     */
    suspend fun mentioned(at: MessageRef, byUserId: UInt, userIds: Collection<UInt>): List<UInt>

    /** An edit removed the mentions of [userIds] from message [messageId]: their notices of it go. */
    suspend fun mentionsDropped(messageId: UInt, userIds: Collection<UInt>)

    /** Message [messageId] was deleted, a moderator's delete included: every notice about it goes. */
    suspend fun messageRemoved(messageId: UInt)

    /**
     * Thread [threadNumber] was handed to [toUserId] by [byUserId]. False (and no notice, no hint) when they took it themself; otherwise
     * the notice is made, ONE `notification.created` hint goes to the new owner and the answer is true.
     */
    suspend fun ownerAssigned(roomId: UInt, threadId: UInt, threadNumber: Int, byUserId: UInt, toUserId: UInt): Boolean

    /**
     * [userId] read thread [threadId] up to message [upToMessageId] at [at]: the unseen notices of that person in the thread about a
     * message up to there, and the thread-level ones (a hand-over has no message), become seen. Runs in the read marker's transaction,
     * WITHOUT the room lock, and touches only that person's rows.
     */
    suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long)
}
