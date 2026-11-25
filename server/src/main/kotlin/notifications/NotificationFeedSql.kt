package ch.nokillswit.notifications

import ch.nokillswit.infra.paging.PageRequest
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** The ids-only projection shared by the ordinary and reply-grouped feed readers. */
internal data class FeedPointer(
    val id: UInt,
    val type: NotificationType,
    val seen: Boolean,
    val createdAt: Long,
    val actorId: UInt,
    val roomId: UInt,
    val threadId: UInt?,
    val messageId: UInt?,
    val replyCount: Int? = null,
    val replyFloorId: UInt? = null,
    val replyCeilingId: UInt? = null,
)

/** A grouped drawer read never examines more than this many raw reply notices for one recipient. */
internal const val GROUPED_REPLY_WINDOW = 5_000

private const val GROUPED_FEED_CTE = """
WITH ordinary AS MATERIALIZED (
    SELECT id, notification_type, actor_id, room_id, thread_id, message_id, created_at, seen_at
    FROM notifications
    WHERE recipient_id = CAST(? AS int8)
      AND room_id = ANY(CAST(? AS int4[]))
      AND notification_type = ANY(CAST(? AS text[]))
      AND in_app
      %SEEN%
      AND (notification_type <> 'THREAD_REPLIED' OR seen_at IS NOT NULL)
    %ORDINARY_ORDER%
    LIMIT %ORDINARY_LIMIT%
), recent_replies AS MATERIALIZED (
    SELECT candidate.id, candidate.actor_id, candidate.room_id, candidate.thread_id,
           candidate.message_id, candidate.created_at
    FROM unnest(CAST(? AS int4[])) active(room_id)
    CROSS JOIN LATERAL (
        SELECT id, actor_id, room_id, thread_id, message_id, created_at
        FROM notifications
        WHERE recipient_id = CAST(? AS int8)
          AND room_id = active.room_id
          AND notification_type = 'THREAD_REPLIED'
          AND in_app
          AND seen_at IS NULL
        ORDER BY id DESC
        LIMIT 5000
    ) candidate
    ORDER BY candidate.id DESC
    LIMIT 5000
), reply_groups AS MATERIALIZED (
    SELECT max(id) AS id, min(id) AS reply_floor_id, room_id, thread_id, min(message_id) AS message_id,
           max(created_at) AS created_at, count(*)::int4 AS reply_count
    FROM recent_replies
    GROUP BY room_id, thread_id
), projected AS (
    SELECT id, notification_type, actor_id, room_id, thread_id, message_id, created_at, seen_at,
           NULL::int4 AS reply_count, NULL::int4 AS reply_floor_id, NULL::int4 AS reply_ceiling_id
    FROM ordinary
    UNION ALL
    SELECT grouped.id, 'THREAD_REPLIED' AS notification_type, representative.actor_id,
           grouped.room_id, grouped.thread_id, grouped.message_id, grouped.created_at,
           NULL::int8 AS seen_at, grouped.reply_count, grouped.reply_floor_id, grouped.id AS reply_ceiling_id
    FROM reply_groups grouped
    JOIN recent_replies representative ON representative.id = grouped.id
)
"""

private fun groupedFeedCte(seen: Boolean?, ordinaryOrder: String, ordinaryLimit: Int): String = GROUPED_FEED_CTE
    .replace("%SEEN%", if (seen == false) "AND seen_at IS NULL" else "")
    .replace("%ORDINARY_ORDER%", ordinaryOrder)
    .replace("%ORDINARY_LIMIT%", ordinaryLimit.toString())

private fun groupedFeedArguments(userId: UInt, roomIds: List<Int>, types: Set<NotificationType>) = listOf(
    LongColumnType() to userId.toLong(),
    ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to roomIds,
    ArrayColumnType<String, List<String>>(TextColumnType()) to types.map { it.name },
    ArrayColumnType<Int, List<Int>>(IntegerColumnType()) to roomIds,
    LongColumnType() to userId.toLong(),
)

/** Counts DISPLAYED entries, after unseen plain replies have been collapsed, with the ordinary 1000 cap. */
internal suspend fun countGroupedFeed(userId: UInt, roomIds: List<Int>, types: Set<NotificationType>, seen: Boolean?): Long {
    val sql = groupedFeedCte(seen, ordinaryOrder = "", ordinaryLimit = MAX_COUNTED_NOTICES + 1) +
        "SELECT count(*) FROM (SELECT 1 FROM projected LIMIT ${MAX_COUNTED_NOTICES + 1}) counted"
    return TransactionManager.current().exec(
        sql,
        groupedFeedArguments(userId, roomIds, types),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Long::class.javaObjectType)!! }!!.toList().single()!!
}

/** Reads one page from the already-grouped projection; sort names came through the shared paging whitelist. */
internal suspend fun readGroupedFeed(
    userId: UInt,
    roomIds: List<Int>,
    types: Set<NotificationType>,
    seen: Boolean?,
    paging: PageRequest,
): List<FeedPointer> {
    val order = paging.sort.joinToString { field ->
        val column = when (field.name) {
            "id" -> "id"
            "createdAt" -> "created_at"
            else -> error("notification sort whitelist invariant")
        }
        "$column ${if (field.descending) "DESC" else "ASC"}"
    }
    val offset = (paging.page - 1).toLong() * paging.pageSize
    val ordinaryLimit = (offset + paging.pageSize).coerceAtMost(MAX_COUNTED_NOTICES.toLong()).toInt()
    val sql = groupedFeedCte(seen, ordinaryOrder = "ORDER BY $order", ordinaryLimit = ordinaryLimit) +
        """SELECT id, notification_type, actor_id, room_id, thread_id, message_id, created_at, seen_at,
                  reply_count, reply_floor_id, reply_ceiling_id
           FROM projected ORDER BY $order LIMIT ? OFFSET ?"""
    val arguments = groupedFeedArguments(userId, roomIds, types) + listOf(
        IntegerColumnType() to paging.pageSize,
        LongColumnType() to offset,
    )
    val rows = TransactionManager.current().exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        val type = NotificationType.valueOf(row.get(1, String::class.java)!!)
        FeedPointer(
            id = row.get(0, Int::class.javaObjectType)!!.toUInt(),
            type = type,
            actorId = row.get(2, Long::class.javaObjectType)!!.toUInt(),
            roomId = row.get(3, Int::class.javaObjectType)!!.toUInt(),
            threadId = row.get(4, Int::class.javaObjectType)?.toUInt(),
            messageId = row.get(5, Int::class.javaObjectType)?.toUInt(),
            createdAt = row.get(6, Long::class.javaObjectType)!!,
            seen = row.get(7, Long::class.javaObjectType) != null,
            replyCount = row.get(8, Int::class.javaObjectType),
            replyFloorId = row.get(9, Int::class.javaObjectType)?.toUInt(),
            replyCeilingId = row.get(10, Int::class.javaObjectType)?.toUInt(),
        )
    }!!.toList()
    // Exposed's raw mapper type is nullable so a mapper may skip a row; this mapper returns a FeedPointer on every path.
    @Suppress("UNCHECKED_CAST")
    return rows as List<FeedPointer>
}
