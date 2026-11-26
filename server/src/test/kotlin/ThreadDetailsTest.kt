package ch.nokillswit

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.infra.revision.REVISION_CONFLICT_PROBLEM_TYPE
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadUpdateRequest
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
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The thread details PUT (0.3.0 S3, `PUT /rooms/{id}/threads/{number}` under `X-Expected-Revision`): the round trip with its history
 * and activity stamps, the merge semantics, the header and revision guards, the archived and closed 409s, the kernel's 400s through
 * the API, retired values surviving a save, the no-op, the audit line (ids and keys only) and race 3 (a revision bumped under a
 * waiting PUT). Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadDetailsTest {
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

    /** A room of the fixture template owned by [owner] with [members], and thread #1 started by [owner] (severity `s1`). */
    private suspend fun room(owner: RoomActor, vararg members: RoomActor, fields: Map<String, JsonElement>? = null): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(owner.id, template, "Det ${UUID.randomUUID().toString().substring(0, 8)}").also { room ->
            mintedRooms += room
            members.forEach { TestRooms.addMember(room, it.id) }
            TestThreads.seed(room, owner.id, title = "Original", fields = fields)
        }
    }

    private fun text(value: String) = JsonPrimitive(value)

    private suspend fun HttpClient.thread(room: UInt): ThreadResponse = get("$rooms/$room/threads/1").body()

    private suspend fun HttpClient.putThread(
        room: UInt,
        revision: Long?,
        body: ThreadUpdateRequest,
        mutationId: String? = null,
    ): HttpResponse =
        put("$rooms/$room/threads/1") {
            if (revision != null) header(EXPECTED_REVISION_HEADER, revision.toString())
            mutationId?.let { header(MUTATION_ID_HEADER, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.putRaw(room: UInt, revision: String?, json: String): HttpResponse = put("$rooms/$room/threads/1") {
        if (revision != null) header(EXPECTED_REVISION_HEADER, revision)
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private fun update(title: String = "Original", vararg fields: Pair<String, JsonElement>) = ThreadUpdateRequest(title, mapOf(*fields))

    @Test
    fun `a title and fields round trip - the revision moves, one event per change, one instant everywhere`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-own", "Olivia")
        val member = actor("det-mem", "Marek")
        val initial = mapOf("severity" to text("s1"), "note" to text("old note"), "tags" to JsonArray(listOf(text("a"))))
        val room = room(owner, member, fields = initial)
        TestThreads.setStamps(room, 1, createdAt = 1000, lastActivityAt = 1000) // backdated: the bump below must move it
        val before = member.client.thread(room)
        assertEquals(1L, before.revision)

        val body = update(
            "  Renamed  ",
            "severity" to text("s2"),
            "note" to JsonNull,
            "lead" to JsonPrimitive(member.id.toLong()),
            "due" to text("2026-12-24"),
            "tags" to JsonArray(listOf(text("a"), text("b"))),
        )
        assertEquals(HttpStatusCode.NoContent, member.client.putThread(room, 1, body).status)

        val after = member.client.thread(room)
        assertEquals(2L, after.revision)
        assertEquals("Renamed", after.title, "the title is trimmed")
        assertEquals(
            mapOf(
                "severity" to text("s2"),
                "lead" to JsonPrimitive(member.id.toLong()),
                "due" to text("2026-12-24"),
                "tags" to JsonArray(listOf(text("a"), text("b"))),
            ),
            after.fields,
            "null cleared the note, the rest merged",
        )
        assertEquals(mapOf(member.id.toString() to "Marek"), after.fieldUserNames)
        assertEquals(before.messageCount, after.messageCount)
        assertEquals(before.status, after.status)
        assertEquals(before.ownerId, after.ownerId, "a details edit never moves the owner")
        assertEquals(after.updatedAt, after.lastActivityAt, "the thread's stamps share one instant")
        assertTrue(after.lastActivityAt > before.lastActivityAt)
        assertEquals(after.lastActivityAt, TestThreads.roomActivity(room), "the room's activity follows the change")

        val events = TestThreads.rawEvents(after.id).filter { it.type != "CREATED" }
        assertEquals(
            listOf("TITLE_CHANGED", "FIELD_CHANGED", "FIELD_CHANGED", "FIELD_CHANGED", "FIELD_CHANGED", "FIELD_CHANGED"),
            events.map { it.type },
        )
        assertTrue(events.all { it.createdAt == after.lastActivityAt && it.userId == member.id }, "every event shares the write's instant")
        val params = events.map { decodeParams(it.params) }
        assertEquals(emptyMap(), params[0], "a rename records that it happened, never either title")
        val byField = params.drop(1).associateBy { it.getValue("field") }
        assertEquals(mapOf("field" to "severity", "fieldLabel" to "Severity", "from" to "Sev one", "to" to "Sev two"), byField["severity"])
        assertEquals(mapOf("field" to "note", "fieldLabel" to "Note"), byField["note"], "TEXT: the change, never the text")
        assertEquals(mapOf("field" to "lead", "fieldLabel" to "Lead", "to" to "Marek"), byField["lead"], "USER shows the name; no `from`")
        assertEquals(mapOf("field" to "due", "fieldLabel" to "Due", "to" to "2026-12-24"), byField["due"])
        assertEquals(mapOf("field" to "tags", "fieldLabel" to "Tags", "from" to "Tag A", "to" to "Tag A, Tag B"), byField["tags"])
    }

    @Test
    fun `fields merge - a key left out is untouched, and only the title changing is a TITLE_CHANGED alone`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-merge")
        val room = room(owner, fields = mapOf("severity" to text("s1"), "note" to text("keep")))
        assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update("Only the title")).status)
        val thread = owner.client.thread(room)
        assertEquals(mapOf("severity" to text("s1"), "note" to text("keep")), thread.fields)
        assertEquals(listOf("CREATED", "TITLE_CHANGED"), TestThreads.rawEvents(thread.id).map { it.type })
        assertEquals(2L, thread.revision)
    }

    @Test
    fun `the header is required and must be one positive integer - 400 before the body is read`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-hdr")
        val room = room(owner)
        val json = """{"title":"X"}"""
        assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, null, json).status, "missing")
        for (bad in listOf("abc", "0", "-1", "01", "1.5", "99999999999999999999")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, bad, json).status, "malformed: $bad")
        }
        // The header comes first: a missing header with a malformed body is the header's 400 (both 400, one problem).
        assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, null, "{ not json").status)
        assertEquals(1L, owner.client.thread(room).revision)
    }

    @Test
    fun `a stale revision is the typed 409 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-stale")
        val room = room(owner)
        assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update("First")).status)
        val activity = TestThreads.roomActivity(room)

        val stale = owner.client.putThread(room, 1, update("Second", "note" to text("late")))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, stale.body<ProblemDetail>().type)
        // A stale revision wins over the no-op too: the compare comes before the kernel.
        assertEquals(HttpStatusCode.Conflict, owner.client.putThread(room, 1, update("First")).status)
        val thread = owner.client.thread(room)
        assertEquals("First", thread.title)
        assertEquals(2L, thread.revision)
        assertEquals(activity, TestThreads.roomActivity(room))
        assertEquals(2, TestThreads.rawEvents(thread.id).size)
    }

    @Test
    fun `an archived room and a closed thread are typed 409s - the archive is checked first`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-state")
        val room = room(owner)
        TestThreads.closeRaw(room, 1)

        val closed = owner.client.putThread(room, 1, update("Closed edit"))
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals(THREAD_CLOSED_PROBLEM_TYPE, closed.body<ProblemDetail>().type)
        // A closed thread is refused before the revision is compared: a stale token still reads thread-closed.
        assertEquals(THREAD_CLOSED_PROBLEM_TYPE, owner.client.putThread(room, 99, update("Closed edit")).body<ProblemDetail>().type)

        TestRooms.archive(room)
        val archived = owner.client.putThread(room, 1, update("Archived edit"))
        assertEquals(HttpStatusCode.Conflict, archived.status)
        assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, archived.body<ProblemDetail>().type, "archived wins over closed")
        assertEquals("Original", owner.client.thread(room).title)
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(owner.client.thread(room).id).map { it.type })
    }

    @Test
    fun `the kernel's 400s come through the API and write nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-400")
        val outsider = actor("det-400-out")
        val room = room(owner)
        val rejected = mapOf(
            "a blank title" to update("  "),
            "a title over 200 characters" to update("t".repeat(201)),
            "a newline in the title" to update("a\nb"),
            "an unknown field" to update("Original", "ghost" to text("x")),
            "a select option that is not one" to update("Original", "severity" to text("s9")),
            "a required field cleared" to update("Original", "severity" to JsonNull),
            "a USER value that is not a member" to update("Original", "lead" to JsonPrimitive(outsider.id.toLong())),
            "a date that is not a day" to update("Original", "due" to text("2026-02-30")),
            "a text of the wrong JSON type" to update("Original", "note" to JsonPrimitive(7)),
        )
        for ((why, body) in rejected) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.putThread(room, 1, body).status, why)
        }
        val malformed = mapOf(
            "malformed JSON" to "{ not json",
            "a missing title" to """{"fields":{}}""",
            "fields not an object" to """{"title":"T","fields":3}""",
        )
        for ((why, json) in malformed) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, "1", json).status, why)
        }
        val thread = owner.client.thread(room)
        assertEquals(1L, thread.revision)
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(thread.id).map { it.type })
    }

    @Test
    fun `a retired field's value survives a save - an unchanged one is ignored, a changed one refused`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-retired")
        val room = room(owner, fields = mapOf("severity" to text("s1"), "note" to text("hidden value")))
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE room_fields SET marked_as_deleted = TRUE WHERE room_id = ? AND \"key\" = 'note'").use { st ->
                st.setInt(1, room.toInt())
                assertEquals(1, st.executeUpdate())
            }
        }
        assertEquals(setOf("severity"), owner.client.thread(room).fields.keys, "hidden on read")

        assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update("Kept", "note" to text("hidden value"))).status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.putThread(room, 2, update("Kept", "note" to text("changed"))).status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.putThread(room, 2, update("Kept", "note" to JsonNull)).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 2, update("Kept again", "severity" to text("s2"))).status)
        val stored = TestThreads.raw(room, 1)!!.fields
        assertTrue("hidden value" in stored, "the retired value is still stored: $stored")
        assertEquals(3L, TestThreads.raw(room, 1)!!.revision)
    }

    @Test
    fun `nothing changed is a 204 with no revision bump, no event, no activity bump and no audit line`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-noop")
        val room = room(owner, fields = mapOf("severity" to text("s1"), "note" to text("same")))
        val before = TestThreads.raw(room, 1)!!
        val activity = TestThreads.roomActivity(room)

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update("Original", "note" to text("  same  "))).status)
            assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update("Original")).status)
            assertTrue(capture.events.none { it.message == "thread.updated" }, "a no-op audits nothing")
        }
        assertEquals(before, TestThreads.raw(room, 1), "the row is untouched, updated_at included")
        assertEquals(activity, TestThreads.roomActivity(room))
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(before.id).map { it.type })
    }

    @Test
    fun `a details change correlates one thread updated hint while a no-op and refusal emit none`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-echo")
        val room = room(owner)
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"

        NotifyCapture().use { capture ->
            val hints = capture.during {
                owner.client.putThread(room, 1, update("Never"), mutationId.uppercase()).also {
                    assertEquals(HttpStatusCode.BadRequest, it.status)
                }
                owner.client.putThread(room, 1, update("Changed"), mutationId).also {
                    assertEquals(HttpStatusCode.NoContent, it.status)
                }
                owner.client.putThread(room, 2, update("Changed"), mutationId).also {
                    assertEquals(HttpStatusCode.NoContent, it.status)
                }
                owner.client.putThread(room, 1, update("Refused"), mutationId).also {
                    assertEquals(HttpStatusCode.Conflict, it.status)
                }
            }
            assertEquals(
                listOf(LiveHint(LiveKind.THREAD_UPDATED, room, threadNumber = 1, mutationId = mutationId, originUserId = owner.id)),
                hints,
            )
        }
    }

    @Test
    fun `a change audits thread updated with ids and keys only - never the title or a value`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-aud")
        val room = room(owner)
        withAuditCapture { capture ->
            val title = "Secret title ${UUID.randomUUID()}"
            val value = "Secret value ${UUID.randomUUID()}"
            assertEquals(HttpStatusCode.NoContent, owner.client.putThread(room, 1, update(title, "note" to text(value))).status)
            val event = capture.awaitEvent { it.message == "thread.updated" && it.hasKeyValue("roomId", room.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(event.hasKeyValue("threadId", owner.client.thread(room).id.toLong()))
            assertTrue(event.hasKeyValue("number", 1))
            assertTrue(event.hasKeyValue("revision", 2L))
            assertTrue(event.hasKeyValue("titleChanged", true))
            assertTrue(event.hasKeyValue("fieldsChanged", "note"))
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue(title !in everything && value !in everything, "no title or value reaches the audit trail")
            // A refused PUT (stale revision) audits no update.
            assertEquals(HttpStatusCode.Conflict, owner.client.putThread(room, 1, update("Other")).status)
            assertEquals(1, capture.events.count { it.message == "thread.updated" })
        }
    }

    @Test
    fun `an unknown thread is 404 after the header and body checks, and another room's number is unreachable`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-404")
        val room = room(owner)
        assertEquals(HttpStatusCode.NotFound, owner.client.put("$rooms/$room/threads/2") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(update())
        }.status)
        assertEquals(HttpStatusCode.NotFound, owner.client.put("$rooms/999999/threads/1") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(update())
        }.status)
    }

    @Test
    fun `race 3 - a revision bumped while the PUT waits on the room lock is a 409 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("det-race3")
        val room = room(owner)
        val activity = TestThreads.roomActivity(room)

        val bump = "UPDATE threads SET revision = revision + 1 WHERE room_id = ? AND \"number\" = ?"
        TestThreads.holdingRoomLock(room, bump, room, 1u) { raw ->
            coroutineScope {
                // The route passes its checks (the bump is uncommitted); the service then waits for the room lock.
                val putting = async { owner.client.putThread(room, 1, update("Raced")) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                val response = putting.await()
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
        }
        val thread = owner.client.thread(room)
        assertEquals("Original", thread.title, "the refused PUT wrote nothing")
        assertEquals(2L, thread.revision, "only the rival's bump")
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(thread.id).map { it.type })
        assertEquals(activity, TestThreads.roomActivity(room))
    }
}
