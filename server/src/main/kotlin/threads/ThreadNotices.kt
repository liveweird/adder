package ch.nokillswit.threads

/**
 * Why somebody is told about an event (`chat-model.md` "Notice kinds (V27, V28)"): the chat's own vocabulary, twelve names that
 * `notifications/NoticeKinds.kt` maps to the persisted `NotificationType` by an exhaustive `when` (a new reason does not compile until it
 * is mapped, and a test pins every name equal to its kind). The order is the PRIORITY of a message event, the most specific first; a
 * hand-over is `THREAD_ASSIGNED` over `THREAD_OWNER_CHANGED`. Public only because [NoticeOutcome] rides the public write results
 * (`PostedMessage`).
 */
enum class NoticeReason {
    MENTIONED,

    /** The author of a quoted message (1.3), right after `MENTIONED`: the order is the priority, and it is NOT a mention reason. */
    MESSAGE_QUOTED,
    TEAM_MENTIONED,
    WATCHERS_MENTIONED,
    HERE_MENTIONED,
    EVERYONE_MENTIONED,
    THREAD_REPLIED,
    THREAD_CREATED,
    THREAD_ASSIGNED,
    THREAD_OWNER_CHANGED,
    THREAD_STATUS_CHANGED,
    THREAD_CLOSED,
    THREAD_REOPENED,
}

/**
 * One person to tell, and why: [reason] is the most specific one that applies; [fallbacks] are the other reasons that apply to the same
 * person, in priority order (a watcher who is also mentioned has `MENTIONED` and the fallback `THREAD_REPLIED`). The writer uses the first
 * reason that can reach the person through either channel, so a kind they switched off on BOTH channels falls back to the next one
 * instead of silencing a message that is also a reply (1.1 A36); only when every applicable reason is fully off is there no notice.
 */
internal class Notice(val userId: UInt, val reason: NoticeReason, val fallbacks: List<NoticeReason> = emptyList())

/**
 * What an edit does to the notices a message already has (`chat-model.md` "Notice kinds (V27, V28)", the edit rules): [remove] the notices
 * of these people go (a "delete and insert" of a less specific notice is a [remove] plus an [add], so the person gets a fresh, unseen
 * notice); [relabel] their notice stays in place under another reason; [add] these people are told now ("newly addressed": addressed by
 * the new body and not by the old one, 1.1 A34). A person is in at most one list,
 * except the "delete and insert" pair above (in [remove] and in [add]); nobody who keeps a notice of the message is in [add] (the email
 * budget counts [add] before the insert).
 */
internal class NoticeRevision(
    val add: List<Notice> = emptyList(),
    val relabel: List<Notice> = emptyList(),
    val remove: Collection<UInt> = emptyList(),
) {
    /** Nothing to do: no notice added, relabelled or removed. */
    val isEmpty: Boolean get() = add.isEmpty() && relabel.isEmpty() && remove.isEmpty()
}

/**
 * What a call made, for the route's `notification.created` audit line (ids and counts only): [recipients] is the number of notices made
 * or refreshed (a repeated thread-level notice replaces the person's unseen one), [kinds] how many of each reason, [addressed] the
 * people a notice was made for BY NAME (a direct mention, a quoted message's author or a hand-over), at most [MAX_AUDITED_ADDRESSED] of
 * them (the rest of a wide event is a count, never 499 ids in a log line).
 */
data class NoticeOutcome(
    val recipients: Int = 0,
    val kinds: Map<NoticeReason, Int> = emptyMap(),
    val addressed: List<UInt> = emptyList(),
    /** Notices an edit RELABELLED in place (not counted in [recipients]): a relabel-only edit leaves this trace in the audit line. */
    val relabelled: Int = 0,
) {
    /** Both outcomes together (a message write can run several followers; the sum is what the route audits). */
    operator fun plus(other: NoticeOutcome): NoticeOutcome = when {
        other.recipients == 0 -> copy(relabelled = relabelled + other.relabelled)
        recipients == 0 -> other.copy(relabelled = relabelled + other.relabelled)
        else -> NoticeOutcome(
            recipients + other.recipients,
            (kinds.keys + other.kinds.keys).associateWith { (kinds[it] ?: 0) + (other.kinds[it] ?: 0) },
            (addressed + other.addressed).distinct().take(MAX_AUDITED_ADDRESSED),
            relabelled + other.relabelled,
        )
    }

    companion object {
        /** Nobody was told. */
        val NONE = NoticeOutcome()
    }
}

/**
 * The most directly addressed people one audit line names: a message mentions at most 20 people by name, its quoted message's author
 * is one more (1.3), and one more says "or more".
 */
internal const val MAX_AUDITED_ADDRESSED = 22

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
    suspend fun forThread(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        byUserId: UInt,
        notices: List<Notice>,
        eventId: UInt? = null,
    ): NoticeOutcome

    /**
     * [userId] read thread [threadId] up to message [upToMessageId] at [at]: the unseen notices of that person in the thread about a
     * message up to there, and the thread-level ones (a hand-over has no message), become seen. Runs in the read marker's transaction,
     * WITHOUT the room lock, and touches only that person's rows.
     */
    suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long)

    /**
     * "Mark all as read" (1.2), step one: the newest id among [userId]'s UNSEEN notices of room [roomId], or null when they hold none
     * (one probe of `idx_notifications_unseen_room`). The settle never goes past it. Runs in the caller's short transaction.
     */
    suspend fun roomUnseenCeiling(roomId: UInt, userId: UInt): UInt?

    /**
     * Step two, one CHUNK: settles at most [limit] of [userId]'s unseen notices of room [roomId] with an id above [after] and at most
     * [ceiling] and whose `created_at` is at most [at] (nothing made after the request is settled by it), oldest id first, stamping them
     * [at]; every kind and channel, a mail-only notice too (it cancels its pending digest, as the read marker does). The rows are locked
     * in id order while they are selected. Runs in its own short transaction, WITHOUT the room lock and without the member row; touches
     * only that person's rows. Answers how many were selected (a full chunk means more may follow: the caller loops, from
     * [SeenChunk.lastId]) and how many were actually updated.
     */
    suspend fun roomSeenChunk(roomId: UInt, userId: UInt, at: Long, after: UInt, ceiling: UInt, limit: Int): SeenChunk
}

/**
 * What one chunk of "Mark all as read" did: [selected] rows were found, [moved] of them were marked seen, and [lastId] is the last id
 * selected (null for an empty chunk), where the next chunk starts.
 */
internal data class SeenChunk(val selected: Int, val moved: Int, val lastId: UInt? = null)

/** The most unseen notices one chunk of "Mark all as read" settles (the shape of the "seen all" POST's chunk). */
internal const val ROOM_SEEN_CHUNK = 1000
