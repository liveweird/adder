package ch.nokillswit.threads

import ch.nokillswit.threads.MessageService.Messages
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The ONE assembly of message rows into [MessageResponse]s, shared by the list, the single read and the pin listing's read
 * ([MessageService.list], [MessageService.read], [MessageService.readInTransaction]); a post builds its own answer (it knows the row and
 * re-reads nothing under the room lock). Whatever a response is made of beyond its own row is read here, once per call, for the whole
 * page: the author names, the mentions, the group mentions and the viewer's reactions, in that order. Runs INSIDE the caller's transaction.
 */
internal class MessageAssembly(
    private val mentions: MessageMentionService,
    private val reactions: MessageReactionService,
) {
    /**
     * [rows] (rows of [Messages], tombstones included) as [viewerId] sees them, in the order of [rows]; [numberOf] gives the per-room
     * number of the thread a row belongs to. The reads are batched across the rows: none per message.
     */
    suspend fun responses(rows: List<ResultRow>, viewerId: UInt, numberOf: (ResultRow) -> Int): List<MessageResponse> {
        val names = userNames(rows.map { it[Messages.authorId].value })
        val mentioned = mentionsOf(rows)
        val grouped = groupMentionsOf(rows)
        val reacted = reactionsOf(viewerId, rows)
        return rows.map {
            val id = it[Messages.id].value
            val author = names.getValue(it[Messages.authorId].value)
            it.toResponse(numberOf(it), author, mentioned[id].orEmpty(), grouped[id].orEmpty(), reacted[id].orEmpty())
        }
    }

    /** The reactions of the LIVE messages among [rows] as [viewerId] sees them, in ONE read. */
    private suspend fun reactionsOf(viewerId: UInt, rows: List<ResultRow>): Map<UInt, List<ReactionSummary>> =
        reactions.summaries(viewerId, rows.filterNot { it[Messages.markedAsDeleted] }.map { it[Messages.id].value })

    /** The mentions of the LIVE messages among [rows], in ONE read (none when no body holds a `<@` token: most pages). */
    private suspend fun mentionsOf(rows: List<ResultRow>): Map<UInt, List<MentionedUser>> =
        mentions.mentionsOf(rows.liveWithTokens())

    /**
     * The group mentions (`<@team:id>`, `<@watchers>`, `<@everyone>`) of the LIVE messages among [rows], in ONE read (none when no body
     * holds a `<@` token).
     */
    private suspend fun groupMentionsOf(rows: List<ResultRow>): Map<UInt, List<GroupMention>> =
        mentions.groups.groupsOf(rows.liveWithTokens())

    /** The ids of the live messages among these rows whose body holds a `<@` token. */
    private fun List<ResultRow>.liveWithTokens(): List<UInt> =
        filter { !it[Messages.markedAsDeleted] && it[Messages.body].contains("<@") }.map { it[Messages.id].value }

    private fun ResultRow.toResponse(
        threadNumber: Int,
        authorName: String,
        mentioned: List<MentionedUser>,
        grouped: List<GroupMention>,
        summaries: List<ReactionSummary>,
    ): MessageResponse {
        val deleted = this[Messages.markedAsDeleted]
        val authorId = this[Messages.authorId].value
        return MessageResponse(
            id = this[Messages.id].value,
            threadNumber = threadNumber,
            authorId = authorId,
            authorName = authorName,
            body = if (deleted) null else this[Messages.body],
            deleted = deleted,
            deletedByModerator = this[Messages.deletedBy]?.value.let { it != null && it != authorId },
            editedAt = if (deleted) null else this[Messages.editedAt],
            createdAt = this[Messages.createdAt],
            mentions = if (deleted) emptyList() else mentioned,
            groupMentions = if (deleted) emptyList() else grouped,
            reactions = if (deleted) emptyList() else summaries,
        )
    }
}
