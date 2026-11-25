package ch.nokillswit.notifications

import ch.nokillswit.infra.db.active
import ch.nokillswit.teams.TeamService
import ch.nokillswit.threads.MessageGroupMentionIndex
import ch.nokillswit.threads.MentionGroupKind
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The current name of the first mentioned team the recipient still belongs to, per message. The message index supplies only teams that
 * were actually written. Joining the active roster and active, mentionable team prevents a notice from exposing another mentioned team.
 */
internal suspend fun teamMentionNames(recipientId: UInt, messageIds: Collection<UInt>): Map<UInt, String> {
    if (messageIds.isEmpty()) return emptyMap()
    val groups = MessageGroupMentionIndex.MessageGroupMentions
    val members = TeamService.TeamMembers
    val teams = TeamService.Teams
    return groups.join(members, JoinType.INNER, additionalConstraint = { groups.teamId eq members.teamId })
        .join(teams, JoinType.INNER, additionalConstraint = { groups.teamId eq teams.id })
        .select(groups.messageId, teams.id, teams.name)
        .where {
            (groups.messageId inList messageIds.toSet()) and
                (groups.groupKind eq MentionGroupKind.TEAM.name) and
                (members.userId eq recipientId) and teams.handle.isNotNull() and teams.active()
        }
        .orderBy(groups.messageId to SortOrder.ASC, teams.id to SortOrder.ASC)
        .map { it[groups.messageId].value to it[teams.name] }
        .toList()
        .distinctBy { it.first }
        .toMap()
}
