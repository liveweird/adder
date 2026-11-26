package ch.nokillswit.notifications

import ch.nokillswit.threads.SeenChunk
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/** Ordered, bounded notice-row writes for read markers and membership removal. */
internal class NoticeSeenWriter(
    private val retention: NotificationRetention,
    private val threadChunk: Int = UNSEEN_PRUNE_BATCH,
    private val afterThreadChunk: suspend () -> Unit = {},
) {
    init {
        require(threadChunk > 0)
    }

    suspend fun threadRead(threadId: UInt, userId: UInt, upToMessageId: UInt, at: Long) {
        val int = IntegerColumnType()
        val long = LongColumnType()
        val ceiling = TransactionManager.current().exec(
            THREAD_UNSEEN_CEILING_SQL,
            listOf(long to userId.toLong(), int to threadId.toInt(), int to upToMessageId.toInt()),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)?.toUInt() }?.toList().orEmpty().singleOrNull() ?: return
        var after = 0u
        do {
            val ids = TransactionManager.current().exec(
                THREAD_SEEN_CHUNK_IDS_SQL,
                listOf(
                    long to userId.toLong(), int to threadId.toInt(), int to upToMessageId.toInt(), int to after.toInt(),
                    int to ceiling.toInt(), int to threadChunk,
                ),
                explicitStatementType = StatementType.SELECT,
            ) { row -> row.get(0, Int::class.javaObjectType)?.toUInt() }?.toList().orEmpty().filterNotNull()
            if (ids.isNotEmpty()) {
                TransactionManager.current().exec(
                    MARK_IDS_SEEN_SQL,
                    listOf(long to at, long to userId.toLong(), ArrayColumnType<Int, List<Int>>(int) to ids.map(UInt::toInt)),
                    explicitStatementType = StatementType.SELECT,
                ) { row -> row.get(0, Int::class.javaObjectType) }?.toList()
                after = ids.last()
                afterThreadChunk()
            }
        } while (ids.size == threadChunk)
    }

    suspend fun prepareMemberRemoval(roomId: UInt, userId: UInt) {
        retention.lockRecipients(listOf(userId))
        retention.deleteMembershipNotices(roomId, userId)
    }

    suspend fun roomUnseenCeiling(roomId: UInt, userId: UInt): UInt? {
        val rows = TransactionManager.current().exec(
            ROOM_UNSEEN_CEILING_SQL,
            listOf(LongColumnType() to userId.toLong(), IntegerColumnType() to roomId.toInt()),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)?.toUInt() }
        return rows?.toList().orEmpty().singleOrNull()
    }

    suspend fun roomSeenChunk(roomId: UInt, userId: UInt, at: Long, after: UInt, ceiling: UInt, limit: Int): SeenChunk {
        val int = IntegerColumnType()
        val long = LongColumnType()
        val transaction = TransactionManager.current()
        val ids = transaction.exec(
            ROOM_SEEN_CHUNK_IDS_SQL,
            listOf(long to userId.toLong(), int to roomId.toInt(), int to after.toInt(), int to ceiling.toInt(), long to at, int to limit),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!! }?.toList().orEmpty().filterNotNull()
        if (ids.isEmpty()) return SeenChunk(0, 0, null)
        val moved = transaction.exec(
            MARK_IDS_SEEN_SQL,
            listOf(long to at, long to userId.toLong(), ArrayColumnType<Int, List<Int>>(int) to ids),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Int::class.javaObjectType)!! }?.toList().orEmpty()
        return SeenChunk(ids.size, moved.size, ids.last().toUInt())
    }
}
