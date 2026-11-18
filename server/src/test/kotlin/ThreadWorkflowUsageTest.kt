package ch.nokillswit

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.rooms.STATUS_IN_USE_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
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
import java.sql.SQLException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The REAL `WorkflowUsage` port (0.3.0 S6, `threads/ThreadWorkflowUsage.kt`) behind the room workflow's GET and PUT: the per-status
 * counts, retiring a status that threads use with a replacement (the threads move, one `STATUS_REPLACED` event each, the audit counts
 * them), a replacement the same PUT adds or revives (the order pin: upsert, then move), the `409 status-in-use` from real counts, the
 * composite status FK that backs it, `closed_at` kept across a CLOSED to CLOSED replacement, and race 6 (the target of a waiting
 * status move retired). The fixture workflow is `open` (initial), `monitoring` (OPEN), `done` and `wontdo` (CLOSED). Rooms, templates
 * and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadWorkflowUsageTest {
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

    private class Cast(val owner: RoomActor, val member: RoomActor, val room: UInt)

    /** A room owned by `owner` (thread rights as a room OWNER) with a plain `member`; no thread yet. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        val owner = seedActor("$prefix-own", name = "Olivia").also { mintedUsers += it.id }
        val member = seedActor("$prefix-mem", name = "Marek").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Usage ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, member.id)
        return Cast(owner, member, room)
    }

    /** Starts [count] threads (numbers 1..count) and moves thread `n` to `statuses[n - 1]` when that is not the initial `open`. */
    private suspend fun Cast.threadsIn(vararg statuses: String) {
        statuses.forEachIndexed { index, status ->
            TestThreads.seed(room, owner.id)
            if (status != "open") assertEquals(HttpStatusCode.NoContent, owner.client.putStatus(room, index + 1, status).status)
        }
    }

    private suspend fun HttpClient.putStatus(room: UInt, number: Int, status: String): HttpResponse =
        put("$rooms/$room/threads/$number/status") {
            contentType(ContentType.Application.Json)
            setBody(ThreadStatusRequest(status))
        }

    private suspend fun HttpClient.workflow(room: UInt): RoomWorkflowResponse = get("$rooms/$room/workflow").body()

    private suspend fun HttpClient.thread(room: UInt, number: Int): ThreadResponse = get("$rooms/$room/threads/$number").body()

    private suspend fun HttpClient.putWorkflow(room: UInt, revision: Long, body: RoomWorkflowRequest): HttpResponse =
        put("$rooms/$room/workflow") {
            header(EXPECTED_REVISION_HEADER, revision.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** The document the room has now, as the PUT body that would change nothing. */
    private fun RoomWorkflowResponse.asRequest() = RoomWorkflowRequest(
        statuses = statuses.map { RoomStatusDef(it.key, it.label, it.colour, it.category, it.initial) },
        fields = fields.map { RoomFieldDef(it.key, it.label, it.type, it.required, it.options) },
    )

    private fun RoomWorkflowRequest.without(key: String) = copy(statuses = statuses.filter { it.key != key })

    private fun RoomWorkflowRequest.replacing(from: String, to: String) = copy(replacements = mapOf(from to to))

    private fun replacedEvents(threadId: UInt) = runBlocking { TestThreads.rawEvents(threadId).filter { it.type == "STATUS_REPLACED" } }

    @Test
    fun `the workflow read counts the room's threads per status - and no other room's`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-count")
        val other = cast("usg-count2")
        assertTrue(cast.owner.client.workflow(cast.room).statuses.all { it.threadCount == 0L }, "a new room counts nothing")
        cast.threadsIn("open", "monitoring", "monitoring", "done")
        other.threadsIn("monitoring")

        val counts = cast.member.client.workflow(cast.room).statuses.associate { it.key to it.threadCount }
        assertEquals(mapOf("open" to 1L, "monitoring" to 2L, "done" to 1L, "wontdo" to 0L), counts)
    }

    @Test
    fun `retiring a status with a replacement moves its threads - one STATUS_REPLACED event each, no activity, audit threadsMoved`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("usg-move")
            cast.threadsIn("monitoring", "monitoring", "open")
            // Backdated, so a move that stamps `updated_at` is told apart from one that does not, whatever the clock resolution.
            (1..3).forEach { TestThreads.setStamps(cast.room, it, createdAt = 1_000, lastActivityAt = 1_000, updatedAt = 1_000) }
            val before = (1..3).map { cast.owner.client.thread(cast.room, it) }
            val roomActivity = TestThreads.roomActivity(cast.room)

            withAuditCapture { capture ->
                val current = cast.owner.client.workflow(cast.room).asRequest()
                val response = cast.owner.client.putWorkflow(cast.room, 1, current.without("monitoring").replacing("monitoring", "open"))
                assertEquals(HttpStatusCode.NoContent, response.status)
                val line = checkNotNull(
                    capture.awaitEvent { it.message == "room.workflow_updated" && it.hasKeyValue("roomId", cast.room.toLong()) },
                )
                assertTrue(line.hasKeyValue("threadsMoved", 2L))
                assertTrue(line.hasKeyValue("statusesRetired", 1))
            }

            val after = (1..3).map { cast.owner.client.thread(cast.room, it) }
            assertEquals(listOf("open", "open", "open"), after.map { it.status })
            assertEquals(listOf(false, false, false), after.map { it.closed })
            after.zip(before).forEach { (now, was) ->
                assertEquals(was.revision, now.revision, "a workflow move is not a details edit")
                assertEquals(was.lastActivityAt, now.lastActivityAt, "a workflow move is not activity")
                assertEquals(was.messageCount, now.messageCount)
            }
            assertEquals(roomActivity, TestThreads.roomActivity(cast.room), "the rail order does not move")
            assertTrue(after[0].updatedAt > before[0].updatedAt && after[1].updatedAt > before[1].updatedAt)
            assertEquals(before[2].updatedAt, after[2].updatedAt, "the thread already in `open` is not touched")

            for (n in 0..1) {
                val event = replacedEvents(after[n].id).single()
                assertEquals(cast.owner.id, event.userId)
                assertEquals(after[n].updatedAt, event.createdAt, "the event and the move share one instant")
                val expected = mapOf("from" to "monitoring", "to" to "open", "fromLabel" to "Monitoring", "toLabel" to "Open")
                assertEquals(expected, decodeParams(event.params))
            }
            assertTrue(replacedEvents(after[2].id).isEmpty())
            val workflow = cast.owner.client.workflow(cast.room)
            assertEquals(2, workflow.revision)
            assertEquals(3L, workflow.statuses.single { it.key == "open" }.threadCount)
        }

    @Test
    fun `one PUT retires two statuses that hold threads - each moves to its own replacement with its own labels`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-two")
        cast.threadsIn("monitoring", "monitoring", "wontdo", "open")
        val current = cast.owner.client.workflow(cast.room).asRequest()

        withAuditCapture { capture ->
            val mapping = mapOf("monitoring" to "open", "wontdo" to "done")
            val twice = current.without("monitoring").without("wontdo").copy(replacements = mapping)
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putWorkflow(cast.room, 1, twice).status)
            val line = checkNotNull(
                capture.awaitEvent { it.message == "room.workflow_updated" && it.hasKeyValue("roomId", cast.room.toLong()) },
            )
            assertTrue(line.hasKeyValue("threadsMoved", 3L))
            assertTrue(line.hasKeyValue("statusesRetired", 2))
        }

        val threads = (1..4).map { cast.owner.client.thread(cast.room, it) }
        assertEquals(listOf("open", "open", "done", "open"), threads.map { it.status })
        assertTrue(threads[2].closed, "the CLOSED thread stays closed")
        val params = threads.map { t -> replacedEvents(t.id).map { decodeParams(it.params) } }
        val toOpen = mapOf("from" to "monitoring", "to" to "open", "fromLabel" to "Monitoring", "toLabel" to "Open")
        val toDone = mapOf("from" to "wontdo", "to" to "done", "fromLabel" to "Won't do", "toLabel" to "Done")
        assertEquals(listOf(listOf(toOpen), listOf(toOpen), listOf(toDone), emptyList()), params)
        val counts = cast.owner.client.workflow(cast.room).statuses.associate { it.key to it.threadCount }
        assertEquals(mapOf("open" to 3L, "done" to 1L), counts.filterValues { it > 0 })
    }

    @Test
    fun `the replacement may be a status the same PUT adds or revives - the upsert runs before the move`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-order")
        cast.threadsIn("monitoring", "open")
        val current = cast.owner.client.workflow(cast.room).asRequest()

        // `monitoring` retires and its thread goes to `blocked`, a status that does not exist until this very PUT.
        val blocked = RoomStatusDef("blocked", "Blocked", StatusColour.RED, StatusCategory.OPEN, initial = false)
        val added = current.without("monitoring").copy(statuses = current.without("monitoring").statuses + blocked)
        val addResponse = cast.owner.client.putWorkflow(cast.room, 1, added.replacing("monitoring", "blocked"))
        assertEquals(HttpStatusCode.NoContent, addResponse.status)
        val moved = cast.owner.client.thread(cast.room, 1)
        assertEquals("blocked", moved.status)
        val addedEvent = replacedEvents(moved.id).single()
        val expected = mapOf("from" to "monitoring", "to" to "blocked", "fromLabel" to "Monitoring", "toLabel" to "Blocked")
        assertEquals(expected, decodeParams(addedEvent.params))

        // The same with a REVIVED target: `blocked` retires and `monitoring` (retired above) returns in the same PUT to take its thread.
        val revived = cast.owner.client.workflow(cast.room).asRequest().let { now ->
            val back = RoomStatusDef("monitoring", "Monitoring again", StatusColour.BLUE, StatusCategory.OPEN, initial = false)
            now.copy(statuses = now.without("blocked").statuses + back).replacing("blocked", "monitoring")
        }
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putWorkflow(cast.room, 2, revived).status)
        assertEquals("monitoring", cast.owner.client.thread(cast.room, 1).status)
        val labels = replacedEvents(moved.id).map { decodeParams(it.params).getValue("toLabel") }
        assertEquals(listOf("Blocked", "Monitoring again"), labels)
        val counts = cast.owner.client.workflow(cast.room).statuses.associate { it.key to it.threadCount }
        assertEquals(mapOf("open" to 1L, "monitoring" to 1L, "done" to 0L, "wontdo" to 0L), counts)
    }

    @Test
    fun `a status that threads use cannot be retired or re-categorised without a replacement - 409 status-in-use from real counts`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("usg-inuse")
            cast.threadsIn("monitoring")
            val current = cast.owner.client.workflow(cast.room).asRequest()

            val retire = cast.owner.client.putWorkflow(cast.room, 1, current.without("monitoring"))
            assertEquals(HttpStatusCode.Conflict, retire.status)
            val problem = retire.body<ProblemDetail>()
            assertEquals(STATUS_IN_USE_PROBLEM_TYPE, problem.type)
            assertEquals(listOf("monitoring"), problem.keys)

            val closing = current.copy(
                statuses = current.statuses.map { if (it.key == "monitoring") it.copy(category = StatusCategory.CLOSED) else it },
            )
            val recategorise = cast.owner.client.putWorkflow(cast.room, 1, closing)
            assertEquals(HttpStatusCode.Conflict, recategorise.status)
            assertEquals(listOf("monitoring"), recategorise.body<ProblemDetail>().keys)

            assertEquals("monitoring", cast.owner.client.thread(cast.room, 1).status, "nothing moved")
            assertEquals(1, cast.owner.client.workflow(cast.room).revision)
            // A status nobody uses retires without a mapping.
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putWorkflow(cast.room, 1, current.without("wontdo")).status)
        }

    @Test
    fun `a thread cannot be pointed at a status key the room does not have - the composite foreign key backs the port`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-fk")
        cast.threadsIn("open")
        PostgresTestSupport.connect().use { raw ->
            raw.prepareStatement("UPDATE threads SET status_key = 'ghost' WHERE room_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                assertEquals("23503", assertFailsWith<SQLException> { st.executeUpdate() }.sqlState)
            }
        }
        assertEquals("open", cast.owner.client.thread(cast.room, 1).status)
    }

    @Test
    fun `a CLOSED to CLOSED replacement keeps the thread's original closedAt`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-closed")
        cast.threadsIn("open")
        TestThreads.closeRaw(cast.room, 1, "done", closedAt = 1_000)
        val before = cast.owner.client.thread(cast.room, 1)
        assertEquals(listOf("done", true, 1_000L), listOf(before.status, before.closed, before.closedAt))

        val current = cast.owner.client.workflow(cast.room).asRequest()
        val replaced = cast.owner.client.putWorkflow(cast.room, 1, current.without("done").replacing("done", "wontdo"))
        assertEquals(HttpStatusCode.NoContent, replaced.status)

        val after = cast.owner.client.thread(cast.room, 1)
        assertEquals(listOf("wontdo", true, 1_000L), listOf(after.status, after.closed, after.closedAt), "closed at the original time")
        assertEquals(before.revision, after.revision)
        val event = replacedEvents(after.id).single()
        assertEquals(mapOf("from" to "done", "to" to "wontdo", "fromLabel" to "Done", "toLabel" to "Won't do"), decodeParams(event.params))
        assertNull(cast.owner.client.workflow(cast.room).statuses.singleOrNull { it.key == "done" }, "the source status is retired")
    }

    @Test
    fun `race 6 - a status move whose target a workflow PUT retires while it waits is a 400 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("usg-race6")
        cast.threadsIn("open")
        val activity = TestThreads.roomActivity(cast.room)

        // The raw transaction holds the room lock and retires `monitoring` (the PUT's effect); neither is visible until it commits.
        TestThreads.holdingRoomLock(
            cast.room,
            "UPDATE room_statuses SET marked_as_deleted = TRUE WHERE room_id = ? AND \"key\" = 'monitoring'",
            cast.room,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the retirement is uncommitted); the move then waits for the room lock.
                val moving = async { cast.member.client.putStatus(cast.room, 1, "monitoring") }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.BadRequest, moving.await().status)
            }
        }
        val thread = TestThreads.raw(cast.room, 1)!!
        assertEquals("open", thread.statusKey)
        assertEquals(listOf("CREATED"), runBlocking { TestThreads.rawEvents(thread.id).map { it.type } })
        assertEquals(activity, TestThreads.roomActivity(cast.room))
    }
}
