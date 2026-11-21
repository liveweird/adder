package ch.nokillswit.threads

/**
 * Why somebody is told about an event (`chat-model.md` "Notice kinds (V27, V28)"): the chat's own vocabulary, eleven names that
 * `notifications/NoticeKinds.kt` maps to the persisted `NotificationType` by an exhaustive `when` (a new reason does not compile until it
 * is mapped, and a test pins every name equal to its kind). The order is the PRIORITY of a message event, the most specific first; a
 * hand-over is `THREAD_ASSIGNED` over `THREAD_OWNER_CHANGED`. Public only because [NoticeOutcome] rides the public write results
 * (`PostedMessage`).
 */
enum class NoticeReason {
    MENTIONED,
    TEAM_MENTIONED,
    WATCHERS_MENTIONED,
    EVERYONE_MENTIONED,
    THREAD_REPLIED,
    THREAD_CREATED,
    THREAD_ASSIGNED,
    THREAD_OWNER_CHANGED,
    THREAD_STATUS_CHANGED,
    THREAD_CLOSED,
    THREAD_REOPENED,
}

/** One person to tell, and why. */
internal class Notice(val userId: UInt, val reason: NoticeReason)

/**
 * What an edit does to the notices a message already has (`chat-model.md` "Notice kinds (V27, V28)", the edit rules): [remove] the notices
 * of these people go (a "delete and insert" of a less specific notice is a [remove] plus an [add], so the person gets a fresh, unseen
 * notice); [relabel] their notice stays in place under another reason; [add] these people are told now. A person is in at most one list.
 */
internal class NoticeRevision(
    val add: List<Notice> = emptyList(),
    val relabel: List<Notice> = emptyList(),
    val remove: Collection<UInt> = emptyList(),
)

/**
 * What a call made, for the route's `notification.created` audit line (ids and counts only): [recipients] is the number of notices made,
 * [kinds] how many of each reason, [addressed] the people a notice was made for BY NAME (a direct mention or a hand-over), at most
 * [MAX_AUDITED_ADDRESSED] of them (the rest of a wide event is a count, never 499 ids in a log line).
 */
data class NoticeOutcome(
    val recipients: Int = 0,
    val kinds: Map<NoticeReason, Int> = emptyMap(),
    val addressed: List<UInt> = emptyList(),
) {
    /** Both outcomes together (a message write can run several followers; the sum is what the route audits). */
    operator fun plus(other: NoticeOutcome): NoticeOutcome = when {
        other.recipients == 0 -> this
        recipients == 0 -> other
        else -> NoticeOutcome(
            recipients + other.recipients,
            (kinds.keys + other.kinds.keys).associateWith { (kinds[it] ?: 0) + (other.kinds[it] ?: 0) },
            (addressed + other.addressed).distinct().take(MAX_AUDITED_ADDRESSED),
        )
    }

    companion object {
        /** Nobody was told. */
        val NONE = NoticeOutcome()
    }
}

/** The most directly addressed people one audit line names: a message mentions at most 20 people by name, and one more says "or more". */
internal const val MAX_AUDITED_ADDRESSED = 21

/**
 * What the chat tells the notification feature (`chat-model.md` "Notifications (V18)", "Notice kinds (V27, V28)"): `threads/` declares this
 * port and `notifications/NoticeWriter` implements it, so the chat never imports the feature that sits above it (the `WorkflowUsage`
 * precedent in `rooms/`); `infra/db/ChatWiring.kt` hands the implementation to the services. EVERY call runs INSIDE the caller's
 * transaction (a post, an edit, a delete or a hand-over, under the room lock), so a notice and its live hint commit with the write that
 * caused them and a rolled-back write announces nothing. A notice is ids only. The one exception to "under the room lock" is
 * [threadRead]: it belongs to the read marker's own short transaction, which takes no room lock (`chat-model.md` "Unread (V19)").
 *
 * The CALLER decides who is told and why (the audience, in the chat's own terms); the writer decides only whether, and how, each person is
 * reached: it never notifies the actor, answers each person once, and judges each person's channels (a kind they switched off in the app
 * AND by email makes no row at all).
 */
internal interface ThreadNotices {
    /**
     * Tells [notices] about message [at] by [byUserId]: one notice per person (the first of a person wins, so the caller lists the most
     * specific reason first), never [byUserId], nobody a notice of this message already exists for. Emits ONE `notification.created` hint
     * to the people whose notice shows in the app when there are any. The email budget counts the recipients BEFORE the insert, so a
     * caller that may name a person who already has a notice of the message (an edit) drops them first, using [ofMessage].
     */
    suspend fun forMessage(at: MessageRef, byUserId: UInt, notices: List<Notice>): NoticeOutcome

    /** The reasons of the notices message [messageId] already has, by recipient (a kind this build does not know is left out). */
    suspend fun ofMessage(messageId: UInt): Map<UInt, NoticeReason>

    /**
     * An edit changed who message [at] addresses: applies [revision] to its notices (see [NoticeRevision]); the added ones are made
     * as [forMessage] does.
     */
    suspend fun reviseMessage(at: MessageRef, byUserId: UInt, revision: NoticeRevision): NoticeOutcome

    /** Message [messageId] was deleted, a moderator's delete included: every notice about it goes. */
    suspend fun messageRemoved(messageId: UInt)

    /**
     * Tells [notices] about thread [threadNumber] (a hand-over or a status move: the notices have no message) by [byUserId], with the same
     * rules and ONE hint as [forMessage]. An empty list writes nothing and answers [NoticeOutcome.NONE].
     */
    suspend fun forThread(roomId: UInt, threadId: UInt, threadNumber: Int, byUserId: UInt, notices: List<Notice>): NoticeOutcome

    /**
     * [userId] read thread [threadId] up to message [upToMessageId] at [at]: the unseen notices of that person in the thread about a
     * message up to there, and the thread-level ones (a hand-over has no message), become seen. Runs in the read marker's transaction,
     * WITHOUT the room lock, and touches only that person's rows.
     */
    suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long)
}
