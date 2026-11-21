package ch.nokillswit.notifications

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.notifications.NotificationService.Notifications
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.ThreadNotices
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/**
 * ONE statement for every recipient of a write, constant text: `unnest` of the recipient ids, `ON CONFLICT DO NOTHING` on
 * `uq_notifications_message`, the people a row was really made for come back. Every parameter is cast, so a null message id (a hand-over)
 * has a type.
 */
private const val INSERT_NOTICES = """
INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at)
SELECT u.r, CAST(? AS int4), CAST(? AS varchar), CAST(? AS int8), CAST(? AS int4), CAST(? AS int4), CAST(? AS int8)
FROM unnest(CAST(? AS int8[])) AS u(r)
ON CONFLICT DO NOTHING
RETURNING recipient_id, id"""

/**
 * The email of these notices is withheld (the per-actor digest budget, V24): `emailed_at` is stamped with the notice's own instant,
 * V22's "settled, never mailed" (the digest's claim and settle skip it). It never touches `seen_at`: the bell is unchanged.
 */
private const val WITHHOLD_EMAIL = """
UPDATE notifications SET emailed_at = created_at WHERE id = ANY(CAST(? AS int4[]))"""

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
 * The chat's [ThreadNotices] port (`chat-model.md` "Notifications (V18)"): the WRITE side of the notices. A notice is a POINTER, ids only:
 * it is made INSIDE the write that caused it (a post, an edit or a hand-over, under the room lock), together with its
 * `notification.created` live hint, so a rolled-back write announces nothing. Rows are hard-deleted with their message
 * ([messageRemoved], [mentionsDropped]); the other two ways a notice goes (the membership it was made for, retention) belong to the
 * schema and to [NotificationService.purgeOlderThan]. The list, the "seen" writes and the retention stay in [NotificationService]; the
 * two share the [NotificationService.Notifications] table object. [emailBudget] is the per-actor digest budget (V24): the default is the
 * config default, a test or the composition root passes its own.
 */
internal class NoticeWriter(
    private val emailBudget: NotificationEmailBudget = NotificationEmailBudget(DEFAULT_EMAIL_BUDGET_PER_ACTOR, ::nowMillis),
) : ThreadNotices {
    override suspend fun mentioned(at: MessageRef, byUserId: UInt, userIds: Collection<UInt>): List<UInt> {
        val recipients = userIds.filter { it != byUserId }.distinct()
        if (recipients.isEmpty()) return emptyList()
        val made = insertNotices(at.roomId, at.threadId, at.messageId, NotificationType.MENTIONED, byUserId, recipients)
        announce(made, at.roomId, at.threadNumber, at.messageId)
        return made
    }

    override suspend fun mentionsDropped(messageId: UInt, userIds: Collection<UInt>) {
        if (userIds.isEmpty()) return
        Notifications.deleteWhere {
            (Notifications.messageId eq messageId) and (Notifications.recipientId inList userIds) and
                (Notifications.type eq NotificationType.MENTIONED.name)
        }
    }

    override suspend fun messageRemoved(messageId: UInt) {
        Notifications.deleteWhere { Notifications.messageId eq messageId }
    }

    override suspend fun ownerAssigned(roomId: UInt, threadId: UInt, threadNumber: Int, byUserId: UInt, toUserId: UInt): Boolean {
        if (toUserId == byUserId) return false
        val made = insertNotices(roomId, threadId, null, NotificationType.THREAD_ASSIGNED, byUserId, listOf(toUserId))
        announce(made, roomId, threadNumber, null)
        return made.isNotEmpty()
    }

    override suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long) {
        val int = IntegerColumnType()
        val long = LongColumnType()
        TransactionManager.current().exec(
            MARK_THREAD_SEEN_SQL,
            listOf(long to at, long to userId.toLong(), int to threadId.toInt(), int to upToMessageId.toInt()),
        )
    }

    /**
     * ONE multi-row `INSERT ... SELECT FROM unnest(...) ON CONFLICT DO NOTHING RETURNING recipient_id` ([INSERT_NOTICES]) under the
     * caller's room lock: answers the people a row was made for (a notice of the same message and kind that already exists is not made
     * twice). `created_at` is the write's own instant ([nowMillis]). Every notice is made (the bell), but the ones past the actor's
     * daily digest budget ([NotificationEmailBudget]) are stamped `emailed_at` at once, so the digest never mails them.
     */
    private suspend fun insertNotices(
        roomId: UInt,
        threadId: UInt,
        messageId: UInt?,
        type: NotificationType,
        actorId: UInt,
        recipients: List<UInt>,
    ): List<UInt> {
        val arguments = listOf(
            IntegerColumnType() to roomId.toInt(),
            TextColumnType() to type.name,
            LongColumnType() to actorId.toLong(),
            IntegerColumnType() to threadId.toInt(),
            IntegerColumnType() to messageId?.toInt(),
            LongColumnType() to nowMillis(),
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to recipients.map { it.toLong() },
        )
        val rows = TransactionManager.current()
            .exec(INSERT_NOTICES, arguments, explicitStatementType = StatementType.SELECT) { row ->
                row.get(0, Long::class.javaObjectType)!!.toUInt() to row.get(1, Int::class.javaObjectType)!!
            }?.toList().orEmpty().filterNotNull()
        // In the order the people were reached, not the order the statement answers them: the budget admits the first ones.
        val made = recipients.filter { r -> rows.any { it.first == r } }
        val over = emailBudget.withheld(actorId, roomId, made)
        if (over.isNotEmpty()) {
            val ids = rows.filter { it.first in over }.map { it.second }
            TransactionManager.current().exec(WITHHOLD_EMAIL, listOf(ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ids))
        }
        return made
    }

    /** ONE `notification.created` hint to [recipients] (nothing for nobody), inside the write's own transaction. */
    private suspend fun announce(recipients: List<UInt>, roomId: UInt, threadNumber: Int, messageId: UInt?) {
        if (recipients.isEmpty()) return
        notifyLive(LiveHint(LiveKind.NOTIFICATION_CREATED, roomId, threadNumber, messageId, targetUserIds = recipients))
    }
}
