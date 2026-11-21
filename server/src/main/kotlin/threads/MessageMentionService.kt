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

/**
 * What an edit did to a message's mentions: the people newly mentioned, the people whose mention the edit removed and the people the
 * new body mentions NOW ([current], in order of appearance: the kept ones, then the added; a kept person may have left the room since).
 */
internal data class MentionChange(val added: List<UInt>, val removed: List<UInt>, val current: List<UInt>)

/**
 * The derived mention index (V17 `message_mentions`, `chat-model.md` "Mentions (V17)"): what a message's `mentions` and the thread list's
 * `mentionedId` filter read. A mention belongs to a MESSAGE; a thread is mentioned while one of its LIVE messages mentions the
 * person. It is the first [MessageFollower] of a message write: the three writes run INSIDE the message's own transaction under the
 * room lock, after the row's own statement, so a body and its mentions commit or roll back together and a delete cannot leave a mention
 * behind a scrubbed body. The table is hard-deleted (`persistence.md` "Mentions (V17)"). Ids only: a body or a name never reaches a log.
 * It is also the one place the chat tells the notification feature about a mention ([notices], the [ThreadNotices] port): a post notifies
 * the members it mentions, an edit only the newly mentioned (and drops the notice of a mention it removed) and a delete removes every
 * notice of the message, all in the same transaction; WHO is told and why is the pure [NoticeAudience] (watchers for a reply, the room
 * for an opening message, a direct mention first), fed with facts read here, inside the write.
 *
 * Only an ACTIVE member of the room, at the moment of the write ([RoomMemberService.activeMemberIds], inside the write), is mentioned; any
 * other id is plain text, no row, never a refusal. [edited] gets only the NEW body (the row has already been overwritten when a follower
 * runs), so it diffs the new body against its OWN rows, never against the old body.
 */
internal class MessageMentionService(
    private val members: RoomMemberService,
    private val notices: ThreadNotices,
    private val watches: ThreadWatchService,
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

    /**
     * A post tells the people it mentions directly and the audience of its kind ([NoticeAudience.ofMessage]): the thread's watchers for a
     * reply, every active member for an opening message. The recipients are read INSIDE the write, under the room lock.
     */
    override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): NoticeOutcome {
        val mentioned = indexInTransaction(ref, body)
        val opening = ref.opening()
        val everyone = if (opening) members.allActiveMemberIds(ref.roomId) else emptyList()
        val watchers = if (opening) emptyList() else watches.recipientIds(ref.threadId)
        return notices.forMessage(ref, authorId, NoticeAudience.ofMessage(opening, authorId, mentioned, watchers, everyone))
    }

    /**
     * An edit tells only the NEWLY addressed ([NoticeAudience.ofEdit]; `change.added`, 1.1 A34) and re-addresses the notices the message
     * already holds, so nobody who keeps a notice is counted for the email budget again. No mention added or removed means nothing to add,
     * relabel or drop (the group tokens of the next step widen this guard), and then nothing is read, not even whether this is the
     * thread's first message.
     */
    override suspend fun edited(ref: MessageRef, authorId: UInt, body: String): NoticeOutcome {
        val change = replaceInTransaction(ref, body)
        if (change.added.isEmpty() && change.removed.isEmpty()) return NoticeOutcome.NONE
        val existing = notices.ofMessage(ref.messageId)
        val opening = ref.opening()
        // Whom a dropped mention falls back to: the room's members for an opening message (only the people it dropped can matter),
        // the thread's watchers for a reply. Everybody added is an active member (replaceInTransaction checked).
        val dropped = change.removed.map { it.toLong() }
        val inRoom = if (opening && dropped.isNotEmpty()) members.activeMemberIds(ref.roomId, dropped) else emptySet()
        // Whom the opening message's "started a thread" can fall back to: the people the edit dropped (to keep a notice) and the people it
        // added (a member holding that notice keeps it when their mention kind is switched off).
        val everyone = (change.removed.filter { it.toLong() in inRoom } + change.added).distinct()
        val watchers = if (opening) emptyList() else watches.recipientIds(ref.threadId)
        val revision = NoticeAudience.ofEdit(opening, authorId, existing, change.current, change.added, watchers, everyone)
        return if (revision.isEmpty) NoticeOutcome.NONE else notices.reviseMessage(ref, authorId, revision)
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
        if (parsed.isEmpty() && existing.isEmpty()) return MentionChange(emptyList(), emptyList(), emptyList())
        val known = existing.toSet()
        val fresh = parsed.filter { it !in known }
        val joined = fresh.takeIf { it.isNotEmpty() }?.let { activeMembers(ref.roomId, it) }.orEmpty().toSet()
        val wanted = parsed.filter { it in known || it in joined }
        val wantedSet = wanted.toSet()
        val removed = existing.filter { it !in wantedSet }
        val added = wanted.filter { it !in known }
        if (removed.isNotEmpty()) index.deleteWhere { (messageId eq ref.messageId) and (userId inList removed) }
        insert(ref, added)
        return MentionChange(added, removed, wanted)
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
