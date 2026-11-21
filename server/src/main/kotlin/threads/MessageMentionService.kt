package ch.nokillswit.threads

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.users.UserService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select

/** What an edit did to a message's mentions: the people newly mentioned and the people whose mention the edit removed. */
internal data class MentionChange(val added: List<UInt>, val removed: List<UInt>)

/**
 * The derived mention index (V17 `message_mentions`, `chat-model.md` "Mentions (V17)"): what a message's `mentions` and the thread list's
 * `mentionedId` filter read. A mention belongs to a MESSAGE; a thread is mentioned while one of its LIVE messages mentions the
 * person. It is the first [MessageFollower] of a message write: the three writes run INSIDE the message's own transaction under the
 * room lock, after the row's own statement, so a body and its mentions commit or roll back together and a delete cannot leave a mention
 * behind a scrubbed body. The table is hard-deleted (`persistence.md` "Mentions (V17)"). Ids only: a body or a name never reaches a log.
 * It is also the one place the chat tells the notification feature about a mention ([notices], the [ThreadNotices] port): a post notifies
 * the members it mentions, an edit only the newly mentioned (and drops the notice of a mention it removed) and a delete removes every
 * notice of the message, all in the same transaction. (1.1.0 S5 makes only `MENTIONED` here; the watchers and the room join the audience
 * with `NoticeAudience`.)
 *
 * Only an ACTIVE member of the room, at the moment of the write ([RoomMemberService.activeMemberIds], inside the write), is mentioned; any
 * other id is plain text, no row, never a refusal. [edited] gets only the NEW body (the row has already been overwritten when a follower
 * runs), so it diffs the new body against its OWN rows, never against the old body.
 */
internal class MessageMentionService(
    private val members: RoomMemberService,
    private val notices: ThreadNotices,
) : MessageFollower {
    /** The derived index. There is no soft delete and no id of its own. */
    object MessageMentions : Table("message_mentions") {
        val messageId = reference("message_id", MessageService.Messages)
        val userId = reference("user_id", UserService.Users)

        /** Copied from the message's thread (a message never changes thread): the filter starts at one person in one room. */
        val threadId = reference("thread_id", ThreadService.Threads)

        /** Copied from the thread's room (a thread never changes room). */
        val roomId = reference("room_id", RoomService.Rooms)
        override val primaryKey = PrimaryKey(messageId, userId)
    }

    override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): NoticeOutcome =
        notices.forMessage(ref, authorId, indexInTransaction(ref, body).map { Notice(it, NoticeReason.MENTIONED) })

    override suspend fun edited(ref: MessageRef, authorId: UInt, body: String): NoticeOutcome {
        val change = replaceInTransaction(ref, body)
        return notices.reviseMessage(
            ref,
            authorId,
            NoticeRevision(add = change.added.map { Notice(it, NoticeReason.MENTIONED) }, remove = change.removed),
        )
    }

    override suspend fun deleted(ref: MessageRef) {
        clearInTransaction(ref.messageId)
        notices.messageRemoved(ref.messageId)
    }

    /** Indexes the mentions of a NEW message [ref] (no row exists yet) and answers the members mentioned, in order of appearance. */
    suspend fun indexInTransaction(ref: MessageRef, body: String): List<UInt> {
        val mentioned = parseMentions(body).takeIf { it.isNotEmpty() }?.let { activeMembers(ref.roomId, it) }.orEmpty()
        insert(ref, mentioned)
        return mentioned
    }

    /**
     * Brings the rows of message [ref] in step with its edited [body]: a person still named keeps their row, a person no longer named
     * loses it, a new member gets one; an unchanged set writes nothing. A person already indexed stays mentioned while the token stays,
     * whatever happened to their membership since (the name stays on the message, as a mention of someone who left does).
     */
    suspend fun replaceInTransaction(ref: MessageRef, body: String): MentionChange {
        val index = MessageMentions
        val existing = index.select(index.userId).where { index.messageId eq ref.messageId }
            .map { it[index.userId].value }.toList()
        val parsed = parseMentions(body)
        if (parsed.isEmpty() && existing.isEmpty()) return MentionChange(emptyList(), emptyList())
        val known = existing.toSet()
        val fresh = parsed.filter { it !in known }
        val joined = fresh.takeIf { it.isNotEmpty() }?.let { activeMembers(ref.roomId, it) }.orEmpty().toSet()
        val wanted = parsed.filter { it in known || it in joined }
        val wantedSet = wanted.toSet()
        val removed = existing.filter { it !in wantedSet }
        val added = wanted.filter { it !in known }
        if (removed.isNotEmpty()) index.deleteWhere { (messageId eq ref.messageId) and (userId inList removed) }
        insert(ref, added)
        return MentionChange(added, removed)
    }

    /** Removes every mention of message [messageId]: its delete, a moderator's included. */
    suspend fun clearInTransaction(messageId: UInt) {
        MessageMentions.deleteWhere { MessageMentions.messageId eq messageId }
    }

    /**
     * The people the messages [messageIds] mention, by current display name (soft-deleted accounts included: a name outlives the
     * account), ordered by user id within a message; ONE query for the whole page. Runs INSIDE the caller's transaction.
     */
    suspend fun mentionsOf(messageIds: Collection<UInt>): Map<UInt, List<MentionedUser>> {
        if (messageIds.isEmpty()) return emptyMap()
        val index = MessageMentions
        val users = UserService.Users
        return index.innerJoin(users).select(index.messageId, index.userId, users.name)
            .where { index.messageId inList messageIds.toSet() }
            .orderBy(index.messageId to SortOrder.ASC, index.userId to SortOrder.ASC)
            .map { it[index.messageId].value to MentionedUser(it[index.userId].value, it[users.name]) }
            .toList()
            .groupBy({ it.first }, { it.second })
    }

    /** The ACTIVE members of room [roomId] among [ids], in the order of [ids]. */
    private suspend fun activeMembers(roomId: UInt, ids: List<UInt>): List<UInt> {
        val active = members.activeMemberIds(roomId, ids.map { it.toLong() })
        return ids.filter { it.toLong() in active }
    }

    private suspend fun insert(ref: MessageRef, userIds: List<UInt>) {
        if (userIds.isEmpty()) return
        MessageMentions.insertRows(userIds) {
            this[MessageMentions.messageId] = ref.messageId
            this[MessageMentions.userId] = it
            this[MessageMentions.threadId] = ref.threadId
            this[MessageMentions.roomId] = ref.roomId
        }
    }
}

/**
 * `EXISTS (SELECT 1 FROM message_mentions WHERE room_id = <roomId> AND thread_id = <threadId> AND user_id IN (<userIds>))`: the thread has
 * a live message that mentions any of [userIds] (already validated ids). Correlated on [threadId], the outer list's `threads.id`; the
 * room predicate lets PostgreSQL probe `idx_message_mentions_user_room` for the people's rows in that room alone and semi-join them.
 */
internal fun threadMentionsAny(roomId: UInt, threadId: Column<EntityID<UInt>>, userIds: List<UInt>): Op<Boolean> {
    val index = MessageMentionService.MessageMentions
    return exists(
        index.select(index.messageId).where {
            (index.roomId eq roomId) and (index.threadId eq threadId) and (index.userId inList userIds)
        },
    )
}
