package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.tags.TagCategoryPageResponse
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadTag
import ch.nokillswit.threads.ThreadTagsRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The thread list's `threadTag` filter (1.2 D3, `chat-model.md` "Tags", `list-endpoints.md` ledger): the CURATED tags, any-of within the
 * key and all-of across the other filters, the names normalised like the PUT's, a RETIRED tag still found (1.2 A6), a well-formed unknown
 * name matching nothing, the 20-distinct bound and the malformed 400, the 404 that outranks every 400, and the hashtag filter `tag`
 * untouched beside it. The probe's plan is in `ThreadSchemaTest`. Rooms, users and the registry are SHARED suite state: every test mints
 * UNIQUE rows and removes them in one `@AfterTest`.
 */
class ThreadTagFilterTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        TestTags.purge()
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.tag(room: UInt, number: Int, tags: List<String>): HttpResponse =
        put("$rooms/$room/threads/$number/tags") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(ThreadTagsRequest(tags))
        }

    private suspend fun HttpClient.numbers(room: UInt, query: String): List<Int> =
        get("$rooms/$room/threads?pageSize=100&sort=number$query").body<ThreadPageResponse>().items.map { it.number }

    /** Room with threads 1 to 4 (a plain member's), tags #1 `a`, #2 `a` + `b`, #3 `c`, #4 none; [Fixture.tags] has four (a, b, c, d). */
    private class Fixture(val cast: ThreadCast, val tags: List<String>) {
        val room get() = cast.room
    }

    private suspend fun ApplicationTestBuilder.fixture(prefix: String): Fixture {
        usePostgresTestcontainer()
        val cast = seedThreadCast(prefix, "Filter", minted)
        val category = cast.admin.client.createCategory(tags = List(4) { uniqueTag() })
        val (a, b, c) = category.tags
        val member = cast.member.client
        TestThreads.seed(cast.room, cast.author.id)
        TestThreads.seed(cast.room, cast.author.id)
        TestThreads.seed(cast.room, cast.author.id)
        assertEquals(HttpStatusCode.NoContent, member.tag(cast.room, 1, listOf(a)).status)
        assertEquals(HttpStatusCode.NoContent, member.tag(cast.room, 2, listOf(b, a)).status)
        assertEquals(HttpStatusCode.NoContent, member.tag(cast.room, 3, listOf(c)).status)
        return Fixture(cast, category.tags)
    }

    @Test
    fun `threadTag is any-of within the key, all-of across the other filters, and counts after the filter`() = testApplication {
        val f = fixture("tfilt-any")
        val (a, b, c) = f.tags
        val member = f.cast.member.client
        assertEquals(listOf(1, 2, 3, 4), member.numbers(f.room, ""))
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=${enc(a)}"))
        assertEquals(listOf(2), member.numbers(f.room, "&threadTag=${enc(b)}"))
        assertEquals(listOf(1, 2, 3), member.numbers(f.room, "&threadTag=${enc(a)}&threadTag=${enc(c)}"), "any-of")
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=${enc(a)}&threadTag=${enc(a)}"), "a repeat is no error")
        val page = member.get("$rooms/${f.room}/threads?pageSize=1&threadTag=${enc(a)}").body<ThreadPageResponse>()
        assertEquals(1, page.items.size)
        assertEquals(2, page.total, "total is computed after the filter, before the page")

        TestThreads.closeRaw(f.room, 2)
        assertEquals(listOf(2), member.numbers(f.room, "&threadTag=${enc(a)}&closed=true"), "all-of across filters")
        assertEquals(listOf(1), member.numbers(f.room, "&threadTag=${enc(a)}&closed=false"))
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=${enc(a)}&ownerId=${f.cast.author.id}"))
        assertEquals(emptyList(), member.numbers(f.room, "&threadTag=${enc(a)}&ownerId=${f.cast.owner.id}"))
    }

    @Test
    fun `names are normalised, an unknown one matches nothing and a hashtag is no tag`() = testApplication {
        val f = fixture("tfilt-norm")
        val (a) = f.tags
        val member = f.cast.member.client
        val spelled = enc("  ${a.uppercase()}  ")
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=$spelled"), "trimmed and lower-cased like the PUT")
        assertEquals(emptyList(), member.numbers(f.room, "&threadTag=${enc("never-held-1234")}"), "well formed, held by nobody")
        // A hashtag in a message is not a curated tag, and the curated filter does not read message text.
        TestThreads.post(f.room, 4, f.cast.author.id, "Plain talk about #$a here")
        assertEquals(listOf(4), member.numbers(f.room, "&tag=${enc(a)}"), "the hashtag filter finds the message")
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=${enc(a)}"))
        assertEquals(emptyList(), member.numbers(f.room, "&threadTag=${enc(a)}&tag=${enc(a)}"), "all-of: no thread has both")
    }

    @Test
    fun `a retired tag still finds the threads that kept it`() = testApplication {
        val f = fixture("tfilt-retired")
        val (a) = f.tags
        val registry = f.cast.admin.client.get("/api/v1/tag-categories?pageSize=100").body<TagCategoryPageResponse>()
        val category = registry.items.single { a in it.tags }
        f.cast.admin.client.saveCategory(category.id, tags = category.tags - a, name = category.name)
        val member = f.cast.member.client
        assertEquals(listOf(1, 2), member.numbers(f.room, "&threadTag=${enc(a)}"))
        val row = member.get("$rooms/${f.room}/threads?sort=number&threadTag=${enc(a)}").body<ThreadPageResponse>().items.first()
        assertEquals(ThreadTag(a, true), row.tags.single { it.name == a })
    }

    @Test
    fun `the bound is 20 distinct names, and a malformed one is a 400 that a non-member never sees`() = testApplication {
        val f = fixture("tfilt-bound")
        val member = f.cast.member.client
        fun many(n: Int) = (1..n).joinToString("") { "&threadTag=name-$it" }
        assertEquals(HttpStatusCode.OK, member.get("$rooms/${f.room}/threads?${many(20)}").status)
        assertEquals(HttpStatusCode.BadRequest, member.get("$rooms/${f.room}/threads?${many(21)}").status)
        for (bad in listOf("bad,name", "-leading", "x".repeat(41), "%07control")) {
            val value = if (bad.startsWith("%")) bad else enc(bad)
            assertEquals(HttpStatusCode.BadRequest, member.get("$rooms/${f.room}/threads?threadTag=$value").status, "'$bad'")
        }
        // The visibility 404 outranks every filter 400.
        assertEquals(HttpStatusCode.NotFound, f.cast.outsider.client.get("$rooms/${f.room}/threads?${many(21)}").status)
        // An ADMIN outside the room reads the filter like any reader.
        assertEquals(listOf(1, 2), f.cast.admin.client.numbers(f.room, "&threadTag=${enc(f.tags.first())}"))
    }

    @Test
    fun `the same tag in two rooms - each list holds its own room's threads and total, never the other's`() = testApplication {
        val f = fixture("tfilt-room1")
        val (a) = f.tags
        val other = seedThreadCast("tfilt-room2", "Filter2", minted)
        assertEquals(HttpStatusCode.NoContent, other.member.client.tag(other.room, 1, listOf(a)).status)
        val firstPath = "$rooms/${f.room}/threads?pageSize=100&sort=number&threadTag=${enc(a)}"
        val first = f.cast.admin.client.get(firstPath).body<ThreadPageResponse>()
        val second = other.admin.client.get("$rooms/${other.room}/threads?pageSize=100&threadTag=${enc(a)}").body<ThreadPageResponse>()
        assertEquals(listOf(1, 2), first.items.map { it.number })
        assertEquals(2L, first.total)
        assertEquals(listOf(other.threadId), second.items.map { it.id }, "only the second room's own thread")
        assertEquals(1L, second.total)
        // A member of one room asking about the other's id is a 404, tag or not.
        assertEquals(HttpStatusCode.NotFound, f.cast.member.client.get("$rooms/${other.room}/threads?threadTag=${enc(a)}").status)
    }
}
