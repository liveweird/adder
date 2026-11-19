package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.SEARCH_BUSY_PROBLEM_TYPE
import ch.nokillswit.threads.SEARCH_TIMEOUT_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadSearchPageResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * The search across rooms over HTTP (`GET /search`, `chat-model.md` "Search (V16)"): the scope is the caller's ACTIVE memberships and
 * nothing else (an ADMIN's too), an archived room is labelled, a deleted room and a removed member's room are gone on the next request,
 * no rooms is an empty page, the bucket is the room search's, the budget is the typed `429`, and the statement shape that keeps the work
 * bounded by the caller's own threads is pinned. The kernel is `FtsTest`, the single-room behaviour `ThreadSearchTest`, the logs
 * `ChatLogHygieneTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows, a UNIQUE search word, and
 * removes them; the callers are fresh users, so only their own rooms can match.
 */
class GlobalSearchTest {
    private val rooms = "/api/v1/rooms"
    private val search = "/api/v1/search"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER) =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun template(): UInt = TestThreads.seedTemplate().also { mintedTemplates += it }

    /** A room owned by [owner] named `<label> <random>`, with one thread holding [body] in its opening message. */
    private suspend fun room(owner: RoomActor, label: String, body: String, title: String = "Thread of $label"): UInt =
        TestRooms.seed(owner.id, template(), "$label ${UUID.randomUUID().toString().substring(0, 8)}").also { room ->
            mintedRooms += room
            TestThreads.seed(room, owner.id, title = title, body = body)
        }

    private fun word(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.raw(query: String): HttpResponse = get("$search$query")

    private suspend fun HttpClient.find(q: String, extra: String = ""): ThreadSearchPageResponse {
        val response = raw("?q=${encoded(q)}$extra")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    @Test
    fun `hits come only from rooms the caller is a member of`() = testApplication {
        usePostgresTestcontainer()
        val term = word("zorro")
        val ann = actor("gs-ann")
        val bob = actor("gs-bob")
        val first = room(ann, "Alpha", "The $term is here")
        val second = room(ann, "Beta", "Also the $term")
        val hidden = room(bob, "Secret", "Their $term too", title = "Hidden $term title")
        TestRooms.addMember(second, bob.id)

        val page = ann.client.find(term)
        assertEquals(2, page.total, "the caller's two rooms, not bob's third")
        assertEquals(setOf(first, second), page.items.map { it.roomId }.toSet())
        page.items.forEach { hit ->
            assertTrue(hit.roomName.startsWith(if (hit.roomId == first) "Alpha " else "Beta "), "each hit names its own room")
            assertEquals(1, hit.threadNumber)
            assertEquals(1, hit.matchCount)
        }
        val raw = ann.client.raw("?q=${encoded(term)}").bodyAsText()
        assertTrue("Secret" !in raw && "Hidden" !in raw, "nothing names the room the caller is not in")
        assertTrue(page.items.none { it.roomId == hidden })

        val bobs = bob.client.find(term)
        assertEquals(setOf(second, hidden), bobs.items.map { it.roomId }.toSet(), "bob is in two of the three")
        assertEquals(page.items.single { it.roomId == second }.title, bobs.items.single { it.roomId == second }.title)
    }

    @Test
    fun `hits of several rooms page and sort together and closed narrows them`() = testApplication {
        usePostgresTestcontainer()
        val term = word("sigma")
        val ann = actor("gs-page")
        val a = room(ann, "PageA", "one $term")
        val b = room(ann, "PageB", "two $term")
        val c = room(ann, "PageC", "three $term")
        TestThreads.closeRaw(b, 1)
        TestThreads.setStamps(a, 1, createdAt = 1, lastActivityAt = 3_000)
        TestThreads.setStamps(b, 1, createdAt = 2, lastActivityAt = 2_000)
        TestThreads.setStamps(c, 1, createdAt = 3, lastActivityAt = 1_000)

        val first = ann.client.find(term, "&pageSize=2")
        assertEquals(3, first.total)
        assertEquals(listOf(a, b), first.items.map { it.roomId }, "the most recently active first, across rooms")
        assertEquals(listOf(c), ann.client.find(term, "&pageSize=2&page=2").items.map { it.roomId })
        assertEquals(listOf(c, b, a), ann.client.find(term, "&sort=lastActivityAt").items.map { it.roomId })
        assertEquals(listOf(b), ann.client.find(term, "&closed=true").items.map { it.roomId })
        assertEquals(listOf(a, c), ann.client.find(term, "&closed=false").items.map { it.roomId })
    }

    @Test
    fun `an ADMIN who is not a member gets no hit from that room`() = testApplication {
        usePostgresTestcontainer()
        val term = word("omega")
        val owner = actor("gs-owner")
        val admin = actor("gs-admin", UserRole.ADMIN)
        val room = room(owner, "Closed door", "The $term inside")

        val single = admin.client.get("$rooms/$room/search?q=${encoded(term)}").body<ThreadSearchPageResponse>()
        assertEquals(listOf(1), single.items.map { it.threadNumber }, "the room's own search admits an ADMIN, who reads every room")
        val global = admin.client.find(term)
        assertEquals(0, global.total, "the global scope is membership only")
        assertEquals(emptyList(), global.items)

        TestRooms.addMember(room, admin.id)
        assertEquals(listOf(room), admin.client.find(term).items.map { it.roomId }, "a member ADMIN finds it")
    }

    @Test
    fun `a removed member loses the room's hits on the next request`() = testApplication {
        usePostgresTestcontainer()
        val term = word("delta")
        val owner = actor("gs-own")
        val member = actor("gs-mem")
        val room = room(owner, "Leaving", "The $term stays")
        TestRooms.addMember(room, member.id)
        assertEquals(listOf(room), member.client.find(term).items.map { it.roomId })

        assertEquals(HttpStatusCode.NoContent, owner.client.delete("$rooms/$room/members/${member.id}").status)
        val after = member.client.find(term)
        assertEquals(0, after.total)
        assertEquals(emptyList(), after.items)
        assertEquals(listOf(room), owner.client.find(term).items.map { it.roomId }, "the owner still finds it")
    }

    @Test
    fun `a deleted room is never found`() = testApplication {
        usePostgresTestcontainer()
        val term = word("gamma")
        val owner = actor("gs-del-own")
        val admin = actor("gs-del-adm", UserRole.ADMIN)
        val kept = room(owner, "Kept", "A $term remains")
        val gone = room(owner, "Gone", "A $term vanishes")
        assertEquals(setOf(kept, gone), owner.client.find(term).items.map { it.roomId }.toSet())

        assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/$gone").status)
        val after = owner.client.find(term)
        assertEquals(1, after.total)
        assertEquals(listOf(kept), after.items.map { it.roomId })
    }

    @Test
    fun `an archived room's hits say so`() = testApplication {
        usePostgresTestcontainer()
        val term = word("theta")
        val owner = actor("gs-arch")
        val live = room(owner, "Live", "The $term moves")
        val old = room(owner, "Old", "The $term rests")
        TestRooms.archive(old)

        val page = owner.client.find(term)
        assertEquals(2, page.total, "an archived room is searched")
        assertEquals(true, page.items.single { it.roomId == old }.roomArchived)
        assertEquals(false, page.items.single { it.roomId == live }.roomArchived)
    }

    @Test
    fun `a caller with no rooms gets an empty page`() = testApplication {
        usePostgresTestcontainer()
        val loner = actor("gs-loner")

        val page = loner.client.find("anything")
        assertEquals(0, page.total)
        assertEquals(emptyList(), page.items)
        assertEquals(1, page.page)
        assertEquals(20, page.pageSize)
    }

    @Test
    fun `a q that is short, has no letter or digit, is repeated or holds a control character is 400 before the rooms are read`() =
        testApplication {
            usePostgresTestcontainer()
            val loner = actor("gs-400")
            val client = loner.client

            assertEquals(HttpStatusCode.BadRequest, client.raw("").status, "q is required")
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=a").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=%20a%20").status, "trimmed first")
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=!!!").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=${"x".repeat(101)}").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=ab%07").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=ab&q=cd").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=ab&closed=maybe").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=ab&pageSize=21").status)
            assertEquals(HttpStatusCode.BadRequest, client.raw("?q=ab&sort=title").status)
            assertEquals(HttpStatusCode.OK, client.raw("?q=ab").status, "a caller with no rooms still passes validation")
            assertEquals(HttpStatusCode.OK, client.raw("?q=ab&unknown=1").status, "unknown parameter names are ignored")
        }

    @Test
    fun `the bucket is shared with the room search`() = testApplication {
        usePostgresTestcontainer()
        val term = word("iota")
        val ann = actor("gs-rate")
        val room = room(ann, "Rate", "The $term waits")

        withAuditCapture { capture ->
            // A 400 spends a token too (the bucket sits before the guards): 30 on the room route, 30 on the global one.
            repeat(30) { assertEquals(HttpStatusCode.BadRequest, ann.client.get("$rooms/$room/search?q=a").status) }
            repeat(30) { assertEquals(HttpStatusCode.BadRequest, ann.client.raw("?q=a").status) }
            val rejected = ann.client.raw("?q=${encoded(term)}")
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertNotNull(rejected.headers["Retry-After"])
            assertTrue(rejected.body<ProblemDetail>().type != SEARCH_TIMEOUT_PROBLEM_TYPE, "a bucket is not a timeout")
            assertEquals(HttpStatusCode.TooManyRequests, ann.client.get("$rooms/$room/search?q=${encoded(term)}").status, "one budget")
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "search") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", ann.id.toLong()))
            assertEquals(emptyList(), leaks(capture, term), "the term is in no audit line")
        }
    }

    @Test
    fun `the budget answers the typed 429`() = testApplication {
        configureApp("search.timeoutSeconds" to "1")
        startApplication()
        val ann = actor("gs-slow")
        val term = word("slowglobal")
        room(ann, "Slow", "The $term waits")
        val capture = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            // The page statement waits behind a table lock held by a raw transaction: past the 1 s budget the database cancels it (57014).
            val started = System.nanoTime()
            val response = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE messages IN ACCESS EXCLUSIVE MODE") }
                try {
                    ann.client.raw("?q=${encoded(term)}")
                } finally {
                    holder.rollback()
                }
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("2", response.headers["Retry-After"])
            val problem = response.body<ProblemDetail>()
            assertEquals(SEARCH_TIMEOUT_PROBLEM_TYPE, problem.type)
            assertTrue(term !in problem.detail.orEmpty(), "the problem never names the term")
            assertTrue(elapsedMillis in 900..10_000, "it answered at the budget ($elapsedMillis ms)")

            val event = capture.awaitEvent { it.message == "search.timeout" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", ann.id.toLong()))
            assertTrue(event.keyValuePairs.orEmpty().any { it.key == "roomId" && it.value == null }, "a search across rooms names no room")
            assertEquals(emptyList(), leaks(capture, term), "the term reached a log line")
            assertTrue(capture.events.none { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR) }, "not a logged failure")

            assertEquals(1, ann.client.find(term).total, "the lock is gone and the pool is clean")
        } finally {
            capture.detach()
        }
    }

    /**
     * An `EXISTS` under an `OR` can be planned as a HASHED subplan that reads every matching message of the WHOLE table (a seq scan of it
     * for a common prefix), whatever rooms the caller is in. The thread statements carry the rooms as ONE array argument and probe the
     * messages through a correlated scalar subquery, which is never hashed (`persistence.md` "Search (V16)"). The driver's own statement
     * log shows what is sent.
     */
    @Test
    fun `the thread statements restrict by room first and probe messages without EXISTS or the GIN index`() = testApplication {
        usePostgresTestcontainer()
        val term = word("kappa")
        val ann = actor("gs-shape")
        room(ann, "Shape", "The $term waits")
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            assertEquals(1, ann.client.find(term).total)
            val statements = capture.events.map { it.formattedMessage }.filter { "threads.room_id = ANY(" in it }
            assertEquals(2, statements.size, "the count and the page: $statements")
            statements.forEach { sql ->
                assertTrue("threads.room_id = ANY(CAST(" in sql, sql)
                assertGenericPlanProbesThroughThreadIndex(sql.removePrefix("Executing query: "))
                assertTrue("LIMIT 1) IS NOT NULL" in sql && "EXISTS" !in sql.uppercase(), sql)
                assertTrue("COALESCE(messages.search, ''::tsvector) @@" in sql, "the probe never names the bare column: $sql")
            }
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    /**
     * The probe's plan, from the statement the driver really sent, as a PREPARED statement's GENERIC plan (what a pooled connection
     * uses from its sixth run) with sequential scans off: it must reach the messages through `idx_messages_thread` and must not be a
     * hashed subplan (a table-wide read). Both are plan-shape facts, not timings: they hold on any table size.
     */
    private fun assertGenericPlanProbesThroughThreadIndex(sql: String) {
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("SET LOCAL plan_cache_mode = force_generic_plan")
                    st.execute("SET LOCAL enable_seqscan = off")
                    st.execute("PREPARE probe_plan(int4[], text, boolean, text) AS $sql")
                    val plan = buildString {
                        st.executeQuery("EXPLAIN EXECUTE probe_plan('{1,2}', 'word', false, 'word')").use { rs ->
                            while (rs.next()) appendLine(rs.getString(1))
                        }
                    }
                    assertTrue("idx_messages_thread" in plan, "the probe reaches messages through its thread index:\n$plan")
                    assertTrue("hashed SubPlan" !in plan, "no hashed (table-wide) subplan:\n$plan")
                    assertTrue("idx_messages_search" !in plan, "no index on the vector is in the plan:\n$plan")
                }
            } finally {
                c.rollback()
            }
        }
    }

    /**
     * At most `search.maxConcurrent` searches run at once, for BOTH routes: the one permit is held by a search stuck behind a table lock,
     * and the next search (the OTHER route, another user, so not the bucket) is refused AT ONCE with the typed `429` `search-busy`
     * (never queued for a pool connection), audited `search.busy`. When the lock goes the first search completes and the permit is back.
     */
    @Test
    fun `a search past the concurrency cap is refused at once with the typed 429 search-busy`() = testApplication {
        configureApp("search.maxConcurrent" to "1", "search.timeoutSeconds" to "20")
        startApplication()
        val term = word("busyterm")
        val ann = actor("gs-busy-a")
        val bob = actor("gs-busy-b")
        val annRoom = room(ann, "BusyA", "The $term waits")
        val bobRoom = room(bob, "BusyB", "The $term too")

        withAuditCapture { capture ->
            val refused = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE messages IN ACCESS EXCLUSIVE MODE") }
                try {
                    coroutineScope {
                        val running = async { ann.client.raw("?q=${encoded(term)}") }
                        awaitLockWait("messages.search")
                        val started = System.nanoTime()
                        val refused = bob.client.get("$rooms/$bobRoom/search?q=${encoded(term)}")
                        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                        assertTrue(elapsedMillis < 3_000, "refused at once, not after a wait ($elapsedMillis ms)")
                        holder.rollback()
                        assertEquals(HttpStatusCode.OK, running.await().status, "the search that held the permit completes")
                        refused
                    }
                } finally {
                    holder.rollback()
                }
            }
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertEquals("1", refused.headers["Retry-After"])
            val problem = refused.body<ProblemDetail>()
            assertEquals(SEARCH_BUSY_PROBLEM_TYPE, problem.type)
            assertTrue(term !in problem.detail.orEmpty(), "the problem never names the term")
            val event = capture.awaitEvent { it.message == "search.busy" && it.hasKeyValue("byUserId", bob.id.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("roomId", bobRoom.toLong()))
            assertEquals(emptyList(), capture.events.filter { term in it.rendered() }.map { it.rendered().take(300) })
        }
        assertEquals(listOf(bobRoom), bob.client.find(term).items.map { it.roomId }, "the permit is back")
        assertEquals(listOf(annRoom), ann.client.find(term).items.map { it.roomId })
    }

    @Test
    fun `a search that times out gives its permit back`() = testApplication {
        configureApp("search.maxConcurrent" to "1", "search.timeoutSeconds" to "1")
        startApplication()
        val term = word("permitterm")
        val ann = actor("gs-permit")
        val annRoom = room(ann, "Permit", "The $term waits")
        PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("LOCK TABLE messages IN ACCESS EXCLUSIVE MODE") }
            try {
                val timedOut = ann.client.raw("?q=${encoded(term)}")
                assertEquals(HttpStatusCode.TooManyRequests, timedOut.status)
                assertEquals(SEARCH_TIMEOUT_PROBLEM_TYPE, timedOut.body<ProblemDetail>().type)
            } finally {
                holder.rollback()
            }
        }
        assertEquals(listOf(annRoom), ann.client.find(term).items.map { it.roomId }, "the one permit was released")
    }

    private fun leaks(capture: LogCapture, term: String): List<String> =
        capture.events.filter { term in it.rendered() }.map { it.rendered().take(300) }

    /** Everything an event can carry, as one string. */
    private fun ch.qos.logback.classic.spi.ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        throwableProxy?.let { append(' ').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(it)) }
    }
}
