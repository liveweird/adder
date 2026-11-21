package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.READ_BUSY_PROBLEM_TYPE
import ch.nokillswit.threads.READ_TIMEOUT_PROBLEM_TYPE
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The bounds of the two room reads that scan a room's rows: the filtered thread list and the tag list
 * share the per-user `chat-read` bucket (300/min) and each runs under ONE `threads.readTimeoutSeconds` deadline and the in-flight caps
 * (`ReadLimiter`, the unit side is `ReadLimiterTest`), a read past the deadline being the
 * audited `429` `read-timeout` with `Retry-After`. The timeout is forced the way `ThreadSearchTest` does it: a raw transaction holds an
 * `ACCESS EXCLUSIVE` table lock, so the statement waits until the database cancels it (57014). Rooms, templates and users are SHARED
 * suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadReadBudgetTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String): RoomActor = seedActor(prefix).also { mintedUsers += it.id }

    private suspend fun room(owner: RoomActor): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(owner.id, template, "Rdb ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
    }

    /** Runs [request] while a raw transaction holds `ACCESS EXCLUSIVE` on [table], and returns the answer and the milliseconds it took. */
    private suspend fun <T> whileLocked(table: String, request: suspend () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val answer = PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE $table IN ACCESS EXCLUSIVE MODE") }
            try {
                request()
            } finally {
                holder.rollback()
            }
        }
        return answer to (System.nanoTime() - started) / 1_000_000
    }

    /** Everything an event can carry, as one string. */
    private fun ch.qos.logback.classic.spi.ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        throwableProxy?.let { append(' ').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(it)) }
    }

    /** Waits until a backend waits on a lock in a statement naming [table]: the read under test is blocked. */
    private suspend fun awaitLockWaiter(table: String, atLeast: Int = 1) {
        repeat(400) {
            val waiting = PostgresTestSupport.connect().use { probe ->
                probe.prepareStatement(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
                ).use { st ->
                    st.setString(1, "%$table%")
                    st.executeQuery().use { rs -> rs.next() && rs.getInt(1) >= atLeast }
                }
            }
            if (waiting) return
            delay(25)
        }
        error("no backend ever waited on a lock for $table")
    }

    private suspend fun assertReadTimeout(
        response: io.ktor.client.statement.HttpResponse,
        elapsedMillis: Long,
        resource: String,
        owner: RoomActor,
        room: UInt,
        marker: String,
        capture: LogCapture,
    ) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("2", response.headers["Retry-After"])
        val problem = response.body<ProblemDetail>()
        assertEquals(READ_TIMEOUT_PROBLEM_TYPE, problem.type)
        assertTrue(marker !in problem.detail.orEmpty(), "the problem never names a parameter value")
        assertTrue(elapsedMillis in 900..10_000, "it answered at the budget ($elapsedMillis ms)")

        val event = capture.awaitEvent { it.message == "read.timeout" }
        assertNotNull(event)
        assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()))
        assertTrue(event.hasKeyValue("roomId", room.toLong()))
        assertTrue(event.hasKeyValue("resource", resource))
        val leaked = capture.events.filter { marker in it.rendered() }
        assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "a parameter value reached a log line")
        val errors = capture.events.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR) }
        assertTrue(errors.isEmpty(), "a cancelled read is not a logged failure")
    }

    @Test
    fun `a thread list that outruns its budget is 429 read-timeout, audited, with the filter value in no log`() = testApplication {
        // ONE process permit: the 200 after the timeout proves the timed-out read gave its permit back.
        configureApp("threads.readTimeoutSeconds" to "1", "threads.readMaxConcurrent" to "1")
        startApplication()
        val owner = actor("rdb-thr")
        val room = room(owner)
        TestThreads.seed(room, owner.id, title = "Slow list")
        val marker = "slowtag" + UUID.randomUUID().toString().replace("-", "").take(12)
        val capture = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            val (response, elapsed) = whileLocked("threads") { owner.client.get("$rooms/$room/threads?tag=$marker") }
            assertReadTimeout(response, elapsed, "threads", owner, room, marker, capture)
            // The lock is gone and the pool is clean: the same list works.
            assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/$room/threads?tag=$marker").status)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a tag list that outruns its budget is 429 read-timeout, audited, with the filter value in no log`() = testApplication {
        // ONE process permit: the 200 after the timeout proves the timed-out read gave its permit back.
        configureApp("threads.readTimeoutSeconds" to "1", "threads.readMaxConcurrent" to "1")
        startApplication()
        val owner = actor("rdb-tag")
        val room = room(owner)
        TestThreads.seed(room, owner.id, title = "Slow tags", body = "#alpha")
        val marker = "slowtag" + UUID.randomUUID().toString().replace("-", "").take(12)
        val capture = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            val (response, elapsed) = whileLocked("message_tags") { owner.client.get("$rooms/$room/tags?q=$marker") }
            assertReadTimeout(response, elapsed, "tags", owner, room, marker, capture)
            assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/$room/tags?q=$marker").status)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `the notification list that outruns its budget is 429 read-timeout with resource notifications and no room in the line`() =
        testApplication {
            configureApp("threads.readTimeoutSeconds" to "1")
            startApplication()
            val owner = actor("rdb-not-to")
            room(owner) // a member of an active room, so the list gets as far as reading the notices
            withAuditCapture { capture ->
                val (response, elapsed) = whileLocked("notifications") { owner.client.get("/api/v1/notifications") }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertEquals("2", response.headers["Retry-After"])
                assertEquals(READ_TIMEOUT_PROBLEM_TYPE, response.body<ProblemDetail>().type)
                assertTrue(elapsed in 900..10_000, "it answered at the budget ($elapsed ms)")
                val event = assertNotNull(
                    capture.awaitEvent { it.message == "read.timeout" && it.hasKeyValue("resource", "notifications") },
                )
                assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()))
                assertTrue(event.hasKeyValue("roomId", null), "a list of the caller's own notices belongs to no room")
            }
            assertEquals(HttpStatusCode.OK, owner.client.get("/api/v1/notifications").status, "the permits are back")
        }

    @Test
    fun `a fifth notification list read of one user is 429 read-busy, resource notifications, no room, the four timing out`() =
        testApplication {
            configureApp("threads.readTimeoutSeconds" to "3")
            startApplication()
            val owner = actor("rdb-not-busy")
            room(owner)
            withAuditCapture { capture ->
                val (answers, _) = whileLocked("notifications") {
                    coroutineScope {
                        val held = List(4) { async { owner.client.get("/api/v1/notifications") } }
                        // The user's four permits are taken, each statement waiting on the lock.
                        awaitLockWaiter("notifications", atLeast = 4)
                        val refused = owner.client.get("/api/v1/notifications") // waits about 1 s for a permit, then is refused
                        refused to held.awaitAll()
                    }
                }
                val (refused, held) = answers
                assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                assertEquals("1", refused.headers["Retry-After"])
                assertEquals(READ_BUSY_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
                val event = assertNotNull(capture.awaitEvent { it.message == "read.busy" && it.hasKeyValue("resource", "notifications") })
                assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()) && event.hasKeyValue("limit", "user"))
                assertTrue(event.hasKeyValue("roomId", null))
                assertTrue(held.all { it.status == HttpStatusCode.TooManyRequests }, "the four ended at their deadline")
            }
            assertEquals(HttpStatusCode.OK, owner.client.get("/api/v1/notifications").status, "the permits are back")
        }

    @Test
    fun `a list read past the process cap is 429 read-busy with Retry-After, audited, while the first one runs into its deadline`() =
        testApplication {
            configureApp("threads.readTimeoutSeconds" to "3", "threads.readMaxConcurrent" to "1")
            startApplication()
            val first = actor("rdb-busy-1")
            val second = actor("rdb-busy-2")
            val room = room(first)
            TestRooms.addMember(room, second.id)
            val marker = "busytag" + UUID.randomUUID().toString().replace("-", "").take(12)
            withAuditCapture { capture ->
                val (answers, _) = whileLocked("threads") {
                    coroutineScope {
                        val slow = async { first.client.get("$rooms/$room/threads") }
                        awaitLockWaiter("threads") // the first read holds the ONE permit, its statement waiting on the lock
                        val refused = second.client.get("$rooms/$room/threads?tag=$marker") // waits about 1 s for a permit, then is refused
                        refused to slow.await()
                    }
                }
                val (refused, slow) = answers
                assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                assertEquals("1", refused.headers["Retry-After"])
                val problem = refused.body<ProblemDetail>()
                assertEquals(READ_BUSY_PROBLEM_TYPE, problem.type)
                assertTrue(marker !in problem.detail.orEmpty(), "the problem never names a parameter value")
                val event = capture.awaitEvent { it.message == "read.busy" }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("byUserId", second.id.toLong()) && event.hasKeyValue("roomId", room.toLong()))
                assertTrue(event.hasKeyValue("resource", "threads") && event.hasKeyValue("limit", "process"))
                assertEquals(READ_TIMEOUT_PROBLEM_TYPE, slow.body<ProblemDetail>().type, "the first one still ended at its deadline")
            }
            assertEquals(HttpStatusCode.OK, second.client.get("$rooms/$room/threads").status, "both permits are back")
        }

    /** `Database.kt` hands ONE `ReadLimiter` to both services: a thread list holding the only permit makes a tag list be refused. */
    @Test
    fun `the thread list and the tag list share ONE read limiter`() = testApplication {
        configureApp("threads.readTimeoutSeconds" to "3", "threads.readMaxConcurrent" to "1")
        startApplication()
        val owner = actor("rdb-shared-1")
        val other = actor("rdb-shared-2")
        val room = room(owner)
        TestRooms.addMember(room, other.id)
        val (answers, _) = whileLocked("threads") {
            coroutineScope {
                val slow = async { owner.client.get("$rooms/$room/threads") }
                awaitLockWaiter("threads")
                // The tag list reads `message_tags`, which is NOT locked: only a shared permit can refuse it.
                other.client.get("$rooms/$room/tags") to slow.await()
            }
        }
        val (tags, threads) = answers
        assertEquals(HttpStatusCode.TooManyRequests, tags.status)
        assertEquals(READ_BUSY_PROBLEM_TYPE, tags.body<ProblemDetail>().type)
        assertEquals(READ_TIMEOUT_PROBLEM_TYPE, threads.body<ProblemDetail>().type)
    }

    @Test
    fun `chat-read is ONE bucket for the thread list and the tag list - 300 a minute per user, then 429 with its audit line`() =
        testApplication {
            usePostgresTestcontainer()
            val limited = actor("rdb-bucket")
            val other = actor("rdb-bucket-other")
            val room = room(limited)
            TestRooms.addMember(room, other.id)

            withAuditCapture { capture ->
                // A 400 spends a token too: the bucket sits before the guards, so the sweep stays cheap.
                repeat(150) { n ->
                    assertEquals(HttpStatusCode.BadRequest, limited.client.get("$rooms/$room/threads?pageSize=0").status, "list ${n + 1}")
                    assertEquals(HttpStatusCode.BadRequest, limited.client.get("$rooms/$room/tags?pageSize=0").status, "tags ${n + 1}")
                }
                val rejectedList = limited.client.get("$rooms/$room/threads")
                assertEquals(HttpStatusCode.TooManyRequests, rejectedList.status)
                assertNotNull(rejectedList.headers["Retry-After"])
                assertTrue(rejectedList.body<ProblemDetail>().type != READ_TIMEOUT_PROBLEM_TYPE, "a bucket is not a timeout")
                assertEquals(HttpStatusCode.TooManyRequests, limited.client.get("$rooms/$room/tags").status, "the bucket is shared")
                val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-read") }
                assertNotNull(event)
                assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
                assertEquals(HttpStatusCode.OK, other.client.get("$rooms/$room/threads").status, "another account has its own bucket")
            }
        }
}
