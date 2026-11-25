package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.equalsAnyInt
import ch.nokillswit.infra.db.ftsMatches
import ch.nokillswit.infra.db.ftsQueryText
import ch.nokillswit.infra.db.ftsRowVector
import ch.nokillswit.infra.db.ftsVector
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomSummary
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.wrapAsExpression
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

val ThreadSearchServiceKey = AttributeKey<ThreadSearchService>("ThreadSearchService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ThreadService.Threads.id,
    "lastActivityAt" to ThreadService.Threads.lastActivityAt,
    "createdAt" to ThreadService.Threads.createdAt,
)

private val MESSAGE_SEARCH_COLUMNS: Map<String, Column<*>> = mapOf("id" to MessageService.Messages.id)

/** The sortable whitelist of a search inside one thread's messages. */
val MESSAGE_SEARCH_SORT_FIELDS: Set<String> = MESSAGE_SEARCH_COLUMNS.keys

/** The sortable whitelist of a search: derived from the column map, so the two cannot disagree. */
val SEARCH_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The columns a hit reads of its thread (never `fields`: the jsonb document can be tens of kilobytes). */
private val HIT_COLUMNS: List<Column<*>> = with(ThreadService.Threads) {
    listOf(id, roomId, number, title, statusKey, closedAt, ownerId, messageCount, lastActivityAt)
}

/**
 * Full-text search over thread titles and message bodies (V16, `chat-model.md` "Search (V16)"): a page of THREADS, each with its newest
 * matching live messages. The whole search is ONE read transaction under ONE deadline of [timeoutSeconds] ([SearchBudget]: each
 * statement's `queryTimeout`, the only way a statement timeout may be set, is the time LEFT, `persistence.md` "Connection pool"): a
 * statement past it, or a deadline spent between two statements, is the typed `429` `search-timeout`, audited `search.timeout` with
 * ids only, and the throwable is never logged (a database failure's message can echo the term). At most [maxConcurrent] searches run
 * in this process at once, for both routes: the next one is refused AT ONCE with the typed `429` `search-busy` (a `tryAcquire`, never
 * a wait, and before any pool connection is asked for), audited `search.busy`, so a flood of searches can never take the pool from
 * the rest of the app. The term itself reaches no log, no audit line and no problem detail. Room data comes only through the `rooms/`
 * services.
 */
class ThreadSearchService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val threadTags: ThreadTagService,
    private val timeoutSeconds: Int,
    maxConcurrent: Int,
    private val clock: () -> Long = System::nanoTime,
) {
    private val permits = Semaphore(maxConcurrent)

    /**
     * The search of one room (the route admitted the caller): 404 when the room vanished since, an empty page for no lexeme. [q] is
     * null only for the watched-threads view ([watching] `true`) or the tag view ([tagNames] given, `parseSearchParams`); [watching] is
     * the caller's own filter and [tagNames] the normalised CURATED tag names (any-of, retired tags included).
     */
    suspend fun searchRoom(
        roomId: UInt,
        callerUserId: UInt,
        q: String?,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean? = null,
        unread: Boolean? = null,
        tagNames: List<String> = emptyList(),
    ): ThreadSearchResult = searchRoomWith(roomId, callerUserId, q, closed, paging, watching, unread, tagNames, null)

    internal suspend fun searchRoomStructured(
        roomId: UInt,
        callerUserId: UInt,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean?,
        unread: Boolean?,
        tagNames: List<String>,
        structured: StructuredSearch,
    ): ThreadSearchResult = searchRoomWith(roomId, callerUserId, null, closed, paging, watching, unread, tagNames, structured)

    private suspend fun searchRoomWith(
        roomId: UInt,
        callerUserId: UInt,
        q: String?,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean?,
        unread: Boolean?,
        tagNames: List<String>,
        structured: StructuredSearch?,
    ): ThreadSearchResult =
        budgeted(callerUserId, roomId) { budget ->
            budget.arm(this)
            val room = rooms.summary(roomId) ?: throw NotFoundException("Room not found")
            searchIn(listOf(room), callerUserId, q, closed, watching, unread, tagNames, paging, budget, structured)
        }

    /**
     * The search across the rooms [callerUserId] is an ACTIVE member of (`GET /search`, any authenticated caller): the scope is
     * `RoomService.memberRooms`, membership only, an ADMIN's too (an ADMIN outside a room finds nothing of it here). A caller with no
     * rooms gets an empty page without a search. The room list is read first, inside the same budgeted transaction, so no statement of
     * the search can name a room the caller is not in; a room the caller leaves after this read is gone from the next request. This
     * read is also what keeps "Threads I watch" (`q` null, [watching] `true`) honest: the view starts from the caller's rooms, never
     * from `thread_watches`, whose rows outlive a deleted room. The same goes for the tag view ([tagNames] given, [q] null): `thread_tags`
     * has no room column, so the tag predicate only ever narrows the threads of the caller's own rooms (the first predicate of
     * [searchIn]), never the other way round.
     */
    suspend fun searchMemberRooms(
        callerUserId: UInt,
        q: String?,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean? = null,
        unread: Boolean? = null,
        tagNames: List<String> = emptyList(),
    ): ThreadSearchResult = searchMemberRoomsWith(callerUserId, q, closed, paging, watching, unread, tagNames, null)

    internal suspend fun searchMemberRoomsStructured(
        callerUserId: UInt,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean?,
        unread: Boolean?,
        tagNames: List<String>,
        structured: StructuredSearch,
    ): ThreadSearchResult = searchMemberRoomsWith(callerUserId, null, closed, paging, watching, unread, tagNames, structured)

    private suspend fun searchMemberRoomsWith(
        callerUserId: UInt,
        q: String?,
        closed: Boolean?,
        paging: PageRequest,
        watching: Boolean?,
        unread: Boolean?,
        tagNames: List<String>,
        structured: StructuredSearch?,
    ): ThreadSearchResult =
        budgeted(callerUserId, null) { budget ->
            budget.arm(this)
            val memberRooms = rooms.memberRooms(callerUserId)
            if (memberRooms.isEmpty()) {
                ThreadSearchResult(emptyList(), 0)
            } else {
                searchIn(memberRooms, callerUserId, q, closed, watching, unread, tagNames, paging, budget, structured)
            }
        }

    /**
     * Runs [block] in one read transaction under ONE [SearchBudget]; a statement the budget cancelled (SQLSTATE `57014`) or a deadline
     * spent between two statements becomes the audited `429` `search-timeout`. Takes one of the process's permits first: none free is
     * the audited `429` `search-busy` at once, before a pool connection is asked for. [roomId] is null for a search across rooms.
     */
    private suspend fun <T> budgeted(
        callerUserId: UInt,
        roomId: UInt?,
        block: suspend R2dbcTransaction.(SearchBudget) -> T,
    ): T {
        if (!permits.tryAcquire()) {
            audit("search.busy", "byUserId" to callerUserId.toLong(), "roomId" to roomId?.toLong())
            throw searchBusy()
        }
        try {
            return budgetedTransaction(database, timeoutSeconds, clock, onTimeout = { timedOut(callerUserId, roomId) }, block = block)
        } finally {
            permits.release()
        }
    }

    /**
     * A flat page of live matching messages of one selected thread. It uses the same search deadline and process semaphore as both
     * thread-search routes. Structured room, owner and participant clauses first decide whether the selected thread is in scope; every
     * message clause must then match the returned message. Thread titles never participate. With `has:pin` alone only message pins
     * match; combined with another message clause a thread pin admits messages matching the remaining clauses, as in structured search.
     */
    internal suspend fun searchThreadMessages(
        roomId: UInt,
        threadId: UInt,
        callerUserId: UInt,
        q: String?,
        structured: StructuredSearch?,
        paging: PageRequest,
    ): MessageSearchResult = budgeted(callerUserId, roomId) { budget ->
        budget.arm(this)
        val room = rooms.summary(roomId) ?: throw NotFoundException("Room not found")
        val resolved = structured?.let { resolveStructuredSearch(it, listOf(room)) { budget.arm(this) } }
        if (resolved?.impossible == true) return@budgeted MessageSearchResult(emptyList(), 0)
        if (resolved != null) {
            budget.arm(this)
            val inScope = ThreadService.Threads.select(ThreadService.Threads.id).where {
                (ThreadService.Threads.id eq threadId) and resolved.threadScopePredicate().asOp()
            }.limit(1).toList().isNotEmpty()
            if (!inScope) return@budgeted MessageSearchResult(emptyList(), 0)
        }
        budget.arm(this)
        val queryText = q?.let { ftsQueryText(it) ?: return@budgeted MessageSearchResult(emptyList(), 0) }
        val messages = MessageService.Messages
        val source = messages.innerJoin(ThreadService.Threads)
        var predicate: Op<Boolean> = (messages.threadId eq threadId) and messages.active()
        if (queryText != null) predicate = predicate and messages.ftsRowVector().ftsMatches(queryText)
        resolved?.messagePredicate("messages", "threads")?.let { predicate = predicate and it.asOp() }
        budget.arm(this)
        val total = source.select(messages.columns).where { predicate }.count()
        budget.arm(this)
        val rows = source.select(messages.columns).where { predicate }.applyPaging(paging, MESSAGE_SEARCH_COLUMNS).toList()
        if (rows.isEmpty()) return@budgeted MessageSearchResult(emptyList(), total)
        val ids = rows.map { it[messages.id].value }
        budget.arm(this)
        val snippets = when {
            queryText != null -> messageSearchSnippets(ids, queryText)
            resolved?.headlineQuery() != null -> messageSearchSnippets(ids, resolved.headlineQuery()!!)
            resolved != null -> rows.associate { it[messages.id].value to plainSnippet(it[messages.body], resolved) }
            else -> emptyMap()
        }
        budget.arm(this)
        val names = userNames(rows.map { it[messages.authorId].value })
        MessageSearchResult(
            rows.map { row ->
                val id = row[messages.id].value
                MessageSearchHit(
                    id = id,
                    authorId = row[messages.authorId].value,
                    authorName = names.getValue(row[messages.authorId].value),
                    createdAt = row[messages.createdAt],
                    snippet = snippets[id].orEmpty(),
                )
            },
            total,
        )
    }

    /** The audited `429` `search-timeout` (ids only, never the term); the cause is deliberately neither logged nor attached. */
    private fun timedOut(callerUserId: UInt, roomId: UInt?): Nothing {
        audit("search.timeout", "byUserId" to callerUserId.toLong(), "roomId" to roomId?.toLong())
        throw searchTimeout()
    }

    /**
     * The statements of a search over [inRooms]: the query text, the count and the page of threads (one predicate: the title matches,
     * or a LIVE message of the thread does), then the message snippets and the title headlines, for the page's thread ids only. With
     * [q] null (the watched-threads and tag views: `watching=true` or a tag alone) there is no text to match: the query-text and the two
     * snippet statements are never sent, the predicate is the rooms and the filters, a hit carries no messages and its title as one
     * non-hit segment, and the page follows the same `sort` (default newest activity first). [watching] narrows to the threads
     * [callerUserId] watches (`true`) or does not (`false`), through the S3 watch predicate correlated on each thread's own room.
     * [tagNames] narrows to the threads that carry ANY of those curated tags (retired ones included): the names are resolved to registry
     * ids in this transaction (a name nobody ever held has no id and is probed as one that matches nothing, so the request does the same
     * statements and the same work as for a tag nobody uses: no timing tells "never a tag" from "retired" or "unused"). The tag predicate
     * is a correlated scalar probe of `thread_tags` per thread ([threadCarriesAnyThreadTag]), ANDed AFTER the rooms, so a thread of a
     * room the caller is not in can never surface through it NOR set the time it takes: the work follows the caller's own threads, never
     * the tag's global usage (`persistence.md` "Thread tags (V33)", `security.md`). Every hit carries its `tags`, read in ONE statement
     * for the page's thread ids.
     */
    private suspend fun R2dbcTransaction.searchIn(
        inRooms: List<RoomSummary>,
        callerUserId: UInt,
        q: String?,
        closed: Boolean?,
        watching: Boolean?,
        unread: Boolean?,
        tagNames: List<String>,
        paging: PageRequest,
        budget: SearchBudget,
        structured: StructuredSearch?,
    ): ThreadSearchResult {
        budget.arm(this)
        val tagIds = threadTags.idsOf(tagNames)
        budget.arm(this)
        val queryText = if (q == null) null else ftsQueryText(q) ?: return ThreadSearchResult(emptyList(), 0)
        val resolved = structured?.let { query ->
            resolveStructuredSearch(query, inRooms) { budget.arm(this) }
        }
        if (resolved?.impossible == true) return ThreadSearchResult(emptyList(), 0)
        val threads = ThreadService.Threads
        var predicate: Op<Boolean> = resolved?.threadPredicate()?.asOp()
            ?: threads.roomId.equalsAnyInt(inRooms.map { it.id.toInt() })
        queryText?.let { predicate = predicate and matchesTitleOrMessage(it) }
        closed?.let { predicate = predicate and (if (it) threads.closedAt.isNotNull() else threads.closedAt.isNull()) }
        if (tagNames.isNotEmpty()) predicate = predicate and threadCarriesAnyThreadTag(threads.id, tagIds)
        watching?.let {
            val watched = threadWatchedByInRoomOf(callerUserId, threads.roomId, threads.id)
            predicate = predicate and (if (it) watched else not(watched))
        }
        unread?.let {
            val unreadThreads = threadUnreadForMembership(callerUserId)
            predicate = predicate and (if (it) unreadThreads else not(unreadThreads))
        }
        budget.arm(this)
        val total = threads.selectAll().where { predicate }.count()
        budget.arm(this)
        val rows = threads.select(HIT_COLUMNS).where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        if (rows.isEmpty()) return ThreadSearchResult(emptyList(), total)

        val ids = rows.map { it[threads.id].value }
        var matches: Map<UInt, ThreadMatches> = emptyMap()
        var titles: Map<UInt, List<SnippetSegment>> = emptyMap()
        if (queryText != null) {
            budget.arm(this)
            matches = messageSnippets(ids, queryText)
            budget.arm(this)
            titles = titleSnippets(ids, queryText)
        } else if (resolved != null) {
            budget.arm(this)
            matches = structuredMessageSnippets(ids, resolved)
            budget.arm(this)
            titles = structuredTitleSnippets(ids, resolved)
        }
        budget.arm(this)
        val tagsOf = threadTags.forThreads(ids)
        budget.arm(this)
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
                tags = tagsOf[id].orEmpty(),
            )
        }
        return ThreadSearchResult(items, total)
    }

    /**
     * `threads.search @@ q OR (the first LIVE message of the thread with messages.search @@ q) IS NOT NULL`. The probe is a correlated
     * scalar subquery on purpose, NOT `EXISTS`: PostgreSQL may turn an `EXISTS` under an `OR` into a HASHED subplan that reads every
     * matching message of the WHOLE table (a seq scan of it when the prefix is common: 1.3 s over 200,000 messages for a caller in 500
     * of 1,000 rooms, and linear in the table), whatever rooms the caller is in. A scalar subquery is never hashed, so the work is
     * bounded by the caller's own threads: the rooms (the first predicate) are restricted FIRST, and each of their threads probes
     * `idx_messages_thread` and stops at its first matching message. There is no index on `messages.search`; the probe matches on
     * [ftsRowVector], not the bare column, so a generic plan could not put one inside the per-thread probe if one were added
     * (`persistence.md` "Search (V16)"). The title vector is a filter on the room index.
     */
    private fun matchesTitleOrMessage(queryText: String): Op<Boolean> {
        val threads = ThreadService.Threads
        val messages = MessageService.Messages
        val firstLiveMatch = wrapAsExpression<Any>(
            messages.select(messages.id).where {
                (messages.threadId eq threads.id) and messages.active() and messages.ftsRowVector().ftsMatches(queryText)
            }.limit(1),
        )
        return threads.ftsVector().ftsMatches(queryText) or firstLiveMatch.isNotNull()
    }
}
