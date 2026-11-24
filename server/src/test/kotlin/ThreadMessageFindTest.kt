package ch.nokillswit

import ch.nokillswit.threads.MessageContextResponse
import ch.nokillswit.threads.MessageSearchPageResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Direct message context and search inside one thread (1.10.0). */
class ThreadMessageFindTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()
    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.cast(prefix: String): ThreadCast = seedThreadCast(prefix, "Find", minted)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun io.ktor.client.HttpClient.context(room: UInt, messageId: UInt, number: Int = 1) =
        get("$rooms/$room/threads/$number/messages/context?messageId=$messageId")

    private suspend fun io.ktor.client.HttpClient.search(room: UInt, query: String, number: Int = 1): MessageSearchPageResponse =
        get("$rooms/$room/threads/$number/messages/search?$query").body()

    private fun bulkReplies(cast: ThreadCast, count: Int): List<UInt> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "INSERT INTO messages (thread_id, author_id, body, created_at, tags_version) " +
                "SELECT ?, ?, 'Reply ' || n, ? + n, 1 FROM generate_series(1, ?) n",
        ).use { statement ->
            statement.setLong(1, cast.threadId.toLong())
            statement.setLong(2, cast.member.id.toLong())
            statement.setLong(3, System.currentTimeMillis())
            statement.setInt(4, count)
            assertEquals(count, statement.executeUpdate())
        }
        connection.prepareStatement("SELECT id FROM messages WHERE thread_id = ? ORDER BY id").use { statement ->
            statement.setLong(1, cast.threadId.toLong())
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getLong(1).toUInt())
                }
            }
        }
    }

    @Test
    fun `context lands directly on a distant tombstone with a bounded window on both sides`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("message-context")
        val ids = bulkReplies(cast, 650)
        val anchorIndex = 575
        val anchor = ids[anchorIndex]
        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$anchor").status)

        val response = cast.member.client.context(cast.room, anchor)
        assertEquals(HttpStatusCode.OK, response.status)
        val context = response.body<MessageContextResponse>()
        assertEquals(anchor, context.messageId)
        assertEquals(51, context.items.size)
        assertEquals(ids.slice(anchorIndex - 25..anchorIndex + 25), context.items.map { it.id })
        assertTrue(context.older)
        assertTrue(context.newer)
        val landed = context.items.single { it.id == anchor }
        assertTrue(landed.deleted)
        assertEquals(null, landed.body)

        val earliest = cast.member.client.context(cast.room, ids.first()).body<MessageContextResponse>()
        assertEquals(ids.take(26), earliest.items.map { it.id })
        assertFalse(earliest.older)
        assertTrue(earliest.newer)

        val latest = cast.member.client.context(cast.room, ids.last()).body<MessageContextResponse>()
        assertEquals(ids.takeLast(26), latest.items.map { it.id })
        assertTrue(latest.older)
        assertFalse(latest.newer)
    }

    @Test
    fun `context is scoped to the visible room and selected thread`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("message-context-guard")
        val second = TestThreads.seed(cast.room, cast.author.id, title = "Second", body = "Second opener")
        val base = "$rooms/${cast.room}/threads/1/messages/context?messageId="

        assertEquals(HttpStatusCode.NotFound, cast.member.client.get(base + second.messageId).status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.get(base + "2147483647").status)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.context(cast.room, cast.opener).status)
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.get(base + "0").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().context(cast.room, cast.opener).status)

        val context = cast.admin.client.context(cast.room, cast.opener).body<MessageContextResponse>()
        assertFalse(context.older)
        assertFalse(context.newer)
        assertEquals(listOf(cast.opener), context.items.map { it.id })
    }

    @Test
    fun `thread message search pages live message hits and supports structured clauses`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("message-search")
        val older = TestThreads.post(cast.room, 1, cast.member.id, "exact signal older")
        val newer = TestThreads.post(cast.room, 1, cast.member.id, "exact signal newer")
        val deleted = TestThreads.post(cast.room, 1, cast.member.id, "exact signal deleted")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$deleted").status)

        val first = cast.member.client.search(cast.room, "q=signal&pageSize=1&sort=-id")
        assertEquals(2L, first.total)
        assertEquals(listOf(newer), first.items.map { it.id })
        assertEquals(listOf("signal"), first.items.single().snippet.filter { it.hit }.map { it.text })
        val second = cast.member.client.search(cast.room, "q=signal&pageSize=1&page=2&sort=-id")
        assertEquals(listOf(older), second.items.map { it.id })

        val structured = encoded("from:#${cast.member.id} owner:#${cast.author.id} \"exact signal\"")
        val structuredPage = cast.member.client.search(
            cast.room,
            "q=$structured&syntax=structured&timeZone=Europe%2FWarsaw&pageSize=1&page=2&sort=-id",
        )
        assertEquals(2L, structuredPage.total)
        assertEquals(listOf(older), structuredPage.items.map { it.id })
        assertTrue(structuredPage.items.all { it.authorId == cast.member.id })

        val titleOnly = encoded("Opening thread")
        assertEquals(0L, cast.member.client.search(cast.room, "q=$titleOnly").total, "thread titles are outside message search")
    }

    @Test
    fun `thread message search validates before querying and hides foreign threads`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("message-search-guard")
        val base = "$rooms/${cast.room}/threads/1/messages/search"

        for (query in listOf("", "?q=x", "?q=signal&pageSize=21", "?q=signal&sort=createdAt", "?q=unknown%3Avalue&syntax=structured")) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get(base + query).status, query)
        }
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get("$base?q=signal").status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.get("$rooms/${cast.room}/threads/99/messages/search?q=signal").status)
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("$base?q=signal").status)
    }
}
