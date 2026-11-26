package ch.nokillswit.notifications

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** Constant-shape inserts and replacements shared by every notice-producing write. */
internal class NoticePersistence(private val digestDelayMillis: Long) {
    suspend fun refreshThreadNotices(
        threadId: UInt,
        actorId: UInt,
        now: Long,
        deliveries: List<Delivery>,
        eventId: UInt?,
    ): List<PersistedNotice> {
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
                IntegerColumnType() to eventId?.toInt(),
            ),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            PersistedNotice(
                row.get(0, Long::class.javaObjectType)!!.toUInt(),
                row.get(3, Int::class.javaObjectType)!!.toUInt(),
                NotificationType.valueOf(row.get(1, String::class.java)!!),
                row.get(2, Boolean::class.javaObjectType) == true,
                row.get(4, Boolean::class.javaObjectType) == true,
            )
        }?.toList().orEmpty().filterNotNull()
        return rows.distinctBy { it.userId to it.type }
    }

    suspend fun insert(
        roomId: UInt,
        threadId: UInt?,
        messageId: UInt?,
        actorId: UInt,
        now: Long,
        deliveries: List<Delivery>,
        eventId: UInt? = null,
    ): List<PersistedNotice> {
        val arguments = listOf(
            IntegerColumnType() to roomId.toInt(),
            LongColumnType() to actorId.toLong(),
            IntegerColumnType() to threadId?.toInt(),
            IntegerColumnType() to messageId?.toInt(),
            LongColumnType() to now,
            LongColumnType() to now,
            IntegerColumnType() to eventId?.toInt(),
            ArrayColumnType<Long, List<Long>>(LongColumnType()) to deliveries.map { it.userId.toLong() },
            ArrayColumnType<String, List<String>>(TextColumnType()) to deliveries.map { it.type.name },
            ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to deliveries.map { it.inApp },
            ArrayColumnType<Boolean, List<Boolean>>(BooleanColumnType()) to deliveries.map { !it.email },
        )
        val rows = TransactionManager.current()
            .exec(INSERT_NOTICES, arguments, explicitStatementType = StatementType.SELECT) { row ->
                PersistedNotice(
                    row.get(0, Long::class.javaObjectType)!!.toUInt(),
                    row.get(1, Int::class.javaObjectType)!!.toUInt(),
                    NotificationType.valueOf(row.get(2, String::class.java)!!),
                    row.get(3, Boolean::class.javaObjectType) == true,
                )
            }?.toList().orEmpty().filterNotNull()
        return rows
    }

    private companion object {
        val THREAD_LEVEL_KINDS = setOf(
            NotificationType.THREAD_STATUS_CHANGED,
            NotificationType.THREAD_CLOSED,
            NotificationType.THREAD_REOPENED,
            NotificationType.THREAD_OWNER_CHANGED,
            NotificationType.THREAD_ASSIGNED,
        )
    }
}
