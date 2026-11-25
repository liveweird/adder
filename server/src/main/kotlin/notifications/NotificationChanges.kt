package ch.nokillswit.notifications

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

private val WORKFLOW_NOTICE_KINDS = setOf(
    NotificationType.THREAD_ASSIGNED,
    NotificationType.THREAD_OWNER_CHANGED,
    NotificationType.THREAD_STATUS_CHANGED,
    NotificationType.THREAD_CLOSED,
    NotificationType.THREAD_REOPENED,
)

internal fun workflowNoticeIds(pointers: List<FeedPointer>): List<Int> =
    pointers.filter { it.type in WORKFLOW_NOTICE_KINDS }.map { it.id.toInt() }

/** Looks up only workflow events for notices on the already-authorized page, with the thread bound in the join. */
internal suspend fun R2dbcTransaction.workflowNoticeChanges(ids: List<Int>): Map<UInt, NotificationChange> = exec(
    WORKFLOW_CHANGES_SQL,
    listOf(ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to ids),
    explicitStatementType = StatementType.SELECT,
) { row ->
    row.get(0, Int::class.javaObjectType)!!.toUInt() to NotificationChange(
        row.get(1, String::class.java)!!,
        row.get(2, String::class.java)!!,
    )
}!!.toList().filterNotNull().toMap()

private const val WORKFLOW_CHANGES_SQL = """
SELECT id, from_value, to_value FROM (
    SELECT n.id,
           e.params::jsonb ->> CASE WHEN e.event_type = 'OWNER_CHANGED' THEN 'fromName' ELSE 'fromLabel' END AS from_value,
           e.params::jsonb ->> CASE WHEN e.event_type = 'OWNER_CHANGED' THEN 'toName' ELSE 'toLabel' END AS to_value
    FROM notifications n JOIN thread_events e ON e.id = n.event_id AND e.thread_id = n.thread_id
    WHERE n.id = ANY(CAST(? AS int4[])) AND e.event_type IN ('STATUS_CHANGED', 'CLOSED', 'REOPENED', 'OWNER_CHANGED')
) changes WHERE from_value IS NOT NULL AND to_value IS NOT NULL
"""
