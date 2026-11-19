package ch.nokillswit.threads

import ch.nokillswit.infra.db.FTS_FRAGMENT_OPTIONS
import ch.nokillswit.infra.db.FTS_INT_ARRAY
import ch.nokillswit.infra.db.FTS_WHOLE_OPTIONS
import ch.nokillswit.infra.db.ftsHeadlineSql
import ch.nokillswit.infra.db.ftsMatchSql
import ch.nokillswit.infra.db.ftsRowVectorSql
import ch.nokillswit.infra.db.splitHeadline
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * The two headline reads of a search page, for the page's thread ids ONLY (`ts_headline` is the expensive call: it is never run over a
 * match that is not shown). Raw statements because they need a window function and the generated column, which no Exposed table object
 * declares (`infra/db/Fts.kt`). Every argument is bound; the markers and the headline options are `infra/db/Fts.kt`'s, and the result is
 * typed segments, never the marked-up string.
 */

/** A thread's newest matching messages with the count of ALL its matches. */
internal class ThreadMatches(val hits: Long, val messages: List<MatchedMessage>)

/** One matching message of a thread, its snippet already split into segments. */
internal class MatchedMessage(val id: UInt, val authorId: UInt, val createdAt: Long, val snippet: List<SnippetSegment>)

/**
 * The [SEARCH_MESSAGES_PER_THREAD] newest matching live messages of each thread and the count of all its matches. The inner query ranks
 * the matches by id alone (no body is read for a match that is not shown); the outer one joins the ranked few back for the body to
 * headline. The match is on `ftsRowVectorSql`, so the statement reads the page's threads through `idx_messages_thread` in every plan
 * mode, whatever index `messages.search` may get later. A tombstone never matches (`NOT marked_as_deleted`, and its
 * vector is empty anyway). The arguments, in order: the headline's
 * query text and options, the thread ids, the match's query text.
 */
private fun messageSnippetsSql() = """
SELECT r.thread_id, r.id, m.author_id, m.created_at, r.hits, ${ftsHeadlineSql("m.body")}
FROM (SELECT x.id, x.thread_id,
             row_number() OVER (PARTITION BY x.thread_id ORDER BY x.id DESC) AS rn,
             count(*)      OVER (PARTITION BY x.thread_id) AS hits
      FROM messages x
      WHERE x.thread_id = ANY(CAST(? AS int4[])) AND NOT x.marked_as_deleted AND ${ftsMatchSql(ftsRowVectorSql("x.search"))}) r
JOIN messages m ON m.id = r.id
WHERE r.rn <= $SEARCH_MESSAGES_PER_THREAD
ORDER BY r.thread_id, r.id DESC"""

internal suspend fun R2dbcTransaction.messageSnippets(threadIds: List<UInt>, queryText: String): Map<UInt, ThreadMatches> {
    val arguments = listOf(
        TextColumnType() to queryText,
        TextColumnType() to FTS_FRAGMENT_OPTIONS,
        FTS_INT_ARRAY to threadIds.map { it.toInt() },
        TextColumnType() to queryText,
    )
    val rows = exec(messageSnippetsSql(), arguments, explicitStatementType = StatementType.SELECT) { row ->
        SnippetRow(
            threadId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
            message = MatchedMessage(
                id = row.get(1, Int::class.javaObjectType)!!.toUInt(),
                authorId = row.get(2, Long::class.javaObjectType)!!.toUInt(),
                createdAt = row.get(3, Long::class.javaObjectType)!!,
                snippet = splitHeadline(row.get(5, String::class.java).orEmpty()).map { it.toWire() },
            ),
            hits = row.get(4, Long::class.javaObjectType)!!,
        )
    }?.toList().orEmpty().filterNotNull()
    return rows.groupBy { it.threadId }.mapValues { (_, own) -> ThreadMatches(own.first().hits, own.map { it.message }) }
}

private class SnippetRow(val threadId: UInt, val message: MatchedMessage, val hits: Long)

/** The whole title of each of [threadIds] with its hits marked (a title that does not match comes back as one plain segment). */
internal suspend fun R2dbcTransaction.titleSnippets(threadIds: List<UInt>, queryText: String): Map<UInt, List<SnippetSegment>> {
    val sql = "SELECT t.id, ${ftsHeadlineSql("t.title")} FROM threads t WHERE t.id = ANY(CAST(? AS int4[]))"
    val arguments = listOf(
        TextColumnType() to queryText,
        TextColumnType() to FTS_WHOLE_OPTIONS,
        FTS_INT_ARRAY to threadIds.map { it.toInt() },
    )
    return exec(sql, arguments, explicitStatementType = StatementType.SELECT) { row ->
        row.get(0, Int::class.javaObjectType)!!.toUInt() to splitHeadline(row.get(1, String::class.java).orEmpty()).map { it.toWire() }
    }?.toList().orEmpty().filterNotNull().toMap()
}
