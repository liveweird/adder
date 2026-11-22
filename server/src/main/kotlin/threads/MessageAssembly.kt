package ch.nokillswit.threads

import ch.nokillswit.threads.MessageService.Messages
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * The ONE assembly of message rows into [MessageResponse]s, shared by the list, the single read and the pin listing's read
 * ([MessageService.list], [MessageService.read], [MessageService.readInTransaction]); a post builds its own answer (it knows the row and
 * re-reads nothing under the room lock). Whatever a response is made of beyond its own row is read here, once per call, for the whole
 * page: the quoted messages first (their authors and mention tokens join the reads below), then the author names, the mentions, the group
 * mentions and the viewer's reactions, in that order. Runs INSIDE the caller's transaction.
 */
internal class MessageAssembly(
    private val mentions: MessageMentionService,
    private val reactions: MessageReactionService,
) {
    /**
     * [rows] (rows of [Messages], tombstones included) as [viewerId] sees them, in the order of [rows]; [numberOf] gives the per-room
     * number of the thread a row belongs to. The reads are batched across the rows: none per message. A row's quote is read through
     * [quotedMessagesOf] (ONE statement for the quoted rows that are not on the page; none when the page holds them all), and its
     * author and mention lists ride the page's own names, mentions and group-mentions reads. The cost bound: a page whose quoted
     * bodies hold no `<@` token adds at most that one statement; a page whose only `<@` tokens sit in an off-page quoted body adds up
     * to four (the quote read, the mentions read, the group-mentions read and the teams lookup of a team mention).
     */
    suspend fun responses(rows: List<ResultRow>, viewerId: UInt, numberOf: (ResultRow) -> Int): List<MessageResponse> {
        val quoted = quotedMessagesOf(rows)
        val names = userNames(rows.map { it[Messages.authorId].value } + quoted.values.map { it.authorId })
        val tokenIds = (rows.liveWithTokens() + quoted.values.filter { it.hasTokens }.map { it.id }).distinct()
        val mentioned = mentions.mentionsOf(tokenIds)
        val grouped = mentions.groups.groupsOf(tokenIds)
        val reacted = reactionsOf(viewerId, rows)
        return rows.map {
            val id = it[Messages.id].value
            val author = names.getValue(it[Messages.authorId].value)
            val quote = quoted[id]?.let { q -> q.toQuote(names.getValue(q.authorId), mentioned[q.id].orEmpty(), grouped[q.id].orEmpty()) }
            it.toResponse(numberOf(it), author, mentioned[id].orEmpty(), grouped[id].orEmpty(), reacted[id].orEmpty(), quote)
        }
    }

    /** The reactions of the LIVE messages among [rows] as [viewerId] sees them, in ONE read. */
    private suspend fun reactionsOf(viewerId: UInt, rows: List<ResultRow>): Map<UInt, List<ReactionSummary>> =
        reactions.summaries(viewerId, rows.filterNot { it[Messages.markedAsDeleted] }.map { it[Messages.id].value })

    /** The ids of the live messages among these rows whose body holds a `<@` token. */
    private fun List<ResultRow>.liveWithTokens(): List<UInt> =
        filter { !it[Messages.markedAsDeleted] && it[Messages.body].contains("<@") }.map { it[Messages.id].value }

    private fun ResultRow.toResponse(
        threadNumber: Int,
        authorName: String,
        mentioned: List<MentionedUser>,
        grouped: List<GroupMention>,
        summaries: List<ReactionSummary>,
        quote: MessageQuote?,
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
            quote = if (deleted) null else quote,
        )
    }
}
