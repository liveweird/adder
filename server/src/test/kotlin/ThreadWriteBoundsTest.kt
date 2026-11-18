package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * What surrounds a thread write (0.3.0 S2): the audit line (ids only, never the title or the body), `room.access_denied` for an outsider's
 * probes, the per-user `chat-post` bucket, and the two lock-wait races the room lock settles - two creates at once, and a creator removed
 * from the room while their create waits. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows.
 */
class ThreadWriteBoundsTest {
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

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER) =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun room(owner: RoomActor, vararg members: RoomActor): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(owner.id, template, "Bnd ${UUID.randomUUID().toString().substring(0, 8)}").also { room ->
            mintedRooms += room
            members.forEach { TestRooms.addMember(room, it.id) }
        }
    }

    private val severity: Map<String, JsonElement> = mapOf("severity" to JsonPrimitive("s1"))
    private val valid = ThreadCreateRequest("T", "B", severity)

    @Test
    fun `creating a thread audits ids only, once, and an outsider's probes audit room access_denied`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-aud")
        val outsider = actor("thr-aud-out")
        val admin = actor("thr-aud-admin", UserRole.ADMIN)
        val room = room(owner)

        withAuditCapture { capture ->
            val title = "Secret title ${UUID.randomUUID()}"
            val body = "Secret body ${UUID.randomUUID()}"
            val thread = owner.client.postJson("$rooms/$room/threads", ThreadCreateRequest(title, body, severity)).body<ThreadResponse>()
            val created = capture.awaitEvent { it.message == "thread.created" && it.hasKeyValue("roomId", room.toLong()) }
            assertNotNull(created)
            assertTrue(created.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(created.hasKeyValue("threadId", thread.id.toLong()))
            assertTrue(created.hasKeyValue("number", 1))
            assertTrue(created.hasKeyValue("messageId", TestThreads.rawMessages(thread.id).single().id.toLong()))
            assertEquals(1, capture.events.count { it.message == "thread.created" && it.hasKeyValue("roomId", room.toLong()) })
            assertTrue(capture.events.none { it.message == "message.posted" }, "the opening message has no line of its own")
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue(title !in everything && body !in everything, "no title or body reaches the audit trail")

            // The outsider's four probes are 404 and audited; an admin outside the room reads without a line, and a 403 has none.
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/$room/threads").status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/$room/threads/1").status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/$room/threads/1/events").status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.postJson("$rooms/$room/threads", valid).status)
            assertEquals(HttpStatusCode.OK, admin.client.get("$rooms/$room/threads").status)
            assertEquals(HttpStatusCode.Forbidden, admin.client.postJson("$rooms/$room/threads", valid).status)
            val denied = capture.events.filter { it.message == "room.access_denied" && it.hasKeyValue("roomId", room.toLong()) }
            assertEquals(4, denied.size)
            assertTrue(denied.all { it.hasKeyValue("byUserId", outsider.id.toLong()) })
            assertEquals(1, capture.events.count { it.message == "thread.created" }, "a refused create audits no creation")
        }
        assertEquals(1, TestThreads.rawAll(room).size)
    }

    @Test
    fun `120 posts a minute per user, then 429 with its audit line - another user and the reads are untouched`() = testApplication {
        usePostgresTestcontainer()
        val limited = actor("thr-bucket")
        val other = actor("thr-bucket-other")
        val room = room(limited, other)
        val invalid = ThreadCreateRequest(" ", "B", severity) // a 400 after the bucket: every attempt spends a token

        withAuditCapture { capture ->
            repeat(120) { n ->
                assertEquals(HttpStatusCode.BadRequest, limited.client.postJson("$rooms/$room/threads", invalid).status, "request ${n + 1}")
            }
            val rejected = limited.client.postJson("$rooms/$room/threads", invalid)
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals(429, rejected.body<ProblemDetail>().status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-post") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            assertEquals(HttpStatusCode.Created, other.client.postJson("$rooms/$room/threads", valid).status)
            assertEquals(HttpStatusCode.OK, limited.client.get("$rooms/$room/threads").status, "reads are not bucketed")
        }
    }

    @Test
    fun `two creates waiting on the room lock take consecutive numbers and write two CREATED events`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-race1")
        val member = actor("thr-race1-mem")
        val room = room(owner, member)

        holdingRoomLock(room) { raw ->
            coroutineScope {
                val creates = listOf(owner, member).map { who ->
                    async { who.client.postJson("$rooms/$room/threads", ThreadCreateRequest("Raced", "B", severity)) }
                }
                awaitLockWait("FOR NO KEY UPDATE", waiters = 2)
                raw.commit()
                val threads = creates.awaitAll().map { response ->
                    assertEquals(HttpStatusCode.Created, response.status)
                    response.body<ThreadResponse>()
                }
                assertEquals(listOf(1, 2), threads.map { it.number }.sorted(), "consecutive numbers, none twice")
                threads.forEach { thread ->
                    assertEquals(listOf("CREATED"), TestThreads.rawEvents(thread.id).map { it.type })
                    assertEquals(1, TestThreads.rawMessages(thread.id).size)
                }
            }
        }
        assertEquals(listOf(1, 2), TestThreads.rawAll(room).map { it.number })
    }

    @Test
    fun `a creator removed from the room while their create waits for the lock gets 404 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("thr-race2")
        val member = actor("thr-race2-mem")
        val room = room(owner, member)
        val activityBefore = TestThreads.roomActivity(room)

        holdingRoomLock(room, "DELETE FROM room_members WHERE room_id = ? AND user_id = ?", room, member.id) { raw ->
            coroutineScope {
                // The route's own pre-check passes (the removal is uncommitted); the create then waits for the room lock.
                val creating = async { member.client.postJson("$rooms/$room/threads", ThreadCreateRequest("Raced", "B", severity)) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, creating.await().status)
            }
        }
        assertEquals(emptyList(), TestThreads.rawAll(room), "the refused create wrote nothing")
        assertEquals(activityBefore, TestThreads.roomActivity(room))
        assertNull(TestRooms.rawRole(room, member.id))
    }

    /** Holds the room lock on a raw transaction (that also runs [statement] bound to [ids], when given) until [body] commits it. */
    private suspend fun holdingRoomLock(
        roomId: UInt,
        statement: String? = null,
        vararg ids: UInt,
        body: suspend (java.sql.Connection) -> Unit,
    ) {
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, roomId.toInt())
                st.executeQuery().close()
            }
            statement?.let {
                raw.prepareStatement(it).use { st ->
                    ids.forEachIndexed { index, id -> st.setLong(index + 1, id.toLong()) }
                    st.executeUpdate()
                }
            }
            try {
                body(raw)
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
    }
}
