package ch.nokillswit.threads

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select

// The group mentions of a message (`chat-model.md` "Group mentions (V25, V30, V43)"): `<@team:id>`, `<@watchers>`, `<@here>`,
// `<@everyone>`. This file is
// the derived index (V30 `message_group_mentions`), what a message's `groupMentions` reads and the `mentionsMe` predicate; the people a
// group addresses are NEVER read from the index but from the live roster, watchers and members at the moment of the write.

/** The four kinds of group mention (the persisted `group_kind`; the enum is the whitelist, no CHECK). */
enum class MentionGroupKind { TEAM, WATCHERS, HERE, EVERYONE }

/**
 * One group mention of a message on the wire. A TEAM mention carries the team's id and its CURRENT [teamHandle] and [teamName] (a renamed
 * handle shows on old messages); the three keywords carry only the kind. The SPA shows a token as `@handle`, `@watchers`, `@here` or
 * `@everyone` only when it is listed here (the SPA follows the server).
 */
@Serializable
data class GroupMention(
    val kind: MentionGroupKind,
    val teamId: UInt? = null,
    val teamHandle: String? = null,
    val teamName: String? = null,
)

/**
 * What a body's group tokens resolved to: the [teams] it names that are MENTIONABLE (an active team with a handle; any other `<@team:id>`
 * is plain text) in order of appearance, and the three keywords. [rosters] holds the ACTIVE rosters of the teams read so far (a set built
 * from a parsed body carries them; one read back from the index does not, and asks for them only when it needs the people).
 */
internal class GroupSet(
    val teams: List<UInt> = emptyList(),
    val watchers: Boolean = false,
    val everyone: Boolean = false,
    val here: Boolean = false,
    val rosters: Map<UInt, List<UInt>> = emptyMap(),
) {
    val isEmpty: Boolean get() = teams.isEmpty() && !watchers && !everyone && !here

    /** The same tokens, ignoring the rosters: the question "did the body's group tokens change". */
    fun sameTokens(other: GroupSet): Boolean =
        teams.toSet() == other.teams.toSet() && watchers == other.watchers && everyone == other.everyone && here == other.here

    /** The held tokens are exactly what [parsed] names (the teams as a SET: their order is no change), before any team is resolved. */
    fun matches(parsed: ParsedMentions): Boolean =
        teams.toSet() == parsed.teams.toSet() && watchers == parsed.watchers && everyone == parsed.everyone && here == parsed.here

    companion object {
        val NONE = GroupSet()
    }
}

/**
 * The derived group index (V30), the second half of the mention follower's work. Hard-deleted with the message's body, written inside the
 * message's own write under the room lock; rows are inert for a team that has since been deleted or lost its handle (every reader joins
 * the ACTIVE teams that have one). Ids only: a body, a handle or a name never reaches a log.
 */
internal class MessageGroupMentionIndex(private val teams: TeamService) {
    object MessageGroupMentions : Table("message_group_mentions") {
        val messageId = reference("message_id", MessageService.Messages)
        val groupKind = varchar("group_kind", length = 10)
        val teamId = reference("team_id", TeamService.Teams).nullable()

        /** Copied from the message's thread (a message never changes thread). */
        val threadId = reference("thread_id", ThreadService.Threads)

        /** Copied from the thread's room (a thread never changes room). */
        val roomId = reference("room_id", RoomService.Rooms)
    }

    /**
     * What [parsed] names, resolved NOW: the mentionable teams among its team ids (ONE read of the teams and their active rosters, none
     * when it names no team), the keywords as written. A team that is unknown, deleted or without a handle is plain text.
     */
    suspend fun resolve(parsed: ParsedMentions): GroupSet {
        val rosters = teams.mentionableRosters(parsed.teams)
        return GroupSet(parsed.teams.filter { it in rosters }, parsed.watchers, parsed.everyone, parsed.here, rosters)
    }

    /** Indexes the groups of a NEW message [ref] (no row exists yet). */
    suspend fun insertInTransaction(ref: MessageRef, groups: GroupSet) = insert(ref, groups.rows())

    /** The groups message [messageId] holds in the index now (its old body's, while an edit has not replaced them). */
    suspend fun heldBy(messageId: UInt): GroupSet {
        val index = MessageGroupMentions
        val rows = index.select(index.groupKind, index.teamId).where { index.messageId eq messageId }
            .map { it[index.groupKind] to it[index.teamId]?.value }.toList()
        return GroupSet(
            teams = rows.mapNotNull { (kind, team) -> team.takeIf { kind == MentionGroupKind.TEAM.name } }.sorted(),
            watchers = rows.any { it.first == MentionGroupKind.WATCHERS.name },
            everyone = rows.any { it.first == MentionGroupKind.EVERYONE.name },
            here = rows.any { it.first == MentionGroupKind.HERE.name },
        )
    }

    /** The ACTIVE rosters of the mentionable teams among [teamIds], reusing the [known] ones and reading the rest in one query. */
    suspend fun rostersOf(teamIds: List<UInt>, known: Map<UInt, List<UInt>>): Collection<List<UInt>> {
        val missing = teamIds.filter { it !in known }
        val read = if (missing.isEmpty()) emptyMap() else teams.mentionableRosters(missing)
        return teamIds.mapNotNull { known[it] ?: read[it] }
    }

    /** Brings the rows of message [ref] from [before] (what [heldBy] read) to [now]: tokens no longer written go, new ones are inserted. */
    suspend fun replaceInTransaction(ref: MessageRef, before: GroupSet, now: GroupSet) {
        val index = MessageGroupMentions
        val kept = before.rows().toSet()
        val wanted = now.rows().toSet()
        val goneTeams = (kept - wanted).mapNotNull { it.second }
        val goneKinds = (kept - wanted).filter { it.second == null }.map { it.first.name }
        if (goneTeams.isNotEmpty()) {
            index.deleteWhere { (messageId eq ref.messageId) and (groupKind eq MentionGroupKind.TEAM.name) and (teamId inList goneTeams) }
        }
        if (goneKinds.isNotEmpty()) index.deleteWhere { (messageId eq ref.messageId) and (groupKind inList goneKinds) }
        insert(ref, now.rows().filter { it !in kept })
    }

    /** Removes every group mention of message [messageId]: its delete, a moderator's included. */
    suspend fun clearInTransaction(messageId: UInt) {
        MessageGroupMentions.deleteWhere { MessageGroupMentions.messageId eq messageId }
    }

    /**
     * The group mentions of the messages [messageIds] as they read now, ONE query for the page: a team shows its CURRENT handle and name,
     * and only a mentionable team does (a deleted or handle-less team's token is plain text). Teams by id, then `WATCHERS`, `HERE`,
     * `EVERYONE`.
     */
    suspend fun groupsOf(messageIds: Collection<UInt>): Map<UInt, List<GroupMention>> {
        if (messageIds.isEmpty()) return emptyMap()
        val index = MessageGroupMentions
        val rows = index.select(index.messageId, index.groupKind, index.teamId)
            .where { index.messageId inList messageIds.toSet() }
            .map { Triple(it[index.messageId].value, it[index.groupKind], it[index.teamId]?.value) }
            .toList()
        val named = teams.mentionableTeams(rows.mapNotNull { it.third })
        return rows.mapNotNull { (message, kind, team) ->
            val group = MentionGroupKind.entries.firstOrNull { it.name == kind } ?: return@mapNotNull null
            when {
                group != MentionGroupKind.TEAM -> message to GroupMention(group)
                else -> named[team]?.let { message to GroupMention(group, it.id, it.handle, it.name) }
            }
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, groups) -> groups.sortedWith(compareBy({ it.kind.ordinal }, { it.teamId })) }
    }

    private suspend fun insert(ref: MessageRef, rows: List<Pair<MentionGroupKind, UInt?>>) {
        if (rows.isEmpty()) return
        MessageGroupMentions.insertRows(rows) { (kind, team) ->
            this[MessageGroupMentions.messageId] = ref.messageId
            this[MessageGroupMentions.groupKind] = kind.name
            this[MessageGroupMentions.teamId] = team
            this[MessageGroupMentions.threadId] = ref.threadId
            this[MessageGroupMentions.roomId] = ref.roomId
        }
    }

    private fun GroupSet.rows(): List<Pair<MentionGroupKind, UInt?>> =
        teams.map { MentionGroupKind.TEAM to it } +
            listOfNotNull(
                (MentionGroupKind.WATCHERS to null).takeIf { watchers },
                (MentionGroupKind.EVERYONE to null).takeIf { everyone },
                (MentionGroupKind.HERE to null).takeIf { here },
            )
}

/**
 * The people a body's group tokens reach, read lazily INSIDE the write under the room lock and at most once each: the room's active
 * members, the thread's watchers (active users who are active members, [ThreadWatchService.recipientIds]) and a team's roster
 * intersected with the room's active members (a team member who is not in the room is never told).
 */
internal class GroupReach(
    private val members: RoomMemberService,
    private val watches: ThreadWatchService,
    private val roomId: UInt,
    private val threadId: UInt,
) {
    private var activeMembers: List<UInt>? = null
    private var threadWatchers: List<UInt>? = null

    /** Every active member of the room. */
    suspend fun members(): List<UInt> = activeMembers ?: members.allActiveMemberIds(roomId).also { activeMembers = it }

    /** The watchers of the thread who are active members. */
    suspend fun watchers(): List<UInt> = threadWatchers ?: watches.recipientIds(threadId).also { threadWatchers = it }

    /** The people of [rosters] (the ACTIVE rosters of the named teams) who are active members of the room, by user id. */
    suspend fun teamMembers(rosters: Collection<List<UInt>>): List<UInt> {
        val named = rosters.flatten().toSet()
        if (named.isEmpty()) return emptyList()
        return members().filter { it in named }
    }
}

/**
 * `EXISTS` a LIVE message of the thread that addresses [userId]: names them (`<@id>`, [threadMentionsAny]), or, for a member of the room
 * ([member]; an ADMIN outside it is addressed by no group), names everyone, a team in [teamIds] (the mentionable teams the person is on
 * NOW) or the watchers while the person watches the thread now. Correlated on [threadId], the outer list's `threads.id`; the group part
 * probes
 * `idx_message_group_mentions_room (room_id, thread_id, group_kind, team_id)`.
 */
internal fun threadMentionsMe(
    roomId: UInt,
    userId: UInt,
    threadId: Column<EntityID<UInt>>,
    member: Boolean,
    teamIds: List<UInt>,
): Op<Boolean> {
    val direct = threadMentionsAny(roomId, threadId, listOf(userId))
    if (!member) return direct
    val index = MessageGroupMentionIndex.MessageGroupMentions
    var addressed: Op<Boolean> = (index.groupKind eq MentionGroupKind.EVERYONE.name) or
        ((index.groupKind eq MentionGroupKind.WATCHERS.name) and threadWatchedBy(roomId, userId, index.threadId))
    if (teamIds.isNotEmpty()) {
        addressed = addressed or ((index.groupKind eq MentionGroupKind.TEAM.name) and (index.teamId inList teamIds))
    }
    val group = exists(
        index.select(index.messageId).where { (index.roomId eq roomId) and (index.threadId eq threadId) and addressed },
    )
    return direct or group or threadHereMentionsUser(roomId, userId, threadId)
}
