package ch.nokillswit

import ch.nokillswit.threads.MAX_THREAD_HASHTAGS
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadListItem
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * A thread's hashtags on the wire (1.2 A8, `chat-model.md` "Hashtags on rows, and `#42`"): `hashtags` on a list row and on the thread
 * itself, the distinct folded keys of its LIVE messages, alphabetical, at most [MAX_THREAD_HASHTAGS], read for a whole page by one
 * statement. The index itself is `MessageTagsTest`, the statement's plan `ThreadSchemaTest`. Rooms, templates and users are SHARED suite
 * state: every test mints UNIQUE rows and removes them.
 */
class ThreadHashtagsTest {
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

    private suspend fun ApplicationTestBuilder.cast(prefix: String): RoomCast = seedRoomCast(prefix, "Hash", minted)

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.rows(room: UInt, query: String = ""): Map<Int, ThreadListItem> =
        get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.associateBy { it.number }

    private suspend fun HttpClient.thread(room: UInt, number: Int): ThreadResponse = get("$rooms/$room/threads/$number").body()

    @Test
    fun `a row and the thread carry the distinct folded hashtags of the live messages, alphabetical`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ht-wire")
        repeat(3) { TestThreads.seed(cast.room, cast.member.id) }
        cast.member.client.post(cast.room, 1, "late #Zeta and #Wdrożenie")
        cast.member.client.post(cast.room, 1, "again #WDROZENIE, then #alpha")
        cast.member.client.post(cast.room, 2, "only #beta here")

        val owner = cast.owner.client
        val rows = owner.rows(cast.room)
        assertEquals(listOf("alpha", "wdrozenie", "zeta"), rows.getValue(1).hashtags, "distinct across messages, folded, alphabetical")
        assertEquals(listOf("beta"), rows.getValue(2).hashtags, "each row has its own thread's tags only")
        assertEquals(emptyList(), rows.getValue(3).hashtags, "a thread with no hashtag lists none")
        assertEquals(rows.getValue(1).hashtags, owner.thread(cast.room, 1).hashtags, "the detail reads the same set")
        assertEquals(emptyList(), owner.thread(cast.room, 3).hashtags)
        assertEquals(listOf("beta"), owner.rows(cast.room, "?tag=beta").values.single().hashtags, "a filtered page too")
        assertEquals(
            listOf("alpha", "wdrozenie", "zeta"),
            cast.member.client.rows(cast.room, "?pageSize=1&sort=number").values.single().hashtags,
            "a one-row page reads one thread's set",
        )
    }

    @Test
    fun `the opening message's hashtags count`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ht-open")
        val response = cast.member.client.postJson(
            "$rooms/${cast.room}/threads",
            ThreadCreateRequest(
                "Opening", "Start with #Kickoff", mapOf("severity" to JsonPrimitive("s1")),
            ),
        )
        assertEquals(HttpStatusCode.Created, response.status)
        val number = response.body<ThreadResponse>().number
        assertEquals(listOf("kickoff"), cast.owner.client.rows(cast.room).getValue(number).hashtags)
    }

    @Test
    fun `an edit adds and drops hashtags, and a delete removes a message's`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ht-edit")
        TestThreads.seed(cast.room, cast.member.id)
        val first = cast.member.client.post(cast.room, 1, "start #one")
        val second = cast.member.client.post(cast.room, 1, "also #two and #three")
        val owner = cast.owner.client
        assertEquals(listOf("one", "three", "two"), owner.rows(cast.room).getValue(1).hashtags)

        assertEquals(
            HttpStatusCode.NoContent,
            cast.member.client.putJson("$rooms/${cast.room}/messages/${second.id}", MessageRequest("now only #four")).status,
        )
        assertEquals(listOf("four", "one"), owner.rows(cast.room).getValue(1).hashtags, "an edit drops and adds")
        assertEquals(listOf("four", "one"), owner.thread(cast.room, 1).hashtags)

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/${first.id}").status)
        assertEquals(listOf("four"), owner.rows(cast.room).getValue(1).hashtags, "a deleted message's hashtags are gone")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${second.id}").status)
        assertEquals(emptyList(), owner.rows(cast.room).getValue(1).hashtags, "a moderator's delete too")
        assertEquals(emptyList(), owner.thread(cast.room, 1).hashtags)
    }

    @Test
    fun `a thread lists at most twenty hashtags, the first twenty in order`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ht-cap")
        TestThreads.seed(cast.room, cast.member.id)
        fun body(prefix: String) = (1..MAX_THREAD_HASHTAGS).joinToString(" ") { "#$prefix${"%02d".format(it)}" }
        cast.member.client.post(cast.room, 1, body("u"))
        cast.member.client.post(cast.room, 1, body("t"))
        val expected = (1..MAX_THREAD_HASHTAGS).map { "t${"%02d".format(it)}" }
        assertEquals(expected, cast.owner.client.rows(cast.room).getValue(1).hashtags, "40 distinct tags, the first 20 alphabetically")
        assertEquals(expected, cast.owner.client.thread(cast.room, 1).hashtags)
    }

    @Test
    fun `an ADMIN outside the room and a member read the same hashtags`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ht-admin")
        val admin = seedActor("ht-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        TestThreads.seed(cast.room, cast.member.id)
        cast.member.client.post(cast.room, 1, "see #incident")
        assertEquals(listOf("incident"), admin.client.rows(cast.room).getValue(1).hashtags)
        assertEquals(listOf("incident"), cast.member.client.rows(cast.room).getValue(1).hashtags)
    }
}
