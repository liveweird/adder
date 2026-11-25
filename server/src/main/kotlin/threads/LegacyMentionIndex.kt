package ch.nokillswit.threads

import ch.nokillswit.threads.MessageGroupMentionIndex.MessageGroupMentions
import ch.nokillswit.threads.MessageMentionService.MessageMentions
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere

/** Remove obsolete index rows without retrospectively addressing or notifying new recipients. The caller holds the message locks. */
internal suspend fun pruneLegacyMentions(parsed: Map<UInt, ParsedMentions>) {
    if (parsed.isEmpty()) return
    MessageMentions.deleteWhere {
        parsed.map { (id, mentions) -> (messageId eq id) and (userId notInList mentions.users) }.compoundOr()
    }
    MessageGroupMentions.deleteWhere {
        parsed.map { (id, mentions) ->
            val absentKeywords = buildList {
                if (!mentions.watchers) add(MentionGroupKind.WATCHERS.name)
                if (!mentions.everyone) add(MentionGroupKind.EVERYONE.name)
                if (!mentions.here) add(MentionGroupKind.HERE.name)
            }
            val absentTeams = (groupKind eq MentionGroupKind.TEAM.name) and (teamId notInList mentions.teams)
            (messageId eq id) and (absentTeams or (groupKind inList absentKeywords))
        }.compoundOr()
    }
    val obsoleteHere = parsed.filterValues { !it.here }.keys
    if (obsoleteHere.isNotEmpty()) MessageHereMentions.deleteWhere { messageId inList obsoleteHere }
}
