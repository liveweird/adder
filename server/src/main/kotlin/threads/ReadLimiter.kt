package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/** The default of `threads.readTimeoutSeconds`: a normal list answers in milliseconds, so this is a runaway's ceiling. */
const val DEFAULT_READ_TIMEOUT_SECONDS = 5

/** The default of `threads.readMaxConcurrent`: well under the pool's 20 connections, so the lists never starve the rest of the app. */
const val DEFAULT_READ_MAX_CONCURRENT = 10

/**
 * The most bounded reads ONE user may have in flight at once. A live hint makes every open tab refetch the room's thread list and, with
 * the filter panel open, the tag list, after the same 300 ms settle (`web/src/utils/liveKeys.ts`): two reads per tab, all in the same
 * instant. 8 is four such tabs together, above any real use; a script that holds more than that is what the cap stops.
 */
const val MAX_READS_PER_USER = 8

/**
 * How long a read waits for a slot, the user's and then the process's TOGETHER (one deadline): a burst after a live hint queues this
 * briefly instead of failing, and nothing waits longer.
 */
const val READ_PERMIT_WAIT_MILLIS = 1_000L

private const val PERMIT_POLL_MILLIS = 20L

/**
 * The bounds of the two reads that scan a room's rows (the filtered thread list and the tag list), the way the search is bounded
 * (`ThreadSearchService`): ONE deadline per read and a cap on how many run at once, so a flood of slow reads can never hold the pool.
 *
 * - **Deadline.** [run] executes its block in one read transaction under ONE [SearchBudget] of [timeoutSeconds] (the search's budget,
 *   reused: each statement's `queryTimeout` is the time left, the only way a statement timeout may be set, `persistence.md`
 *   "Connection pool"). A statement the budget cancelled (SQLSTATE `57014`), or a deadline spent between two statements, is the audited
 *   `429` `read-timeout` (`read.timeout`).
 * - **In-flight caps.** At most [perUser] reads of one user run at once, and at most [maxConcurrent] in the whole process (both reads
 *   together, one limiter is shared). Both are `tryAcquire`s polled for at most [maxWaitMillis] IN TOTAL (a legitimate burst after a
 *   live hint, several tabs at once, queues briefly), then refused as the audited `429` `read-busy` (`read.busy`). The wait happens
 *   BEFORE a pool connection is asked for, so a queued read holds none: a read holds a connection only while it runs, and at most
 *   [maxConcurrent] do (the boot check keeps that, with the searches, under the pool size).
 *
 * Both permits are released in a `finally`; the per-user count is a map whose entry is removed when it reaches 0, so it never grows
 * beyond the users with a read in flight. The audit lines carry ids and the resource name, never a parameter value, and a cancelled
 * statement's cause is neither logged nor attached (a database failure's message can echo a bound value).
 */
class ReadLimiter(
    private val timeoutSeconds: Int = DEFAULT_READ_TIMEOUT_SECONDS,
    maxConcurrent: Int = DEFAULT_READ_MAX_CONCURRENT,
    private val perUser: Int = MAX_READS_PER_USER,
    private val maxWaitMillis: Long = READ_PERMIT_WAIT_MILLIS,
) {
    // kotlinx's Semaphore, not java.util.concurrent's: it throws on a release past its permits, so a refactor that releases twice fails
    // loudly instead of silently raising the cap (`ThreadSearchService` does the same).
    private val permits = Semaphore(maxConcurrent)
    private val inFlight = ConcurrentHashMap<UInt, Int>()

    /** How many users have a read in flight: 0 when idle (a test seam for "the map never leaks"). */
    internal fun usersInFlight(): Int = inFlight.size

    /** [resource] names the read (`threads` or `tags`) in the audit lines. */
    internal suspend fun <T> run(
        database: R2dbcDatabase,
        callerUserId: UInt,
        roomId: UInt,
        resource: String,
        block: suspend R2dbcTransaction.(SearchBudget) -> T,
    ): T {
        val deadline = System.nanoTime() + maxWaitMillis * NANOS_PER_MILLI
        if (!waitFor(deadline) { enterUser(callerUserId) }) busy(callerUserId, roomId, resource, "user")
        try {
            if (!waitFor(deadline) { permits.tryAcquire() }) busy(callerUserId, roomId, resource, "process")
            try {
                return budgetedTransaction(
                    database,
                    timeoutSeconds,
                    onTimeout = { timedOut(callerUserId, roomId, resource) },
                    block = block,
                )
            } finally {
                permits.release()
            }
        } finally {
            leaveUser(callerUserId)
        }
    }

    /** [attempt] once, then every [PERMIT_POLL_MILLIS] until [deadline] (nanoTime): a bounded wait that cannot leak a permit. */
    private suspend fun waitFor(deadline: Long, attempt: () -> Boolean): Boolean {
        if (attempt()) return true
        while (System.nanoTime() < deadline) {
            delay(PERMIT_POLL_MILLIS)
            if (attempt()) return true
        }
        return false
    }

    private fun enterUser(userId: UInt): Boolean {
        var admitted = false
        inFlight.compute(userId) { _, count ->
            val current = count ?: 0
            if (current >= perUser) {
                current
            } else {
                admitted = true
                current + 1
            }
        }
        return admitted
    }

    private fun leaveUser(userId: UInt) {
        inFlight.compute(userId) { _, count -> if (count == null || count <= 1) null else count - 1 }
    }

    private fun busy(callerUserId: UInt, roomId: UInt, resource: String, limit: String): Nothing {
        audit("read.busy", "byUserId" to callerUserId.toLong(), "roomId" to roomId.toLong(), "resource" to resource, "limit" to limit)
        throw readBusy()
    }

    private fun timedOut(callerUserId: UInt, roomId: UInt, resource: String): Nothing {
        audit("read.timeout", "byUserId" to callerUserId.toLong(), "roomId" to roomId.toLong(), "resource" to resource)
        throw readTimeout()
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
