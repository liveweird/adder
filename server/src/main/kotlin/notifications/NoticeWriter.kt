package ch.nokillswit.notifications

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.notifications.NotificationService.Notifications
import ch.nokillswit.threads.MAX_AUDITED_ADDRESSED
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeOutcome
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.NoticeRevision
import ch.nokillswit.threads.ThreadNotices
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

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
 * daily budget; `in_app = false` when the app switch is off). In-app off and nothing mailable (email off, no mailer or over the budget)
 * makes no row. A row that is not mailable is settled at insert (`emailed_at = created_at`). The budget runs BEFORE the insert, on the
 * email-eligible recipients only, in the order the caller listed them (the most specific first), so a caller that can address a person
 * twice must drop the people who already have a notice of the message (`ofMessage`) before calling.
 */
internal class NoticeWriter(
    private val emailBudget: NotificationEmailBudget = NotificationEmailBudget(DEFAULT_EMAIL_BUDGET_PER_ACTOR, clock = ::nowMillis),
    /** False when the deployment has no outbound email: nothing can be mailed, so email is never a reason to make a row. */
    private val mailAvailable: Boolean = true,
    /** The digest's delay (`notifications.email.delaySeconds`): a notice still waiting for it is coalesced into only while younger. */
    private val digestDelayMillis: Long = DEFAULT_DIGEST_DELAY_MILLIS,
) : ThreadNotices {
    override suspend fun forMessage(at: MessageRef, byUserId: UInt, notices: List<Notice>): NoticeOutcome =
        make(at.roomId, at.threadId, at.threadNumber, at.messageId, byUserId, notices)

    override suspend fun forThread(roomId: UInt, threadId: UInt, threadNumber: Int, byUserId: UInt, notices: List<Notice>): NoticeOutcome =
        make(roomId, threadId, threadNumber, null, byUserId, notices)

    override suspend fun ofMessage(messageId: UInt): Map<UInt, NoticeReason> {
        val n = Notifications
        return n.select(n.recipientId, n.type).where { n.messageId eq messageId }
            .map { it[n.recipientId] to it[n.type] }.toList()
            .mapNotNull { (recipient, name) ->
                NotificationType.entries.firstOrNull { it.name == name }?.let { recipient to it.toNoticeReason() }
            }.toMap()
    }

    /**
     * Applies an edit's [revision]: the removed people's notices go, the relabelled ones keep their row (and its seen state) under the
     * new kind with their channels judged again ([relabel]), the added ones are made like [forMessage]'s. The deletes are ONE statement,
     * the relabels ONE statement. People whose bell changed (a notice that stopped showing, started showing, or moved in or out of the
     * addressed kinds) get ONE `notification.created` hint, so their open tabs re-read.
     */
    override suspend fun reviseMessage(at: MessageRef, byUserId: UInt, revision: NoticeRevision): NoticeOutcome {
        val (add, remove) = withoutUnchangedUpgrades(at.messageId, revision)
        val relabelled = relabel(at.messageId, revision.relabel)
        val deletes = (remove + relabelled.deleted).toSet()
        if (deletes.isNotEmpty()) {
            Notifications.deleteWhere { (Notifications.messageId eq at.messageId) and (Notifications.recipientId inList deletes) }
        }
        announce(relabelled.changedBell, at.roomId, at.threadNumber, at.messageId)
        return make(at.roomId, at.threadId, at.threadNumber, at.messageId, byUserId, add)
    }

    /**
     * A "delete and insert" upgrade (a less specific notice, now addressed more specifically) is a fresh unseen notice only when the
     * person's channels really make a DIFFERENT kind of it: when the kind the writer would choose (their fallback when the top kind is
     * switched off) equals the one they hold, the row stays - no fresh unseen row, no second charge to the email budget.
     */
    private suspend fun withoutUnchangedUpgrades(messageId: UInt, revision: NoticeRevision): Pair<List<Notice>, Collection<UInt>> {
        val upgrades = revision.add.filter { it.userId in revision.remove }
        if (upgrades.isEmpty()) return revision.add to revision.remove
        val held = heldRows(messageId, upgrades.map { it.userId })
        val kinds = upgrades.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct()
        val preferences = effectivePreferencesOf(upgrades.map { it.userId }, kinds)
        val unchanged = upgrades.filter { judge(it, preferences)?.type == held[it.userId]?.type }.map { it.userId }.toSet()
        return revision.add.filter { it.userId !in unchanged } to revision.remove.filter { it !in unchanged }
    }

    /** What [relabel] decided: the people whose row goes, and the people whose bell changes (a hint for them). */
    private class Relabelled(val deleted: List<UInt>, val changedBell: List<UInt>)

    /**
     * A relabelled notice keeps its row (and its seen state) under the new kind, but the person's channels are judged again for it, with
     * the usual fallback to their next applicable reason: a kind switched off in the app is no longer shown (`in_app` follows the
     * verdict), and a row that would be neither shown nor mailable is deleted. Email counts as reachable only while the row has not been
     * mailed or settled (`emailed_at IS NULL`): that stamp is never reopened, so a settled row cannot be revived by an edit. One read of
     * the rows, one update, the deletes joining the caller's.
     */
    private suspend fun relabel(messageId: UInt, relabels: List<Notice>): Relabelled {
        if (relabels.isEmpty()) return Relabelled(emptyList(), emptyList())
        val transaction = TransactionManager.current()
        val held = heldRows(messageId, relabels.map { it.userId })
        val kinds = relabels.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct()
        val preferences = effectivePreferencesOf(relabels.map { it.userId }, kinds)
        val deleted = mutableListOf<UInt>()
        val changed = mutableListOf<UInt>()
        val updates = mutableListOf<Delivery>()
        for (notice in relabels) {
            val row = held[notice.userId] ?: continue
            val verdict = judge(notice, preferences, emailReachable = row.unmailed)
            if (verdict == null) {
                deleted += notice.userId
                if (row.inApp) changed += notice.userId
                continue
            }
            updates += verdict
            val addressedBefore = row.type?.let { it in ADDRESSED } ?: false
            if (row.inApp != verdict.inApp || addressedBefore != (verdict.type in ADDRESSED)) changed += notice.userId
        }
        if (updates.isNotEmpty()) {
            transaction.exec(
                RELABEL_ROWS,
                listOf(
                    ArrayColumnType<Long, List<Long>>(LongColumnType()) to updates.map { it.userId.toLong() },
                    ArrayColumnType<String, List<String>>(TextColumnType()) to updates.map { it.type.name },
                    ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to updates.map { it.inApp },
                    IntegerColumnType() to messageId.toInt(),
                ),
            )
        }
        return Relabelled(deleted, changed.distinct())
    }

    /** The rows [users] hold about message [messageId]: kind, whether they show, whether their mail is still to come. */
    private suspend fun heldRows(messageId: UInt, users: List<UInt>): Map<UInt, Held> = TransactionManager.current().exec(
        READ_RELABEL_ROWS,
        listOf(
            IntegerColumnType() to messageId.toInt(),
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to users.map { it.toLong() },
        ),
        explicitStatementType = StatementType.SELECT,
    ) { row ->
        Held(
            row.get(0, Long::class.javaObjectType)!!.toUInt(),
            NotificationType.entries.firstOrNull { it.name == row.get(1, String::class.java) },
            row.get(2, Boolean::class.javaObjectType) == true,
            row.get(3, Boolean::class.javaObjectType) == true,
        )
    }?.toList().orEmpty().filterNotNull().associateBy { it.userId }

    /** One existing row of an edit's relabel: the person, its kind, whether it shows, and whether its mail is still to come. */
    private class Held(val userId: UInt, val type: NotificationType?, val inApp: Boolean, val unmailed: Boolean)

    override suspend fun messageRemoved(messageId: UInt) {
        Notifications.deleteWhere { Notifications.messageId eq messageId }
    }

    override suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long) {
        val int = IntegerColumnType()
        val long = LongColumnType()
        TransactionManager.current().exec(
            MARK_THREAD_SEEN_SQL,
            listOf(long to at, long to userId.toLong(), int to threadId.toInt(), int to upToMessageId.toInt()),
        )
    }

    /** One person the writer will insert a row for: the kind and the two channel verdicts. */
    private data class Delivery(val userId: UInt, val type: NotificationType, val inApp: Boolean, val email: Boolean)

    private fun Notice.allReasons(): List<NoticeReason> = listOf(reason) + fallbacks

    /**
     * How [notice] reaches its person: the FIRST of its reasons (the most specific, then the fallbacks in priority order) whose kind is
     * switched on for the app or for email, with the channels that are on; null when every applicable reason is off on both channels.
     * [emailReachable] is false when email cannot be the reason (the row was already mailed or settled).
     */
    private fun judge(notice: Notice, preferences: Map<UInt, EffectivePreferences>, emailReachable: Boolean = true): Delivery? {
        val person = preferences[notice.userId] ?: EffectivePreferences.DEFAULTS
        for (reason in notice.allReasons()) {
            val type = reason.toNotificationType()
            val inApp = person.on(type, NotificationChannel.IN_APP)
            val email = mailAvailable && emailReachable && person.on(type, NotificationChannel.EMAIL)
            if (inApp || email) return Delivery(notice.userId, type, inApp, email)
        }
        return null
    }

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
    ): NoticeOutcome {
        val everyone = notices.filter { it.userId != actorId }.distinctBy { it.userId }
        val wanted = withinBroadcastBudget(everyone, roomId, actorId)
        if (wanted.isEmpty()) return NoticeOutcome.NONE
        val preferences = effectivePreferencesOf(
            wanted.map { it.userId },
            wanted.flatMap { it.allReasons() }.map { it.toNotificationType() }.distinct(),
        )
        val candidates = wanted.mapNotNull { judge(it, preferences) }
        if (candidates.isEmpty()) return NoticeOutcome.NONE
        val now = nowMillis()
        val refreshed = if (messageId == null) refreshThreadNotices(threadId, actorId, now, candidates) else emptyList()
        val refreshedIds = refreshed.map { it.first }.toSet()
        val fresh = candidates.filter { it.userId !in refreshedIds }
        val over = emailBudget.withheld(actorId, roomId, fresh.filter { it.email }.map { it.userId })
        // Over the budget the email is withheld; a person the app switch also leaves out is reached by nothing: no row.
        val deliveries = fresh.map { if (it.userId in over) it.copy(email = false) else it }.filter { it.inApp || it.email }
        val made = if (deliveries.isEmpty()) emptyList() else insert(roomId, threadId, messageId, actorId, now, deliveries)
        val madeIds = made.map { it.first }.toSet()
        // The hint's targets keep the audience's priority order, the rows made and the rows refreshed alike.
        val shown = deliveries.filter { it.inApp && it.userId in madeIds }.map { it.userId }.toSet() +
            refreshed.filter { it.third }.map { it.first }
        announce(candidates.map { it.userId }.filter { it in shown }, roomId, threadNumber, messageId)
        val reasons = (made + refreshed.map { it.first to it.second }).map { (user, type) -> user to type.toNoticeReason() }
        if (reasons.isEmpty()) return NoticeOutcome.NONE
        return NoticeOutcome(
            recipients = reasons.size,
            kinds = reasons.groupingBy { it.second }.eachCount(),
            addressed = reasons.filter { it.second in DIRECT }.map { it.first }.take(MAX_AUDITED_ADDRESSED),
        )
    }

    /**
     * Replaces the unseen thread-level notices ([REFRESH_THREAD_NOTICES]) that [deliveries] of a coalescing kind would repeat; answers
     * (person, kind, shows in the app) of the rows made in their place. One statement, and none when no delivery is of a coalescing kind.
     */
    private suspend fun refreshThreadNotices(
        threadId: UInt,
        actorId: UInt,
        now: Long,
        deliveries: List<Delivery>,
    ): List<Triple<UInt, NotificationType, Boolean>> {
        val coalescing = deliveries.filter { it.type in THREAD_LEVEL_KINDS }
        if (coalescing.isEmpty()) return emptyList()
        val rows = TransactionManager.current().exec(
            REFRESH_THREAD_NOTICES,
            listOf(
                ArrayColumnType<Long, List<Long>>(LongColumnType()) to coalescing.map { it.userId.toLong() },
                ArrayColumnType<String, List<String>>(TextColumnType()) to coalescing.map { it.type.name },
                ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to coalescing.map { it.inApp },
                IntegerColumnType() to threadId.toInt(),
                LongColumnType() to now - digestDelayMillis,
                LongColumnType() to actorId.toLong(),
                IntegerColumnType() to threadId.toInt(),
                LongColumnType() to now,
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            Triple(
                row.get(0, Long::class.javaObjectType)!!.toUInt(),
                row.get(1, String::class.java)!!,
                row.get(2, Boolean::class.javaObjectType) == true,
            )
        }?.toList().orEmpty().filterNotNull()
        return rows
            .mapNotNull { (user, name, inApp) -> NotificationType.entries.firstOrNull { it.name == name }?.let { Triple(user, it, inApp) } }
            .distinctBy { it.first to it.second }
    }

    /**
     * The wide-event budget (V29): an event reaching more than [WIDE_EVENT_RECIPIENTS] people counts one against the actor's day. Over
     * the budget only the people named directly ([DIRECT]: a `<@id>` mention, a hand-over) stay; the write itself is never refused.
     */
    private suspend fun withinBroadcastBudget(wanted: List<Notice>, roomId: UInt, actorId: UInt): List<Notice> {
        if (wanted.size <= WIDE_EVENT_RECIPIENTS) return wanted
        // Over the budget a direct notice loses its fallbacks too: only the kinds in DIRECT can be told, never a reply it fell back to.
        val direct = wanted.filter { it.reason in DIRECT }.map { Notice(it.userId, it.reason) }
        return if (emailBudget.admitBroadcast(actorId, roomId, withheld = wanted.size - direct.size)) wanted else direct
    }

    /** [INSERT_NOTICES] for [deliveries] (a notice that cannot be mailed is settled at insert); answers (person, kind) made. */
    private suspend fun insert(
        roomId: UInt,
        threadId: UInt,
        messageId: UInt?,
        actorId: UInt,
        now: Long,
        deliveries: List<Delivery>,
    ): List<Pair<UInt, NotificationType>> {
        val arguments = listOf(
            IntegerColumnType() to roomId.toInt(),
            LongColumnType() to actorId.toLong(),
            IntegerColumnType() to threadId.toInt(),
            IntegerColumnType() to messageId?.toInt(),
            LongColumnType() to now,
            LongColumnType() to now,
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to deliveries.map { it.userId.toLong() },
            ArrayColumnType<String, List<String>>(TextColumnType()) to deliveries.map { it.type.name },
            ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to deliveries.map { it.inApp },
            ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to deliveries.map { !it.email },
        )
        return TransactionManager.current()
            .exec(INSERT_NOTICES, arguments, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)!!.toUInt() to row.get(2, String::class.java)!!
            }?.toList().orEmpty().filterNotNull()
            .mapNotNull { (user, name) -> NotificationType.entries.firstOrNull { it.name == name }?.let { user to it } }
    }

    /** ONE `notification.created` hint to [recipients] (nothing for nobody), inside the write's own transaction. */
    private suspend fun announce(recipients: List<UInt>, roomId: UInt, threadNumber: Int, messageId: UInt?) {
        if (recipients.isEmpty()) return
        notifyLive(LiveHint(LiveKind.NOTIFICATION_CREATED, roomId, threadNumber, messageId, targetUserIds = recipients.distinct()))
    }

    private companion object {
        /** The reasons that name a person themself (the audit line lists them: a direct mention and a hand-over). */
        val DIRECT = setOf(NoticeReason.MENTIONED, NoticeReason.THREAD_ASSIGNED)

        /** The default of the digest delay (`notifications.email.delaySeconds`, 300). */
        const val DEFAULT_DIGEST_DELAY_MILLIS = 300_000L

        /** The kinds with no message to be unique by: a repeat is folded into the person's unseen row ([REFRESH_THREAD_NOTICES]). */
        val THREAD_LEVEL_KINDS = setOf(
            NotificationType.THREAD_STATUS_CHANGED,
            NotificationType.THREAD_CLOSED,
            NotificationType.THREAD_REOPENED,
            NotificationType.THREAD_OWNER_CHANGED,
            NotificationType.THREAD_ASSIGNED,
        )

        /** The kinds addressed TO the person (the four mention kinds and a hand-over): what the rail's count and the `@` marks count. */
        val ADDRESSED = setOf(
            NotificationType.MENTIONED,
            NotificationType.TEAM_MENTIONED,
            NotificationType.WATCHERS_MENTIONED,
            NotificationType.EVERYONE_MENTIONED,
            NotificationType.THREAD_ASSIGNED,
        )
    }
}
