package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.threads.ThreadResponse
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
import java.sql.Connection
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The room's `FOR NO KEY UPDATE` write lock and workflow replacement (persistence.md "Row locks vs. FK locks"). A workflow PUT can
 * update a thread while an append-only child holds PostgreSQL's FK `FOR KEY SHARE` lock on it, while two workflow writers still
 * serialize on the room and leave one valid document and thread state.
 */
@OptIn(ExperimentalUnsignedTypes::class)
class RowLockVersusDeriveTest {
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

    private data class Cast(val owner: RoomActor, val room: UInt, val thread: ThreadResponse)

    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        val owner = seedActor("$prefix-own").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(
            owner.id,
            template,
            "Lock ${UUID.randomUUID().toString().take(8)}",
        ).also { mintedRooms += it }
        TestThreads.seed(room, owner.id)
        assertEquals(
            HttpStatusCode.NoContent,
            owner.client.putJson(
                "$rooms/$room/threads/1/status",
                ch.nokillswit.threads.ThreadStatusRequest("monitoring"),
            ).status,
        )
        return Cast(owner, room, owner.client.get("$rooms/$room/threads/1").body())
    }

    private fun RoomWorkflowResponse.asRequest() = RoomWorkflowRequest(
        statuses = statuses.map { RoomStatusDef(it.key, it.label, it.colour, it.category, it.initial) },
        fields = fields.map { RoomFieldDef(it.key, it.label, it.type, it.required, it.options) },
    )

    private fun RoomWorkflowRequest.replaceMonitoring() = copy(
        statuses = statuses.filter { it.key != "monitoring" },
        replacements = mapOf("monitoring" to "open"),
    )

    private suspend fun HttpClient.workflow(room: UInt): RoomWorkflowResponse = get("$rooms/$room/workflow").body()

    private suspend fun HttpClient.replaceWorkflow(room: UInt, body: RoomWorkflowRequest): HttpResponse =
        put("$rooms/$room/workflow") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A raw transaction left open on its own backend. Closing it rolls back. */
    private class OpenTransaction : AutoCloseable {
        private val connection: Connection = PostgresTestSupport.connect().also { it.autoCommit = false }

        fun appendEvent(threadId: UInt, userId: UInt) {
            connection.prepareStatement(
                "INSERT INTO thread_events (thread_id, user_id, created_at, event_type, params) VALUES (?, ?, 1, 'TITLE_CHANGED', '{}')",
            ).use { statement ->
                statement.setInt(1, threadId.toInt())
                statement.setLong(2, userId.toLong())
                statement.executeUpdate()
            }
        }

        override fun close() {
            runCatching { connection.rollback() }
            connection.close()
        }
    }

    @Test
    fun `a workflow replacement does not wait for a child transaction holding key-share on the thread`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rowlock-fk")
        val replacement = cast.owner.client.workflow(cast.room).asRequest().replaceMonitoring()

        OpenTransaction().use { derive ->
            derive.appendEvent(cast.thread.id, cast.owner.id)
            val response = withContext(Dispatchers.Default) {
                withTimeout(PROMPT_MILLIS) { cast.owner.client.replaceWorkflow(cast.room, replacement) }
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

        val thread = cast.owner.client.get("$rooms/${cast.room}/threads/1").body<ThreadResponse>()
        assertEquals("open", thread.status)
        assertEquals(1, TestThreads.rawEvents(thread.id).count { it.type == "STATUS_REPLACED" })
        assertNull(cast.owner.client.workflow(cast.room).statuses.singleOrNull { it.key == "monitoring" })
    }

    @Test
    fun `two workflow replacements serialize and leave one valid final state`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rowlock-serial")
        val replacement = cast.owner.client.workflow(cast.room).asRequest().replaceMonitoring()

        TestThreads.holdingRoomLock(cast.room) { holder ->
            coroutineScope {
                val first = async(Dispatchers.Default) { cast.owner.client.replaceWorkflow(cast.room, replacement).status }
                val second = async(Dispatchers.Default) { cast.owner.client.replaceWorkflow(cast.room, replacement).status }
                awaitLockWait("FOR NO KEY UPDATE", waiters = 2)
                holder.commit()
                assertEquals(
                    listOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict),
                    withTimeout(PROMPT_MILLIS) { listOf(first.await(), second.await()).sortedBy { it.value } },
                )
            }
        }

        val thread = cast.owner.client.get("$rooms/${cast.room}/threads/1").body<ThreadResponse>()
        assertEquals("open", thread.status)
        assertEquals(1, TestThreads.rawEvents(thread.id).count { it.type == "STATUS_REPLACED" })
        val workflow = cast.owner.client.workflow(cast.room)
        assertEquals(2, workflow.revision)
        assertNull(workflow.statuses.singleOrNull { it.key == "monitoring" })
    }

    private companion object {
        const val PROMPT_MILLIS = 5_000L
    }
}
