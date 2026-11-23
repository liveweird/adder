package ch.nokillswit.threads

import ch.nokillswit.audit.audit
import ch.nokillswit.plugins.RateLimitAuditThrottle
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/** The default of `threads.readTimeoutSeconds`: a normal list answers in milliseconds, so this is a runaway's ceiling. */
const val DEFAULT_READ_TIMEOUT_SECONDS = 5

/**
 * The default of `threads.readMaxConcurrent` (the thread list and the tag list): well under the pool's 20 connections, so the lists never
 * starve the rest of the app. 8, so that with the searches (4), the notice list (4) and the thread detail (its own
 * pool, `THREAD_READ_MAX_CONCURRENT` in `infra/db/Database.kt`, 3) the readers together are 19, below the pool of 20 (the boot check).
 */
const val DEFAULT_READ_MAX_CONCURRENT = 8

/**
 * The most notification-list reads ONE user may have in flight. It is HALF the process cap, `NOTIFICATION_READ_MAX_CONCURRENT` (4, in
 * `infra/db/Database.kt` beside the boot check that counts it), so one account, however many tabs or scripts, can never hold every
 * slot and starve the others. The burst of a hint on one tab is three reads; the third queues for the bounded permit wait
 * ([READ_PERMIT_WAIT_MILLIS]) behind the first two, which answer in milliseconds, instead of being refused.
 */
const val NOTIFICATION_READS_PER_USER = 2

/**
 * The most thread-detail reads ONE user may have in flight. The detail read has its OWN limiter (`ChatWiring.kt`: process cap
 * `THREAD_READ_MAX_CONCURRENT` = 3, fixed, in `infra/db/Database.kt` beside the boot check that counts it), because it is NOT in the
 * `chat-read` bucket (a live hint re-reads the open thread unspaced): an unbucketed loop of it must never be able to take
 * `threads.readMaxConcurrent` from the bucketed lists, nor they it. A hint makes ONE detail read per open tab, a few milliseconds each,
 * so 2 slots serve two tabs at once and a third queues for the bounded wait ([READ_PERMIT_WAIT_MILLIS]); 2 of the pool's 3 keeps one
 * account (however many tabs or scripts) from holding every slot.
 */
const val THREAD_READS_PER_USER = 2

/**
 * The most bounded reads ONE user may have in flight at once (the thread and tag lists and Home; the notice list and the thread detail
 * have their own, [NOTIFICATION_READS_PER_USER] and [THREAD_READS_PER_USER]). A live hint makes every open tab refetch the room's thread
 * list and,
 * with the filter panel open, the tag list, after the same 300 ms settle (`web/src/utils/liveKeys.ts`): two reads per tab, all in the
 * same instant. 8 is four such tabs together, above any real use; a script that holds more than that is what the cap stops.
 */
const val MAX_READS_PER_USER = 8

/**
 * How long a read waits for a slot, the user's and then the process's TOGETHER (one deadline): a burst after a live hint queues this
 * briefly instead of failing, and nothing waits longer.
 */
const val READ_PERMIT_WAIT_MILLIS = 1_000L

private const val PERMIT_POLL_MILLIS = 20L

/**
 * The two limiters `ThreadService` reads through: [lists] for the filtered thread list (resource `threads`, `chat-read`-bucketed) and
 * [detail] for one thread's detail (resource `thread`, unbucketed), each its own pool of permits and per-user slots, so one kind of read
 * cannot crowd out the other. `ChatWiring.kt` passes the configured sizes; the defaults are for a service built by hand (a test).
 */
class ThreadReadLimiters(
    val lists: ReadLimiter = ReadLimiter(),
    val detail: ReadLimiter = ReadLimiter(perUser = THREAD_READS_PER_USER),
)

/**
 * The bounds of the reads that scan chat rows (the filtered thread list, the tag list and Home), of the thread detail and of the
 * notice list, the way the search is bounded (`ThreadSearchService`): ONE deadline per read and a cap on how many run at once, so a
 * flood of slow reads can never hold the pool. THREE instances exist (`ChatWiring.kt`), each its own pool of permits and per-user
 * slots: the lists (`threads`, `tags` and `home`, `chat-read`-bucketed), the thread detail (`thread`, unbucketed) and the notice list
 * (`notifications`), so a flood of one kind can never crowd out another.
 *
 * - **Deadline.** [run] executes its block in one read transaction under ONE [SearchBudget] of [timeoutSeconds] (the search's budget,
 *   reused: each statement's `queryTimeout` is the time left, the only way a statement timeout may be set, `persistence.md`
 *   "Connection pool"). A statement the budget cancelled (SQLSTATE `57014`), or a deadline spent between two statements, is the audited
 *   `429` `read-timeout` (`read.timeout`).
 * - **In-flight caps.** At most [perUser] reads of one user run at once, and at most [maxConcurrent] in the whole process (of the
 *   resources THIS instance serves). Both are `tryAcquire`s polled for at most [maxWaitMillis] IN TOTAL (a legitimate burst after a
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

    // The coalescing of the two reads no `chat-read` bucket bounds (`COALESCED_RESOURCES`): one line a minute per user and resource,
    // with `suppressed` (the `rate_limit.exceeded` idiom, `plugins/RateLimits.kt`; the keys are the signed-in accounts, so it is bounded).
    private val auditThrottle = RateLimitAuditThrottle()

    /** How many users have a read in flight: 0 when idle (a test seam for "the map never leaks"). */
    internal fun usersInFlight(): Int = inFlight.size

    /**
     * [resource] names the read (`threads`, `tags`, `home`, `thread` (one thread's detail) or `notifications`) in the audit lines;
     * [roomId] is null for a read that belongs to no room (Home and the caller's own notification list), and then the lines carry no
     * room id.
     */
    internal suspend fun <T> run(
        database: R2dbcDatabase,
        callerUserId: UInt,
        roomId: UInt?,
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

    /**
     * The audit line of a refusal, or null when the read's resource is coalesced and a line was already written this minute. The two
     * reads that no `chat-read` bucket bounds, the caller's own notice list and the thread detail, are audited ONCE per user, resource
     * and minute, with the count folded since the last line (`suppressed`): one wide event makes hundreds of tabs read at the same
     * instant and the overflow is refused together, and a script can hold a user's slots and be refused at the pace of the 1 s wait (or
     * run to the 5 s deadline again and again), so a line per refusal would be a flood of identical lines. The bucketed lists
     * (`threads`, `tags`, `home`) are still one line per refusal, with `suppressed` 0.
     */
    private fun auditSuppressed(event: String, callerUserId: UInt, resource: String): Long? =
        if (resource !in COALESCED_RESOURCES) 0L else auditThrottle.admit("$event:$resource:$callerUserId")

    private fun busy(callerUserId: UInt, roomId: UInt?, resource: String, limit: String): Nothing {
        auditSuppressed("read.busy", callerUserId, resource)?.let { suppressed ->
            audit("read.busy", *readFields(callerUserId, roomId, resource), "limit" to limit, "suppressed" to suppressed)
        }
        throw readBusy()
    }

    private fun timedOut(callerUserId: UInt, roomId: UInt?, resource: String): Nothing {
        auditSuppressed("read.timeout", callerUserId, resource)?.let { suppressed ->
            audit("read.timeout", *readFields(callerUserId, roomId, resource), "suppressed" to suppressed)
        }
        throw readTimeout()
    }

    /** The audit fields both lines share; a read that belongs to no room (the caller's own notice list) carries NO `roomId` key. */
    private fun readFields(callerUserId: UInt, roomId: UInt?, resource: String): Array<Pair<String, Any?>> = listOfNotNull(
        "byUserId" to callerUserId.toLong(),
        roomId?.let { "roomId" to it.toLong() },
        "resource" to resource,
    ).toTypedArray()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val COALESCED_RESOURCES = setOf("notifications", "thread")
    }
}
