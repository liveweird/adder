package ch.nokillswit

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.rooms.RoomResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Threads over HTTP, the S2 half (0.3.0): start (the thread, its opening message, its `CREATED` event and the activity bump in one
 * write), every 400, the archived 409, the read, the paged list with its `closed` filter and sorts, and `ownerOf`. The audit trail,
 * the `chat-post` bucket and the lock-wait races are `ThreadWriteBoundsTest`; the role matrix per caller is `ThreadAuthzMatrixTest`; the
 * history is `ThreadEventsTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadTest {
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

    private suspend fun ApplicationTestBuilder.actor(prefix: String, name: String = "Test"): RoomActor =
        seedActor(prefix, name = name).also { mintedUsers += it.id }

    /** A room of the fixture template owned by [owner], with [members] added as plain members. */
    private suspend fun room(owner: RoomActor, vararg members: RoomActor): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(owner.id, template, "Thr ${UUID.randomUUID().toString().substring(0, 8)}").also { room ->
            mintedRooms += room
            members.forEach { TestRooms.addMember(room, it.id) }
        }
    }

    private fun text(value: String) = JsonPrimitive(value)

    private val severity: Map<String, JsonElement> = mapOf("severity" to text("s1"))

    @Test
    fun `starting a thread writes the thread, its opening message, its CREATED event and the activity bump together`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-own", "Olivia")
        val member = actor("thr-mem", "Marek")
        val room = room(owner, member)
        val activityBefore = TestThreads.roomActivity(room)

        val fields = mapOf(
            "severity" to text("s2"),
            "note" to text("  hot  "),
            "lead" to JsonPrimitive(owner.id.toLong()),
            "due" to text("2026-12-24"),
        )
        val response = member.client.postJson("$rooms/$room/threads", ThreadCreateRequest("  Disk full  ", "First line\r\nsecond", fields))
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("/api/v1/rooms/$room/threads/1", response.headers["Location"])
        val thread = response.body<ThreadResponse>()
        assertEquals(1, thread.number)
        assertEquals("Disk full", thread.title, "the title is trimmed")
        assertEquals("open", thread.status, "a thread starts in the room's initial status")
        assertEquals(false, thread.closed)
        assertNull(thread.closedAt)
        assertEquals(member.id, thread.ownerId, "the creator owns the thread at first")
        assertEquals("Marek", thread.ownerName)
        assertEquals(member.id, thread.createdById)
        assertEquals("Marek", thread.createdByName)
        assertEquals(1, thread.messageCount, "the opening message counts")
        assertEquals(1L, thread.revision)
        assertEquals(
            mapOf("severity" to text("s2"), "note" to text("hot"), "lead" to JsonPrimitive(owner.id.toLong()), "due" to text("2026-12-24")),
            thread.fields,
        )
        assertEquals(mapOf(owner.id.toString() to "Olivia"), thread.fieldUserNames)
        assertEquals(thread.createdAt, thread.lastActivityAt)
        assertEquals(thread.createdAt, thread.updatedAt)
        assertEquals(thread, member.client.get("$rooms/$room/threads/1").body<ThreadResponse>(), "a read answers what the create answered")

        val messages = TestThreads.rawMessages(thread.id)
        assertEquals(listOf("First line\nsecond"), messages.map { it.body }, "the opening message, CRLF normalised")
        assertEquals(listOf(member.id), messages.map { it.authorId })
        val event = TestThreads.rawEvents(thread.id).single()
        assertEquals("CREATED", event.type)
        assertEquals(member.id, event.userId)
        assertEquals(thread.createdAt, event.createdAt, "the event shares the thread's instant")
        assertEquals(mapOf("title" to "Disk full", "status" to "open", "statusLabel" to "Open"), decodeParams(event.params))
        assertEquals(thread.createdAt, TestThreads.roomActivity(room), "the room's activity is the thread's creation")
        assertTrue(TestThreads.roomActivity(room) > activityBefore)
    }

    @Test
    fun `a direct service caller is sanitised and validated too - a control character is a 400, not a database error`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-direct")
        val room = room(owner)
        assertFailsWith<BadRequestException> { TestThreads.seed(room, owner.id, title = "a\u0000b") }
        assertFailsWith<BadRequestException> { TestThreads.seed(room, owner.id, body = "a\u0000b") }
        assertEquals(emptyList(), TestThreads.rawAll(room))
        assertEquals("T", TestThreads.raw(room, TestThreads.seed(room, owner.id, title = "  T  ").number)!!.title, "the service trims")
    }

    @Test
    fun `thread numbers count per room from 1 and a room's threads never share a number`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-num")
        val first = room(owner)
        val second = room(owner)
        val numbers = listOf(first, first, second, first).map { room ->
            owner.client.postJson("$rooms/$room/threads", ThreadCreateRequest("T", "B", severity)).body<ThreadResponse>().number
        }
        assertEquals(listOf(1, 2, 1, 3), numbers)
        assertEquals(listOf(1, 2, 3), TestThreads.rawAll(first).map { it.number })
        assertEquals(listOf(1), TestThreads.rawAll(second).map { it.number })
    }

    @Test
    fun `every invalid create is a 400 and writes nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-bad")
        val outsider = actor("thr-bad-out")
        val room = room(owner)
        fun request(
            title: String = "T",
            body: String = "B",
            fields: Map<String, JsonElement> = severity,
        ) = ThreadCreateRequest(title, body, fields)
        val rejected = mapOf(
            "a blank title" to request(title = "  "),
            "a title over 200 characters" to request(title = "t".repeat(201)),
            "a newline in the title" to request(title = "a\nb"),
            "a bidi override in the title" to request(title = "a‮b"),
            "a blank body" to request(body = " \n "),
            "a body over 8000 characters" to request(body = "x".repeat(8001)),
            "a control character in the body" to request(body = "a\u0000b"),
            "a line separator in the body" to request(body = "a b"),
            "a missing required field" to request(fields = emptyMap()),
            "a required field set to null" to request(fields = mapOf("severity" to JsonNull)),
            "an unknown field" to request(fields = severity + ("ghost" to text("x"))),
            "a select option that is not one" to request(fields = mapOf("severity" to text("s9"))),
            "a USER value that is not a member" to request(fields = severity + ("lead" to JsonPrimitive(outsider.id.toLong()))),
            "a date that is not a day" to request(fields = severity + ("due" to text("2026-02-30"))),
            "a multi-select that repeats an option" to request(fields = severity + ("tags" to JsonArray(listOf(text("a"), text("a"))))),
            "a text of the wrong JSON type" to request(fields = severity + ("note" to JsonPrimitive(7))),
        )
        val before = TestThreads.roomActivity(room)
        for ((why, body) in rejected) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.postJson("$rooms/$room/threads", body).status, why)
        }
        val malformed = listOf(
            "malformed JSON" to "{ not json",
            "a missing title" to """{"body":"B","fields":{"severity":"s1"}}""",
            "fields that are not an object" to """{"title":"T","body":"B","fields":"x"}""",
        )
        for ((why, body) in malformed) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.postJson("$rooms/$room/threads", body).status, why)
        }
        assertEquals(emptyList(), TestThreads.rawAll(room), "no thread was written")
        assertEquals(before, TestThreads.roomActivity(room), "the room's activity did not move")
    }

    @Test
    fun `an archived room refuses a new thread with the typed room-archived 409 and still reads`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-arch")
        val room = room(owner)
        TestThreads.seed(room, owner.id)
        TestRooms.archive(room)

        val refused = owner.client.postJson("$rooms/$room/threads", ThreadCreateRequest("T", "B", severity))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        assertEquals(1, TestThreads.rawAll(room).size, "nothing new was written")
        assertEquals(1L, owner.client.get("$rooms/$room/threads").body<ThreadPageResponse>().total, "reads still work")
        assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/$room/threads/1").status)
    }

    @Test
    fun `a read shows the active fields, hides a retired one and says closed for a closed status`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-read", "Olivia")
        val room = room(owner)
        val withValues = severity + ("note" to text("keep me")) + ("lead" to JsonPrimitive(owner.id.toLong()))
        val created = TestThreads.seed(room, owner.id, fields = withValues)
        assertEquals(setOf("severity", "note", "lead"), owner.client.get("$rooms/$room/threads/1").body<ThreadResponse>().fields.keys)

        retireField(room, "note")
        TestThreads.closeRaw(room, created.number)
        val thread = owner.client.get("$rooms/$room/threads/1").body<ThreadResponse>()
        assertEquals(setOf("severity", "lead"), thread.fields.keys, "a retired field's value is hidden")
        assertTrue(TestThreads.raw(room, 1)!!.fields.contains("keep me"), "...but kept")
        assertEquals("done", thread.status)
        assertTrue(thread.closed)
        assertNotNull(thread.closedAt)

        assertEquals(HttpStatusCode.NotFound, owner.client.get("$rooms/$room/threads/2").status)
        val pastInt32 = owner.client.get("$rooms/$room/threads/4294967295")
        assertEquals(HttpStatusCode.BadRequest, pastInt32.status, "a number past int32 is refused")
    }

    private fun retireField(room: UInt, key: String) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE room_fields SET marked_as_deleted = TRUE WHERE room_id = ? AND \"key\" = ?").use { st ->
                st.setInt(1, room.toInt())
                st.setString(2, key)
                assertEquals(1, st.executeUpdate())
            }
        }
    }

    @Test
    fun `the list filters by closed, sorts by the whitelist, pages and rejects what it does not know`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-list", "Olivia")
        val member = actor("thr-list-mem", "Marek")
        val room = room(owner, member)
        TestThreads.seed(room, owner.id, title = "Aaa")
        TestThreads.seed(room, member.id, title = "Bbb")
        TestThreads.seed(room, owner.id, title = "Ccc")
        TestThreads.closeRaw(room, 2)
        TestThreads.setStamps(room, 1, createdAt = 2000, lastActivityAt = 3000)
        TestThreads.setStamps(room, 2, createdAt = 3000, lastActivityAt = 1000)
        TestThreads.setStamps(room, 3, createdAt = 1000, lastActivityAt = 2000)
        suspend fun numbers(query: String): List<Int> =
            owner.client.get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.map { it.number }

        val all = owner.client.get("$rooms/$room/threads").body<ThreadPageResponse>()
        assertEquals(listOf(1, 2, 3), all.items.map { it.number }, "the default sort is id ascending")
        assertEquals(3L, all.total)
        val second = all.items[1]
        assertEquals(
            listOf("Bbb", "done", true, member.id, "Marek", 1),
            listOf(second.title, second.status, second.closed, second.ownerId, second.ownerName, second.messageCount),
        )
        assertEquals(listOf(2), numbers("?closed=true"))
        assertEquals(listOf(1, 3), numbers("?closed=false"))
        assertEquals(listOf(1, 3), numbers("?closed=false&sort=number"))
        assertEquals(listOf(3, 2, 1), numbers("?sort=-number"))
        assertEquals(listOf(3, 2, 1), numbers("?sort=-title"))
        assertEquals(listOf(1, 3, 2), numbers("?sort=-lastActivityAt"))
        assertEquals(listOf(2, 1, 3), numbers("?sort=-createdAt"))
        val page = owner.client.get("$rooms/$room/threads?pageSize=2&page=2").body<ThreadPageResponse>()
        assertEquals(listOf(3), page.items.map { it.number })
        assertEquals(3L, page.total, "the total is before pagination")

        for (bad in listOf("?closed=maybe", "?closed=true&closed=false", "?sort=ownerName", "?pageSize=101", "?page=0")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.get("$rooms/$room/threads$bad").status, bad)
        }
    }

    @Test
    fun `a room's threads are its own - the list of another room never shows them`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-own-list")
        val one = room(owner)
        val other = room(owner)
        TestThreads.seed(one, owner.id)
        assertEquals(1L, owner.client.get("$rooms/$one/threads").body<ThreadPageResponse>().total)
        assertEquals(0L, owner.client.get("$rooms/$other/threads").body<ThreadPageResponse>().total)
        assertEquals(HttpStatusCode.NotFound, owner.client.get("$rooms/$other/threads/1").status)
    }

    @Test
    fun `starting a thread moves the room up the rail`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-rail")
        val older = room(owner)
        val newer = room(owner)
        suspend fun rail() = owner.client.get("$rooms?member=true&sort=-lastActivityAt").body<RoomPageResponse>().items
            .map { it.id }.filter { it == older || it == newer }

        TestThreads.seed(older, owner.id)
        assertEquals(listOf(older, newer), rail(), "the room with the newer activity comes first")
        assertTrue(owner.client.get("$rooms/$older").body<RoomResponse>().let { it.lastActivityAt > it.createdAt })
    }

    @Test
    fun `ownerOf reads the owner without a lock and answers null for no such thread`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-ownerof")
        val member = actor("thr-ownerof-mem")
        val room = room(owner, member)
        TestThreads.seed(room, member.id)
        assertEquals(member.id, TestThreads.service.ownerOf(room, 1))
        assertNull(TestThreads.service.ownerOf(room, 2))
    }
}
