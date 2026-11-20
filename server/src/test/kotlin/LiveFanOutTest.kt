package ch.nokillswit

import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.toPayload
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.LiveTicketResponse
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NOTIFY across processes (`live.md` "The NOTIFY wire and the listener", the two-instance pattern of `test-fixtures.md`): a write
 * made by one
 * application instance reaches a socket held by ANOTHER one over the shared database, a killed LISTEN connection reconnects and tells its
 * sockets to resync, and a payload that is not a hint is ignored. The second instance is a [TestApplication] started inside a
 * `testApplication` and stopped in a `finally`; mint and redeem both happen on the instance that holds the socket (tickets are per
 * process).
 */
class LiveFanOutTest {
    private val users = mutableListOf<UInt>()
    private val roomIds = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(roomIds)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    /** A member of a fresh room with a thread: the writer's REST client lives on the first instance, the token for the second is raw. */
    private class Scene(val writer: RoomActor, val room: UInt, val thread: Int, val email: String, val password: String)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.scene(): Scene {
        val email = uniqueEmail("fanout")
        val id = TestUsers.seed(email = email, password = "pw")
        users += id
        val writer = RoomActor(id, authedClient(email, "pw"))
        val template = TestThreads.seedTemplate().also { templates += it }
        val room = TestRooms.seed(id, template, "Fan ${UUID.randomUUID().toString().substring(0, 8)}").also { roomIds += it }
        return Scene(writer, room, TestThreads.seed(room, id).number, email, "pw")
    }

    /** Runs [block] with a second application instance over the same database (stopped afterwards). */
    private suspend fun withSecondInstance(block: suspend (TestApplication) -> Unit) {
        val other = TestApplication { configureApp() }
        other.start()
        try {
            block(other)
        } finally {
            other.stop()
        }
    }

    /** Logs the scene's user in on [other] and opens an authenticated socket there, with the ticket minted by [other] itself. */
    private suspend fun openOn(other: TestApplication, scene: Scene): LiveTestSocket {
        val http = other.createClient { testClientDefaults() }
        val token = http.login(scene.email, scene.password).body<LoginResponse>().token
        val ticket = http.post("/api/v1/live/tickets") { header(HttpHeaders.Authorization, "Bearer $token") }.body<LiveTicketResponse>()
        val socket = openLive(other.createClient { install(WebSockets) })
        socket.auth(ticket.ticket)
        assertTrue(socket.nextFrame() is LiveServerFrame.Ready)
        return socket
    }

    private fun terminateBackend(pid: Int) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT pg_terminate_backend(?)").use { st ->
            st.setInt(1, pid)
            st.executeQuery().use { it.next() }
        }
    }

    private fun notifyRaw(payload: String) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT pg_notify('adder_live', ?)").use { st ->
            st.setString(1, payload)
            st.execute()
        }
    }

    @Test
    fun `a write on one instance reaches a socket on another`() = testApplication {
        usePostgresTestcontainer()
        val scene = scene()
        withSecondInstance { other ->
            val socket = openOn(other, scene)
            socket.subscribe(scene.room)
            assertTrue(socket.nextNonResync() is LiveServerFrame.Subscribed)
            awaitListenerUp(other.application.attributes[LiveHubKey])

            val posted = scene.writer.client.postJson(
                "/api/v1/rooms/${scene.room}/threads/${scene.thread}/messages",
                MessageRequest("Across instances"),
            )
            assertEquals(HttpStatusCode.Created, posted.status)
            assertEquals(
                LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, scene.room, scene.thread, posted.body<MessageResponse>().id),
                socket.nextNonResync(),
            )
            socket.close()
        }
    }

    @Test
    fun `a notice reaches the mentioned member's socket on another instance, subscribed or not, and no subscriber`() = testApplication {
        usePostgresTestcontainer()
        val scene = scene()
        val bobEmail = uniqueEmail("fanout-bob")
        val bobId = TestUsers.seed(email = bobEmail, password = "pw").also { users += it }
        TestRooms.addMember(scene.room, bobId)
        val bob = Scene(RoomActor(bobId, authedClient(bobEmail, "pw")), scene.room, scene.thread, bobEmail, "pw")
        withSecondInstance { other ->
            val writerSocket = openOn(other, scene)
            writerSocket.subscribe(scene.room)
            assertTrue(writerSocket.nextNonResync() is LiveServerFrame.Subscribed)
            val bobSocket = openOn(other, bob) // never subscribes: the notice is addressed to the person, not to the room
            awaitListenerUp(other.application.attributes[LiveHubKey])

            val posted = scene.writer.client.postJson(
                "/api/v1/rooms/${scene.room}/threads/${scene.thread}/messages",
                MessageRequest("Hello <@$bobId>"),
            )
            assertEquals(HttpStatusCode.Created, posted.status)
            val id = posted.body<MessageResponse>().id
            assertEquals(LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, scene.room, scene.thread, id), writerSocket.nextNonResync())
            assertEquals(
                LiveServerFrame.Event(LiveKind.NOTIFICATION_CREATED, scene.room, scene.thread, id),
                bobSocket.nextNonResync(),
                "the mentioned member's socket hears the notice although it never subscribed to the room",
            )
            assertNull(writerSocket.nextFrameOrNull(500), "a subscriber of the room that is not the target hears nothing of it")
            bobSocket.close()
            writerSocket.close()
        }
    }

    @Test
    fun `a killed listener reconnects and tells its sockets to resync`() = testApplication {
        usePostgresTestcontainer()
        val scene = scene()
        withSecondInstance { other ->
            val hub = other.application.attributes[LiveHubKey]
            val socket = openOn(other, scene)
            socket.subscribe(scene.room)
            assertTrue(socket.nextNonResync() is LiveServerFrame.Subscribed)
            awaitListenerUp(hub)
            while (socket.nextFrameOrNull(500) != null) delay(1) // the first LISTEN's own resync, if it came

            val pid = assertNotNull(hub.listener.backendPid)
            terminateBackend(pid)
            assertEquals(LiveServerFrame.Resync, socket.nextFrame(15_000), "the gap is reported to the socket")
            awaitListenerUp(hub)
            assertTrue(assertNotNull(hub.listener.backendPid) != pid, "a new backend")

            // And it hears again.
            scene.writer.client.postJson("/api/v1/rooms/${scene.room}/threads/${scene.thread}/messages", MessageRequest("After the kill"))
            assertTrue(socket.nextNonResync() is LiveServerFrame.Event)
            socket.close()
        }
    }

    @Test
    fun `a payload that is not a hint is ignored, the next real one is delivered`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val scene = scene()
        val socket = openAuthenticatedLive(liveClient(), scene.writer)
        socket.subscribe(scene.room)
        assertTrue(socket.nextNonResync() is LiveServerFrame.Subscribed)
        awaitListenerUp(hub)

        notifyRaw("not json at all")
        notifyRaw("""{"v":2,"kind":"room.updated","roomId":${scene.room}}""") // another protocol version
        notifyRaw("""{"v":1,"kind":"room.vanished","roomId":${scene.room}}""") // a kind this build does not know
        val real = LiveHint(LiveKind.ROOM_UPDATED, scene.room)
        notifyRaw(real.toPayload())
        assertEquals(LiveServerFrame.Event(LiveKind.ROOM_UPDATED, scene.room), socket.nextNonResync())
        socket.close()
    }
}
