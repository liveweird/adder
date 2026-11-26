package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.memberGuard
import ch.nokillswit.rooms.readStanding
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.security.MessageDigest
import java.util.HexFormat

val MessageReactionServiceKey = AttributeKey<MessageReactionService>("MessageReactionService")

/**
 * One grouped read for a page of messages (`chat-model.md` "Reactions (V20)"): per (message, emoji) the count, whether the reader is among
 * them and the first three reactors by time. The reader's id is the FIRST parameter (it appears first in the text). The names are an
 * `ARRAY(... LIMIT 3)` subquery per group, so a popular emoji never collects its whole list of reactors (a top-3 sort per group, over the
 * key's own prefix); the groups come in the order each emoji first appeared (`COLLATE "C"` makes the tie-break by bytes).
 */
private const val SUMMARIES_SQL = """
SELECT g.message_id, g.emoji, g.cnt, g.mine,
       ARRAY(SELECT r.user_id FROM message_reactions r WHERE r.message_id = g.message_id AND r.emoji = g.emoji
             ORDER BY r.created_at, r.user_id LIMIT 3)
FROM (SELECT message_id, emoji, count(*) AS cnt, bool_or(user_id = ?) AS mine, min(created_at) AS first_at
      FROM message_reactions
      WHERE message_id = ANY(CAST(? AS int4[]))
      GROUP BY message_id, emoji) g
ORDER BY g.first_at, g.emoji"""

/** How many reactors a summary names. */
private const val NAMED_REACTORS = 3

/** Opaque URL-safe handle for one emoji. The chosen emoji itself never enters a URL or request log. */
internal fun reactionKey(emoji: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(emoji.toByteArray()))

/** What a reaction write changed, for the route's audit line (null from the service when the state was already the requested one). */
data class ReactionChange(val threadId: UInt, val messageId: UInt, val reacted: Boolean, val notices: NoticeOutcome = NoticeOutcome.NONE)

private class SummaryRow(val messageId: UInt, val emoji: String, val count: Int, val reacted: Boolean, val reactors: List<UInt>)

/**
 * The reactions of messages (V20 `message_reactions`): the single write, the grouped read the message reads attach, and the follower that
 * clears a deleted message's rows. Reactions can notify the author without bumping activity or unread replies. Each reaction is ONE
 * message write under the room lock ([withMessageWrite], the MEMBER guard on the re-read caller), so the two limits cannot be passed
 * by two writers at once. The emoji is chosen text: it is never logged, audited or put in a path (`security.md` "Log hygiene").
 */
class MessageReactionService internal constructor(
    private val database: R2dbcDatabase,
    private val notices: ThreadNotices?,
) {
    constructor(database: R2dbcDatabase) : this(database, null)
    /** One row per (message, emoji, person); hard-deleted (a toggle), compared by bytes in the database (`COLLATE "C"`). */
    object Reactions : Table("message_reactions") {
        val messageId = reference("message_id", MessageService.Messages)
        val userId = reference("user_id", UserService.Users)
        val emoji = varchar("emoji", MAX_REACTION_UNITS)
        val createdAt = long("created_at")
        override val primaryKey = PrimaryKey(messageId, emoji, userId)
    }

    /**
     * Sets whether [caller] has [emoji] on message [messageId] of room [roomId] ([emoji] is already judged by [requireReactionEmoji]).
     * Under the room lock: the message (a missing, deleted or foreign-room one is `404`), an archived room `409`, a closed thread `409`,
     * then the state it already has is null (nothing written, no hint), the limits `409 reaction-limit` (only ADDING is ever refused),
     * the write and ONE `message.reaction_changed` hint.
     */
    suspend fun set(
        roomId: UInt,
        messageId: UInt,
        caller: CallerPrincipal,
        emoji: String,
        reacted: Boolean,
        mutationId: String? = null,
    ): ReactionChange? =
        database.withMessageWrite(roomId, messageId, caller, memberGuard, actGuard = { _, _, _ -> }) { write ->
            write.threadWrite.requireRoomActive()
            write.threadWrite.requireThreadOpen()
            val me = write.caller.userId
            val mine = Reactions.select(Reactions.emoji)
                .where { (Reactions.messageId eq messageId) and (Reactions.userId eq me) }
                .map { it[Reactions.emoji] }.toList()
            if ((emoji in mine) == reacted) return@withMessageWrite null
            if (reacted) {
                requireWithinLimits(messageId, emoji, mine.size)
                Reactions.insert {
                    it[Reactions.messageId] = messageId
                    it[userId] = me
                    it[Reactions.emoji] = emoji
                    it[createdAt] = nowMillis()
                }
            } else {
                Reactions.deleteWhere { (Reactions.messageId eq messageId) and (Reactions.userId eq me) and (Reactions.emoji eq emoji) }
            }
            notifyLive(
                LiveHint(
                    LiveKind.MESSAGE_REACTION_CHANGED,
                    roomId,
                    threadNumber = write.thread.number,
                    messageId = messageId,
                    mutationId = mutationId,
                    originUserId = caller.userId.takeIf { mutationId != null },
                ),
            )
            val noticeOutcome = if (mine.none { it != emoji }) {
                val ref = MessageRef(roomId, write.thread.id, write.thread.number, messageId, opening = false)
                notifyReaction(ref, me, write.message.authorId, reacted)
            } else {
                NoticeOutcome.NONE
            }
            ReactionChange(write.thread.id, messageId, reacted, noticeOutcome)
        }

    private suspend fun notifyReaction(ref: MessageRef, actor: UInt, author: UInt, reacting: Boolean): NoticeOutcome {
        val writer = notices ?: return NoticeOutcome.NONE
        if (actor == author) return NoticeOutcome.NONE
        if (reacting && readStanding(ref.roomId, author, lock = false)?.role == null) return NoticeOutcome.NONE
        return writer.reactionChanged(ref, actor, author, reacting)
    }

    /** The two limits of ADDING [emoji] to message [messageId] (`409 reaction-limit`): [mineCount] of the caller's already, 20 distinct. */
    private suspend fun requireWithinLimits(messageId: UInt, emoji: String, mineCount: Int) {
        if (mineCount >= MAX_REACTIONS_PER_PERSON) throw reactionLimit()
        val distinct = Reactions.select(Reactions.emoji).where { Reactions.messageId eq messageId }.withDistinct()
            .map { it[Reactions.emoji] }.toList()
        if (emoji !in distinct && distinct.size >= MAX_DISTINCT_REACTIONS) throw reactionLimit()
    }

    /**
     * The reactions of [messageIds] as [viewerId] sees them, by message (a message without any is absent). One grouped read, then the
     * names of the first reactors; runs INSIDE the caller's transaction (the message reads' own).
     */
    internal suspend fun summaries(viewerId: UInt, messageIds: Collection<UInt>): Map<UInt, List<ReactionSummary>> {
        if (messageIds.isEmpty()) return emptyMap()
        val ints = ArrayColumnType<Int, List<Int>>(IntegerColumnType())
        val rows = TransactionManager.current().exec(
            SUMMARIES_SQL,
            listOf(LongColumnType() to viewerId.toLong(), ints to messageIds.map { it.toInt() }),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            SummaryRow(
                messageId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                emoji = row.get(1, String::class.java)!!,
                count = row.get(2, Long::class.javaObjectType)!!.toInt(),
                reacted = row.get(3, Boolean::class.javaObjectType)!!,
                reactors = row.get(4, Array<Long>::class.java)!!.map { it.toUInt() },
            )
        }?.toList().orEmpty().filterNotNull()
        if (rows.isEmpty()) return emptyMap()
        val names = userNames(rows.flatMap { it.reactors })
        return rows.groupBy { it.messageId }.mapValues { (_, perEmoji) ->
            perEmoji.take(MAX_DISTINCT_REACTIONS).map {
                ReactionSummary(
                    it.emoji,
                    reactionKey(it.emoji),
                    it.count,
                    it.reacted,
                    it.reactors.take(NAMED_REACTORS).map(names::getValue),
                )
            }
        }
    }

    /** Every person behind one summary, by reaction time then id, through the summary's opaque [key]. */
    suspend fun reactors(messageId: UInt, key: String, paging: PageRequest): ReactorListResult? = suspendTransaction(database) {
        val emoji = Reactions.select(Reactions.emoji).where { Reactions.messageId eq messageId }.withDistinct()
            .map { it[Reactions.emoji] }.toList().singleOrNull { reactionKey(it) == key } ?: return@suspendTransaction null
        val predicate = (Reactions.messageId eq messageId) and (Reactions.emoji eq emoji)
        val total = Reactions.select(Reactions.userId).where { predicate }.count()
        val rows = Reactions.select(Reactions.userId, Reactions.createdAt).where { predicate }
            .applyPaging(paging, mapOf("id" to Reactions.userId, "createdAt" to Reactions.createdAt)).toList()
        val names = userNames(rows.map { it[Reactions.userId].value })
        ReactorListResult(rows.map { ReactorResponse(it[Reactions.userId].value, names.getValue(it[Reactions.userId].value)) }, total)
    }

    /** The follower of a message's writes: a deleted message loses its reactions in the delete's own transaction. */
    internal val follower: MessageFollower = object : MessageFollower {
        override suspend fun deleted(ref: MessageRef) {
            Reactions.deleteWhere { Reactions.messageId eq ref.messageId }
        }
    }
}
