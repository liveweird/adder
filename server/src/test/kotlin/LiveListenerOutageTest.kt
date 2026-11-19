package ch.nokillswit

import ch.nokillswit.live.LIVE_UNAVAILABLE_PROBLEM_TYPE
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A listener that stays down is invisible to the users it serves: hints committed meanwhile reach nobody (`live.md` "The NOTIFY wire and
 * the listener"). Past `LiveHub.deafAfterMillis` the mint answers `503 live-unavailable` and the open sockets close 1013, so the client's
 * badge appears and it retries; when the listener is back, the next mint works. The hub's threshold is shrunk to 0 (any outage) and its
 * check period to 25 ms BEFORE the first mint, which starts the watcher.
 */
class LiveListenerOutageTest {
    private val minted = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { minted.forEach { TestUsers.softDelete(it) } }

    private fun terminateBackend(pid: Int) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT pg_terminate_backend(?)").use { st ->
            st.setInt(1, pid)
            st.executeQuery().use { it.next() }
        }
    }

    @Test
    fun `a listener that is down answers the mint 503 and closes open sockets 1013, and the next mint works once it is back`() =
        testApplication {
            usePostgresTestcontainer()
            val hub = application.attributes[LiveHubKey]
            hub.deafAfterMillis = 0
            hub.deafCheckMillis = 25
            val user = seedActor("deaf").also { minted += it.id }
            val ticket = user.client.mintLiveTicket().ticket // the first mint starts the listener
            awaitListenerUp(hub)
            val socket = openLive(liveClient())
            socket.auth(ticket)
            assertTrue(socket.nextFrame() is ch.nokillswit.live.LiveServerFrame.Ready)

            withAuditCapture { capture ->
                terminateBackend(assertNotNull(hub.listener.backendPid))
                // Wait for the hub to call itself deaf, then mint ONCE: no request loop against the 60/min bucket.
                withTimeout(10_000) { while (!hub.isDeaf) delay(10) }
                val refused = user.client.post("/api/v1/live/tickets")
                assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
                assertNotNull(refused.headers[HttpHeaders.RetryAfter])
                assertEquals(LIVE_UNAVAILABLE_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
                val line = assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "listener-down") })
                assertTrue(line.hasKeyValue("byUserId", user.id.toLong()))

                assertEquals(1013, assertNotNull(socket.awaitClose(10_000)).code, "the open socket is told to retry")
            }

            awaitListenerUp(hub)
            assertEquals(HttpStatusCode.OK, user.client.post("/api/v1/live/tickets").status, "back up: the channel works again")
        }
}
