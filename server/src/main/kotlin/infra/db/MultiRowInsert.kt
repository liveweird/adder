package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The largest number of bind parameters one [insertRows] statement carries. The PostgreSQL wire
 * protocol counts a Bind message's parameters in an Int16 (65,535); half of that leaves headroom,
 * and the row chunk is derived from it, so a wide table simply gets smaller chunks.
 */
private const val MAX_BIND_PARAMETERS = 32_000

/** The most rows one statement of a table whose statement lists [columnCount] columns carries (its bind-parameter cap). */
internal fun maxRowsPerStatement(columnCount: Int): Int = (MAX_BIND_PARAMETERS / columnCount).coerceAtLeast(1)

/**
 * Test seam (`MultiRowInsertTest`): a coroutine-context element, installed with
 * `withContext(StatementProbe { table, rows -> … }) { … }`, that [writeRows] calls with the table and the number of rows a statement
 * carries, just before it runs. Those are the only rows [insertRows] and [insertRowsIgnoringConflicts] have in flight. It sees only the
 * writes of coroutines running under it, so a concurrent writer elsewhere in the
 * same JVM can neither reach it nor be miscounted by it (it replaced a process-wide mutable hook).
 */
internal class StatementProbe(val onStatement: (Table, Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StatementProbe>
}

/** The row a [insertRows] body fills: `this[Column] = value`, the same DSL as `batchInsert`'s lambda. */
class InsertRow {
    internal val values = LinkedHashMap<Column<*>, Any?>()

    operator fun <S> set(column: Column<S>, value: S) {
        values[column] = value
    }

    // A `reference(…)` column is a `Column<EntityID<ID>>` that callers fill with the plain id, exactly
    // as `batchInsert`'s own setter takes it; it is bound through the referenced id column's own type.
    @JvmName("setEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>>, value: ID) {
        values[column] = value
    }

    @JvmName("setNullableEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>?>, value: ID?) {
        values[column] = value
    }
}

/**
 * `batchInsert`'s call shape — `table.insertRows(rows) { this[Col] = it.value }` — executed as ONE
 * multi-row `INSERT … VALUES (…), (…)` per chunk instead of one statement execution per row.
 *
 * Why it exists: `exposed-r2dbc`'s `batchInsert` runs every
 * row as its own bound execution of the prepared statement — ~0.16-0.19 ms per row however small
 * the row — so a bulk write of ~15,000 rows spent seconds in it. The same rows as multi-row `VALUES` statements
 * take ~10 x less (3,600 rows:
 * 620 ms `batchInsert`, 54 ms here; the database's own cost with its indexes and foreign-key check
 * is ~40 ms). Nothing else differs: the rows, their order and their column values are the ones
 * `batchInsert` writes (every value still goes through its column's own type, every value is
 * still a bound parameter, never string-built) and it runs inside the caller's current transaction.
 *
 * Contract (checked, not assumed): every row sets the SAME columns. The statement lists the table's
 * columns in declaration order: each column a row sets, plus each column carrying an Exposed
 * client-side `default(…)` that no row sets (filled with that default, as `batchInsert` does). Any
 * other unset column is omitted, so its database default applies (a `SERIAL` id, a NULL). Generated
 * values are never returned (no caller reads the surrogate id). An empty [rows] is a no-op. Every set
 * column must belong to this table, and the table must have no sequence-backed `autoIncrement("seq")`
 * column (Exposed would insert `nextval(...)` for it; plain `SERIAL` ids, which the database fills, are fine).
 * Exposed's client-side value validation (a `varchar` length check) is NOT run: an overflow fails
 * server-side (SQLSTATE 22001) instead of in the client.
 *
 * **Statements are bounded in rows and in payload** ([payloadChunkSizes]): at most the bind cap's rows ([MAX_BIND_PARAMETERS]) and
 * at most [MAX_PAYLOAD_CHARS] characters of `String` values (a lone heavier row goes alone); the rows keep their order and their one
 * transaction.
 *
 * **Statement texts are quantized** ([payloadChunkSizes]): the SQL text depends on the row count, and every
 * distinct text becomes one named server-side prepared statement that the driver caches per connection,
 * so a chunk is either the table's maximum rows per statement or a power of two — a handful of texts per
 * table, however many rows a batch has.
 *
 * **Rows are built per statement, and the plan is the same.** [insertRows] and [insertRowsIgnoringConflicts] run `body` for a row only
 * while the statement that carries it is assembled ([lazyStatements]): rows are built until the next statement is decided (the row cap
 * reached, or the payload budget overflowed by one row, the payload of a row being known once it is built) and the rest wait for the
 * next one, so the rows in flight are one statement's cap ([maxRowsPerStatement]), not the call's: the `InsertRow` maps, the boxed
 * numbers and the payload texts `body` renders for the rest of a large call do not exist yet (WORKLOGS batches of up to ~30,000 rows,
 * `build-times.md` WHY 51). The statements are EXACTLY [payloadChunkSizes]' over the full list, power-of-two rounding included. `body`
 * therefore runs lazily, exactly once per row, and must be pure (the first row, built to read the columns, is handed to the first
 * statement, not built again, and is not kept for the call). A row that sets other columns than the first fails the call when its statement
 * is assembled, after the earlier statements ran: the caller's transaction rolls them back. A writer may bound its own groups tighter
 * (a writer handing in groups of at most 1,024 rows / 512 Ki characters, to cap what it holds beside
 * the batch); a group is under [MAX_PAYLOAD_CHARS], so it normally becomes one statement (a group of a non-power-of-two row count
 * becomes its binary decomposition, as any batch does) and the two bounds never contradict.
 *
 * **Memory (`MultiRowInsertRetentionTest`):** every chunk's executed statement is released
 * as soon as it ran (`clearExecutedStatements`), so a transaction's heap does not grow with the rows it writes.
 * `clearExecutedStatements()` empties the transaction's WHOLE list — the caller's earlier statements too (a
 * per-batch SELECT, ~80 MB at scale 20 if kept) — and resets `openResultRowsCount`. Per-batch reads
 * are therefore bounded only because every batch ends in an `insertRows`: a step that reads per batch but writes via
 * `batchInsert`/`exec` would bring the linear retention back.
 */
suspend fun <T : Table, E> T.insertRows(rows: Iterable<E>, body: InsertRow.(E) -> Unit) {
    writeRows("insertRows", rows, body)
}

/**
 * [insertRows]' append-only sibling — `table.insertRowsIgnoringConflicts(rows) { … }` is ONE multi-row
 * `INSERT … VALUES (…), (…) ON CONFLICT DO NOTHING` per chunk (the same chunking, quantized statement texts, bind-parameter cap
 * and contract as [insertRows]; the suffix is constant). It is the batch form of Exposed's `insertIgnore`: a row that violates
 * any unique constraint is silently dropped, the first of two rows sharing a key winning (PostgreSQL skips a duplicate inside
 * one `DO NOTHING` statement — unlike `DO UPDATE`, which could not touch a row twice). Nothing is returned, so the number of
 * rows actually written is unknown to the caller.
 */
suspend fun <T : Table, E> T.insertRowsIgnoringConflicts(rows: Iterable<E>, body: InsertRow.(E) -> Unit) {
    writeRows("insertRowsIgnoringConflicts", rows, body, ignoreConflicts = true)
}

private suspend fun <T : Table, E> T.writeRows(
    caller: String,
    rows: Iterable<E>,
    body: InsertRow.(E) -> Unit,
    ignoreConflicts: Boolean = false,
) {
    val source = rows as? List<E> ?: rows.toList()
    if (source.isEmpty()) return
    fun build(element: E): Map<Column<*>, Any?> = InsertRow().also { it.body(element) }.values
    // Each row is built only while its statement is being assembled (see "Memory").
    val firstRow = build(source.first())
    // A copy: the key view would keep the first row alive for the whole call.
    val setColumns = LinkedHashSet(firstRow.keys)
    require(setColumns.isNotEmpty()) { "$caller: a row that sets no column cannot be inserted" }
    fun checked(row: Map<Column<*>, Any?>) = row.also { require(it.keys == setColumns) { "$caller: every row must set the same columns" } }
    requireWritable(caller, setColumns)
    val columns = this.columns.filter { it in setColumns || it.defaultValueFun != null }
    val tx = TransactionManager.current()
    val header = "INSERT INTO ${tx.identity(this)} (${columns.joinToString(", ") { tx.identity(it) }}) VALUES "
    val suffix = if (ignoreConflicts) " ON CONFLICT DO NOTHING" else ""
    val maxRows = maxRowsPerStatement(columns.size)
    // The lazy plan's first row is the one built above; the rest are built as their statement is assembled (`head` is dropped once used).
    var head: Map<Column<*>, Any?>? = firstRow
    var next = 1
    val statements = lazyStatements(source.size, maxRows, MAX_PAYLOAD_CHARS.toLong(), ::payloadChars) {
        head?.also { head = null } ?: checked(build(source[next++]))
    }
    for (chunk in statements) {
        val size = chunk.size
        val arguments = ArrayList<Pair<IColumnType<*>, Any?>>(size * columns.size)
        val sql = StringBuilder(header)
        chunk.forEachIndexed { index, row ->
            if (index > 0) sql.append(", ")
            sql.append('(')
            columns.forEachIndexed { columnIndex, column ->
                val value = if (column in row) row[column] else column.defaultValueFun?.invoke()
                if (columnIndex > 0) sql.append(", ")
                val bound = bindable(column, value)
                sql.append(marker(bound.first, bound.second))
                arguments += bound
            }
            sql.append(')')
        }
        sql.append(suffix)
        coroutineContext[StatementProbe]?.onStatement?.invoke(this, size)
        tx.exec(sql.toString(), arguments)
        // Exposed keeps every executed statement until the transaction ends (commit or rollback; `closeStatementsAndConnection`
        // only clears the list, closing nothing, and `executeIn` itself clears it when `!supportsMultipleResultSets`, so this
        // is a sanctioned operation): a 32,000-parameter statement is ~8 MB of parsed tokens + encoded parameters, and a bulk
        // write is ONE transaction.
        tx.clearExecutedStatements()
    }
}

/** The checks on the table and the columns a call sets that do not depend on any row's value (see the contract on [insertRows]). */
private fun Table.requireWritable(caller: String, setColumns: Set<Column<*>>) {
    setColumns.firstOrNull { it.table != this }?.let { foreign ->
        throw IllegalArgumentException("$caller: column ${foreign.name} belongs to ${foreign.table.tableName}, not ${this.tableName}")
    }
    require(this.columns.none { (it.columnType as? AutoIncColumnType<*>)?.sequence != null }) {
        "$caller: ${this.tableName} has a sequence-backed autoIncrement column; use batchInsert"
    }
}

/** The characters of `String` values a row binds (the payload the budget counts; defaults a row leaves unset are not counted). */
private fun payloadChars(row: Map<Column<*>, Any?>): Int = row.values.sumOf { (it as? String)?.length ?: 0 }

/**
 * The type and value to bind for [column]. An `EntityID` (reference / id) column is bound as its
 * underlying id column (`EntityIDColumnType.notNullValueToDB` insists on an `EntityID` wrapper,
 * which a plain-id caller does not have and the wire never needs).
 */
private fun bindable(column: Column<*>, value: Any?): Pair<IColumnType<*>, Any?> {
    val type = column.columnType
    if (type !is EntityIDColumnType<*>) return type to value
    return type.idColumn.columnType to (if (value is EntityID<*>) value.value else value)
}

// The column type's own placeholder (`?` for every type the callers use; a type that needs a cast
// says so here, exactly as Exposed's single-row insert asks it).
@Suppress("UNCHECKED_CAST") // the value comes from the same column's own `set`, so its type always matches
private fun marker(type: IColumnType<*>, value: Any?): String = (type as IColumnType<Any?>).parameterMarker(value)
