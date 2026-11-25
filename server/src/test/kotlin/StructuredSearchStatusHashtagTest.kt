package ch.nokillswit

import ch.nokillswit.threads.MessageSearchPageResponse
import ch.nokillswit.threads.ThreadSearchPageResponse
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
import kotlinx.coroutines.runBlocking

/** Status and exact live-message hashtag clauses across all three structured-search views. */
class StructuredSearchStatusHashtagTest {
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

    private suspend fun ApplicationTestBuilder.cast(prefix: String): ThreadCast = seedThreadCast(prefix, "Structured", minted)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun io.ktor.client.HttpClient.roomSearch(room: UInt, query: String): ThreadSearchPageResponse =
        get("$rooms/$room/search?q=${encoded(query)}&syntax=structured").body()

    private suspend fun io.ktor.client.HttpClient.globalSearch(query: String): ThreadSearchPageResponse =
        get("/api/v1/search?q=${encoded(query)}&syntax=structured").body()

    private suspend fun io.ktor.client.HttpClient.messageSearch(room: UInt, number: Int, query: String): MessageSearchPageResponse =
        get("$rooms/$room/threads/$number/messages/search?q=${encoded(query)}&syntax=structured").body()

    private fun investigating(cast: ThreadCast, vararg threadIds: UInt) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "INSERT INTO room_statuses (room_id, key, label, colour, category, is_initial, position) " +
                "VALUES (?, 'investigating', 'Investigating', 'BLUE', 'OPEN', FALSE, 4)",
        ).use { statement ->
            statement.setLong(1, cast.room.toLong())
            statement.executeUpdate()
        }
        connection.prepareStatement("UPDATE threads SET status_key = 'investigating' WHERE id = ANY(?)").use { statement ->
            statement.setArray(1, connection.createArrayOf("integer", threadIds.map(UInt::toInt).toTypedArray()))
            assertEquals(threadIds.size, statement.executeUpdate())
        }
    }

    @Test
    fun `status and hashtag clauses keep thread scope and same-message AND semantics in every search view`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("structured-status-tag")
        val titleOnly = TestThreads.seed(cast.room, cast.author.id, title = "Needle in title", body = "Separate #Żółć tag")
        val partial = TestThreads.seed(cast.room, cast.author.id, title = "Partial", body = "Needle #incident-response")
        investigating(cast, cast.threadId, titleOnly.id, partial.id)

        TestThreads.post(cast.room, 1, cast.member.id, "Needle without the tag")
        TestThreads.post(cast.room, 1, cast.member.id, "Separate #Żółć tag")
        val query = "status:investigating hashtag:#zolc needle"

        assertEquals(emptyList(), cast.member.client.roomSearch(cast.room, query).items)
        assertEquals(emptyList(), cast.member.client.globalSearch(query).items)
        assertEquals(emptyList(), cast.member.client.messageSearch(cast.room, 1, query).items)

        val matching = TestThreads.post(cast.room, 1, cast.member.id, "Needle on #Żółć")
        assertEquals(listOf(1), cast.member.client.roomSearch(cast.room, query).items.map { it.threadNumber })
        assertEquals(listOf(cast.room to 1), cast.member.client.globalSearch(query).items.map { it.roomId to it.threadNumber })
        assertEquals(listOf(matching), cast.member.client.messageSearch(cast.room, 1, query).items.map { it.id })

        assertEquals(emptyList(), cast.member.client.roomSearch(cast.room, "status:investigating status:open").items)
        assertEquals(emptyList(), cast.member.client.roomSearch(cast.room, "status:unknown").items)
        assertEquals(emptyList(), cast.member.client.roomSearch(cast.room, "hashtag:incident").items, "no partial tag match")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$matching").status)
        assertEquals(emptyList(), cast.member.client.roomSearch(cast.room, query).items, "deleted messages lose their tag match")
    }
}
