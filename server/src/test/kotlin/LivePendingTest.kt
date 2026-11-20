package ch.nokillswit

import ch.nokillswit.infra.addressKey
import ch.nokillswit.live.LIVE_UNAVAILABLE_PROBLEM_TYPE
import ch.nokillswit.live.LiveHub
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The unauthenticated sockets of `GET /api/v1/live` (`live/LivePending.kt`, `live.md` "Bounds"): the per-address share of the global
 * cap, the pending counter returning to zero however a socket ends, and the pre-auth frame bounds. Distinct client addresses are made
 * with `X-Forwarded-For` under `http.behindProxy` (the same address the per-IP bucket keys by).
 */
class LivePendingTest {
    private val path = "/api/v1/live"
    private val minted = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { minted.forEach { TestUsers.softDelete(it) } }

    private fun LiveHub.pendingNow() = pendingAuth.get()

    private suspend fun LiveHub.awaitPending(count: Int) = withTimeout(10_000) { while (pendingNow() != count) delay(20) }

    private suspend fun LiveTestSocket.expectClose(code: Short) {
        val reason = assertNotNull(awaitClose(), "the server closed with no reason")
        assertEquals(code, reason.code, "close code (reason: ${reason.message})")
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String): RoomActor = seedActor(prefix).also { minted += it.id }

    @Test
    fun `the address key is the whole IPv4 address, an IPv6 address by its 64, and an IPv4-mapped one as IPv4`() {
        assertEquals("203.0.113.9", addressKey("203.0.113.9"))
        assertNotEquals(addressKey("203.0.113.9"), addressKey("203.0.113.10"))
        val a = addressKey("2001:db8:1:2::1")
        assertEquals(a, addressKey("2001:DB8:1:2:ffff:ffff:ffff:ffff"), "the same /64, any spelling")
        assertEquals(a, addressKey("[2001:db8:1:2::7]"), "brackets")
        assertEquals(a, addressKey("2001:db8:1:2::1%eth0"), "a zone id")
        assertNotEquals(a, addressKey("2001:db8:1:3::1"), "the next /64")
        assertEquals("203.0.113.9", addressKey("::ffff:203.0.113.9"))
        // Text that is no address literal is no key at all (the call falls back to the TCP peer, `ClientAddressKeyTest`); a resolver is
        // never asked about it either.
        val junk = listOf(".a:b", "a:b", "evil.example:80", "1:2:3:4:5:6:7:8:9", " LocalHost ", "not:an:address!", "", "999.1.1.1", "1.2.3")
        for (text in junk + "x".repeat(500)) {
            assertNull(addressKey(text), "'${text.take(20)}' is not an address")
        }
    }

    @Test
    fun `one address cannot take every unauthenticated slot, a second still gets in, and an IPv6 64 counts as one`() = testApplication {
        configureApp(
            "http.behindProxy" to "true", "live.maxPendingAuth" to "6", "live.maxPendingPerAddress" to "2",
            "live.authTimeoutMillis" to "30000",
        )
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val ws = liveClient()
        val http = jsonClient()
        suspend fun admit(address: String) = http.get(path) {
            header(HttpHeaders.Origin, LIVE_TEST_ORIGIN)
            header(HttpHeaders.XForwardedFor, address)
        }.status
        suspend fun open(address: String) = openLive(ws) { header(HttpHeaders.XForwardedFor, address) }
        val opened = mutableListOf<LiveTestSocket>()

        opened += open("203.0.113.9")
        opened += open("203.0.113.9")
        hub.awaitPending(2)
        withAuditCapture { capture ->
            val refused = http.get(path) {
                header(HttpHeaders.Origin, LIVE_TEST_ORIGIN)
                header(HttpHeaders.XForwardedFor, "203.0.113.9")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, refused.status, "the address holds its share")
            assertEquals(LIVE_UNAVAILABLE_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
            assertNotNull(refused.headers[HttpHeaders.RetryAfter])
            val line = assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "capacity") })
            assertTrue(line.hasKeyValue("limit", "pending-per-address"))
            assertNull(line.keyValuePairs?.firstOrNull { it.key == "ip" }, "no client address in the audit line")
        }
        assertTrue(runCatching { open("203.0.113.9") }.isFailure, "the third upgrade from the address is refused")
        assertEquals(2, hub.pendingNow())

        // Another address is untouched by the first one's share.
        assertEquals(HttpStatusCode.BadRequest, admit("203.0.113.10"), "admitted, then a plain GET is a 400")
        opened += open("203.0.113.10")
        hub.awaitPending(3)

        // An IPv6 /64 is ONE client: two sockets from different addresses of it fill its share.
        opened += open("2001:db8:1:2::1")
        opened += open("2001:db8:1:2:aaaa::9")
        hub.awaitPending(5)
        assertEquals(HttpStatusCode.ServiceUnavailable, admit("2001:db8:1:2:bbbb::1"), "a third address of the same /64")
        assertEquals(HttpStatusCode.BadRequest, admit("2001:db8:1:3::1"), "the next /64 is another client")

        // The global cap still binds: the sixth slot goes to a new address, the seventh finds the table full.
        opened += open("198.51.100.1")
        hub.awaitPending(6)
        assertEquals(HttpStatusCode.ServiceUnavailable, admit("198.51.100.2"), "the global cap")

        // Closing the sockets frees the address.
        opened.forEach { it.close() }
        hub.awaitPending(0)
        assertEquals(HttpStatusCode.BadRequest, admit("203.0.113.9"), "the share is free again")
    }

    @Test
    fun `upgrades racing past the advisory check never hold more than the per-address share`() = testApplication {
        configureApp(
            "http.behindProxy" to "true", "live.maxPendingAuth" to "50", "live.maxPendingPerAddress" to "1",
            "live.authTimeoutMillis" to "30000",
        )
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val ws = liveClient()
        val sockets = coroutineScope {
            (1..16).map { async { runCatching { openLive(ws) { header(HttpHeaders.XForwardedFor, "203.0.113.50") } } } }
                .awaitAll().mapNotNull { it.getOrNull() }
        }
        // Whether a loser was turned away at admission (no socket) or at the session's own claim (closed 1013), the share holds. Which of
        // the two paths each loser took depends on timing, so the claim race itself is pinned deterministically by `LiveHubTest`
        // ("the pending-auth claim never exceeds its caps"); this proves the end to end invariant.
        withTimeout(10_000) { while (sockets.count { it.session.closeReason.isCompleted } < sockets.size - 1) delay(20) }
        assertEquals(1, hub.pendingNow(), "exactly one socket holds the address's slot")
        sockets.filter { it.session.closeReason.isCompleted }.forEach { assertEquals(1013, it.awaitClose()?.code) }
        sockets.forEach { it.close() }
    }

    @Test
    fun `the defaults are 8 unauthenticated sockets per address and a 2 second deadline`() = testApplication {
        configureApp("http.behindProxy" to "true")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val ws = liveClient()
        val sockets = (1..8).map { openLive(ws) { header(HttpHeaders.XForwardedFor, "203.0.113.77") } }
        hub.awaitPending(8)
        val ninth = jsonClient().get(path) {
            header(HttpHeaders.Origin, LIVE_TEST_ORIGIN)
            header(HttpHeaders.XForwardedFor, "203.0.113.77")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, ninth.status)
        val started = System.nanoTime()
        sockets.first().expectClose(4401)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(seconds < 4.0, "the default deadline is 2 s, took $seconds s")
        sockets.forEach { it.close() }
    }

    @Test
    fun `the pending count returns to zero after a deadline, a bad first frame and an unknown ticket`() = testApplication {
        configureApp("live.authTimeoutMillis" to "300")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val ws = liveClient()

        val silent = openLive(ws)
        silent.expectClose(4401)
        hub.awaitPending(0)

        val early = openLive(ws)
        early.sendText("""{"type":"subscribe","roomIds":[1]}""")
        early.expectClose(1008)
        hub.awaitPending(0)

        val junk = openLive(ws)
        junk.sendText("not json")
        junk.expectClose(1008)
        hub.awaitPending(0)

        val unknown = openLive(ws)
        unknown.auth("x".repeat(43))
        unknown.expectClose(4401)
        hub.awaitPending(0)

        val gone = openLive(ws)
        gone.close() // the client leaves before sending anything
        hub.awaitPending(0)
    }

    @Test
    fun `a good ticket moves the socket out of the pending count`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val user = actor("pending-ok")
        val socket = openAuthenticatedLive(liveClient(), user)
        hub.awaitPending(0)
        assertEquals(1, hub.socketsOf(user.id))
        socket.close()
    }

    @Test
    fun `fragments that total more than 8 KiB close 1009 before authentication and free the slot`() = testApplication {
        configureApp("live.authTimeoutMillis" to "30000")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val socket = openLive(liveClient())
        hub.awaitPending(1)
        val part = ByteArray(4_000) { 'a'.code.toByte() }
        // The server closes at the third fragment; later sends may fail on the closed socket.
        runCatching { repeat(4) { socket.session.outgoing.send(Frame.Text(false, part)) } }
        socket.expectClose(1009)
        hub.awaitPending(0)
    }

    @Test
    fun `protocol pings do not extend the authentication deadline`() = testApplication {
        configureApp("live.authTimeoutMillis" to "600")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val socket = openLive(liveClient())
        val started = System.nanoTime()
        coroutineScope {
            val pinger = async {
                runCatching {
                    repeat(40) {
                        socket.session.outgoing.send(Frame.Ping(ByteArray(0)))
                        delay(50)
                    }
                }
            }
            socket.expectClose(4401)
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(millis < 1_800, "closed after $millis ms; the deadline is 600 ms and pings (every 50 ms for 2 s) must not move it")
            pinger.cancel()
        }
        hub.awaitPending(0)
    }
}
