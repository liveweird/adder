package ch.nokillswit.threads

import ch.nokillswit.plugins.isQueryCanceled
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One DEADLINE for the whole database work of a search request. A transaction's `queryTimeout` bounds each STATEMENT, and a search runs
 * several (the member rooms, the tag names' ids, the query text, the count, the page, the page's tags, the snippets, the titles, the
 * names), so a per-statement budget alone would let a request run for the sum of them. The budget is armed before each statement: its
 * `queryTimeout` becomes the whole seconds LEFT (rounded up: the driver takes whole seconds), and once the deadline has passed the next
 * statement is never sent. A request therefore ends within the configured budget plus at most one second of rounding, whatever the
 * number of its statements. [clock] is nanoseconds (`System.nanoTime`; a test passes its own).
 */
internal class SearchBudget(totalSeconds: Int, private val clock: () -> Long = System::nanoTime) {
    private val endNanos = clock() + totalSeconds * NANOS_PER_SECOND

    /** The whole seconds left (at least 1); throws [BudgetSpent] when the deadline has passed. */
    fun remainingSeconds(): Int {
        val left = endNanos - clock()
        if (left <= 0) throw BudgetSpent()
        return ((left + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND).toInt()
    }

    /** Arms the statements that follow with the time left. */
    fun arm(transaction: R2dbcTransaction) {
        transaction.queryTimeout = remainingSeconds()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * The ONE budgeted read transaction (the search's, the list reads', the thread detail's and the notice list's): [block] runs in a
 * single transaction under a fresh [SearchBudget] of [timeoutSeconds]. A statement the budget cancelled (SQLSTATE `57014`) or a
 * deadline spent between two statements calls [onTimeout], which audits and throws the caller's typed `429`; any other failure
 * propagates untouched. The cancelled statement's cause is deliberately dropped (a database failure's message can echo a bound value).
 */
internal suspend fun <T> budgetedTransaction(
    database: R2dbcDatabase,
    timeoutSeconds: Int,
    clock: () -> Long = System::nanoTime,
    onTimeout: () -> Nothing,
    block: suspend R2dbcTransaction.(SearchBudget) -> T,
): T {
    val budget = SearchBudget(timeoutSeconds, clock)
    try {
        return suspendTransaction(database) { block(budget) }
    } catch (_: BudgetSpent) {
        onTimeout()
    } catch (failure: Exception) {
        if (!failure.isQueryCanceled()) throw failure
        onTimeout()
    }
}

/** The deadline of a [SearchBudget] passed between two statements: answered like a cancelled statement (the audited `429`). */
internal class BudgetSpent : RuntimeException("The search budget is spent")
