package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.THREAD_SORT_FIELDS
import ch.nokillswit.users.USER_SORT_FIELDS
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The bound of the `sort` list: one entry per whitelisted field, so a field named twice (with or
 * without the `-`) and a list longer than the whitelist are a `400` on every list, the thread list's computed `status` sort included.
 * The paging pipeline's other rules are the list endpoints' own tests. Rooms, templates and users are SHARED suite state: every test
 * mints UNIQUE rows and removes them.
 */
class SortListBoundsTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.threadListUrl(): Pair<RoomActor, String> {
        val owner = seedActor("sortb", UserRole.USER).also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Srt ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestThreads.seed(room, owner.id)
        return owner to "/api/v1/rooms/$room/threads"
    }

    private suspend fun HttpClient.sorted(url: String, sort: String): HttpResponse = get("$url?sort=$sort")

    private suspend fun HttpResponse.assertRefused(part: String) {
        assertEquals(HttpStatusCode.BadRequest, status)
        val detail = body<ProblemDetail>().detail.orEmpty()
        assertTrue(part in detail, "the 400 names the rule: $detail")
    }

    @Test
    fun `the thread list refuses a repeated sort field, with or without the minus`() = testApplication {
        usePostgresTestcontainer()
        val (owner, url) = threadListUrl()
        owner.client.sorted(url, "status,status").assertRefused("twice")
        owner.client.sorted(url, "status,-status").assertRefused("twice")
        owner.client.sorted(url, "-title,createdAt,title").assertRefused("twice")
        // `id` is the tiebreaker the paging helper appends: naming it twice is the same refusal.
        owner.client.sorted(url, "id,-id").assertRefused("twice")
    }

    @Test
    fun `the thread list refuses a sort longer than its whitelist - an attack-sized one never reaches the SQL`() = testApplication {
        usePostgresTestcontainer()
        val (owner, url) = threadListUrl()
        val fields = THREAD_SORT_FIELDS.toList()
        // One entry more than the whitelist holds: every name is valid, one is a repeat.
        owner.client.sorted(url, (fields + fields.first()).joinToString(",")).assertRefused("at most ${fields.size}")
        // The shape of the audit's attack: hundreds of `status` entries (a bound CASE per entry) in the request line.
        owner.client.sorted(url, List(570) { "status" }.joinToString(",")).assertRefused("at most ${fields.size}")
    }

    @Test
    fun `an unknown sort field is echoed bounded, never whole`() = testApplication {
        usePostgresTestcontainer()
        val (owner, url) = threadListUrl()
        val response = owner.client.sorted(url, "x".repeat(3_000))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val detail = response.body<ProblemDetail>().detail.orEmpty()
        assertTrue(detail.startsWith("Unknown sort field: ") && detail.length < 300, "a long field is cut: ${detail.length}")
    }

    @Test
    fun `valid multi-field sorts still pass on the thread list, the whole whitelist at once included`() = testApplication {
        usePostgresTestcontainer()
        val (owner, url) = threadListUrl()
        assertEquals(HttpStatusCode.OK, owner.client.sorted(url, "-lastActivityAt,title").status)
        assertEquals(HttpStatusCode.OK, owner.client.sorted(url, "status,-createdAt,number").status)
        assertEquals(HttpStatusCode.OK, owner.client.sorted(url, THREAD_SORT_FIELDS.joinToString(",")).status)
        assertEquals(HttpStatusCode.OK, owner.client.sorted(url, THREAD_SORT_FIELDS.joinToString(",") { "-$it" }).status)
    }

    @Test
    fun `the users list refuses a repeated field and an over-long sort, and takes a valid multi-field one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seedActor("sortb-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        val url = "/api/v1/users"
        val fields = USER_SORT_FIELDS.toList()
        admin.client.sorted(url, "${fields.first()},-${fields.first()}").assertRefused("twice")
        admin.client.sorted(url, (fields + fields.first()).joinToString(",")).assertRefused("at most ${fields.size}")
        assertEquals(HttpStatusCode.OK, admin.client.sorted(url, fields.joinToString(",")).status)
        assertEquals(HttpStatusCode.OK, admin.client.sorted(url, "${fields[0]},-${fields[1]}").status)
    }
}
