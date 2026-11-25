package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.threads.MessagePostRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadSearchPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ThreadLifecycleTest {
    private val users = mutableListOf<UInt>()
    private val rooms = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun room(owner: UInt): UInt {
        val template = TestThreads.seedTemplate().also { templates += it }
        return TestRooms.seed(owner, template, "Lifecycle ${UUID.randomUUID()}", accessType = RoomAccessType.PUBLIC).also { rooms += it }
    }

    @Test
    fun `merge keeps source links and history and redirects new activity to a single destination`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("merge-owner").also { users += it.id }
        val member = seedActor("merge-member").also { users += it.id }
        val room = room(owner.id)
        TestRooms.addMember(room, member.id)
        val source = TestThreads.seed(room, member.id, title = "Duplicate")
        val target = TestThreads.seed(room, owner.id, title = "Canonical")
        val final = TestThreads.seed(room, owner.id, title = "Final")
        val base = "/api/v1/rooms/$room/threads"
        val sourcePath = "$base/${source.number}"
        val targetPath = "$base/${target.number}"
        val finalPath = "$base/${final.number}"

        assertEquals(HttpStatusCode.Forbidden, member.client.merge(sourcePath, target.number))
        assertEquals(HttpStatusCode.BadRequest, owner.client.merge(sourcePath, source.number))
        assertEquals(HttpStatusCode.NotFound, owner.client.merge(sourcePath, 1000))
        assertEquals(HttpStatusCode.NoContent, owner.client.merge(sourcePath, target.number))
        assertEquals(HttpStatusCode.NoContent, owner.client.merge(sourcePath, target.number))
        assertEquals(HttpStatusCode.Conflict, owner.client.merge(targetPath, source.number))
        assertEquals(1, TestThreads.rawEvents(source.id).count { it.type == "MERGED_INTO" })
        val merged = owner.client.get(sourcePath).body<ThreadResponse>()
        assertEquals(target.number, merged.mergedIntoThreadNumber)
        assertEquals(1L, owner.client.get(targetPath).body<ThreadResponse>().mergedSourceCount)
        assertEquals(HttpStatusCode.OK, owner.client.get("$sourcePath/messages").status)
        assertEquals(HttpStatusCode.Conflict, owner.client.post("$sourcePath/messages") {
            contentType(ContentType.Application.Json)
            setBody(MessagePostRequest("New reply"))
        }.status)
        assertEquals(listOf(target.number, final.number), owner.client.get(base).body<ThreadPageResponse>().items.map { it.number })

        assertEquals(HttpStatusCode.NoContent, owner.client.merge(targetPath, final.number))
        assertEquals(final.number, owner.client.get(sourcePath).body<ThreadResponse>().mergedIntoThreadNumber)
        assertEquals(2L, owner.client.get(finalPath).body<ThreadResponse>().mergedSourceCount)
        assertEquals(
            listOf(source.number, target.number),
            owner.client.get("$base?mergedInto=${final.number}").body<ThreadPageResponse>().items.map { it.number },
        )
    }

    @Test
    fun `deleting a canonical thread hides it and merged conversations from reader routes and search`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("delete-owner").also { users += it.id }
        val member = seedActor("delete-member").also { users += it.id }
        val room = room(owner.id)
        TestRooms.addMember(room, member.id)
        val source = TestThreads.seed(room, owner.id, title = "Raredeletedterm")
        val target = TestThreads.seed(room, owner.id, title = "Raredeletedterm target")
        val base = "/api/v1/rooms/$room/threads"
        val sourcePath = "$base/${source.number}"
        val targetPath = "$base/${target.number}"

        assertEquals(HttpStatusCode.Forbidden, member.client.delete(targetPath).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.merge(sourcePath, target.number))
        assertEquals(HttpStatusCode.NoContent, owner.client.post("/api/v1/rooms/$room/archive").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.merge(targetPath, target.number))
        assertEquals(HttpStatusCode.Conflict, owner.client.delete(targetPath).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.post("/api/v1/rooms/$room/unarchive").status)
        assertTrue(member.client.get("/api/v1/notifications").body<NotificationPageResponse>().items.any { it.roomId == room })
        TestThreads.closeRaw(room, target.number)
        assertEquals(HttpStatusCode.NoContent, owner.client.delete(targetPath).status)
        assertEquals(HttpStatusCode.NotFound, owner.client.delete(targetPath).status)
        for (path in listOf(sourcePath, targetPath, "$sourcePath/events", "$sourcePath/messages", "$targetPath/watchers")) {
            assertEquals(HttpStatusCode.NotFound, owner.client.get(path).status, path)
        }
        val sourceMessage = "/api/v1/rooms/$room/messages/${source.messageId}"
        assertEquals(HttpStatusCode.NotFound, owner.client.get(sourceMessage).status)
        assertTrue(member.client.get("/api/v1/notifications").body<NotificationPageResponse>().items.none { it.roomId == room })
        assertEquals(0L, owner.client.get(base).body<ThreadPageResponse>().total)
        assertEquals(0L, owner.client.get("$base?mergedInto=${target.number}").body<ThreadPageResponse>().total)
        assertEquals(0L, owner.client.get("/api/v1/rooms/$room/search?q=raredeletedterm").body<ThreadSearchPageResponse>().total)
        val replacement = TestThreads.seed(room, owner.id, title = "New thread")
        assertEquals(target.number + 1, replacement.number)
    }

    private suspend fun HttpClient.merge(source: String, target: Int): HttpStatusCode = put("$source/merge") {
        contentType(ContentType.Application.Json)
        setBody("""{"targetNumber":$target}""")
    }.status
}
