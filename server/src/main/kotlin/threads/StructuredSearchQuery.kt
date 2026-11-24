package ch.nokillswit.threads

import ch.nokillswit.infra.db.FTS_CONFIG
import ch.nokillswit.infra.db.FTS_INT_ARRAY
import ch.nokillswit.infra.db.ftsMatchSql
import ch.nokillswit.infra.db.ftsRowVectorSql
import ch.nokillswit.rooms.RoomSummary
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ArrayColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

private val LONG_ARRAY = ArrayColumnType<Long, List<Long>>(LongColumnType())
private val TEXT_ARRAY = ArrayColumnType<String, List<String>>(TextColumnType())
private val TEXT_TYPE = TextColumnType()
private val LONG_TYPE = LongColumnType()

internal data class SearchSql(val text: String, val arguments: List<Pair<IColumnType<*>, Any?>> = emptyList()) {
    fun and(other: SearchSql): SearchSql = SearchSql("($text) AND (${other.text})", arguments + other.arguments)
    fun or(other: SearchSql): SearchSql = SearchSql("($text) OR (${other.text})", arguments + other.arguments)
    fun negated(): SearchSql = SearchSql("NOT ($text)", arguments)
}

internal data class ResolvedStructuredSearch(
    val query: StructuredSearch,
    val roomIds: List<Int>,
    val authorIds: List<Set<Long>>,
    val participantIds: List<Set<Long>>,
    val ownerIds: List<Set<Long>>,
    val textQueries: Map<SearchTextClause, String>,
) {
    val impossible: Boolean
        get() = roomIds.isEmpty() || (authorIds + participantIds + ownerIds).any(Set<Long>::isEmpty)

    val hasPositiveText: Boolean get() = query.text.any { !it.excluded }
    val hasMessageSpecificExceptPin: Boolean
        get() = query.authors.isNotEmpty() || query.startMillis != null || query.endMillis != null || query.link ||
            query.reactions.isNotEmpty() || query.emojis.isNotEmpty()

    fun headlineQuery(): String? = query.text.asSequence()
        .filter { !it.excluded && it.kind != SearchTextKind.PHRASE }
        .map(textQueries::getValue)
        .filter(String::isNotBlank)
        .joinToString(" & ", prefix = "(", postfix = ")")
        .takeIf { it != "()" }

    fun titleHeadlineQuery(): String? = titlePredicate("threads")?.let { headlineQuery() }

    fun literalHighlights(): List<String> = buildList {
        query.text.filter { !it.excluded && it.kind == SearchTextKind.PHRASE }.mapTo(this) { it.value }
        addAll(query.emojis)
    }

    fun threadPredicate(threadAlias: String = "threads"): SearchSql {
        var result = roomIdsSql(threadAlias)
        ownerIds.forEach { result = result.and(anyLong("$threadAlias.owner_id", it)) }
        participantIds.forEach { ids -> result = result.and(participantSql(threadAlias, ids)) }

        val message = messagePredicate("m", threadAlias) ?: return result
        val probe = messageProbe(threadAlias, message)
        val title = titlePredicate(threadAlias)
        return result.and(if (title == null) probe else probe.or(title))
    }

    fun messagePredicate(messageAlias: String, threadAlias: String): SearchSql? {
        var result: SearchSql? = null
        fun add(part: SearchSql) {
            result = result?.and(part) ?: part
        }

        query.text.forEach { add(textSql(it, "$messageAlias.body", ftsRowVectorSql("$messageAlias.search"))) }
        authorIds.forEach { add(anyLong("$messageAlias.author_id", it)) }
        query.startMillis?.let { add(SearchSql("$messageAlias.created_at >= CAST(? AS int8)", listOf(LONG_TYPE to it))) }
        query.endMillis?.let { add(SearchSql("$messageAlias.created_at < CAST(? AS int8)", listOf(LONG_TYPE to it))) }
        if (query.link) {
            add(
                SearchSql(
                    "$messageAlias.body ~* '(^|[^[:alnum:]_])(http://[^[:space:]]+|https://[^[:space:]]+|" +
                        "www\\.[^[:space:]]+|mailto:[^[:space:]]+)'",
                ),
            )
        }
        query.emojis.forEach { add(SearchSql("strpos($messageAlias.body, ?) > 0", listOf(TEXT_TYPE to it))) }
        query.reactions.forEach { emoji -> add(reactionSql(messageAlias, emoji)) }
        if (query.pinned) {
            val otherMessageClauses = query.text.isNotEmpty() || hasMessageSpecificExceptPin
            add(if (otherMessageClauses) messageOrThreadPinSql(messageAlias, threadAlias) else messagePinSql(messageAlias))
        }
        return result
    }

    private fun roomIdsSql(threadAlias: String) = SearchSql(
        "$threadAlias.room_id = ANY(CAST(? AS int4[]))",
        listOf(FTS_INT_ARRAY to roomIds),
    )

    private fun titlePredicate(threadAlias: String): SearchSql? {
        val textAllowed = hasPositiveText && !hasMessageSpecificExceptPin && !query.pinned
        val pinAllowed = query.pinned && !hasMessageSpecificExceptPin && (query.text.isEmpty() || hasPositiveText)
        if (!textAllowed && !pinAllowed) return null

        var result: SearchSql? = null
        query.text.forEach { clause ->
            val part = textSql(clause, "$threadAlias.title", "$threadAlias.search")
            result = result?.and(part) ?: part
        }
        if (query.pinned) {
            val pin = threadPinSql(threadAlias)
            result = result?.and(pin) ?: pin
        }
        return result
    }

    private fun textSql(clause: SearchTextClause, value: String, vector: String): SearchSql {
        val positive = when (clause.kind) {
            SearchTextKind.PHRASE -> SearchSql("strpos(lower($value), lower(?)) > 0", listOf(TEXT_TYPE to clause.value))
            SearchTextKind.WORD, SearchTextKind.PREFIX -> SearchSql(
                ftsMatchSql(vector),
                listOf(TEXT_TYPE to textQueries.getValue(clause)),
            )
        }
        return if (clause.excluded) positive.negated() else positive
    }
}

internal suspend fun R2dbcTransaction.resolveStructuredSearch(
    query: StructuredSearch,
    rooms: List<RoomSummary>,
    beforeStatement: suspend () -> Unit,
): ResolvedStructuredSearch {
    val roomIds = resolveRooms(query.rooms, rooms)
    val references = query.authors + query.participants + query.owners
    val namedReferences = references.filter { it.name != null }
    val users = if (namedReferences.isEmpty() || roomIds.isEmpty()) {
        emptyList()
    } else {
        beforeStatement()
        candidateUsers(roomIds, namedReferences)
    }
    val queries = query.text.filter { it.kind != SearchTextKind.PHRASE }.associateWith {
        beforeStatement()
        textQuery(it)
    }
    return ResolvedStructuredSearch(
        query = query,
        roomIds = roomIds,
        authorIds = query.authors.map { resolveUser(it, users) },
        participantIds = query.participants.map { resolveUser(it, users) },
        ownerIds = query.owners.map { resolveUser(it, users) },
        textQueries = queries,
    )
}

private fun resolveRooms(references: List<SearchReference>, rooms: List<RoomSummary>): List<Int> {
    if (references.isEmpty()) return rooms.map { it.id.toInt() }
    val resolved = references.map { reference ->
        rooms.filter { room ->
            reference.id?.let { room.id == it } ?: room.name.equals(reference.name, ignoreCase = true)
        }.map { it.id.toInt() }.toSet()
    }
    return resolved.reduce(Set<Int>::intersect).sorted()
}

private data class CandidateUser(val id: Long, val name: String, val email: String)

private suspend fun R2dbcTransaction.candidateUsers(
    roomIds: List<Int>,
    references: List<SearchReference>,
): List<CandidateUser> {
    val names = references.mapNotNull { it.name }.distinct()
    val sql = """
SELECT u.id, u.name, u.email
FROM users u
WHERE (lower(u.name) = ANY(SELECT lower(v) FROM unnest(CAST(? AS text[])) v)
    OR lower(u.email) = ANY(SELECT lower(v) FROM unnest(CAST(? AS text[])) v))
  AND ((SELECT t.id FROM threads t
        WHERE t.room_id = ANY(CAST(? AS int4[])) AND t.owner_id = u.id LIMIT 1) IS NOT NULL
    OR (SELECT m.id FROM threads t JOIN messages m ON m.thread_id = t.id
        WHERE t.room_id = ANY(CAST(? AS int4[])) AND m.author_id = u.id AND NOT m.marked_as_deleted LIMIT 1) IS NOT NULL)
""".trimIndent()
    val arguments = listOf(
        TEXT_ARRAY to names,
        TEXT_ARRAY to names,
        FTS_INT_ARRAY to roomIds,
        FTS_INT_ARRAY to roomIds,
    )
    return exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        CandidateUser(
            id = row.get(0, Long::class.javaObjectType)!!,
            name = row.get(1, String::class.java)!!,
            email = row.get(2, String::class.java)!!,
        )
    }?.toList().orEmpty().filterNotNull()
}

private fun resolveUser(reference: SearchReference, users: List<CandidateUser>): Set<Long> = users.filter { user ->
    user.name.equals(reference.name, ignoreCase = true) ||
        user.email.equals(reference.name, ignoreCase = true)
}.mapTo(mutableSetOf()) { it.id }.ifEmpty { reference.id?.let { setOf(it.toLong()) }.orEmpty() }

private suspend fun R2dbcTransaction.textQuery(clause: SearchTextClause): String {
    val function = if (clause.kind == SearchTextKind.PREFIX) {
        "string_agg(quote_literal(lexeme) || ':*', ' & ')::tsquery"
    } else {
        "plainto_tsquery('$FTS_CONFIG'::regconfig, ?)"
    }
    val sql = if (clause.kind == SearchTextKind.PREFIX) {
        "SELECT $function::text FROM unnest(to_tsvector('$FTS_CONFIG'::regconfig, ?))"
    } else {
        "SELECT $function::text"
    }
    return exec(sql, listOf(TEXT_TYPE to clause.value), explicitStatementType = StatementType.SELECT) { row ->
        row.get(0, String::class.java)
    }?.toList()?.singleOrNull() ?: ""
}

private fun anyLong(expression: String, ids: Set<Long>) = SearchSql(
    "$expression = ANY(CAST(? AS int8[]))",
    listOf(LONG_ARRAY to ids.sorted()),
)

private fun participantSql(threadAlias: String, ids: Set<Long>): SearchSql = SearchSql(
    """(SELECT m.id FROM messages m
        WHERE m.thread_id = $threadAlias.id AND NOT m.marked_as_deleted
          AND m.author_id = ANY(CAST(? AS int8[])) LIMIT 1) IS NOT NULL""".trimIndent(),
    listOf(LONG_ARRAY to ids.sorted()),
)

private fun messageProbe(threadAlias: String, message: SearchSql): SearchSql = SearchSql(
    """(SELECT m.id FROM messages m
        WHERE m.thread_id = $threadAlias.id AND NOT m.marked_as_deleted AND (${message.text}) LIMIT 1) IS NOT NULL""".trimIndent(),
    message.arguments,
)

private fun reactionSql(messageAlias: String, emoji: String?): SearchSql {
    val specific = if (emoji == null) SearchSql("TRUE") else SearchSql("r.emoji = ?", listOf(TEXT_TYPE to emoji))
    return SearchSql(
        "(SELECT r.message_id FROM message_reactions r WHERE r.message_id = $messageAlias.id " +
            "AND ${specific.text} LIMIT 1) IS NOT NULL",
        specific.arguments,
    )
}

private fun messageOrThreadPinSql(messageAlias: String, threadAlias: String): SearchSql = SearchSql(
    """(SELECT p.id FROM room_pins p
        WHERE p.thread_id = $threadAlias.id
          AND (p.message_id = $messageAlias.id OR p.message_id IS NULL)
        LIMIT 1) IS NOT NULL""".trimIndent(),
)

private fun messagePinSql(messageAlias: String): SearchSql = SearchSql(
    "(SELECT p.id FROM room_pins p WHERE p.message_id = $messageAlias.id LIMIT 1) IS NOT NULL",
)

private fun threadPinSql(threadAlias: String): SearchSql = SearchSql(
    "(SELECT p.id FROM room_pins p WHERE p.thread_id = $threadAlias.id AND p.message_id IS NULL LIMIT 1) IS NOT NULL",
)

internal fun SearchSql.asOp(): Op<Boolean> = StructuredSearchOp(this)

private class StructuredSearchOp(private val sql: SearchSql) : Op<Boolean>() {
    init {
        check(sql.text.count { it == '?' } == sql.arguments.size) { "Structured search SQL argument mismatch" }
    }

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        sql.text.split('?').forEachIndexed { index, text ->
            if (index > 0) {
                val (type, value) = sql.arguments[index - 1]
                queryBuilder.registerArgument(type, value)
            }
            queryBuilder.append(text)
        }
    }
}
