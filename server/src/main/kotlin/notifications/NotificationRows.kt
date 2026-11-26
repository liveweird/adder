package ch.nokillswit.notifications

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/** Consume a notice query's result, including a write with RETURNING. A mapper may skip a row it cannot interpret. */
internal suspend fun <T : Any> R2dbcTransaction.noticeRows(
    sql: String,
    arguments: Iterable<Pair<IColumnType<*>, Any?>>,
    transform: (Row) -> T?,
): List<T> = checkNotNull(exec(sql, arguments, StatementType.SELECT, transform)).filterNotNull().toList()

/** Read a projection backed by a non-null column. The optional columns are still read with Row.get. */
internal fun <T : Any> Row.noticeColumn(index: Int, type: Class<T>): T = checkNotNull(get(index, type))
