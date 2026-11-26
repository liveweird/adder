package ch.nokillswit.infra.db

/**
 * The most characters of `String` parameter values (`jsonb` payloads, any text) one statement carries: 1 MiB.
 * The bind cap alone bounds the PARAMETER COUNT, not the bytes: wide payload rows flushed in statements capped only at the bind
 * cap's ~3,555 rows were ~55 MB of jsonb each, and two such statements at once got a Postgres backend OOM-killed.
 * A statement costs the backend several times its payload (the Bind message, the parsed value, the jsonb conversion, the tuple),
 * so the budget keeps that to a few MiB; a real multi-MB flush splits too: behaviourally
 * fine, the rows keep their order and their one transaction. Counted in `String.length` (UTF-16 units), which UNDER-counts the UTF-8
 * bytes of non-ASCII text by up to 3x, so such a statement can carry up to ~3 MiB; harmless at this budget (a memory guard, not a
 * quota). Only `String` values count. Rows of short keys are far below it, so their statements are the ones the bind cap alone made.
 * A single row over the budget is written alone, never refused here (a writer that must refuse an oversized item does so
 * upstream, with its own stored-size ceiling). The rows are built lazily against this same budget (see [lazyStatements]).
 */
internal const val MAX_PAYLOAD_CHARS = 1 shl 20

/**
 * The row counts of the statements that write rows weighing [payloads] (each row's `String` characters, in order), each statement
 * at most [maxRows] rows and, unless it is a single row, at most [maxPayload] characters. A statement is [maxRows] rows or a
 * power of two, so the SQL texts stay a handful per table; when no row is heavy the result is the full chunks of [maxRows] and then
 * the remainder's binary decomposition (13 -> 8, 4, 1; pinned against an oracle in `MultiRowInsertPayloadTest`). A statement is the
 * longest fitting run, rounded DOWN to a power of two unless it hit [maxRows]; a run that stopped on the payload budget leaves its
 * remainder for the next statement. A row heavier than
 * [maxPayload] alone is a statement of one row. The budget is inclusive (a run summing to exactly [maxPayload] fits).
 */
internal fun payloadChunkSizes(payloads: List<Int>, maxRows: Int, maxPayload: Long): List<Int> {
    val sizes = ArrayList<Int>()
    var start = 0
    while (start < payloads.size) {
        var fit = 0
        var sum = 0L
        while (start + fit < payloads.size && fit < maxRows) {
            sum += payloads[start + fit]
            if (fit > 0 && sum > maxPayload) break
            fit++
        }
        val size = if (fit == maxRows) maxRows else Integer.highestOneBit(fit)
        sizes += size
        start += size
    }
    return sizes
}

/**
 * The statements [payloadChunkSizes] would plan over [total] rows, but built lazily: [next] builds the next row ([payloadOf] is its
 * payload) only when the statement being decided needs it, so a row exists from its building until its statement is handed out and the
 * rows in flight are at most [maxRows] (one statement's cap), never the call's. The plan is exactly [payloadChunkSizes]' over the full
 * list, power-of-two rounding included: a statement is [maxRows] rows when that many fit; otherwise a power of two, and then the
 * rows still unemitted decide how far to look. With `left` rows unemitted at most `min(maxRows, left)` can join, so a statement is
 * decided as soon as that many are built and fit (a [maxRows] run when `left >= maxRows`, else the highest power of two not above
 * `left`), or as soon as the budget overflows at row `k` (then the highest power of two not above `k`). Rows built past the statement
 * (the overflowing row, a power-of-two remainder) stay for the next one. Pinned against [payloadChunkSizes] in `MultiRowInsertPayloadTest`.
 */
internal fun <R> lazyStatements(
    total: Int,
    maxRows: Int,
    maxPayload: Long,
    payloadOf: (R) -> Int,
    next: () -> R,
): Sequence<List<R>> = sequence {
    val pending = ArrayDeque<Pair<R, Int>>()
    var left = total
    while (left > 0) {
        val reach = minOf(maxRows, left)
        val target = if (reach == maxRows) maxRows else Integer.highestOneBit(reach)
        var fit = 0
        var sum = 0L
        var size = 0
        while (size == 0) {
            if (fit == target) {
                size = target
            } else {
                if (fit == pending.size) next().let { pending.addLast(it to payloadOf(it)) }
                sum += pending[fit].second
                if (fit > 0 && sum > maxPayload) size = Integer.highestOneBit(fit) else fit++
            }
        }
        yield(List(size) { pending.removeFirst().first })
        left -= size
    }
}
