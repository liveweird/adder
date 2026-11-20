package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.SEARCH_TIMEOUT_PROBLEM_TYPE
import ch.nokillswit.threads.SnippetSegment
import ch.nokillswit.threads.ThreadSearchHit
import ch.nokillswit.threads.ThreadSearchPageResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
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
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * The room search over HTTP (`GET /rooms/{id}/search`, V16, `chat-model.md` "Search (V16)"): words, prefixes and accents, the
 * every-term-in-one-message rule, titles, the three newest matches and their count, deleted and edited messages, `closed`, paging, the
 * `400`s, the guard order (a non-member's `404` outranks a bad `q`), an ADMIN outside the room, an archived room, the per-user `search`
 * bucket and the statement budget. The kernel is `FtsTest`, the columns `ThreadSchemaTest`, the logs `ChatLogHygieneTest`. Rooms,
 * templates and users are SHARED suite state: every test mints UNIQUE rows and removes them; each search is scoped to its own room.
 */
class ThreadSearchTest {
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

    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    private suspend fun ApplicationTestBuilder.cast(prefix: String): RoomCast = seedRoomCast(prefix, "Srch", minted)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.raw(room: UInt, query: String): HttpResponse = get("$rooms/$room/search$query")

    private suspend fun HttpClient.search(room: UInt, q: String, extra: String = ""): ThreadSearchPageResponse {
        val response = raw(room, "?q=${encoded(q)}$extra")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.numbers(room: UInt, q: String, extra: String = ""): List<Int> =
        search(room, q, extra).items.map { it.threadNumber }.sorted()

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private fun List<SnippetSegment>.hits(): List<String> = filter { it.hit }.map { it.text }

    private fun List<SnippetSegment>.plain(): String = joinToString("") { it.text }

    @Test
    fun `a word finds the message and its thread, with the hit marked`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-word")
        TestThreads.seed(cast.room, cast.owner.id, title = "Deploy notes", body = "Opening line")
        val posted = cast.member.client.post(cast.room, 1, "The outage started yesterday")

        val page = cast.member.client.search(cast.room, "outage")
        assertEquals(1, page.total)
        val hit = page.items.single()
        assertEquals(1, hit.threadNumber)
        assertEquals(cast.room, hit.roomId)
        assertTrue(hit.roomName.startsWith("Srch "))
        assertEquals(false, hit.roomArchived)
        assertEquals("open", hit.status)
        assertEquals(false, hit.closed)
        assertEquals(cast.owner.id, hit.ownerId)
        assertEquals(2, hit.messageCount)
        assertEquals(1, hit.matchCount)
        assertEquals("Deploy notes", hit.titleSnippet.plain())
        assertEquals(emptyList(), hit.titleSnippet.hits(), "the title did not match")
        val message = hit.messages.single()
        assertEquals(posted.id, message.id)
        assertEquals(cast.member.id, message.authorId)
        assertEquals(posted.createdAt, message.createdAt)
        assertEquals(listOf("outage"), message.snippet.hits())
        // ts_headline drops a short word at the edge of a fragment ("The"), so the snippet is the fragment, not the whole message.
        assertEquals("outage started yesterday", message.snippet.plain())
    }

    @Test
    fun `a prefix finds inflected forms`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-prefix")
        TestThreads.seed(cast.room, cast.owner.id, title = "One", body = "Awaria bazy danych")
        TestThreads.seed(cast.room, cast.owner.id, title = "Two", body = "Po awarii w nocy")
        TestThreads.seed(cast.room, cast.owner.id, title = "Three", body = "Baza wrocila, outages ended, wdrozenie gotowe")
        val client = cast.member.client

        assertEquals(listOf(1, 2), client.numbers(cast.room, "awari"), "awaria and awarii share a prefix")
        assertEquals(listOf(3), client.numbers(cast.room, "outage"), "a prefix finds the plural")
        assertEquals(listOf(1), client.numbers(cast.room, "bazy"), "there is no stemming: bazy does not find baza")
        assertEquals(listOf(1, 3), client.numbers(cast.room, "baz"), "a three-character term is a prefix: bazy and baza")
        assertEquals(emptyList(), client.numbers(cast.room, "wd"), "a two-character term is exact")
        assertEquals(listOf(3), client.numbers(cast.room, "WDRO"), "case is ignored")
    }

    @Test
    fun `ASCII finds diacritics and the snippet keeps them`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-accent")
        TestThreads.seed(cast.room, cast.owner.id, title = "Zoo", body = "Żółw i żółwie nad rzeką")
        TestThreads.seed(cast.room, cast.owner.id, title = "Plain", body = "plain zolw here")

        val page = cast.member.client.search(cast.room, "zolw")
        assertEquals(listOf(1, 2), page.items.map { it.threadNumber }.sorted())
        val polish = page.items.single { it.threadNumber == 1 }.messages.single().snippet
        assertEquals(listOf("Żółw", "żółwie"), polish.hits(), "the hits are the ORIGINAL words")
        assertEquals("Żółw i żółwie nad rzeką", polish.plain())
        assertEquals(listOf(1, 2), cast.member.client.numbers(cast.room, "ŻÓŁW"), "diacritics in the query fold too")
    }

    @Test
    fun `every term must match within one message or the title`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-terms")
        TestThreads.seed(cast.room, cast.owner.id, title = "Split", body = "alpha only") // 1: alpha and beta in two messages
        cast.member.client.post(cast.room, 1, "beta only")
        TestThreads.seed(cast.room, cast.owner.id, title = "Same message", body = "alpha beta together") // 2
        TestThreads.seed(cast.room, cast.owner.id, title = "alpha beta title", body = "nothing") // 3
        // 4: the title has one term, a message the other
        TestThreads.seed(cast.room, cast.owner.id, title = "alpha topic", body = "beta in the opener")

        assertEquals(listOf(2, 3), cast.member.client.numbers(cast.room, "alpha beta"))
        assertEquals(listOf(1, 2, 3, 4), cast.member.client.numbers(cast.room, "beta"))
    }

    @Test
    fun `a title match is a hit without message snippets`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-title")
        TestThreads.seed(cast.room, cast.owner.id, title = "Zaginiony deploy", body = "nothing relevant")

        val hit = cast.member.client.search(cast.room, "zagin").items.single()
        assertEquals(emptyList(), hit.messages)
        assertEquals(0, hit.matchCount)
        assertEquals(listOf("Zaginiony"), hit.titleSnippet.hits())
        assertEquals("Zaginiony deploy", hit.titleSnippet.plain())
        assertEquals("Zaginiony deploy", hit.title)
    }

    @Test
    fun `a thread carries its three newest matches and the count of all`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-three")
        TestThreads.seed(cast.room, cast.owner.id, title = "Busy", body = "first gamma line")
        val ids = (2..5).map { cast.member.client.post(cast.room, 1, "gamma number $it").id }
        cast.member.client.post(cast.room, 1, "no match here")
        TestThreads.seed(cast.room, cast.owner.id, title = "Quiet", body = "one gamma only")

        val page = cast.member.client.search(cast.room, "gamma")
        assertEquals(2, page.total, "total counts threads, not messages")
        val busy = page.items.single { it.threadNumber == 1 }
        assertEquals(5, busy.matchCount)
        assertEquals(ids.takeLast(3).reversed(), busy.messages.map { it.id }, "the three newest, newest first")
        assertEquals(listOf(1L), listOf(page.items.single { it.threadNumber == 2 }.matchCount))
    }

    @Test
    fun `a deleted message is not found`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-del")
        TestThreads.seed(cast.room, cast.owner.id, title = "Cleanup", body = "nothing here")
        val secret = cast.member.client.post(cast.room, 1, "secret wombat")
        assertEquals(listOf(1), cast.member.client.numbers(cast.room, "wombat"))

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/${secret.id}").status)
        val after = cast.member.client.search(cast.room, "wombat")
        assertEquals(0, after.total)
        assertEquals(emptyList(), after.items)
        assertEquals(listOf(1), cast.member.client.numbers(cast.room, "cleanup"), "the thread itself is still found by its title")
    }

    @Test
    fun `an edited message is found by its new words only`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-edit")
        TestThreads.seed(cast.room, cast.owner.id, title = "Edits", body = "opener")
        val message = cast.member.client.post(cast.room, 1, "old pelican")
        assertEquals(listOf(1), cast.member.client.numbers(cast.room, "pelican"))

        val edit = cast.member.client.putJson("$rooms/${cast.room}/messages/${message.id}", MessageRequest("new heron"))
        assertEquals(HttpStatusCode.NoContent, edit.status)
        assertEquals(emptyList(), cast.member.client.numbers(cast.room, "pelican"))
        assertEquals(listOf(1), cast.member.client.numbers(cast.room, "heron"))
    }

    @Test
    fun `closed narrows by category`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-closed")
        repeat(3) { TestThreads.seed(cast.room, cast.owner.id, title = "T$it", body = "delta words") }
        TestThreads.closeRaw(cast.room, 2, "done")
        val client = cast.member.client

        assertEquals(listOf(1, 2, 3), client.numbers(cast.room, "delta"))
        assertEquals(listOf(2), client.numbers(cast.room, "delta", "&closed=true"))
        assertEquals(listOf(1, 3), client.numbers(cast.room, "delta", "&closed=false"))
        assertEquals(true, client.search(cast.room, "delta", "&closed=true").items.single().closed)
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=delta&closed=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=delta&closed=true&closed=false").status)
    }

    @Test
    fun `total counts threads and pageSize above 20 is 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-page")
        repeat(25) { n -> TestThreads.seed(cast.room, cast.owner.id, title = "Page $n", body = "epsilon one") }
        (1..25).forEach { cast.member.client.post(cast.room, it, "epsilon two") }
        TestThreads.setStamps(cast.room, 7, createdAt = 1, lastActivityAt = 9_000_000_000_000)
        val client = cast.member.client

        val first = client.search(cast.room, "epsilon")
        assertEquals(25, first.total, "two matching messages per thread still count one thread")
        assertEquals(20, first.items.size)
        assertEquals(20, first.pageSize)
        assertEquals(7, first.items.first().threadNumber, "the default order is the most recently active first")
        assertEquals(5, client.search(cast.room, "epsilon", "&page=2").items.size)
        assertEquals(3, client.search(cast.room, "epsilon", "&pageSize=3").items.size)
        val oldestFirst = client.search(cast.room, "epsilon", "&sort=createdAt")
        assertEquals(7, oldestFirst.items.first().threadNumber, "createdAt ascending: thread 7 is the oldest")
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=epsilon&pageSize=21").status)
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=epsilon&pageSize=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=epsilon&page=0").status)
        assertEquals(HttpStatusCode.BadRequest, client.raw(cast.room, "?q=epsilon&sort=title").status, "title is not a search sort")
    }

    @Test
    fun `a q that is short, has no letter or digit, is repeated or holds a control character is 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-q")
        TestThreads.seed(cast.room, cast.owner.id, title = "Quiet", body = "nothing")
        val marker = "term" + UUID.randomUUID().toString().replace("-", "")
        val refused = listOf(
            "",
            "?q=",
            "?q=%20%20",
            "?q=a",
            "?q=!!!",
            "?q=--%20;",
            "?q=$marker&q=other",
            "?q=$marker%07",
            "?q=$marker%00",
            "?q=" + "x".repeat(101),
        )
        for (query in refused) {
            val response = cast.member.client.raw(cast.room, query)
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
            val detail = response.body<ProblemDetail>().detail.orEmpty()
            assertTrue(marker !in detail, "a 400 detail never echoes the term: $detail")
        }
        assertEquals(HttpStatusCode.OK, cast.member.client.raw(cast.room, "?q=" + "x".repeat(100)).status, "100 characters is the maximum")

        // What is a plain term is a plain term: hostile input finds nothing and breaks nothing.
        for (hostile in listOf("<script>", "'; DROP TABLE messages; --", "foo:* | bar", "back\\slash", "' OR 1=1 --")) {
            assertEquals(emptyList(), cast.member.client.search(cast.room, hostile).items, hostile)
        }
        assertEquals(listOf(1), cast.member.client.numbers(cast.room, "nothing"), "the table is still there")
    }

    @Test
    fun `a non-member is 404 even with a bad q`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-404")
        TestThreads.seed(cast.room, cast.owner.id, title = "Hidden", body = "hidden words")

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.raw(cast.room, "?q=hidden").status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.raw(cast.room, "?q=a").status, "404 before 400")
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.raw(cast.room, "").status)
            val missing = cast.outsider.client.raw(2_000_000_000u, "?q=hidden")
            assertEquals(HttpStatusCode.NotFound, missing.status, "a missing room is the same 404")
            val denied = capture.awaitEvent { it.message == "room.access_denied" && it.hasKeyValue("byUserId", cast.outsider.id.toLong()) }
            assertNotNull(denied)
            assertTrue(denied.hasKeyValue("roomId", cast.room.toLong()))
        }
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.raw(cast.room, "?q=a").status, "a member's bad q is the 400")
    }

    @Test
    fun `an ADMIN outside the room searches it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-admin")
        TestThreads.seed(cast.room, cast.owner.id, title = "Admin visible", body = "zeta words")
        val admin = seedActor("srch-adm", UserRole.ADMIN).also { mintedUsers += it.id }

        val page = admin.client.search(cast.room, "zeta")
        assertEquals(listOf(1), page.items.map { it.threadNumber })
    }

    @Test
    fun `an archived room is searched`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-arch")
        TestThreads.seed(cast.room, cast.owner.id, title = "Old", body = "theta words")
        TestRooms.archive(cast.room)

        val hit: ThreadSearchHit = cast.member.client.search(cast.room, "theta").items.single()
        assertEquals(true, hit.roomArchived)
    }

    @Test
    fun `the 61st search in a minute is 429 and audited`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-rate")
        TestThreads.seed(cast.room, cast.owner.id, title = "Rate", body = "iota words")

        withAuditCapture { capture ->
            // A 400 spends a token too: the bucket sits before the guards, so the sweep stays cheap.
            repeat(60) { n ->
                assertEquals(HttpStatusCode.BadRequest, cast.member.client.raw(cast.room, "?q=a").status, "search ${n + 1}")
            }
            val rejected = cast.member.client.raw(cast.room, "?q=iota")
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertNotNull(rejected.headers["Retry-After"])
            assertTrue(rejected.body<ProblemDetail>().type != SEARCH_TIMEOUT_PROBLEM_TYPE, "a bucket is not a timeout")
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "search") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", cast.member.id.toLong()))
            assertEquals(HttpStatusCode.OK, cast.owner.client.raw(cast.room, "?q=iota").status, "another account has its own bucket")
            assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.raw(cast.room, "?q=iota").status, "the bucket stays empty")
        }
    }

    @Test
    fun `a search that outruns its budget is 429 search-timeout, audited, with the term in no log`() = testApplication {
        configureApp("search.timeoutSeconds" to "1")
        startApplication()
        val cast = cast("srch-slow")
        TestThreads.seed(cast.room, cast.owner.id, title = "Slow", body = "kappa words")
        val marker = "slowterm" + UUID.randomUUID().toString().replace("-", "").take(12)
        val capture = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            // The page statement waits behind a table lock held by a raw transaction: past the 1 s budget the database cancels it (57014).
            val started = System.nanoTime()
            val response = PostgresTestSupport.connect().use { holder ->
                holder.autoCommit = false
                holder.createStatement().use { it.execute("LOCK TABLE messages IN ACCESS EXCLUSIVE MODE") }
                try {
                    cast.member.client.raw(cast.room, "?q=$marker")
                } finally {
                    holder.rollback()
                }
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("2", response.headers["Retry-After"])
            val problem = response.body<ProblemDetail>()
            assertEquals(SEARCH_TIMEOUT_PROBLEM_TYPE, problem.type)
            assertTrue(marker !in problem.detail.orEmpty(), "the problem never names the term")
            assertTrue(elapsedMillis in 900..10_000, "it answered at the budget ($elapsedMillis ms)")

            val event = capture.awaitEvent { it.message == "search.timeout" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", cast.member.id.toLong()))
            assertTrue(event.hasKeyValue("roomId", cast.room.toLong()))
            val leaked = capture.events.filter { marker in it.rendered() }
            assertEquals(emptyList(), leaked.map { it.rendered().take(300) }, "the term reached a log line")
            val errors = capture.events.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR) }
            assertTrue(errors.isEmpty(), "a cancelled search is not a logged failure")

            // The lock is gone and the pool is clean: the same search works.
            assertEquals(listOf(1), cast.member.client.numbers(cast.room, "kappa"))
        } finally {
            capture.detach()
        }
    }

    /**
     * A plain Exposed `insert` answers `RETURNING *`: with the stored `search` vector (about as large as the body) that would ride back on
     * every post. The driver's own query log shows what is sent, so the message INSERT is pinned to return `messages.id` alone.
     */
    @Test
    fun `a message INSERT returns its id only, never the search vector`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("srch-ret")
        TestThreads.seed(cast.room, cast.owner.id, title = "Returning", body = "opener")
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            cast.member.client.post(cast.room, 1, "a reply")
            val inserts = capture.events.map { it.formattedMessage }.filter { "INSERT INTO messages" in it }
            assertEquals(1, inserts.size, "captured: $inserts")
            assertTrue(inserts.single().endsWith("RETURNING messages.id"), inserts.single())
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    /** Everything an event can carry, as one string. */
    private fun ch.qos.logback.classic.spi.ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        throwableProxy?.let { append(' ').append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(it)) }
    }
}
