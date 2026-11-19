package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.LIVE_SOCKET_LIMIT_PROBLEM_TYPE
import ch.nokillswit.live.MAX_SUBSCRIPTIONS
import ch.nokillswit.plugins.MetricRegistryKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.telemetry.LatencyCollector
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.users.UserRole
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The live WebSocket end to end (`live/LiveSocket.kt`, `live/LiveHub.kt`) on a `testApplication`: the first-frame ticket, the close
 * codes of the handshake and of a bad frame, the per-user cap, subscribe (readable, refused, ADMIN), delivery and its negative cases
 * (proved by ORDER: a marker write after the one that must not arrive, never a sleep), ping, and the 1001 close of a stopping server.
 * Rooms, users and templates are shared suite state: every test mints unique rows and removes them.
 */
class LiveSocketTest {
    private val rooms = "/api/v1/rooms"
    private val users = mutableListOf<UInt>()
    private val roomIds = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(roomIds)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { users += it.id }

    private suspend fun room(owner: RoomActor, vararg members: RoomActor): UInt {
        val template = TestThreads.seedTemplate().also { templates += it }
        val room = TestRooms.seed(owner.id, template, "Live ${UUID.randomUUID().toString().substring(0, 8)}").also { roomIds += it }
        members.forEach { TestRooms.addMember(room, it.id) }
        return room
    }

    private suspend fun LiveTestSocket.expectClose(code: Short) {
        val reason = assertNotNull(awaitClose(), "the server closed with no reason")
        assertEquals(code, reason.code, "close code (reason: ${reason.message})")
    }

    private suspend fun LiveTestSocket.ping() {
        sendText("""{"type":"ping"}""")
        while (true) if (nextFrame() == LiveServerFrame.Pong) return
    }

    @Test
    fun `a ticket in the first frame opens the socket, and closing it frees the slot`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val user = actor("live-open")
        val socket = openLive(liveClient())
        socket.auth(user.client.mintLiveTicket().ticket)
        assertEquals(LiveServerFrame.Ready(protocol = 1, maxSubscriptions = 128), socket.nextFrame())
        assertEquals(1, hub.socketsOf(user.id))
        assertEquals(1, hub.socketCount)

        socket.close()
        withTimeout(10_000) { while (hub.socketsOf(user.id) > 0) delay(25) }
    }

    @Test
    fun `no auth frame within the deadline closes 4401 and is audited`() = testApplication {
        configureApp("live.authTimeoutMillis" to "300")
        startApplication()
        withAuditCapture { capture ->
            val socket = openLive(liveClient())
            socket.expectClose(4401)
            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "auth") })
        }
    }

    @Test
    fun `an unknown or used ticket closes 4401, and a ticket is single use`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-used")
        val ws = liveClient()
        val ticket = user.client.mintLiveTicket().ticket

        val first = openLive(ws)
        first.auth(ticket)
        assertTrue(first.nextFrame() is LiveServerFrame.Ready)
        val replay = openLive(ws)
        replay.auth(ticket)
        replay.expectClose(4401)

        val unknown = openLive(ws)
        unknown.auth("x".repeat(43))
        unknown.expectClose(4401)
        first.close()
    }

    @Test
    fun `a ticket redeemed after its access token expired closes 4401`() = testApplication {
        // A ticket never outlives its token (`LiveTickets` clamps it), so a 1 s token makes a ticket already expired a second later.
        configureApp("jwt.accessExpiresInSeconds" to "1")
        startApplication()
        val user = actor("live-expired")
        val ticket = user.client.mintLiveTicket().ticket
        delay(1_300)
        val socket = openLive(liveClient())
        socket.auth(ticket)
        socket.expectClose(4401)
    }

    @Test
    fun `a ticket whose token was revoked after the mint closes 4003`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-revoked")
        val ticket = user.client.mintLiveTicket().ticket
        assertEquals(HttpStatusCode.NoContent, user.client.post("/api/v1/logout").status)

        withAuditCapture { capture ->
            val socket = openLive(liveClient())
            socket.auth(ticket)
            socket.expectClose(4003)
            val event = capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("verdict", "revoked") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", user.id.toLong()))
        }
    }

    @Test
    fun `a bad frame closes the socket - binary 1003, anything unparseable or misplaced 1008`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-bad")
        val ws = liveClient()
        withAuditCapture { capture ->
            val garbage = openLive(ws)
            garbage.sendText("not json")
            garbage.expectClose(1008)

            val beforeAuth = openLive(ws)
            beforeAuth.subscribe(1u)
            beforeAuth.expectClose(1008)

            val binary = openLive(ws)
            binary.session.send(io.ktor.websocket.Frame.Binary(true, byteArrayOf(1, 2, 3)))
            binary.expectClose(1003)

            val twice = openAuthenticatedLive(ws, user)
            twice.auth(user.client.mintLiveTicket().ticket)
            twice.expectClose(1008)

            val unknown = openAuthenticatedLive(ws, user)
            unknown.sendText("""{"type":"subscribe","roomIds":[0]}""")
            unknown.expectClose(1008)

            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "bad-frame") })
        }
    }

    @Test
    fun `the per-user cap refuses the mint with 429 and closes 4429 on a race`() = testApplication {
        configureApp("live.maxSocketsPerUser" to "1")
        startApplication()
        val user = actor("live-cap")
        val ws = liveClient()
        // Both tickets are minted before any socket exists, so only the redemption can enforce the cap.
        val first = user.client.mintLiveTicket().ticket
        val second = user.client.mintLiveTicket().ticket
        val open = openLive(ws)
        open.auth(first)
        assertTrue(open.nextFrame() is LiveServerFrame.Ready)

        withAuditCapture { capture ->
            val raced = openLive(ws)
            raced.auth(second)
            raced.expectClose(4429)
            val event = capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "socket-cap") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", user.id.toLong()))
        }

        val refused = user.client.post("/api/v1/live/tickets")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("60", refused.headers[HttpHeaders.RetryAfter])
        assertEquals(LIVE_SOCKET_LIMIT_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        open.close()
    }

    @Test
    fun `a member subscribes and receives the room's hints, with ids only`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("live-sub-own")
        val member = actor("live-sub-mem")
        val room = room(owner, member)
        val thread = TestThreads.seed(room, member.id)
        val socket = openAuthenticatedLive(liveClient(), member)
        socket.subscribe(room)
        assertEquals(LiveServerFrame.Subscribed(listOf(room), emptyList(), emptyList()), socket.nextNonResync())
        awaitListenerUp(hub)

        val posted = member.client.postJson("$rooms/$room/threads/${thread.number}/messages", MessageRequest("Hello live"))
        assertEquals(HttpStatusCode.Created, posted.status)
        val messageId = posted.body<MessageResponse>().id
        assertEquals(
            LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, room, threadNumber = thread.number, messageId = messageId),
            socket.nextNonResync(),
        )
        socket.close()
    }

    @Test
    fun `a non-member is refused uniformly, and only an existing room is audited`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("live-den-own")
        val outsider = actor("live-den-out")
        val room = room(owner)
        val missing = 2_000_000_000u
        val socket = openAuthenticatedLive(liveClient(), outsider)
        withAuditCapture { capture ->
            socket.subscribe(room, missing)
            val ack = socket.nextNonResync() as LiveServerFrame.Subscribed
            assertEquals(emptyList(), ack.roomIds)
            assertEquals(setOf(room, missing), ack.refused.toSet(), "an unreadable room and a missing one look the same")
            assertEquals(emptyList(), ack.capped)

            val event = capture.awaitEvent { it.message == "live.subscribe_denied" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", outsider.id.toLong()))
            assertTrue(event.hasKeyValue("roomIds", listOf(room.toLong())), "never the id that does not exist")
        }
        socket.close()
    }

    @Test
    fun `an ADMIN subscribes to a room they are not in`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("live-adm-own")
        val admin = actor("live-adm", UserRole.ADMIN)
        val room = room(owner)
        val socket = openAuthenticatedLive(liveClient(), admin)
        withAuditCapture { capture ->
            socket.subscribe(room)
            assertEquals(LiveServerFrame.Subscribed(listOf(room), emptyList(), emptyList()), socket.nextNonResync())
            assertTrue(capture.events.none { it.message == "live.subscribe_denied" })
        }
        socket.close()
    }

    @Test
    fun `a room past the per-socket limit is capped and audited`() = testApplication {
        usePostgresTestcontainer()
        val admin = actor("live-capped", UserRole.ADMIN)
        val marker = UUID.randomUUID().toString().substring(0, 8)
        val ids = PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO rooms (name, last_activity_at, created_at, updated_at) " +
                    "SELECT ? || '-' || g, 1, 1, 1 FROM generate_series(1, ?) g RETURNING id",
            ).use { st ->
                st.setString(1, "Cap $marker")
                st.setInt(2, MAX_SUBSCRIPTIONS + 1)
                st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1).toUInt() else null }.toList() }
            }
        }
        roomIds += ids
        val socket = openAuthenticatedLive(liveClient(), admin)
        withAuditCapture { capture ->
            socket.subscribe(*ids.take(MAX_SUBSCRIPTIONS).toUIntArray())
            val full = socket.nextNonResync() as LiveServerFrame.Subscribed
            assertEquals(ids.take(MAX_SUBSCRIPTIONS).toSet(), full.roomIds.toSet())

            socket.subscribe(ids.last())
            assertEquals(LiveServerFrame.Subscribed(emptyList(), emptyList(), listOf(ids.last())), socket.nextNonResync())
            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "subscription-cap") })
        }
        socket.close()
    }

    @Test
    fun `an unsubscribed room's hints never arrive, and unsubscribe stops delivery`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("live-neg-own")
        val member = actor("live-neg-mem")
        val watched = room(owner, member)
        val other = room(owner, member)
        val threadWatched = TestThreads.seed(watched, member.id)
        val threadOther = TestThreads.seed(other, member.id)
        val socket = openAuthenticatedLive(liveClient(), member)
        socket.subscribe(watched)
        assertTrue(socket.nextNonResync() is LiveServerFrame.Subscribed)
        awaitListenerUp(hub)

        // The write the socket must NOT hear comes first; the marker after it proves (NOTIFY is in commit order) it never arrived.
        member.client.postJson("$rooms/$other/threads/${threadOther.number}/messages", MessageRequest("elsewhere"))
        val marker = member.client.postJson("$rooms/$watched/threads/${threadWatched.number}/messages", MessageRequest("marker"))
        val markerId = marker.body<MessageResponse>().id
        val first = socket.nextNonResync() as LiveServerFrame.Event
        assertEquals(watched, first.roomId)
        assertEquals(markerId, first.messageId)

        // Unsubscribe, barrier with a ping (frames are handled in order), then write: the next thing heard is the later marker.
        socket.unsubscribe(watched)
        socket.ping()
        member.client.postJson("$rooms/$watched/threads/${threadWatched.number}/messages", MessageRequest("after unsubscribe"))
        socket.subscribe(other)
        assertEquals(LiveServerFrame.Subscribed(listOf(other), emptyList(), emptyList()), socket.nextNonResync())
        val laterMarker = member.client.postJson("$rooms/$other/threads/${threadOther.number}/messages", MessageRequest("later marker"))
        val heard = socket.nextNonResync() as LiveServerFrame.Event
        assertEquals(other, heard.roomId, "the unsubscribed room's hint never arrived")
        assertEquals(laterMarker.body<MessageResponse>().id, heard.messageId)
        socket.close()
    }

    @Test
    fun `a socket that loses the race for the last slot is closed 1013`() = testApplication {
        configureApp("live.maxSockets" to "1")
        startApplication()
        val first = actor("live-race-a")
        val second = actor("live-race-b")
        val ws = liveClient()
        val ticketA = first.client.mintLiveTicket().ticket
        val ticketB = second.client.mintLiveTicket().ticket
        // Both upgrade while the table is empty (admission passes), then authenticate one after the other.
        val a = openLive(ws)
        val b = openLive(ws)
        a.auth(ticketA)
        assertTrue(a.nextFrame() is LiveServerFrame.Ready)
        withAuditCapture { capture ->
            b.auth(ticketB)
            b.expectClose(CloseReason.Codes.TRY_AGAIN_LATER.code)
            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "capacity") })
        }
        a.close()
    }

    @Test
    fun `ping is answered with pong`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-ping")
        val socket = openAuthenticatedLive(liveClient(), user)
        socket.sendText("""{"type":"ping"}""")
        assertEquals(LiveServerFrame.Pong, socket.nextNonResync())
        socket.close()
    }

    @Test
    fun `a stopping server closes every socket 1001 and refuses new ones`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val a = actor("live-stop-a")
        val b = actor("live-stop-b")
        val ws = liveClient()
        val first = openAuthenticatedLive(ws, a)
        val second = openAuthenticatedLive(ws, b)

        // What Ktor raises first when the engine stops; the hub's own 2 s grace runs on this thread, so it goes off the test coroutine.
        withContext(Dispatchers.IO) { application.monitor.raise(ApplicationStopPreparing, application.environment) }
        first.expectClose(CloseReason.Codes.GOING_AWAY.code)
        second.expectClose(CloseReason.Codes.GOING_AWAY.code)
        withTimeout(5_000) { while (hub.socketCount > 0) delay(25) }
        assertTrue(hub.isStopping)

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.ServiceUnavailable, a.client.post("/api/v1/live/tickets").status, "a stopping server mints nothing")
            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "stopping") })
        }
    }

    @Test
    fun `no log line carries a ticket, even across a refused, a revoked and a served socket`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-logs")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            val ws = liveClient()
            val tickets = mutableListOf<String>()
            val served = openLive(ws)
            tickets += user.client.mintLiveTicket().ticket
            served.auth(tickets.last())
            assertTrue(served.nextFrame() is LiveServerFrame.Ready)
            val replay = openLive(ws)
            replay.auth(tickets.last()) // used: refused and audited
            replay.expectClose(4401)
            val garbage = openLive(ws)
            garbage.sendText("""{"type":"auth","ticket":"${"g".repeat(43)}","x":1}""") // a malformed auth frame
            garbage.expectClose(1008)
            served.close()
            val rendered = capture.events.map { it.rendered() }
            assertTrue(rendered.any { "live.refused" in it }, "the capture saw the audit lines")
            assertTrue(rendered.none { r -> tickets.any { it in r } || "g".repeat(43) in r }, "a ticket leaked into a log line")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `the latency telemetry counts the ticket mint but not the socket's whole life`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("live-latency")
        val registry = application.attributes[MetricRegistryKey]
        val collector = LatencyCollector(registry)
        collector.collect()
        val socket = openAuthenticatedLive(liveClient(), user)
        delay(200)
        socket.close()
        val hub = application.attributes[LiveHubKey]
        withTimeout(10_000) { while (hub.socketCount > 0) delay(25) }
        delay(200) // the call's timer stops when its handler returns
        val labels = collector.collect().map { it.label }.toSet()
        assertTrue("/api/v1/live/tickets" in labels, "the mint is an ordinary request: $labels")
        assertTrue("/api/v1/live" !in labels, "the socket is excluded: $labels")
        // Guard against a vacuous pass: Ktor really did time a series for the socket's route.
        assertTrue(registry.names.any { it.startsWith("ktor.calls.") && it.endsWith(".timer") && "api/v1/live/" in it && "tickets" !in it })
    }

    private fun ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
    }
}
