package ch.nokillswit.notifications

import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** Enqueues only notices created now; enabling push never replays historical rows. Runs in the notice write's transaction. */
internal suspend fun enqueueBrowserPush(notificationIds: List<UInt>) {
    if (notificationIds.isEmpty()) return
    TransactionManager.current().exec(
        ENQUEUE_PUSH_SQL,
        listOf(ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to notificationIds.map(UInt::toInt)),
        explicitStatementType = StatementType.INSERT,
    )
}

private const val ENQUEUE_PUSH_SQL = """
INSERT INTO notification_push_queue (notification_id, subscription_id)
SELECT n.id, s.id
FROM notifications n
JOIN browser_push_subscriptions s ON s.user_id = n.recipient_id
JOIN users u ON u.id = n.recipient_id AND NOT u.marked_as_deleted AND u.credential_revision = s.credential_revision
WHERE n.id = ANY(CAST(? AS int4[])) AND n.in_app AND n.seen_at IS NULL
ON CONFLICT DO NOTHING
"""
