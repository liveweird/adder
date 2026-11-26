package ch.nokillswit

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.live.LiveAudit
import ch.nokillswit.live.LiveConfig
import ch.nokillswit.live.LiveHub
import ch.nokillswit.live.LiveLeaseCoordinator
import ch.nokillswit.live.LIVE_TICKET_TTL_MILLIS
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.LiveSessionDeps
import ch.nokillswit.live.LiveSessionLeaseStore
import ch.nokillswit.live.LiveTickets
import ch.nokillswit.live.liveSession
import ch.nokillswit.plugins.configureSockets
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.reactivestreams.Publisher
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The error closes of `live/LiveSocket.kt` that the production route cannot be made to reach: a session whose collaborators fail, whose
 * clock has run on, whose hub is stopping, or that finds the unauthenticated cap full where the route's admission would have refused
 * first. `LiveSocketTest` covers the closes a client causes, over the real application; this class runs [liveSession] on a bare
 * application (the WebSocket plugin and one route) with its own [LiveSessionDeps] and a hub that never starts its listener (as
 * `LiveHubTest`), a blocklist or room service pointed at an unreachable database, and a clock seam. Not mounted inside the real
 * application: the request body limit wraps every call but the exact live path (`plugins/Http.kt`) and would starve a second route of
 * every client frame.
 */
class LiveSocketFaultsTest {
    private val path = "/live"
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { users.forEach { TestUsers.softDelete(it) } }

    private val neverConnects = object : ConnectionFactory {
        override fun create(): Publisher<out Connection> = error("these tests never start the listener")
        override fun getMetadata(): ConnectionFactoryMetadata = error("not used")
    }

    /** An unreachable database: every query fails fast with a connection error (`BlocklistOutageTest`). */
    private fun unreachable() =
        R2dbcDatabase.connect(url = "r2dbc:postgresql://localhost:1/nonexistent", user = "nobody", password = "nobody")

    private class Rig(val tickets: LiveTickets, val hub: LiveHub, val client: HttpClient)

    private fun config(maxPendingAuth: Int = 100) = LiveConfig(
        maxTickets = 100, maxSockets = 10, maxSocketsPerUser = 4, maxPendingAuth = maxPendingAuth, maxPendingPerAddress = 100,
        authTimeoutMillis = 2_000,
    )

    private fun rig(
        config: LiveConfig = config(),
        blocklist: TokenBlocklistService = TokenBlocklistService(sharedDatabaseForTests()),
        rooms: RoomService = TestRooms.service,
        clock: () -> Long = System::currentTimeMillis,
        block: suspend Rig.() -> Unit,
    ) = testApplication {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val tickets = LiveTickets(LiveTicketMemoryStore(config.maxTickets, LIVE_TICKET_TTL_MILLIS))
            val hub = LiveHub(scope, config.maxSockets, neverConnects)
            val leases = LiveLeaseCoordinator(
                LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID()), scope, config.maxSocketsPerUser,
            )
            val deps = LiveSessionDeps(config, tickets, hub, leases, blocklist, rooms, LiveAudit(), clock)
            application {
                configureSockets()
                routing { webSocket(path) { liveSession(deps) } }
            }
            Rig(tickets, hub, liveClient()).block()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun Rig.open(): LiveTestSocket = LiveTestSocket(client.webSocketSession(path))

    /** A new USER and a ticket for it, bound to a token that lives another minute (the shape `POST /live/tickets` binds). */
    private suspend fun Rig.userWithTicket(): Pair<UInt, String> {
        val id = TestUsers.seed(uniqueEmail("fault"), "pw", role = UserRole.USER).also { users += it }
        val ticket = tickets.issue(
            userId = id, isAdmin = false, roles = emptySet(), jti = UUID.randomUUID().toString(), credentialRevision = 0,
            tokenExpiresAtMillis = System.currentTimeMillis() + 60_000,
        ).ticket
        return id to ticket
    }

    /** Opens a socket, redeems a fresh ticket and reads `ready`. */
    private suspend fun Rig.readySocket(): Pair<UInt, LiveTestSocket> {
        val (id, ticket) = userWithTicket()
        val socket = open()
        socket.auth(ticket)
        assertTrue(socket.nextFrame() is LiveServerFrame.Ready)
        return id to socket
    }

    private suspend fun LiveTestSocket.expectClose(code: Short) {
        val reason = assertNotNull(awaitClose(), "the server closed with no reason")
        assertEquals(code, reason.code, "close code (reason: ${reason.message})")
    }

    @Test
    fun `a failing verdict query closes the socket 1011, logs the class only and frees the pending slot`() =
        rig(blocklist = TokenBlocklistService(unreachable())) {
            val (_, ticket) = userWithTicket()
            val capture = LogCapture("ch.nokillswit.live.LiveSocket")
            try {
                val socket = open()
                socket.auth(ticket)
                socket.expectClose(CloseReason.Codes.INTERNAL_ERROR.code)
                val line = assertNotNull(capture.awaitEvent { it.formattedMessage.startsWith("live socket failed (") })
                val logged = line.formattedMessage
                assertTrue("localhost" !in logged && "nobody" !in logged, "a class name, never a driver message")
                withTimeout(10_000) { while (hub.pendingAuth.get() != 0) delay(20) }
            } finally {
                capture.detach()
            }
        }

    @Test
    fun `a failing room check on a subscribe closes the socket 1011 and unregisters it`() =
        rig(rooms = RoomService(unreachable(), TestTemplates.service, TestRooms.workflows)) {
            val (userId, socket) = readySocket()
            socket.sendText("""{"type":"subscribe","roomIds":[1]}""")
            socket.expectClose(CloseReason.Codes.INTERNAL_ERROR.code)
            withTimeout(10_000) { while (hub.socketsOf(userId) > 0) delay(20) }
        }

    @Test
    fun `a clock past the token's expiry at the redeem closes 4001`() = rig(clock = { Long.MAX_VALUE }) {
        // The ticket's own deadline is clamped to the token's, so only a clock step between the two reads gets here: the session's
        // clock says the token ended while the ticket store's still says it has not.
        val (_, ticket) = userWithTicket()
        val socket = open()
        socket.auth(ticket)
        socket.expectClose(4001)
    }

    @Test
    fun `a ticket redeemed after the hub began stopping is answered 1001`() = rig {
        val (_, ticket) = userWithTicket()
        hub.shutdown(graceMillis = 50)
        val socket = open()
        socket.auth(ticket)
        socket.expectClose(CloseReason.Codes.GOING_AWAY.code)
    }

    @Test
    fun `the global unauthenticated cap holds at the session even when admission was raced`() =
        rig(config = config(maxPendingAuth = 1).copy(authTimeoutMillis = 30_000)) {
            val first = open()
            withTimeout(10_000) { while (hub.pendingAuth.get() != 1) delay(20) }
            withAuditCapture { capture ->
                val second = open()
                second.expectClose(CloseReason.Codes.TRY_AGAIN_LATER.code)
                val line = assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "capacity") })
                assertTrue(line.hasKeyValue("limit", "pending-auth"))
            }
            first.close()
            withTimeout(10_000) { while (hub.pendingAuth.get() != 0) delay(20) }
        }

    @Test
    fun `a malformed frame or an invalid unsubscribe after the handshake closes 1008`() = rig {
        val (_, garbage) = readySocket()
        garbage.sendText("not json")
        garbage.expectClose(1008)

        val (_, unsubscribe) = readySocket()
        unsubscribe.sendText("""{"type":"unsubscribe","roomIds":[0]}""")
        unsubscribe.expectClose(1008)
    }
}
