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
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * ONE statement for every recipient of an event, constant text: `unnest` of the recipients, their kinds and their two per-person flags,
 * `ON CONFLICT DO NOTHING` on `uq_notifications_message_recipient` (one notice per message and person), the notices really made come back.
 * Per row: [in_app] (`u.a`: false is a mail-only notice the bell never shows) and `emailed_at` (`u.s`: the digest must never mail it, so it
 * is stamped with the notice's own instant, V22's "settled, never mailed": the kind's email switch is off, or the actor is over the daily
 * budget). Every parameter is cast, so a null message id (a hand-over, a status move) has a type.
 */
private const val INSERT_NOTICES = """
INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at, in_app, emailed_at)
SELECT u.r, CAST(? AS int4), u.t, CAST(? AS int8), CAST(? AS int4), CAST(? AS int4), CAST(? AS int8), u.a,
       CASE WHEN u.s THEN CAST(? AS int8) END
FROM unnest(CAST(? AS int8[]), CAST(? AS varchar[]), CAST(? AS bool[]), CAST(? AS bool[])) AS u(r, t, a, s)
ON CONFLICT DO NOTHING
RETURNING recipient_id, id, notification_type"""

/**
 * The unseen notices of one person in one thread up to a message become seen (the read marker's write, run without the room lock): the
 * person and the thread are the two leading keys of the partial index `idx_notifications_unseen_thread` (V19), so the statement touches
 * only that person's unseen rows of that thread however many notices they hold. A hand-over has no message and is seen with the thread.
 * Arguments: the instant, the person, the thread, the message.
 */
internal const val MARK_THREAD_SEEN_SQL = """
UPDATE notifications SET seen_at = CAST(? AS int8)
WHERE recipient_id = CAST(? AS int8) AND thread_id = CAST(? AS int4) AND seen_at IS NULL
  AND (message_id IS NULL OR message_id <= CAST(? AS int4))"""

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
    private val emailBudget: NotificationEmailBudget = NotificationEmailBudget(DEFAULT_EMAIL_BUDGET_PER_ACTOR, ::nowMillis),
    /** False when the deployment has no outbound email: nothing can be mailed, so email is never a reason to make a row. */
    private val mailAvailable: Boolean = true,
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
     * Applies an edit's [revision]: the removed people's notices go, the relabelled ones keep their row (and its seen state, and the
     * channels it was made with) under the new kind, the added ones are made like [forMessage]'s.
     */
    override suspend fun reviseMessage(at: MessageRef, byUserId: UInt, revision: NoticeRevision): NoticeOutcome {
        if (revision.remove.isNotEmpty()) {
            Notifications.deleteWhere { (Notifications.messageId eq at.messageId) and (Notifications.recipientId inList revision.remove) }
        }
        for (notice in revision.relabel) {
            Notifications.update({ (Notifications.messageId eq at.messageId) and (Notifications.recipientId eq notice.userId) }) {
                it[type] = notice.reason.toNotificationType().name
            }
        }
        return make(at.roomId, at.threadId, at.threadNumber, at.messageId, byUserId, revision.add)
    }

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

    /**
     * The one path every notice takes: filter (never the actor, a person once), judge the channels, run the email budget, insert in ONE
     * statement and hint the people whose notice shows in the app. [messageId] is null for a thread-level notice.
     */
    private suspend fun make(
        roomId: UInt,
        threadId: UInt,
        threadNumber: Int,
        messageId: UInt?,
        actorId: UInt,
        notices: List<Notice>,
    ): NoticeOutcome {
        val wanted = notices.filter { it.userId != actorId }.distinctBy { it.userId }
        if (wanted.isEmpty()) return NoticeOutcome.NONE
        val preferences = effectivePreferencesOf(wanted.map { it.userId }, wanted.map { it.reason.toNotificationType() }.distinct())
        val candidates = wanted.mapNotNull { notice ->
            val type = notice.reason.toNotificationType()
            val person = preferences[notice.userId] ?: EffectivePreferences.DEFAULTS
            val inApp = person.on(type, NotificationChannel.IN_APP)
            val email = mailAvailable && person.on(type, NotificationChannel.EMAIL)
            if (inApp || email) Delivery(notice.userId, type, inApp, email) else null
        }
        if (candidates.isEmpty()) return NoticeOutcome.NONE
        val over = emailBudget.withheld(actorId, roomId, candidates.filter { it.email }.map { it.userId })
        // Over the budget the email is withheld; a person the app switch also leaves out is reached by nothing: no row.
        val deliveries = candidates.map { if (it.userId in over) it.copy(email = false) else it }.filter { it.inApp || it.email }
        if (deliveries.isEmpty()) return NoticeOutcome.NONE
        val made = insert(roomId, threadId, messageId, actorId, nowMillis(), deliveries)
        val madeIds = made.map { it.first }.toSet()
        announce(deliveries.filter { it.inApp && it.userId in madeIds }.map { it.userId }, roomId, threadNumber, messageId)
        val reasons = made.map { (user, type) -> user to type.toNoticeReason() }
        return NoticeOutcome(
            recipients = made.size,
            kinds = reasons.groupingBy { it.second }.eachCount(),
            addressed = reasons.filter { it.second in DIRECT }.map { it.first }.take(MAX_AUDITED_ADDRESSED),
        )
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
        notifyLive(LiveHint(LiveKind.NOTIFICATION_CREATED, roomId, threadNumber, messageId, targetUserIds = recipients))
    }

    private companion object {
        /** The reasons that name a person themself (the audit line lists them: a direct mention and a hand-over). */
        val DIRECT = setOf(NoticeReason.MENTIONED, NoticeReason.THREAD_ASSIGNED)
    }
}
