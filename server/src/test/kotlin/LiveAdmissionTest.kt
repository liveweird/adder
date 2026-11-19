package ch.nokillswit

import ch.nokillswit.live.LIVE_UNAVAILABLE_PROBLEM_TYPE
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.live.originAllowed
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Admission to `GET /api/v1/live` (`live/LiveAdmission.kt`): the Origin rule (CSWSH), the per-IP bucket and the `503`s of a stopping or
 * full server. A plain (non-upgrade) GET passes the same gates and then answers 400, so every refusal here is a plain `jsonClient()` GET
 * (validated against the spec); the upgrade itself is `LiveSocketTest`.
 */
class LiveAdmissionTest {
    private val path = "/api/v1/live"
    private val minted = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { minted.forEach { TestUsers.softDelete(it) } }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.actor(prefix: String): RoomActor =
        seedActor(prefix).also { minted += it.id }

    private fun allowed(origin: String?, host: String, port: Int, vararg cors: String) = originAllowed(origin, host, port, cors.toList())

    @Test
    fun `the Origin matcher table - default ports, case, IPv6, null, missing, the cors list`() {
        // Same host and port, in the spellings a browser and a proxy produce.
        assertTrue(allowed("http://localhost", "localhost", 80), "testApplication: Host localhost, port 80")
        assertTrue(allowed("http://localhost:80", "localhost", 80), "an explicit default port")
        assertTrue(allowed("HTTP://LocalHost", "localhost", 80), "scheme and host are case-insensitive")
        assertTrue(allowed("http://localhost:8087", "localhost", 8087), "compose")
        assertTrue(allowed("http://localhost:5179", "localhost", 5179), "Vite, Host preserved")
        assertTrue(allowed("https://app.example.com", "app.example.com", 443), "behind a TLS proxy: X-Forwarded-Proto https")
        assertTrue(allowed("https://app.example.com:443", "APP.example.com", 443))
        assertTrue(allowed("http://[::1]:8087", "[::1]", 8087), "IPv6 literal, brackets on both sides")
        assertTrue(allowed("http://[::1]:8087", "::1", 8087), "brackets are not part of the comparison")
        // Every other authority is a different origin.
        assertFalse(allowed("http://localhost", "localhost", 8087), "default port 80 is not 8087")
        assertFalse(allowed("http://localhost:8087", "localhost", 80))
        assertFalse(allowed("https://localhost", "localhost", 80), "https defaults to 443")
        assertFalse(allowed("http://evil.example", "localhost", 80))
        assertFalse(allowed("http://localhost.evil.example", "localhost", 80), "a suffix is not the host")
        assertFalse(allowed("http://evil.example/localhost", "localhost", 80), "a path is not an origin")
        assertFalse(allowed("http://user@localhost", "localhost", 80), "user info is not an origin")
        assertFalse(allowed("http://localhost:99999", "localhost", 99999), "an impossible port")
        // No usable header.
        assertFalse(allowed(null, "localhost", 80), "missing")
        assertFalse(allowed("null", "localhost", 80), "an opaque origin")
        assertFalse(allowed("", "localhost", 80))
        assertFalse(allowed("ftp://localhost", "localhost", 80), "only http and https")
        assertFalse(allowed("file://localhost", "localhost", 80))
        assertFalse(allowed("localhost", "localhost", 80), "no scheme")
        // `http.corsHosts`: host[:port], any http(s) scheme; no port means the scheme's default.
        assertTrue(allowed("https://other.example.com", "app.example.com", 443, "other.example.com"))
        assertTrue(allowed("http://other.example.com", "app.example.com", 443, "other.example.com"))
        assertTrue(allowed("https://other.example.com:8443", "app.example.com", 443, "other.example.com:8443"))
        assertTrue(allowed("https://OTHER.example.com", "app.example.com", 443, " other.example.com "))
        assertFalse(allowed("https://other.example.com:8443", "app.example.com", 443, "other.example.com"), "an unnamed port")
        assertFalse(allowed("https://other.example.com", "app.example.com", 443, "other.example.com:8443"))
        assertFalse(allowed("https://third.example.com", "app.example.com", 443, "other.example.com"))
        assertFalse(allowed("null", "app.example.com", 443, "other.example.com"))
        assertFalse(allowed("https://other.example.com", "app.example.com", 443, "not a host"), "a malformed entry matches nothing")
    }

    @Test
    fun `a cross-origin, null, non-http or missing Origin is 403 and audited once`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        withAuditCapture { capture ->
            for (origin in listOf("http://evil.example", "null", "ftp://localhost", "http://localhost:8087")) {
                val response = client.get(path) { header(HttpHeaders.Origin, origin) }
                assertEquals(HttpStatusCode.Forbidden, response.status, origin)
                assertEquals(403, response.body<ProblemDetail>().status)
            }
            assertEquals(HttpStatusCode.Forbidden, client.get(path).status, "no Origin header at all")

            val event = capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "origin") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("suppressed", 0L))
            assertEquals(1, capture.events.count { it.message == "live.refused" }, "coalesced: one line per reason a minute")
            assertTrue(capture.events.none { it.message == "authz.denied" }, "live.refused is the only line, not a second authz.denied")
        }
    }

    @Test
    fun `a same-origin plain GET passes admission and is 400, never a 401 or a 101`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("Expected a WebSocket upgrade", response.body<ProblemDetail>().detail)
    }

    @Test
    fun `an Origin listed in the cors hosts is admitted`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        val client = jsonClient()
        assertEquals(HttpStatusCode.BadRequest, client.get(path) { header(HttpHeaders.Origin, "https://app.example.com") }.status)
        // A foreign origin never reaches the route when CORS is on: the CORS plugin itself refuses it first (a bodiless 403, for
        // every route).
        val sameOrigin = client.get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }
        assertEquals(HttpStatusCode.BadRequest, sameOrigin.status, "same-origin still passes")
    }

    @Test
    fun `behind a proxy the Origin is judged against the forwarded host and scheme`() = testApplication {
        configureApp("http.behindProxy" to "true")
        startApplication()
        val client = jsonClient()
        suspend fun status(origin: String, forwarded: Boolean = true) = client.get(path) {
            header(HttpHeaders.Origin, origin)
            if (forwarded) {
                header(HttpHeaders.XForwardedHost, "app.example.com")
                header(HttpHeaders.XForwardedProto, "https")
            }
        }.status
        assertEquals(HttpStatusCode.BadRequest, status("https://app.example.com"), "the browser's origin is the forwarded authority")
        assertEquals(HttpStatusCode.Forbidden, status("http://app.example.com"), "a plain-http origin against an https front door")
        assertEquals(HttpStatusCode.Forbidden, status("https://evil.example"))
        assertEquals(HttpStatusCode.Forbidden, status("https://app.example.com", forwarded = false), "the forwarded headers decide")
    }

    @Test
    fun `the per-IP connect bucket answers 429 past its limit and audits it`() = testApplication {
        configureApp("security.rateLimit.liveConnectPerMinute" to "2")
        startApplication()
        val client = jsonClient()
        withAuditCapture { capture ->
            repeat(2) { assertEquals(HttpStatusCode.BadRequest, client.get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }.status) }
            val limited = client.get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals(429, limited.body<ProblemDetail>().status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "live-connect") }
            assertNotNull(event)
            assertNull(event.keyValuePairs?.firstOrNull { it.key == "byUserId" }, "a per-IP bucket names no user")
        }
    }

    @Test
    fun `a full server answers 503 on the upgrade and on the mint, and frees up when the socket closes`() = testApplication {
        configureApp("live.maxSockets" to "1")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val holder = actor("live-full-a")
        val other = actor("live-full-b")
        val socket = openAuthenticatedLive(liveClient(), holder)
        withAuditCapture { capture ->
            val plain = jsonClient().get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }
            assertEquals(HttpStatusCode.ServiceUnavailable, plain.status)
            assertNotNull(plain.headers[HttpHeaders.RetryAfter])
            assertEquals(LIVE_UNAVAILABLE_PROBLEM_TYPE, plain.body<ProblemDetail>().type)

            val mint = other.client.post("/api/v1/live/tickets")
            assertEquals(HttpStatusCode.ServiceUnavailable, mint.status)
            assertNotNull(mint.headers[HttpHeaders.RetryAfter])
            assertEquals(LIVE_UNAVAILABLE_PROBLEM_TYPE, mint.body<ProblemDetail>().type)

            assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "capacity") })
        }
        socket.close()
        withTimeout(10_000) { while (hub.socketCount > 0) delay(25) }
        assertEquals(HttpStatusCode.OK, other.client.post("/api/v1/live/tickets").status, "the slot is free again")
    }

    @Test
    fun `sockets that never authenticate are capped at 503 and release their slot when they do`() = testApplication {
        configureApp("live.maxPendingAuth" to "1")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val user = actor("live-pending")
        val ws = liveClient()
        val http = jsonClient()
        val pending = openLive(ws)
        withTimeout(10_000) { while (hub.pendingAuth.get() < 1) delay(25) }

        val refused = http.get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        assertEquals(LIVE_UNAVAILABLE_PROBLEM_TYPE, refused.body<ProblemDetail>().type)

        pending.auth(user.client.mintLiveTicket().ticket)
        assertEquals("ready", pending.nextFrame().let { if (it is ch.nokillswit.live.LiveServerFrame.Ready) "ready" else "$it" })
        withTimeout(10_000) { while (hub.pendingAuth.get() > 0) delay(25) }
        assertEquals(HttpStatusCode.BadRequest, http.get(path) { header(HttpHeaders.Origin, LIVE_TEST_ORIGIN) }.status, "admitted again")
        pending.close()
    }
}
