package ch.nokillswit.threads

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.presence.ForegroundPresence
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.teams.TeamService
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
 * What an edit did to a message's direct mentions: the people newly mentioned, the people whose mention the edit removed, the people the
 * new body mentions NOW ([current], in order of appearance: the kept ones, then the added; a kept person may have left the room since)
 * and the people the old body's rows named ([previous]).
 */
internal data class MentionChange(
    val added: List<UInt>,
    val removed: List<UInt>,
    val current: List<UInt>,
    val previous: List<UInt> = emptyList(),
)

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
    teams: TeamService,
    private val presence: ForegroundPresence,
) : MessageFollower {
    /** The group mentions (V30): `<@team:id>`, `<@watchers>`, `<@everyone>`, kept in step with the same body by the same write. */
    val groups = MessageGroupMentionIndex(teams)

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
     * A post tells the people it mentions directly, the teams, watchers and room it names (`<@team:id>`, `<@watchers>`, `<@everyone>`)
     * and the audience of its kind ([NoticeAudience.ofMessage]): the thread's watchers for a reply, every active member for an opening
     * message. Every audience is read INSIDE the write, under the room lock, and only what the body needs. The author of the message
     * a post quotes (1.3) is told `MESSAGE_QUOTED` when they are an ACTIVE member now and not the author: their membership is judged in
     * the very statement that judges the people the body mentions, because a notice for a non-member would break `fk_notifications_member`.
     */
    override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): NoticeOutcome {
        val parsed = parseMentions(body)
        val quotedAuthor = ref.quotedAuthor()?.takeIf { it != authorId }
        val (mentioned, quoted) = indexChecked(ref, parsed, quotedAuthor)
        val named = groups.resolve(parsed)
        groups.insertInTransaction(ref, named)
        val opening = ref.opening()
        val reach = GroupReach(members, watches, ref.roomId, ref.threadId)
        val hereMentioned = if (named.here) reach.hereRecipients(authorId) else emptyList()
        if (named.here) replaceHereSnapshot(ref, hereMentioned)
        val teamMembers = reach.teamMembers(named.rosters.values)
        val audience = NoticeAudience.ofMessage(
            opening,
            authorId,
            mentioned,
            watchers = if (opening) emptyList() else reach.watchers(),
            members = if (opening || named.everyone) reach.members() else emptyList(),
            teamMentioned = teamMembers,
            watchersMentioned = named.watchers,
            everyoneMentioned = named.everyone,
            hereMentioned = hereMentioned,
            quoted = quoted,
        )
        return notices.forMessage(ref, authorId, audience)
    }

    /**
     * An edit tells only the NEWLY addressed ([NoticeAudience.ofEdit]; 1.1 A34): addressed by the new body, by name or through a group,
     * and not by the old one. The old body's groups are still in the index (the row has been overwritten, the index has not), so the old
     * audience is computed as of NOW: a team's roster, the watchers, the room's members; a KEPT group token re-addresses nobody, but a
     * person the edit now names directly is always newly addressed. The
     * notices the message already holds are re-addressed (relabelled, deleted, upgraded), so nobody who keeps a notice is counted for the
     * email budget again. No mention added or removed and the same group tokens means nothing to add, relabel or drop, and then nothing is
     * read, not even whether this is the thread's first message.
     */
    override suspend fun edited(ref: MessageRef, authorId: UInt, body: String): NoticeOutcome {
        val parsed = parseMentions(body)
        val change = replaceInTransaction(ref, body, parsed)
        val held = groups.heldBy(ref.messageId)
        val heldHere = if (held.here) hereSnapshot(ref.messageId) else emptyList()
        val directSame = change.added.isEmpty() && change.removed.isEmpty()
        // The common edit changes no token: decided from the parsed ids against the held rows, before any team is read.
        if (directSame && held.matches(parsed)) return NoticeOutcome.NONE
        val now = groups.resolve(parsed)
        if (!held.sameTokens(now)) groups.replaceInTransaction(ref, held, now)
        if (directSame && held.sameTokens(now)) return NoticeOutcome.NONE
        val existing = notices.ofMessage(ref.messageId)
        val opening = ref.opening()
        val reach = GroupReach(members, watches, ref.roomId, ref.threadId)
        val nowHere = when {
            !now.here -> emptyList()
            held.here -> heldHere
            else -> reach.hereRecipients(authorId)
        }
        if (held.here != now.here) replaceHereSnapshot(ref, nowHere)
        val before = previousReasons(reach, change, held, now, opening, heldHere)
        // Whom a dropped mention falls back to: the room's members for an opening message (only the people it dropped, the people it added
        // and the people who hold a mention notice can matter), the thread's watchers for a reply.
        val holders = existing.filterValues { it in NoticeAudience.MENTION_REASONS }.keys
        val everyone = when {
            now.everyone || now.rosters.isNotEmpty() -> reach.members()
            opening -> inRoom(ref.roomId, (change.removed + change.added + holders).distinct())
            else -> emptyList()
        }
        // The author of the quoted message, when they are still an active member (one more statement, only past the early returns).
        val quoted = ref.quotedAuthor()?.takeIf { it != authorId }?.let { inRoom(ref.roomId, listOf(it)).firstOrNull() }
        val revision = NoticeAudience.ofEdit(
            opening,
            authorId,
            existing,
            change.current,
            before,
            if (opening) emptyList() else reach.watchers(),
            everyone,
            groups = MentionGroupAudience(
                teams = reach.teamMembers(now.rosters.values),
                watchers = now.watchers,
                everyone = now.everyone,
                here = nowHere,
            ),
            quoted = quoted,
        )
        return if (revision.isEmpty) NoticeOutcome.NONE else notices.reviseMessage(ref, authorId, revision)
    }

    /**
     * The most specific mention reason each person had from the OLD body, as of now (1.1 A39): the people its direct rows named, the active
     * members on the roster of its teams, the watchers if it named them, the room if it named everyone. The old groups are read only when
     * the new body has groups too (a person the edit names directly is newly addressed whatever a group did before, `MENTIONED` being
     * the most specific reason), and a team that has lost its handle since contributes nobody.
     */
    private suspend fun previousReasons(
        reach: GroupReach,
        change: MentionChange,
        held: GroupSet,
        now: GroupSet,
        opening: Boolean,
        heldHere: Collection<UInt>,
    ): Map<UInt, NoticeReason> {
        val before = HashMap<UInt, NoticeReason>()
        fun note(users: Collection<UInt>, reason: NoticeReason) {
            for (user in users) before.merge(user, reason) { a, b -> if (a.ordinal <= b.ordinal) a else b }
        }
        note(change.previous, NoticeReason.MENTIONED)
        if (held.isEmpty || now.isEmpty) return before
        note(reach.teamMembers(groups.rostersOf(held.teams, now.rosters)), NoticeReason.TEAM_MENTIONED)
        // `<@watchers>` in an OPENING message reached nobody (only its author watches): it is no old reason, and costs no read.
        if (held.watchers && !opening) note(reach.watchers(), NoticeReason.WATCHERS_MENTIONED)
        if (held.everyone) note(reach.members(), NoticeReason.EVERYONE_MENTIONED)
        if (held.here) note(heldHere, NoticeReason.HERE_MENTIONED)
        return before
    }

    private suspend fun GroupReach.hereRecipients(authorId: UInt): List<UInt> {
        val candidates = members().filter { it != authorId }
        val present = presence.eligibleUserIds(candidates)
        return candidates.filter { it in present }
    }

    /** The people among [ids] who are active members of room [roomId]. */
    private suspend fun inRoom(roomId: UInt, ids: List<UInt>): List<UInt> =
        if (ids.isEmpty()) emptyList() else members.activeMemberIds(roomId, ids.map { it.toLong() }).map { it.toUInt() }

    override suspend fun deleted(ref: MessageRef) {
        clearInTransaction(ref.messageId)
        groups.clearInTransaction(ref.messageId)
        replaceHereSnapshot(ref, emptyList())
        notices.messageRemoved(ref.messageId)
    }

    /** Indexes the mentions of a NEW message [ref] (no row exists yet) and answers the members mentioned, in order of appearance. */
    suspend fun indexInTransaction(ref: MessageRef, body: String, parsed: ParsedMentions = parseMentions(body)): List<UInt> =
        indexChecked(ref, parsed, null).first

    /**
     * [indexInTransaction] that also judges [quotedAuthor], the author of the message a post quotes: ONE membership statement for the
     * mentioned people and the quoted author together (none when there is neither). Answers the members mentioned, in order of
     * appearance, and the quoted author when they are an ACTIVE member now (null otherwise: no notice may name a non-member).
     */
    private suspend fun indexChecked(ref: MessageRef, parsed: ParsedMentions, quotedAuthor: UInt?): Pair<List<UInt>, UInt?> {
        val asked = (parsed.users + listOfNotNull(quotedAuthor)).distinct()
        val active = if (asked.isEmpty()) emptySet() else members.activeMemberIds(ref.roomId, asked.map { it.toLong() })
        val mentioned = parsed.users.filter { it.toLong() in active }
        insert(ref, mentioned)
        return mentioned to quotedAuthor?.takeIf { it.toLong() in active }
    }

    /**
     * Brings the rows of message [ref] in step with its edited [body]: a person still named keeps their row, a person no longer named
     * loses it, a new member gets one; an unchanged set writes nothing. A person already indexed stays mentioned while the token stays,
     * whatever happened to their membership since (the name stays on the message, as a mention of someone who left does).
     */
    suspend fun replaceInTransaction(ref: MessageRef, body: String, mentions: ParsedMentions = parseMentions(body)): MentionChange {
        val index = MessageMentions
        val existing = index.select(index.userId).where { index.messageId eq ref.messageId }
            .map { it[index.userId].value }.toList()
        val parsed = mentions.users
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
        return MentionChange(added, removed, wanted, existing)
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
