package ch.nokillswit

import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.LiveTicketResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * The live-channel harness (0.4.0 S4, `test-fixtures.md` "Live fixtures"). A WebSocket needs the client plugin that `jsonClient()` does not
 * install: [liveClient] is used for SOCKETS ONLY, never for `/api/` HTTP (it bypasses the OpenAPI conformance plugin; the plain GETs and
 * the ticket mints of a live test go through `jsonClient()`/`authedClient()`). [openLive] records the `101` in the coverage report, as the
 * conformance plugin cannot wrap an upgrade.
 */
fun ApplicationTestBuilder.liveClient(): HttpClient = createClient { install(WebSockets) }

/** The server frames as the client reads them: the same `type` discriminator, tolerant of fields a later protocol adds. */
private val frameJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

/** The origin a same-host test socket sends (`testApplication`'s host is `localhost`, port 80). */
const val LIVE_TEST_ORIGIN = "http://localhost"

/** Mints a ticket with this client's bearer (an [authedClient] or a [RoomActor.client]) and answers its 200 body. */
suspend fun HttpClient.mintLiveTicket(): LiveTicketResponse = post("/api/v1/live/tickets").body()

/** One open test socket: text in, frames out, and the close the server answered with. */
class LiveTestSocket(val session: DefaultClientWebSocketSession) {
    suspend fun sendText(text: String) = session.send(Frame.Text(text))

    suspend fun auth(ticket: String) = sendText("""{"type":"auth","ticket":"$ticket"}""")

    suspend fun subscribe(vararg roomIds: UInt) = sendText("""{"type":"subscribe","roomIds":[${roomIds.joinToString(",")}]}""")

    suspend fun unsubscribe(vararg roomIds: UInt) = sendText("""{"type":"unsubscribe","roomIds":[${roomIds.joinToString(",")}]}""")

    /** The next server frame; fails the test when none arrives within [timeoutMillis] or the socket closed instead. */
    suspend fun nextFrame(timeoutMillis: Long = FRAME_TIMEOUT_MILLIS): LiveServerFrame {
        val frame = withTimeout(timeoutMillis) { session.incoming.receive() }
        return frameJson.decodeFromString(LiveServerFrame.serializer(), (frame as Frame.Text).readText())
    }

    /** The next frame, or null when the socket closed or nothing arrived within [timeoutMillis] (the negative assertion). */
    suspend fun nextFrameOrNull(timeoutMillis: Long): LiveServerFrame? = try {
        withTimeoutOrNull(timeoutMillis) { session.incoming.receive() }
            ?.let { frameJson.decodeFromString(LiveServerFrame.serializer(), (it as Frame.Text).readText()) }
    } catch (_: ClosedReceiveChannelException) {
        null
    }

    /** The close the server ended the socket with (the test fails with a timeout when it never closes). */
    suspend fun awaitClose(timeoutMillis: Long = FRAME_TIMEOUT_MILLIS): CloseReason? =
        withTimeout(timeoutMillis) { session.closeReason.await() }

    suspend fun close() {
        try {
            session.close()
        } catch (_: Exception) {
            // already closed by the server
        }
    }

    companion object {
        const val FRAME_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Opens `GET /api/v1/live` on [client] ([liveClient]) with [origin] as its `Origin` header (null sends none) and answers the open
 * socket; the
 * upgrade's `101` is recorded in the OpenAPI coverage. The caller authenticates ([LiveTestSocket.auth]) and closes.
 */
suspend fun openLive(client: HttpClient, origin: String? = LIVE_TEST_ORIGIN): LiveTestSocket {
    val session = client.webSocketSession("/api/v1/live") { if (origin != null) header(HttpHeaders.Origin, origin) }
    OpenApiCoverage.record("GET", "/api/v1/live", 101)
    return LiveTestSocket(session)
}

/** Opens a socket, authenticates it with a fresh ticket of [actor] and returns it after its `ready`. */
suspend fun openAuthenticatedLive(client: HttpClient, actor: RoomActor): LiveTestSocket {
    val socket = openLive(client)
    socket.auth(actor.client.mintLiveTicket().ticket)
    val ready = socket.nextFrame()
    check(ready is LiveServerFrame.Ready) { "expected ready, got $ready" }
    return socket
}

/**
 * Waits (bounded) until the hub's LISTEN session is up: a write made before that is lost to the socket (at-most-once), as in
 * production.
 */
suspend fun awaitListenerUp(hub: ch.nokillswit.live.LiveHub, timeoutMillis: Long = 15_000) {
    withTimeout(timeoutMillis) {
        while (!hub.listener.isUp) kotlinx.coroutines.delay(25)
    }
}

/**
 * The next frame that is not a `resync` (the first LISTEN's own gap signal can arrive at any point of a test that started the
 * listener).
 */
suspend fun LiveTestSocket.nextNonResync(timeoutMillis: Long = LiveTestSocket.FRAME_TIMEOUT_MILLIS): LiveServerFrame {
    while (true) {
        val frame = nextFrame(timeoutMillis)
        if (frame !is LiveServerFrame.Resync) return frame
    }
}
