package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadEventPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The thread status PUT (0.3.0 S4, `PUT /rooms/{id}/threads/{number}/status`): moves between OPEN statuses by any reader, close, reopen and
 * CLOSED to CLOSED by a steward only (the thread owner while a member, the room OWNER, an ADMIN; D1), `closedAt` kept in step, the reopen
 * in the history (binding), the 400s, the archived 409, the no-op, the audit line and race 4 (the thread closed under a member's OPEN to
 * OPEN move). The fixture workflow is `open` (initial), `monitoring` (OPEN), `done` and `wontdo` (CLOSED). Rooms, templates and users are
 * SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadStatusTest {
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

    private class Cast(val owner: RoomActor, val threadOwner: RoomActor, val member: RoomActor, val admin: RoomActor, val room: UInt)

    /** A room owned by `owner`, with thread #1 started by `threadOwner` (a plain member), another plain `member` and a non-member ADMIN. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String, role: UserRole = UserRole.USER, name: String = "Test") =
            seedActor("$prefix-$suffix", role, name).also { mintedUsers += it.id }
        val owner = actor("own", name = "Olivia")
        val threadOwner = actor("thr", name = "Tomasz")
        val member = actor("mem", name = "Marek")
        val admin = actor("adm", UserRole.ADMIN)
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(
            owner.id, template, "Sts ${UUID.randomUUID().toString().substring(0, 8)}", accessType = RoomAccessType.PUBLIC,
        ).also { mintedRooms += it }
        TestRooms.addMember(room, threadOwner.id)
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, threadOwner.id)
        return Cast(owner, threadOwner, member, admin, room)
    }

    private suspend fun HttpClient.putStatus(room: UInt, status: String): HttpResponse =
        put("$rooms/$room/threads/1/status") {
            contentType(ContentType.Application.Json)
            setBody(ThreadStatusRequest(status))
        }

    private suspend fun HttpClient.thread(room: UInt): ThreadResponse = get("$rooms/$room/threads/1").body()

    private fun eventTypes(threadId: UInt) = runBlocking { TestThreads.rawEvents(threadId).map { it.type } }

    @Test
    fun `any reader moves a thread between OPEN statuses - the event and the stamps share one instant`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-move")
        TestThreads.setStamps(cast.room, 1, createdAt = 1000, lastActivityAt = 1000) // backdated: the bump below must move it
        val before = cast.member.client.thread(cast.room)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.putStatus(cast.room, "monitoring").status)

        val after = cast.member.client.thread(cast.room)
        assertEquals("monitoring", after.status)
        assertEquals(false, after.closed)
        assertNull(after.closedAt)
        assertEquals(before.revision, after.revision, "a status move is not a details edit: the revision stays")
        assertEquals(before.ownerId, after.ownerId)
        assertEquals(after.updatedAt, after.lastActivityAt)
        assertTrue(after.lastActivityAt > before.lastActivityAt)
        assertEquals(after.lastActivityAt, TestThreads.roomActivity(cast.room))
        val event = TestThreads.rawEvents(after.id).single { it.type != "CREATED" }
        assertEquals("STATUS_CHANGED", event.type)
        assertEquals(cast.member.id, event.userId)
        assertEquals(after.lastActivityAt, event.createdAt)
        val expected = mapOf("from" to "open", "to" to "monitoring", "fromLabel" to "Open", "toLabel" to "Monitoring")
        assertEquals(expected, decodeParams(event.params))

        // An administrator outside the room moves it too (a reader), and the move back is just as plain.
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.putStatus(cast.room, "open").status)
        assertEquals("open", cast.owner.client.thread(cast.room).status)
    }

    @Test
    fun `close and reopen by each steward keep closedAt in step and are in the history`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-steward")
        val id = cast.owner.client.thread(cast.room).id

        // The thread owner (a plain member) closes; the room owner reopens; an ADMIN closes; the thread owner reopens.
        assertEquals(HttpStatusCode.NoContent, cast.threadOwner.client.putStatus(cast.room, "done").status)
        val closed = cast.owner.client.thread(cast.room)
        assertEquals(listOf("done", true), listOf(closed.status, closed.closed))
        assertEquals(closed.updatedAt, closed.closedAt, "closed_at is the instant of the move into CLOSED")

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putStatus(cast.room, "open").status)
        val reopened = cast.owner.client.thread(cast.room)
        assertEquals(listOf("open", false), listOf(reopened.status, reopened.closed))
        assertNull(reopened.closedAt, "a move into an OPEN status clears closed_at")

        assertEquals(HttpStatusCode.NoContent, cast.admin.client.putStatus(cast.room, "wontdo").status)
        assertEquals(HttpStatusCode.NoContent, cast.threadOwner.client.putStatus(cast.room, "monitoring").status)
        assertNull(cast.owner.client.thread(cast.room).closedAt)

        assertEquals(listOf("CREATED", "CLOSED", "REOPENED", "CLOSED", "REOPENED"), eventTypes(id))
        // The reopen is in the history the API serves, with who and the label snapshots (the binding rule).
        val history = cast.member.client.get("$rooms/${cast.room}/threads/1/events?sort=id").body<ThreadEventPageResponse>().items
        val reopen = history.last()
        assertEquals(listOf("REOPENED", cast.threadOwner.id), listOf(reopen.type.name, reopen.actorId))
        assertEquals(mapOf("from" to "wontdo", "to" to "monitoring", "fromLabel" to "Won't do", "toLabel" to "Monitoring"), reopen.params)
        assertEquals(mapOf("from" to "open", "to" to "wontdo", "fromLabel" to "Open", "toLabel" to "Won't do"), history[3].params)
    }

    @Test
    fun `a move between CLOSED statuses is a steward's STATUS_CHANGED and keeps the original closedAt`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-cc")
        val closedAt = 1000L // backdated: the move below must not re-stamp it
        TestThreads.closeRaw(cast.room, 1, closedAt = closedAt)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putStatus(cast.room, "wontdo").status)
        val thread = cast.owner.client.thread(cast.room)
        assertEquals(listOf("wontdo", true), listOf(thread.status, thread.closed))
        assertEquals(closedAt, thread.closedAt, "the instant the thread closed survives a CLOSED to CLOSED move")
        assertTrue(thread.updatedAt > closedAt, "...while the move itself is stamped")
        assertEquals(listOf("CREATED", "STATUS_CHANGED"), eventTypes(thread.id))
    }

    @Test
    fun `a plain member cannot close, reopen or move a closed thread - 403 even for the status it already has`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-403")
        val id = cast.owner.client.thread(cast.room).id
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "done").status, "close")
        assertEquals("open", cast.owner.client.thread(cast.room).status)

        TestThreads.closeRaw(cast.room, 1)
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "open").status, "reopen")
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "wontdo").status, "CLOSED to CLOSED")
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "done").status, "the same CLOSED status: 403 first")
        val thread = cast.owner.client.thread(cast.room)
        assertEquals(listOf("done", true), listOf(thread.status, thread.closed))
        assertEquals(listOf("CREATED"), eventTypes(id), "nothing was written")
    }

    @Test
    fun `setting the status it already has is a 204 with no event, no audit line and no bump`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-noop")
        val before = TestThreads.raw(cast.room, 1)!!
        val activity = TestThreads.roomActivity(cast.room)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.member.client.putStatus(cast.room, "open").status)
            TestThreads.closeRaw(cast.room, 1)
            val closedRow = TestThreads.raw(cast.room, 1)!!
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putStatus(cast.room, "done").status, "a steward's no-op")
            assertEquals(closedRow, TestThreads.raw(cast.room, 1), "the row is untouched")
            assertTrue(capture.events.none { it.message == "thread.status_changed" })
        }
        assertEquals(before.lastActivityAt, TestThreads.raw(cast.room, 1)!!.lastActivityAt)
        assertEquals(activity, TestThreads.roomActivity(cast.room))
        assertEquals(listOf("CREATED"), eventTypes(before.id))
    }

    @Test
    fun `an unknown, retired or malformed status is a 400 for every reader`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-400")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE room_statuses SET marked_as_deleted = TRUE WHERE room_id = ? AND \"key\" = 'wontdo'").use { st ->
                st.setInt(1, cast.room.toInt())
                assertEquals(1, st.executeUpdate())
            }
        }
        for (key in listOf("ghost", "wontdo", "", "Open", "x".repeat(500))) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.putStatus(cast.room, key).status, "key '${key.take(10)}'")
            assertEquals(HttpStatusCode.BadRequest, cast.admin.client.putStatus(cast.room, key).status, "admin, key '${key.take(10)}'")
        }
        for (json in listOf("{ not json", "{}", """{"status":5}""")) {
            val response = cast.member.client.put("$rooms/${cast.room}/threads/1/status") {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, json)
        }
        assertEquals("open", cast.owner.client.thread(cast.room).status)
    }

    @Test
    fun `an archived room refuses every move with the typed 409 - the no-op included - and a missing thread is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-arch")
        TestRooms.archive(cast.room)
        for (status in listOf("monitoring", "open")) {
            val refused = cast.owner.client.putStatus(cast.room, status)
            assertEquals(HttpStatusCode.Conflict, refused.status, status)
            assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        }
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "done").status, "403 comes before the archived 409")
        assertEquals("open", cast.owner.client.thread(cast.room).status)
        val missing = cast.owner.client.put("$rooms/${cast.room}/threads/9/status") {
            contentType(ContentType.Application.Json)
            setBody(ThreadStatusRequest("open"))
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun `a move audits thread status_changed with ids and keys, and a refused one audits no thread line`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-aud")
        val threadId = cast.owner.client.thread(cast.room).id.toLong()
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.member.client.putStatus(cast.room, "monitoring").status)
            assertEquals(HttpStatusCode.NoContent, cast.threadOwner.client.putStatus(cast.room, "done").status)
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putStatus(cast.room, "open").status)
            assertEquals(HttpStatusCode.Forbidden, cast.member.client.putStatus(cast.room, "done").status)
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.putStatus(cast.room, "ghost").status)
            val lines = capture.events.filter { it.message == "thread.status_changed" && it.hasKeyValue("roomId", cast.room.toLong()) }
            assertEquals(3, lines.size, "only the three writes audit")
            val expected = listOf(
                Triple("open", "monitoring", "MOVED") to cast.member.id,
                Triple("monitoring", "done", "CLOSED") to cast.threadOwner.id,
                Triple("done", "open", "REOPENED") to cast.owner.id,
            )
            lines.zip(expected).forEach { (line, want) ->
                val (keys, actor) = want
                assertTrue(line.hasKeyValue("from", keys.first) && line.hasKeyValue("to", keys.second), "from/to of ${keys.third}")
                assertTrue(line.hasKeyValue("transition", keys.third))
                assertTrue(line.hasKeyValue("byUserId", actor.toLong()))
                assertTrue(line.hasKeyValue("threadId", threadId) && line.hasKeyValue("number", 1))
            }
        }
    }

    @Test
    fun `race 4 - a thread closed under a member's OPEN to OPEN move is a 403 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("sts-race4")
        val activity = TestThreads.roomActivity(cast.room)

        TestThreads.holdingRoomLock(
            cast.room,
            "UPDATE threads SET status_key = 'done', closed_at = ? WHERE room_id = ? AND \"number\" = ?",
            1_000u, cast.room, 1u,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the close is uncommitted: the thread is still open); the move then waits for the room lock.
                val moving = async { cast.member.client.putStatus(cast.room, "monitoring") }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.Forbidden, moving.await().status)
            }
        }
        val thread = TestThreads.raw(cast.room, 1)!!
        assertEquals(listOf("done", 1_000L), listOf(thread.statusKey, thread.closedAt), "only the rival's close")
        assertEquals(listOf("CREATED"), eventTypes(thread.id))
        assertEquals(activity, TestThreads.roomActivity(cast.room))
        assertNotNull(thread.closedAt)
    }
}
