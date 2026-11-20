package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.threads.BudgetSpent
import ch.nokillswit.threads.READ_BUSY_PROBLEM_TYPE
import ch.nokillswit.threads.READ_TIMEOUT_PROBLEM_TYPE
import ch.nokillswit.threads.ReadLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The in-flight bounds of the two list reads (`threads/ReadLimiter.kt`, `ReadLimiter`): at most N reads per user (refused at once), at
 * most M in the process (a bounded wait, then refused), both as the typed `429` `read-busy`; every permit comes back after a success, a
 * failure, a timeout and a cancellation, and the per-user map never keeps an idle user. The HTTP side (the 429 body, `Retry-After` and
 * the audit line over a real lock) is `ThreadReadBudgetTest`. No row is touched: a read here only holds its transaction.
 */
class ReadLimiterTest {
    private val database = sharedDatabaseForTests()
    private val room = 1u

    /** A read of [user] that has started (holds its permits) and finishes when [release] completes. */
    private class Held(val release: CompletableDeferred<Unit>, val result: Deferred<String>)

    private suspend fun CoroutineScope.hold(limiter: ReadLimiter, user: UInt): Held {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            limiter.run(database, user, room, "threads") {
                entered.complete(Unit)
                release.await()
                "done"
            }
        }
        withTimeout(10_000) { entered.await() }
        return Held(release, result)
    }

    private suspend fun ReadLimiter.quick(user: UInt): String = run(database, user, room, "tags") { "ok" }

    private fun TooManyRequestsException.assertBusy() {
        assertEquals(READ_BUSY_PROBLEM_TYPE, problemType)
        assertEquals(1, retryAfterSeconds)
    }

    @Test
    fun `a user past the per-user cap is refused at once, audited, and another user is not`() = runBlocking {
        val limiter = ReadLimiter(perUser = 2, maxWaitMillis = 200)
        withAuditCapture { capture ->
            val first = hold(limiter, 7u)
            val second = hold(limiter, 7u)
            val refused = assertFailsWith<TooManyRequestsException> { limiter.quick(7u) }
            refused.assertBusy()
            val event = capture.awaitEvent { it.message == "read.busy" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", 7L) && event.hasKeyValue("limit", "user") && event.hasKeyValue("resource", "tags"))
            assertEquals("ok", limiter.quick(8u), "another user has its own cap")

            first.release.complete(Unit)
            assertEquals("done", first.result.await())
            assertEquals("ok", limiter.quick(7u), "a finished read frees its slot")
            second.release.complete(Unit)
            assertEquals("done", second.result.await())
        }
        assertEquals(0, limiter.usersInFlight())
    }

    @Test
    fun `tabs that fire together are not refused - six concurrent reads of one user all succeed`() = runBlocking {
        val limiter = ReadLimiter()
        // The shape of a live hint: three tabs refetch the thread list and the tag list in the same instant.
        val held = (1..6).map { hold(limiter, 5u) }
        assertEquals(1, limiter.usersInFlight())
        held.forEach { it.release.complete(Unit) }
        assertEquals(List(6) { "done" }, held.map { it.result.await() })

        // And past the per-user cap a burst QUEUES for the bounded wait instead of failing: five reads through two slots.
        val narrow = ReadLimiter(perUser = 2, maxWaitMillis = 5_000)
        val results = (1..5).map { async { narrow.run(database, 5u, room, "threads") {
                delay(100)
                "ok"
            } } }
        assertEquals(List(5) { "ok" }, withTimeout(15_000) { results.map { it.await() } })
        assertEquals(0, narrow.usersInFlight())
    }

    @Test
    fun `a read waits for its user slot no longer than the one bounded wait, then is refused`() = runBlocking {
        val limiter = ReadLimiter(perUser = 1, maxWaitMillis = 300)
        val holder = hold(limiter, 3u)
        val waited = measureTime { assertFailsWith<TooManyRequestsException> { limiter.quick(3u) }.assertBusy() }
        assertTrue(waited >= 250.milliseconds && waited < 5_000.milliseconds, "it waited its bounded time: $waited")
        holder.release.complete(Unit)
        holder.result.await()
        assertEquals(0, limiter.usersInFlight())
    }

    @Test
    fun `a read cancelled while it waits for the process permit leaves nothing behind and the cap intact`() = runBlocking {
        val limiter = ReadLimiter(maxConcurrent = 1, maxWaitMillis = 400)
        val holder = hold(limiter, 1u)
        val waiter = async { limiter.quick(2u) }
        delay(100) // the waiter is polling for the permit
        waiter.cancelAndJoin()
        assertEquals(1, limiter.usersInFlight(), "only the holder's user is left")
        // The cancelled waiter returned nothing it never took: the cap is still ONE, so a second concurrent read is still refused.
        assertFailsWith<TooManyRequestsException> { limiter.quick(3u) }.assertBusy()
        holder.release.complete(Unit)
        holder.result.await()
        assertEquals(0, limiter.usersInFlight())
        assertEquals("ok", limiter.quick(2u))
        val again = hold(limiter, 1u)
        assertFailsWith<TooManyRequestsException> { limiter.quick(3u) }.assertBusy()
        again.release.complete(Unit)
        assertEquals("done", again.result.await())
    }

    @Test
    fun `a read past the process cap waits briefly then is refused, and is let in when a slot frees meanwhile`() = runBlocking {
        val limiter = ReadLimiter(maxConcurrent = 1, maxWaitMillis = 400)
        withAuditCapture { capture ->
            val holder = hold(limiter, 1u)
            val waited = measureTime {
                assertFailsWith<TooManyRequestsException> { limiter.quick(2u) }.assertBusy()
            }
            assertTrue(waited >= 350.milliseconds && waited < 5_000.milliseconds, "it waited its bounded time, not forever: $waited")
            val event = capture.awaitEvent { it.message == "read.busy" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", 2L) && event.hasKeyValue("limit", "process"))
            holder.release.complete(Unit)
            holder.result.await()
        }

        val patient = ReadLimiter(maxConcurrent = 1, maxWaitMillis = 5_000)
        val holder = hold(patient, 1u)
        val queued = async { patient.quick(2u) }
        delay(150)
        assertTrue(!queued.isCompleted, "the burst is queued, not refused")
        holder.release.complete(Unit)
        assertEquals("ok", withTimeout(10_000) { queued.await() })
        holder.result.await()
        assertEquals(0, patient.usersInFlight())
    }

    @Test
    fun `every permit comes back after a success, a failure, a spent budget and a cancellation`() = runBlocking {
        val limiter = ReadLimiter(maxConcurrent = 1, perUser = 1, maxWaitMillis = 200)
        assertEquals("ok", limiter.quick(1u))
        assertFailsWith<IllegalStateException> { limiter.run(database, 1u, room, "tags") { error("boom") } }
        val timedOut = assertFailsWith<TooManyRequestsException> { limiter.run(database, 1u, room, "tags") { throw BudgetSpent() } }
        assertEquals(READ_TIMEOUT_PROBLEM_TYPE, timedOut.problemType)

        val cancelled = hold(limiter, 1u)
        cancelled.result.cancelAndJoin()
        // With ONE process permit and ONE per-user slot, any leak above would refuse these.
        assertEquals("ok", limiter.quick(1u))
        assertEquals("ok", limiter.quick(2u))
        assertEquals(0, limiter.usersInFlight())
    }

    @Test
    fun `the per-user map holds only the users with a read in flight`() = runBlocking {
        val limiter = ReadLimiter(maxConcurrent = 16)
        (1u..300u).forEach { assertEquals("ok", limiter.quick(it)) }
        assertEquals(0, limiter.usersInFlight(), "300 distinct users left nothing behind")
        assertFailsWith<IllegalStateException> { limiter.run(database, 999u, room, "tags") { error("boom") } }
        assertEquals(0, limiter.usersInFlight(), "a failed read left nothing behind")

        val a = hold(limiter, 10u)
        val b = hold(limiter, 10u)
        val c = hold(limiter, 11u)
        assertEquals(2, limiter.usersInFlight(), "two users, however many reads each")
        listOf(a, b, c).forEach { it.release.complete(Unit) }
        listOf(a, b, c).forEach { it.result.await() }
        assertEquals(0, limiter.usersInFlight())
    }
}
