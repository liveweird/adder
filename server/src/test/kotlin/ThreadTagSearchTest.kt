package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.tags.TagCategoryPageResponse
import ch.nokillswit.threads.ThreadSearchPageResponse
import ch.nokillswit.threads.ThreadTag
import ch.nokillswit.threads.ThreadTagsRequest
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Search by CURATED tag (1.2.0 S8, `chat-model.md` "Search (V16)" and "Room view, tags and unread (1.2)"): both search routes take the
 * thread list's repeated `threadTag`, `q` is optional with it (a tag alone is a valid search, served by the no-`q` path of 1.1), a hit
 * carries its `tags`, and the cross-room view never leaves the caller's own rooms, because `thread_tags` has no room column. The text
 * search is `ThreadSearchTest`/`GlobalSearchTest`, the filter on the list `ThreadTagFilterTest`, the statement budget `SearchBudgetTest`.
 * Rooms, users and the registry are SHARED suite state: every test mints UNIQUE rows and removes them in one `@AfterTest`.
 */
class ThreadTagSearchTest {
    private val rooms = "/api/v1/rooms"
    private val search = "/api/v1/search"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        TestTags.purge()
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { minted.users += it.id }

    private suspend fun room(owner: RoomActor, label: String): UInt {
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        return TestRooms.seed(
            owner.id, template, "$label ${UUID.randomUUID().toString().substring(0, 8)}", accessType = RoomAccessType.PUBLIC,
        ).also { minted.rooms += it }
    }

    private suspend fun HttpClient.tag(room: UInt, number: Int, tags: List<String>) {
        val response = put("$rooms/$room/threads/$number/tags") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(ThreadTagsRequest(tags))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private suspend fun HttpClient.inRoom(room: UInt, query: String): ThreadSearchPageResponse {
        val response = get("$rooms/$room/search$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.everywhere(query: String): ThreadSearchPageResponse {
        val response = get("$search$query")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private fun ThreadSearchPageResponse.at() = items.map { it.roomId to it.threadNumber }

    private fun tagged(vararg names: String) = names.joinToString("") { "&threadTag=${enc(it)}" }.removePrefix("&").let { "?$it" }

    /**
     * Ann owns room A and is a member of B; Bob owns B and C (Ann is NOT in C); an ADMIN (a member of nothing) curates the registry
     * `t1..t4`. Threads (activity stamps in brackets, so the order is certain): A#1 `t1` (1000, body holds [term]), A#2 `t1`+`t2` (3000),
     * A#3 untagged (500, body holds [term]), B#1 `t1`+`t3` (2000), C#1 `t1` (4000).
     */
    private class World(
        val ann: RoomActor,
        val bob: RoomActor,
        val admin: RoomActor,
        val a: UInt,
        val b: UInt,
        val c: UInt,
        val tags: List<String>,
        val term: String,
        val categoryId: UInt,
    ) {
        val t1 get() = tags[0]
        val t2 get() = tags[1]
        val t3 get() = tags[2]
        val t4 get() = tags[3]
    }

    private suspend fun ApplicationTestBuilder.world(prefix: String): World {
        usePostgresTestcontainer()
        val ann = actor("$prefix-ann")
        val bob = actor("$prefix-bob")
        val admin = actor("$prefix-adm", UserRole.ADMIN)
        val category = admin.client.createCategory(tags = List(4) { uniqueTag() })
        val term = "tagword" + UUID.randomUUID().toString().replace("-", "").take(12)
        val a = room(ann, "TagA")
        val b = room(bob, "TagB")
        val c = room(bob, "TagC")
        TestRooms.addMember(b, ann.id)
        TestThreads.seed(a, ann.id, title = "Alpha one", body = "The $term here")
        TestThreads.seed(a, ann.id, title = "Alpha two", body = "Something else")
        TestThreads.seed(a, ann.id, title = "Alpha three", body = "Also the $term")
        TestThreads.seed(b, bob.id, title = "Beta one", body = "Beta body")
        TestThreads.seed(c, bob.id, title = "Gamma one", body = "Hidden body")
        val (t1, t2, t3) = category.tags
        ann.client.tag(a, 1, listOf(t1))
        ann.client.tag(a, 2, listOf(t1, t2))
        bob.client.tag(b, 1, listOf(t1, t3))
        bob.client.tag(c, 1, listOf(t1))
        val stamps = listOf(Triple(a, 1, 1000L), Triple(a, 2, 3000L), Triple(a, 3, 500L), Triple(b, 1, 2000L), Triple(c, 1, 4000L))
        for ((room, number, stamp) in stamps) TestThreads.setStamps(room, number, stamp, stamp)
        return World(ann, bob, admin, a, b, c, category.tags, term, category.id)
    }

    @Test
    fun `a tag alone finds the room's tagged threads with no query, newest first, and every hit carries its tags`() = testApplication {
        val w = world("tsrch-room")
        val page = w.ann.client.inRoom(w.a, tagged(w.t1))
        assertEquals(listOf(w.a to 2, w.a to 1), page.at(), "newest activity first")
        assertEquals(2, page.total)
        page.items.forEach { hit ->
            assertEquals(emptyList(), hit.messages, "no query, no message snippet")
            assertEquals(0L, hit.matchCount)
            assertEquals(listOf(hit.title to false), hit.titleSnippet.map { it.text to it.hit }, "the title as ONE non-hit segment")
        }
        assertEquals(listOf(ThreadTag(w.t1, false), ThreadTag(w.t2, false)), page.items.first().tags.sortedBy { it.name })
        assertEquals(listOf(ThreadTag(w.t1, false)), page.items.last().tags)

        assertEquals(listOf(w.a to 1, w.a to 2), w.ann.client.inRoom(w.a, tagged(w.t1) + "&sort=lastActivityAt").at())
        assertEquals(listOf(w.a to 2), w.ann.client.inRoom(w.a, tagged(w.t2)).at())
        assertEquals(listOf(w.a to 2, w.a to 1), w.ann.client.inRoom(w.a, tagged(w.t2, w.t1)).at(), "any-of")
        assertEquals(listOf(w.a to 2), w.ann.client.inRoom(w.a, tagged(w.t1) + "&pageSize=1").at())
        assertEquals(listOf(w.a to 1), w.ann.client.inRoom(w.a, tagged(w.t1) + "&pageSize=1&page=2").at())
        assertEquals(emptyList(), w.ann.client.inRoom(w.a, tagged(w.t3)).at(), "a tag only another room's thread carries")
    }

    @Test
    fun `a hit of a text search carries its tags too, and with a tag the text narrows`() = testApplication {
        val w = world("tsrch-qtag")
        val plain = w.ann.client.inRoom(w.a, "?q=${enc(w.term)}")
        assertEquals(setOf(w.a to 1, w.a to 3), plain.at().toSet(), "the text alone finds both threads")
        assertEquals(listOf(ThreadTag(w.t1, false)), plain.items.single { it.threadNumber == 1 }.tags)
        assertEquals(emptyList(), plain.items.single { it.threadNumber == 3 }.tags, "an untagged hit has an empty list")

        val narrowed = w.ann.client.inRoom(w.a, "?q=${enc(w.term)}&threadTag=${enc(w.t1)}")
        assertEquals(listOf(w.a to 1), narrowed.at(), "the tag keeps the tagged thread of the two the text finds")
        val hit = narrowed.items.single()
        assertEquals(1L, hit.matchCount, "with a q the hit carries its messages as ever")
        assertEquals(1, hit.messages.size)
        assertEquals(listOf(w.term), hit.messages.single().snippet.filter { it.hit }.map { it.text })
        val wrongText = w.ann.client.inRoom(w.a, "?q=${enc(w.term)}&threadTag=${enc(w.t2)}")
        assertEquals(emptyList(), wrongText.at(), "tagged t2 but not matching the text")
    }

    @Test
    fun `a tag with watching and closed is all-of, and a blank q counts as none beside a tag`() = testApplication {
        val w = world("tsrch-watch")
        // Ann started both threads, which watches them (1.1): she lets go of #1, so she watches #2 alone.
        assertEquals(HttpStatusCode.NoContent, w.ann.client.putJson("$rooms/${w.a}/threads/1/watch", WatchRequest(false)).status)
        assertEquals(listOf(w.a to 2), w.ann.client.inRoom(w.a, tagged(w.t1) + "&watching=true").at())
        val notWatched = w.ann.client.inRoom(w.a, tagged(w.t1) + "&watching=false")
        assertEquals(listOf(w.a to 1), notWatched.at(), "the tagged threads Ann does not watch")
        assertEquals(listOf(w.a to 2), w.ann.client.everywhere(tagged(w.t2) + "&watching=true").at())

        TestThreads.closeRaw(w.a, 1)
        assertEquals(listOf(w.a to 1), w.ann.client.inRoom(w.a, tagged(w.t1) + "&closed=true").at())
        assertEquals(listOf(w.a to 2), w.ann.client.inRoom(w.a, tagged(w.t1) + "&closed=false").at())
        assertEquals(listOf(w.a to 2, w.a to 1), w.ann.client.inRoom(w.a, tagged(w.t1) + "&q=%20%20").at(), "a blank q is none")
    }

    @Test
    fun `a retired tag still finds its threads and is marked, a name the registry never held matches nothing`() = testApplication {
        val w = world("tsrch-retired")
        val registry = w.admin.client.get("/api/v1/tag-categories?pageSize=100").body<TagCategoryPageResponse>()
        val category = registry.items.single { it.id == w.categoryId }
        val retired = w.admin.client.saveCategory(category.id, tags = category.tags - w.t2, name = category.name)
        assertEquals(HttpStatusCode.NoContent, retired.status)

        val page = w.ann.client.inRoom(w.a, tagged(w.t2))
        assertEquals(listOf(w.a to 2), page.at(), "a retired tag stays on its thread (1.2 A6) and the search still finds it")
        assertTrue(ThreadTag(w.t2, true) in page.items.single().tags)
        assertEquals(listOf(w.a to 2), w.ann.client.everywhere(tagged(w.t2)).at())

        for (query in listOf(tagged("never-held-${UUID.randomUUID().toString().take(8)}"), tagged(w.t4))) {
            val empty = w.ann.client.inRoom(w.a, query)
            assertEquals(0L, empty.total)
            assertEquals(emptyList(), empty.items)
            assertEquals(0L, w.ann.client.everywhere(query).total)
        }
        val spelled = w.ann.client.inRoom(w.a, tagged("  ${w.t1.uppercase()} "))
        assertEquals(listOf(w.a to 2, w.a to 1), spelled.at(), "normalised like the list filter")
    }

    @Test
    fun `a name the registry never held runs the same thread statements as a tag nobody uses, so no timing tells them apart`() =
        testApplication {
            val w = world("tsrch-same")
            val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
            val saved = driver.level
            val capture = LogCapture(driver.name)
            try {
                driver.level = ch.qos.logback.classic.Level.DEBUG
                fun threadStatements() = capture.events.map { it.formattedMessage }.filter { "threads.room_id = ANY(" in it }
                assertEquals(0L, w.ann.client.everywhere(tagged(w.t4)).total, "a registry tag nobody carries")
                val used = threadStatements().size
                assertTrue(used >= 2, "the count and the page: $used")
                assertEquals(0L, w.ann.client.everywhere(tagged("never-held-${UUID.randomUUID().toString().take(8)}")).total)
                assertEquals(2 * used, threadStatements().size, "the same statements for a name the registry never held")
            } finally {
                driver.level = saved
                capture.detach()
            }
        }

    @Test
    fun `a malformed tag filter and a missing q are 400s, the tag bound is 20, and a non-member still gets 404`() = testApplication {
        val w = world("tsrch-400")
        val client = w.ann.client
        for (path in listOf("$rooms/${w.a}/search", search)) {
            suspend fun status(query: String): HttpStatusCode = client.get("$path$query").status
            assertEquals(HttpStatusCode.BadRequest, status("?threadTag=-bad"), "outside the tag grammar")
            assertEquals(HttpStatusCode.BadRequest, status("?threadTag=${"x".repeat(41)}"), "longer than a tag name")
            assertEquals(HttpStatusCode.BadRequest, status("?threadTag="), "a blank tag names none, so there is still no q")
            assertEquals(HttpStatusCode.OK, status("?q=outage&threadTag="), "a q with a blank tag is the text search")
            assertEquals(HttpStatusCode.BadRequest, status("?q=a&threadTag=${enc(w.t1)}"), "a given q is validated as ever")
            assertEquals(HttpStatusCode.BadRequest, status("?q=!!!&threadTag=${enc(w.t1)}"))
            assertEquals(HttpStatusCode.BadRequest, status(""), "no q, no tag, no watching")
            assertEquals(HttpStatusCode.BadRequest, status("?watching=false"), "watching=false alone is not a search")
            assertEquals(HttpStatusCode.BadRequest, status("?closed=true"))
            assertEquals(HttpStatusCode.BadRequest, status("?q=%20%20"), "a blank q with nothing beside it")
            val twenty = (1..20).joinToString("") { "&threadTag=name-$it" }
            assertEquals(HttpStatusCode.OK, status("?${twenty.removePrefix("&")}"), "20 distinct names are the bound")
            assertEquals(HttpStatusCode.BadRequest, status("?${twenty.removePrefix("&")}&threadTag=name-21"), "the 21st")
            assertEquals(HttpStatusCode.OK, status("?${twenty.removePrefix("&")}&threadTag=name-1"), "a repeat is no 21st")
        }
        val outsiderBad = client.get("$rooms/${w.c}/search?threadTag=-bad")
        assertEquals(HttpStatusCode.NotFound, outsiderBad.status, "a non-member's bad filter is still a 404")
        assertEquals(HttpStatusCode.NotFound, client.get("$rooms/${w.c}/search${tagged(w.t1)}").status)
    }

    @Test
    fun `the search across rooms lists only the caller's rooms, whatever the tag`() = testApplication {
        val w = world("tsrch-scope")
        // Ann is in A and B, not in C: C#1 carries t1 and is the newest thread of all, and never shows.
        val ann = w.ann.client.everywhere(tagged(w.t1))
        assertEquals(listOf(w.a to 2, w.b to 1, w.a to 1), ann.at(), "two of the three rooms, newest activity first")
        assertEquals(3, ann.total)
        assertEquals(setOf(ThreadTag(w.t1, false), ThreadTag(w.t3, false)), ann.items.single { it.roomId == w.b }.tags.toSet())
        assertEquals(listOf(w.b to 1), w.ann.client.everywhere(tagged(w.t3)).at(), "any tag, still only her rooms")
        assertEquals(listOf(w.a to 2, w.b to 1, w.a to 1), w.ann.client.everywhere(tagged(w.t1, w.t2, w.t3, w.t4)).at())
        assertEquals(listOf(w.a to 1), w.ann.client.everywhere("?q=${enc(w.term)}&threadTag=${enc(w.t1)}").at(), "the tag and the text")

        val bob = w.bob.client.everywhere(tagged(w.t1))
        assertEquals(listOf(w.c to 1, w.b to 1), bob.at(), "Bob is in B and C, not in A")
        assertEquals(2, bob.total)
        // The room route is the same scope for a member, and a 404 for Ann in C: nothing about C is disclosed either way.
        assertEquals(HttpStatusCode.NotFound, w.ann.client.get("$rooms/${w.c}/search${tagged(w.t1)}").status)
        assertEquals(listOf(w.c to 1), w.bob.client.inRoom(w.c, tagged(w.t1)).at())
    }

    @Test
    fun `an ADMIN finds a room's tagged threads by the room route, and across rooms only in the rooms they belong to`() = testApplication {
        val w = world("tsrch-admin")
        assertEquals(listOf(w.c to 1), w.admin.client.inRoom(w.c, tagged(w.t1)).at(), "an ADMIN reads this public room, member or not")
        val none = w.admin.client.everywhere(tagged(w.t1))
        assertEquals(0L, none.total, "membership is the scope across rooms, an ADMIN's too")
        assertEquals(emptyList(), none.items)

        TestRooms.addMember(w.c, w.admin.id)
        assertEquals(listOf(w.c to 1), w.admin.client.everywhere(tagged(w.t1)).at(), "only the room they joined, not A and not B")
    }

    @Test
    fun `a member removed from a room loses its tagged threads on the next request`() = testApplication {
        val w = world("tsrch-left")
        assertEquals(3, w.ann.client.everywhere(tagged(w.t1)).total)
        assertEquals(HttpStatusCode.NoContent, w.bob.client.delete("$rooms/${w.b}/members/${w.ann.id}").status)
        assertEquals(listOf(w.a to 2, w.a to 1), w.ann.client.everywhere(tagged(w.t1)).at())
        assertEquals(0L, w.ann.client.everywhere(tagged(w.t3)).total, "the only thread tagged t3 is in the room she left")
    }

    @Test
    fun `the tag view sends no query text, no snippet and no message read, and reads the tags in one statement`() = testApplication {
        val w = world("tsrch-shape")
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        var planned = ""
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            assertEquals(3, w.ann.client.everywhere(tagged(w.t1)).total)
            val all = capture.events.map { it.formattedMessage }
            val textSearch = listOf("ts_headline", "string_agg", "to_tsvector", "tsquery", "plainto")
            assertTrue(all.none { sql -> textSearch.any { it in sql.lowercase() } }, "no query text and no snippet statement: $all")
            val threadStatements = all.filter { "threads.room_id = ANY(" in it }
            assertEquals(2, threadStatements.size, "the count and the page: $threadStatements")
            threadStatements.forEach { sql ->
                assertTrue("thread_tags" in sql && "LIMIT 1" in sql.uppercase(), "the tag probe is a scalar probe with LIMIT 1: $sql")
                assertTrue("EXISTS" !in sql.uppercase(), "never an EXISTS the planner may flatten into a tag-side join: $sql")
                assertTrue("thread_tags.thread_id = threads.id" in sql, "correlated on the thread: $sql")
                assertTrue("messages" !in sql, "no message is read: $sql")
                val roomsFirst = sql.indexOf("threads.room_id = ANY(") < sql.indexOf("thread_tags")
                assertTrue(roomsFirst, "the rooms come first in the text (a proxy; the EXPLAIN below pins the plan): $sql")
            }
            val pageTags = all.count { "FROM thread_tags" in it && "JOIN" in it.uppercase() && "thread_id IN" in it }
            assertEquals(1, pageTags, "the page's tags: one statement: $all")
            planned = threadStatements.last().removePrefix("Executing query: ")
        } finally {
            driver.level = saved
            capture.detach()
        }
        for (mode in listOf("auto", "force_generic_plan")) {
            // Tag 0 is the id a name the registry never held is probed as (`threadCarriesAnyThreadTag`).
            for (tag in listOf(1, 7, 0)) {
                val plan = explainTagPage(planned, tag, mode)
                assertTrue("Seq Scan" !in plan, "$mode, tag $tag: no scan of the whole threads or thread_tags table:\n$plan")
                val tagIndex = "idx_thread_tags_tag" in plan || "thread_tags_pkey" in plan
                assertTrue(tagIndex, "$mode, tag $tag: thread_tags through an index:\n$plan")
                val threadsIndex = "idx_threads_room_" in plan || "threads_pkey" in plan || "uq_threads_room_number" in plan
                assertTrue(threadsIndex, "$mode, tag $tag: threads through an index (the caller's rooms first):\n$plan")
                assertTrue("messages" !in plan, "no message table in the plan:\n$plan")
                // The time must not follow the tag's usage (1.2 branch audit): one probe per thread of the caller's rooms, never a
                // join that starts from the tag side and reads every thread that carries it.
                assertTrue("SubPlan" in plan, "$mode, tag $tag: a per-thread probe:\n$plan")
                assertTrue("Semi Join" !in plan && "Hash" !in plan, "$mode, tag $tag: no join from the tag side:\n$plan")
            }
        }
    }

    /**
     * The captured page statement of the tag view, PREPARED and explained over a seeded spread inside a rolled-back transaction of the
     * shared container: 30,000 threads in 1,000 rooms with 12 tags (tag 1 on about a fifth of them, each other tag on one in twelve), the
     * caller in 40 rooms. Foreign keys are off for the seed (`replica`), the tables are analysed, the planner is NOT restricted: this is
     * the plan the server would choose (`persistence.md` "Thread tags (V33)" has the measured times).
     */
    private fun explainTagPage(sql: String, tag: Int, mode: String): String = PostgresTestSupport.connect().use { c ->
        c.autoCommit = false
        try {
            c.createStatement().use { st ->
                st.execute("SET LOCAL session_replication_role = replica")
                st.execute(
                    "INSERT INTO threads (id, room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, " +
                        "created_at, updated_at) " +
                        "SELECT 1600000000 + t, 2100000000 + (t % 1000), t, 'T', 'open', 1, 1, t, 1, 1 FROM generate_series(1, 30000) AS t",
                )
                st.execute(
                    "INSERT INTO thread_tags (thread_id, tag_id, added_at, added_by) " +
                        "SELECT 1600000000 + t, 1 + (t % 12), 1, 1 FROM generate_series(1, 30000) AS t",
                )
                st.execute(
                    "INSERT INTO thread_tags (thread_id, tag_id, added_at, added_by) " +
                        "SELECT 1600000000 + t, 1, 1, 1 FROM generate_series(1, 30000) AS t WHERE t % 12 <> 0 AND t % 10 = 0",
                )
                st.execute("ANALYZE threads; ANALYZE thread_tags")
                st.execute("SET LOCAL plan_cache_mode = $mode")
                st.execute("PREPARE tag_plan(int4[], boolean, int4) AS $sql")
                val rooms = (0 until 40).joinToString(",", "'{", "}'") { (2100000000 + it).toString() }
                buildString {
                    st.executeQuery("EXPLAIN EXECUTE tag_plan($rooms, false, $tag)").use { rs ->
                        while (rs.next()) appendLine(rs.getString(1))
                    }
                }
            }
        } finally {
            c.rollback()
        }
    }
}
