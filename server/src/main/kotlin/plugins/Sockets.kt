package ch.nokillswit.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlin.time.Duration.Companion.seconds

/** The largest inbound frame (`live.md` "Bounds"): a bigger one closes the socket 1009; a `subscribe` of 128 ids is under 1.5 KB. */
internal const val MAX_FRAME_BYTES = 8192L

/**
 * Installs the WebSocket plugin for the live channel (`live/`, `.claude/docs/live.md`). The protocol heartbeat is Ktor's: a ping every
 * 25 seconds (inside ingress-nginx's 60 s read timeout) and a 15 second pong timeout, after which the connection is closed. No
 * extensions (nothing is compressed: the frames are tiny) and `masking = false` (a server never masks its own frames; the client's frames
 * are masked by the protocol regardless). Which routes upgrade is the route's own business (`webSocket {}`); nothing else is affected.
 */
fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = 25.seconds
        timeout = 15.seconds
        maxFrameSize = MAX_FRAME_BYTES
        masking = false
    }
}
