package ch.nokillswit.notifications

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.notifications.NotificationService.Notifications
import ch.nokillswit.rooms.RoomNotices
import ch.nokillswit.threads.MAX_AUDITED_ADDRESSED
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeOutcome
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.NoticeRevision
import ch.nokillswit.threads.SeenChunk
import ch.nokillswit.threads.ThreadNotices
import ch.nokillswit.threads.mutedInThread
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The chat's [ThreadNotices] port (`chat-model.md` "Notifications (V18)", "Notice kinds (V27, V28)"): the WRITE side of the notices. A
 * notice is a POINTER, ids only: it is made INSIDE the write that caused it (a post, an edit, a hand-over or a status move, under the room
 * lock), together with its `notification.created` live hint, so a rolled-back write announces nothing. Rows are hard-deleted with their
 * message ([messageRemoved], [reviseMessage]); the other two ways a notice goes (the membership it was made for, retention) belong to the
 * schema and to [NotificationService.purgeOlderThan]. The list, the "seen" writes and the retention stay in [NotificationService]; the
 * two share the [NotificationService.Notifications] table object. [emailBudget] is the per-actor digest budget (V24): the default is the
 * config default, a test or the composition root passes its own.
 *
 * **Channels are judged here, when the notice is made** (1.1 D7): ONE read of the recipients' preferences, then per person the kind's
 * in-app switch and email switch (stored deviations over `NotificationType.defaultOn`). A person gets a row only if the notice can reach
 * them: it SHOWS in the app (in-app on; `in_app = true`), or it can be MAILED (email on, a mailer exists, and the actor is inside the
 * daily budget; `in_app = false` when the app switch is off). In-app off and nothing mailable makes no row. A row that is not
 * mailable is settled at insert (`emailed_at = created_at`). The budget runs BEFORE the insert, on the
 * email-eligible recipients only, in the order the caller listed them (the most specific first); a caller that can address a person
 * twice drops those who already hold a notice of the message (`ofMessage`).
 */
internal class NoticeWriter(
    private val emailBudget: NotificationEmailBudget = NotificationEmailBudget(DEFAULT_EMAIL_BUDGET_PER_ACTOR, clock = ::nowMillis),
    /** False when the deployment has no outbound email: nothing can be mailed, so email is never a reason to make a row. */
    private val mailAvailable: Boolean = true,
    /** The digest's delay (`notifications.email.delaySeconds`): a notice still waiting for it is coalesced into only while younger. */
    private val digestDelayMillis: Long = DEFAULT_DIGEST_DELAY_MILLIS,
    /** False when the deployment has no VAPID configuration; disabled push must not accumulate undeliverable queue rows. */
    private val pushAvailable: Boolean = false,
    private val retention: NotificationRetention = NotificationRetention(),
) : ThreadNotices, RoomNotices {
    private val judger = NoticeJudge(mailAvailable)
    private val persistence = NoticePersistence(digestDelayMillis)
    private val seenWriter = NoticeSeenWriter(retention)

    override suspend fun forMessage(at: MessageRef, byUserId: UInt, notices: List<Notice>): NoticeOutcome {
        val recipients = recipientsOf(notices, byUserId)
        retention.lockRecipients(recipients)
        return make(at.roomId, at.threadId, at.threadNumber, at.messageId, byUserId, notices, lockedRecipients = recipients)
    }

    override suspend fun reactionChanged(
        at: MessageRef,
        byUserId: UInt,
        authorId: UInt,
        reacting: Boolean,
    ): NoticeOutcome {
        if (authorId == byUserId) return NoticeOutcome.NONE
        if (reacting) {
            retention.lockRecipients(listOf(authorId))
            return deliver(
                at.roomId,
                at.threadId,
                at.threadNumber,
                at.messageId,
                byUserId,
                listOf(Notice(authorId, NoticeReason.REACTION_ADDED)),
                retentionRecipients = listOf(authorId),
            )
        }
        val n = Notifications
        val rows = n.select(n.inApp).where {
            (n.messageId eq at.messageId) and
                (n.recipientId eq authorId) and
                (n.actorId eq byUserId) and
                (n.type eq NotificationType.REACTION_ADDED.name)
        }.map { it[n.inApp] }.toList()
        if (rows.isEmpty()) return NoticeOutcome.NONE
        n.deleteWhere {
            (n.messageId eq at.messageId) and
                (n.recipientId eq authorId) and
                (n.actorId eq byUserId) and
                (n.type eq NotificationType.REACTION_ADDED.name)
        }
        if (rows.any { it }) announce(listOf(authorId), at.roomId, at.threadNumber, at.messageId)
        return NoticeOutcome.NONE
    }

    override suspend fun forThread(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        byUserId: UInt,
        notices: List<Notice>,
        eventId: UInt?,
    ): NoticeOutcome {
        val recipients = recipientsOf(notices, byUserId)
        retention.lockRecipients(recipients)
        return make(roomId, threadId, threadNumber, null, byUserId, notices, eventId, recipients)
    }

    override suspend fun membersAdded(roomId: UInt, actorId: UInt, recipients: List<UInt>, named: Set<UInt>): Int {
        val type = NotificationType.ROOM_ADDED
        val people = recipients.filterNot { it == actorId }
        retention.lockRecipients(people)
        val audience = roomNoticeAudience(roomId, actorId, people, named)
        val preferences = effectivePreferencesOf(audience, listOf(type))
        val deliveries = withinEmailBudget(actorId, roomId, audience.mapNotNull { judger.judge(it, type, preferences) })
        if (deliveries.isEmpty()) return 0
        val made = persistence.insert(roomId, null, null, actorId, nowMillis(), deliveries)
        val retained = retention.trimLocked(people, made)
        if (pushAvailable) enqueueBrowserPush(retained.survivors.map { it.notificationId })
        val survivors = retained.survivors.map { it.userId }.toSet()
        val targets = deliveries.filter { it.inApp && it.userId in survivors }.map { it.userId } + retained.bellChanged
        announce(targets, roomId, null, null)
        return retained.survivors.size
    }

    private suspend fun roomNoticeAudience(roomId: UInt, actorId: UInt, people: List<UInt>, named: Set<UInt>): List<UInt> {
        if (people.size <= WIDE_EVENT_RECIPIENTS) return people
        val direct = people.filter { it in named }.take(WIDE_EVENT_RECIPIENTS)
        return if (emailBudget.admitBroadcast(actorId, roomId, withheld = people.size - direct.size)) people else direct
    }

    override suspend fun ofMessage(messageId: UInt): Map<UInt, NoticeReason> {
        val n = Notifications
        return n.select(n.recipientId, n.type).where { n.messageId eq messageId }
            .map { it[n.recipientId] to it[n.type] }.toList()
            .mapNotNull { (recipient, name) ->
                THREAD_NOTIFICATION_TYPES.firstOrNull { it.name == name }?.toNoticeReason()?.let { recipient to it }
            }.toMap()
    }

    /**
     * Applies an edit's [revision]: removed notices go, relabelled ones keep their row and seen state under the new kind with the
     * channels judged again ([NoticeJudge.relabel]), added ones are made like [forMessage]'s; everybody whose bell changed gets ONE hint.
     *
     * **One event for the wide-event budget (1.1 A35, A38):** wide when the people it ADDS (actor left out, before the channels are judged)
     * or the bells its relabels change exceed [WIDE_EVENT_RECIPIENTS]. Refused, the labels still change (a label must not lie), the
     * relabelled get no hint and only the people named directly are added (an edit never counts a quoted author as named directly: the
     * quote did not change); the outcome's `relabelled` leaves a trace. **An upgrade deletes
     * the old row only when its replacement is made**; when it is dropped (the budget, the person's switches, the email budget) the old
     * row stays only while its kind is still one of the body's reasons for the person, and is otherwise relabelled in place.
     */
    override suspend fun reviseMessage(at: MessageRef, byUserId: UInt, revision: NoticeRevision): NoticeOutcome {
        val recipients = (revision.add.map { it.userId } + revision.relabel.map { it.userId } + revision.remove)
            .filterNot { it == byUserId }.distinct()
        retention.lockRecipients(recipients)
        val relabelled = judger.relabel(at.messageId, revision.relabel)
        val candidates = distinctOthers(revision.add, byUserId)
        val full = candidates.associateBy { it.userId }
        // A quote never changes, so an edit never makes MESSAGE_QUOTED the reason that names a person directly: an edit that adds a group
        // mention for the quoted author is a group notice (budgeted and withheld like any), only a `<@id>` mention keeps its place.
        val direct = candidates.filter { it.reason in EDIT_DIRECT }.map { Notice(it.userId, it.reason) }
        val hinted = relabelled.changedBell.size
        val admitted = (candidates.size <= WIDE_EVENT_RECIPIENTS && hinted <= WIDE_EVENT_RECIPIENTS) ||
            emailBudget.admitBroadcast(byUserId, at.roomId, withheld = candidates.size - direct.size + hinted)
        val offered = if (admitted) candidates else direct
        val refused = full.keys - offered.map { it.userId }.toSet()
        val upgrading = revision.remove.filter { it in full }.toSet()
        val held = if (upgrading.isEmpty()) emptyMap() else judger.heldRows(at.messageId, upgrading.toList())
        val (add, unchanged) = withoutUnchangedUpgrades(offered, upgrading, held)
        val adding = add.map { it.userId }.toSet()
        val removing = revision.remove.filter { it !in refused && it !in unchanged }
        val replacing = removing.filter { it in adding }.toSet()
        // A dropped upgrade keeps its old row only while that kind is still earned by the body; else it is relabelled in place.
        fun stale(user: UInt): Boolean {
            val reason = held[user]?.type?.toNoticeReason()
            return reason == null || reason !in full.getValue(user).allReasons()
        }
        val dropped = upgrading.filter { (it in refused || it in unchanged) && stale(it) }
        val early = judger.relabel(at.messageId, dropped.map { full.getValue(it) })
        deleteNotices(at.messageId, (removing.filter { it !in replacing } + relabelled.deleted + early.deleted).toSet())
        var late = NoticeJudge.Relabelled(emptyList(), emptyList())
        val hints = if (admitted) relabelled.changedBell + early.changedBell else emptyList()
        val outcome = deliver(
            at.roomId, at.threadId, at.threadNumber, at.messageId, byUserId, add, hints, retentionRecipients = recipients,
        ) { delivered ->
            deleteNotices(at.messageId, replacing.filter { it in delivered }.toSet())
            late = judger.relabel(at.messageId, replacing.filter { it !in delivered && stale(it) }.map { full.getValue(it) })
            deleteNotices(at.messageId, late.deleted.toSet())
            if (admitted) late.changedBell else emptyList()
        }
        return outcome.copy(relabelled = relabelled.count + early.count + late.count)
    }

    private suspend fun deleteNotices(messageId: UInt, recipients: Set<UInt>) {
        if (recipients.isEmpty()) return
        Notifications.deleteWhere { (Notifications.messageId eq messageId) and (Notifications.recipientId inList recipients) }
    }

    /**
     * An upgrade (a less specific notice, now addressed more specifically) is a fresh unseen notice only when the person's channels make
     * a DIFFERENT kind of it: when the kind the writer would choose (a fallback if the top kind is off) is the one they hold, or no
     * applicable reason reaches them, the upgrade is dropped (no fresh row, no second charge to the email budget). Answers the notices
     * to add and the people whose upgrade is dropped.
     */
    private suspend fun withoutUnchangedUpgrades(
        add: List<Notice>,
        upgrading: Set<UInt>,
        held: Map<UInt, NoticeJudge.Held>,
    ): Pair<List<Notice>, Set<UInt>> {
        val upgrades = add.filter { it.userId in upgrading }
        if (upgrades.isEmpty()) return add to emptySet()
        val kinds = upgrades.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct()
        val preferences = effectivePreferencesOf(upgrades.map { it.userId }, kinds)
        val unchanged = upgrades.filter { upgrade ->
            val verdict = judger.judge(upgrade, preferences)
            verdict == null || verdict.type == held[upgrade.userId]?.type
        }.map { it.userId }.toSet()
        return add.filter { it.userId !in unchanged } to unchanged
    }

    override suspend fun messageRemoved(messageId: UInt) {
        Notifications.deleteWhere { Notifications.messageId eq messageId }
    }

    override suspend fun threadsRemoved(roomId: UInt, threadIds: List<UInt>) {
        if (threadIds.isEmpty()) return
        val ids = threadIds.distinct()
        val recipients = Notifications.select(Notifications.recipientId).where {
            (Notifications.roomId eq roomId) and (Notifications.threadId inList ids) and Notifications.inApp
        }.withDistinct().map { it[Notifications.recipientId] }.toList()
        Notifications.deleteWhere { (Notifications.roomId eq roomId) and (Notifications.threadId inList ids) }
        // `notification_push_queue.notification_id` is ON DELETE CASCADE (V44), so the same delete also cancels queued browser delivery.
        announce(recipients, roomId, null, null)
    }

    override suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long) =
        seenWriter.threadRead(threadId, userId, upToMessageId, at)

    override suspend fun prepareMemberRemoval(roomId: UInt, userId: UInt) = seenWriter.prepareMemberRemoval(roomId, userId)

    override suspend fun roomUnseenCeiling(roomId: UInt, userId: UInt): UInt? = seenWriter.roomUnseenCeiling(roomId, userId)

    override suspend fun roomSeenChunk(roomId: UInt, userId: UInt, at: Long, after: UInt, ceiling: UInt, limit: Int): SeenChunk =
        seenWriter.roomSeenChunk(roomId, userId, at, after, ceiling, limit)

    /**
     * The one path every notice takes: filter (never the actor, a person once), the wide-event budget, judge the channels (with the
     * fallbacks), coalesce a repeated thread-level notice into the unseen row the person already holds, run the email budget on what is
     * left, insert in ONE statement and hint the people whose notice shows in the app. [messageId] is null for a thread-level notice.
     */
    private suspend fun make(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        messageId: UInt?,
        actorId: UInt,
        notices: List<Notice>,
        eventId: UInt? = null,
        lockedRecipients: Collection<UInt>,
    ): NoticeOutcome {
        val wanted = withinBroadcastBudget(distinctOthers(notices, actorId), roomId, actorId)
        return deliver(
            roomId, threadId, threadNumber, messageId, actorId, wanted, eventId = eventId,
            retentionRecipients = lockedRecipients,
        )
    }

    /** [notices] without the actor and with a person once (the first of a person wins: the most specific reason is listed first). */
    private fun distinctOthers(notices: List<Notice>, actorId: UInt) = notices.filter { it.userId != actorId }.distinctBy { it.userId }

    private fun recipientsOf(notices: List<Notice>, actorId: UInt): List<UInt> =
        distinctOthers(notices, actorId).map { it.userId }

    /**
     * What [make] does once the wide-event budget has chosen the audience [wanted]: judge the channels (with the fallbacks), coalesce a
     * repeated thread-level notice into the unseen row the person already holds, run the email budget on what is left, insert in ONE
     * statement and send ONE hint to the people whose notice shows in the app, plus [extraTargets] (an edit's relabelled people).
     * [beforeInsert] runs ONCE, with the people who WILL get a row, right before the insert (an upgrade deletes the old row there, never
     * earlier), also when nobody does; the targets it answers join the hint.
     */
    private suspend fun deliver(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        messageId: UInt?,
        actorId: UInt,
        wanted: List<Notice>,
        extraTargets: List<UInt> = emptyList(),
        eventId: UInt? = null,
        retentionRecipients: Collection<UInt>,
        beforeInsert: suspend (Set<UInt>) -> List<UInt> = { emptyList() },
    ): NoticeOutcome {
        val muted = mutedInThread(threadId)
        val eligible = wanted.filterNot { it.userId in muted }
        val preferences = effectivePreferencesOf(
            eligible.map { it.userId },
            eligible.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct(),
        )
        val candidates = eligible.mapNotNull { judger.judge(it, preferences) }
        val now = nowMillis()
        val refreshed = if (messageId == null) {
            persistence.refreshThreadNotices(threadId, actorId, now, candidates, eventId)
        } else {
            emptyList()
        }
        val refreshedIds = refreshed.map { it.userId }.toSet()
        val fresh = candidates.filter { it.userId !in refreshedIds }
        val deliveries = withinEmailBudget(actorId, roomId, fresh)
        val late = beforeInsert(deliveries.map { it.userId }.toSet())
        val made = if (deliveries.isEmpty()) {
            emptyList()
        } else {
            persistence.insert(roomId, threadId, messageId, actorId, now, deliveries, eventId)
        }
        val retained = retention.trimLocked(retentionRecipients, refreshed + made)
        if (pushAvailable) enqueueBrowserPush(retained.survivors.map { it.notificationId })
        val madeIds = retained.survivors.map { it.userId }.toSet()
        // The hint's targets keep the audience's priority order, the rows made and the rows refreshed alike.
        val shown = deliveries.filter { it.inApp && it.userId in madeIds }.map { it.userId }.toSet() +
            retained.survivors.filter { it.inApp }.map { it.userId }
        val visibleChanges = (candidates.map { it.userId }.filter { it in shown } + extraTargets + late).filterNot { it in muted }
        // Retention can remove an older bell row in another room; that invalidation is required even when this thread is muted.
        announce(visibleChanges + retained.bellChanged, roomId, threadNumber, messageId)
        val reasons = retained.survivors.map { it.userId to checkNotNull(it.type.toNoticeReason()) }
        if (reasons.isEmpty()) return NoticeOutcome.NONE
        return NoticeOutcome(
            recipients = reasons.size,
            kinds = reasons.groupingBy { it.second }.eachCount(),
            addressed = reasons.filter { it.second in DIRECT }.map { it.first }.take(MAX_AUDITED_ADDRESSED),
        )
    }

    private suspend fun withinEmailBudget(actorId: UInt, roomId: UInt, deliveries: List<Delivery>): List<Delivery> {
        val over = emailBudget.withheld(actorId, roomId, deliveries.filter { it.email }.map { it.userId })
        // Over budget, email-only rows reach nobody; in-app rows still go through.
        return deliveries.map { if (it.userId in over) it.copy(email = false) else it }.filter { it.inApp || it.email }
    }

    /**
     * The wide-event budget (V29): an event reaching more than [WIDE_EVENT_RECIPIENTS] people counts one against the actor's day. Over
     * the budget only the people named directly ([DIRECT]: a `<@id>` mention, the author of a quoted message, a hand-over) stay; the write
     * itself is never refused.
     */
    private suspend fun withinBroadcastBudget(wanted: List<Notice>, roomId: UInt, actorId: UInt): List<Notice> {
        if (wanted.size <= WIDE_EVENT_RECIPIENTS) return wanted
        // Over the budget a direct notice loses its fallbacks too: only the kinds in DIRECT can be told, never a reply it fell back to.
        val direct = wanted.filter { it.reason in DIRECT }.map { Notice(it.userId, it.reason) }
        return if (emailBudget.admitBroadcast(actorId, roomId, withheld = wanted.size - direct.size)) wanted else direct
    }

    /** ONE `notification.created` hint to [recipients] (nothing for nobody), inside the write's own transaction. */
    private suspend fun announce(recipients: List<UInt>, roomId: UInt, threadNumber: Int?, messageId: UInt?) {
        if (recipients.isEmpty()) return
        notifyLive(LiveHint(LiveKind.NOTIFICATION_CREATED, roomId, threadNumber, messageId, targetUserIds = recipients.distinct()))
    }

    private companion object {
        /**
         * The reasons that name a person themself (the audit line lists them: a direct mention, the author of a quoted message and a
         * hand-over); a refused wide event keeps them.
         */
        val DIRECT = setOf(NoticeReason.MENTIONED, NoticeReason.MESSAGE_QUOTED, NoticeReason.THREAD_ASSIGNED)

        /** [DIRECT] for an EDIT: the quote is not among them (see [reviseMessage]). */
        val EDIT_DIRECT = DIRECT - NoticeReason.MESSAGE_QUOTED

        /** The default of the digest delay (`notifications.email.delaySeconds`, 300). */
        const val DEFAULT_DIGEST_DELAY_MILLIS = 300_000L

    }
}
