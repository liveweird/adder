package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.ftsMatches
import ch.nokillswit.infra.db.ftsQueryText
import ch.nokillswit.infra.db.ftsVector
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.plugins.isQueryCanceled
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomSummary
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ThreadSearchServiceKey = AttributeKey<ThreadSearchService>("ThreadSearchService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ThreadService.Threads.id,
    "lastActivityAt" to ThreadService.Threads.lastActivityAt,
    "createdAt" to ThreadService.Threads.createdAt,
)

/** The columns a hit reads of its thread (never `fields`: the jsonb document can be tens of kilobytes). */
private val HIT_COLUMNS: List<Column<*>> = with(ThreadService.Threads) {
    listOf(id, roomId, number, title, statusKey, closedAt, ownerId, messageCount, lastActivityAt)
}

/**
 * Full-text search over thread titles and message bodies (V16, `chat-model.md` "Search (V16)"): a page of THREADS, each with its newest
 * matching live messages. The whole search is ONE read transaction under [timeoutSeconds] as its `queryTimeout` (the only way a
 * statement timeout may be set, `persistence.md` "Connection pool"): a statement past it is the typed `429` `search-timeout`, audited
 * `search.timeout` with ids only, and the throwable is never logged (a database failure's message can echo the term). The term itself
 * reaches no log, no audit line and no problem detail. Room data comes only through the `rooms/` services.
 */
class ThreadSearchService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val timeoutSeconds: Int,
) {
    /** The search of one room (the route admitted the caller): 404 when the room vanished since, an empty page for no lexeme. */
    suspend fun searchRoom(roomId: UInt, callerUserId: UInt, q: String, closed: Boolean?, paging: PageRequest): ThreadSearchResult =
        budgeted(callerUserId, roomId) {
            val room = rooms.summary(roomId) ?: throw NotFoundException("Room not found")
            searchIn(listOf(room), q, closed, paging)
        }

    /**
     * Runs [block] in one read transaction under the statement budget; a statement the budget cancelled (SQLSTATE `57014`) becomes the
     * audited `429`. [roomId] is null for a search across rooms.
     */
    private suspend fun budgeted(callerUserId: UInt, roomId: UInt?, block: suspend R2dbcTransaction.() -> ThreadSearchResult) =
        try {
            suspendTransaction(database) {
                queryTimeout = timeoutSeconds
                block()
            }
        } catch (failure: Exception) {
            if (!failure.isQueryCanceled()) throw failure
            audit("search.timeout", "byUserId" to callerUserId.toLong(), "roomId" to roomId?.toLong())
            throw searchTimeout()
        }

    /**
     * The statements of a search over [inRooms]: the query text, the count and the page of threads (one predicate: the title matches,
     * or a LIVE message of the thread does), then the message snippets and the title headlines, for the page's thread ids only.
     */
    private suspend fun R2dbcTransaction.searchIn(
        inRooms: List<RoomSummary>,
        q: String,
        closed: Boolean?,
        paging: PageRequest,
    ): ThreadSearchResult {
        val queryText = ftsQueryText(q) ?: return ThreadSearchResult(emptyList(), 0)
        val threads = ThreadService.Threads
        var predicate: Op<Boolean> = (threads.roomId inList inRooms.map { it.id }) and matchesTitleOrMessage(queryText)
        closed?.let { predicate = predicate and (if (it) threads.closedAt.isNotNull() else threads.closedAt.isNull()) }
        val total = threads.selectAll().where { predicate }.count()
        val rows = threads.select(HIT_COLUMNS).where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        if (rows.isEmpty()) return ThreadSearchResult(emptyList(), total)

        val ids = rows.map { it[threads.id].value }
        val matches = messageSnippets(ids, queryText)
        val titles = titleSnippets(ids, queryText)
        val names = userNames(rows.map { it[threads.ownerId].value } + matches.values.flatMap { m -> m.messages.map { it.authorId } })
        val byRoom = inRooms.associateBy { it.id }
        val items = rows.map { row ->
            val id = row[threads.id].value
            val room = byRoom.getValue(row[threads.roomId].value)
            val own = matches[id]
            ThreadSearchHit(
                roomId = room.id,
                roomName = room.name,
                roomArchived = room.archived,
                threadNumber = row[threads.number],
                title = row[threads.title],
                titleSnippet = titles[id] ?: listOf(SnippetSegment(row[threads.title], false)),
                status = row[threads.statusKey],
                closed = row[threads.closedAt] != null,
                ownerId = row[threads.ownerId].value,
                ownerName = names.getValue(row[threads.ownerId].value),
                messageCount = row[threads.messageCount],
                lastActivityAt = row[threads.lastActivityAt],
                matchCount = own?.hits ?: 0,
                messages = own?.messages.orEmpty().map {
                    MessageSearchHit(it.id, it.authorId, names.getValue(it.authorId), it.createdAt, it.snippet)
                },
            )
        }
        return ThreadSearchResult(items, total)
    }

    /**
     * `threads.search @@ q OR EXISTS (a live message of the thread with messages.search @@ q)`: PostgreSQL turns the EXISTS into a hashed
     * subplan fed by `idx_messages_search`, and the title vector is a filter on the room's own index (`threads.search` has none).
     */
    private fun matchesTitleOrMessage(queryText: String): Op<Boolean> {
        val threads = ThreadService.Threads
        val messages = MessageService.Messages
        val liveMessageMatches = exists(
            messages.select(messages.id).where {
                (messages.threadId eq threads.id) and messages.active() and messages.ftsVector().ftsMatches(queryText)
            },
        )
        return threads.ftsVector().ftsMatches(queryText) or liveMessageMatches
    }
}
