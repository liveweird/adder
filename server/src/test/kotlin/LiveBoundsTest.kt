package ch.nokillswit

import ch.nokillswit.live.INBOUND_FRAME_LIMIT
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.live.LiveServerFrame
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-socket bounds of the live channel (`live.md` "Bounds") that cost something when a client abuses them: the inbound frame rate
 * (1008 and a coalesced audit), a frame over 8 KiB (1009), and a subscribe flood against a room the caller cannot read (one audit line,
 * not one per frame). The binary / malformed / misplaced-frame closes and the 128-subscription `capped` are in `LiveSocketTest`.
 */
class LiveBoundsTest {
    private val users = mutableListOf<UInt>()
    private val roomIds = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(roomIds)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String): RoomActor = seedActor(prefix).also { users += it.id }

    private suspend fun LiveTestSocket.expectClose(code: Short) {
        val reason = assertNotNull(awaitClose(), "the server closed with no reason")
        assertEquals(code, reason.code, "close code (reason: ${reason.message})")
    }

    @Test
    fun `more than 30 frames in 10 seconds closes 1008 and is audited`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val user = actor("bound-rate")
        val socket = openAuthenticatedLive(liveClient(), user)
        withAuditCapture { capture ->
            repeat(INBOUND_FRAME_LIMIT) { socket.sendText("""{"type":"ping"}""") }
            socket.sendText("""{"type":"ping"}""") // the 31st
            socket.expectClose(1008)
            val line = assertNotNull(capture.awaitEvent { it.message == "live.refused" && it.hasKeyValue("reason", "inbound-rate") })
            assertTrue(line.hasKeyValue("byUserId", user.id.toLong()))
        }
        withTimeout(10_000) { while (hub.socketsOf(user.id) > 0) delay(25) }
    }

    @Test
    fun `a frame over 8 KiB closes 1009`() = testApplication {
        usePostgresTestcontainer()
        val user = actor("bound-big")
        val socket = openAuthenticatedLive(liveClient(), user)
        socket.sendText("""{"type":"subscribe","roomIds":[${"1,".repeat(5_000)}1]}""")
        socket.expectClose(1009)
    }

    @Test
    fun `a subscribe flood against a room the caller cannot read is one audit line, not one per frame`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("bound-own")
        val outsider = actor("bound-out")
        val template = TestThreads.seedTemplate().also { templates += it }
        val room = TestRooms.seed(owner.id, template, "Bound ${UUID.randomUUID().toString().substring(0, 8)}").also { roomIds += it }
        val socket = openAuthenticatedLive(liveClient(), outsider)
        withAuditCapture { capture ->
            repeat(20) {
                socket.subscribe(room)
                assertEquals(LiveServerFrame.Subscribed(emptyList(), listOf(room), emptyList()), socket.nextNonResync())
            }
            assertNotNull(capture.awaitEvent { it.message == "live.subscribe_denied" })
            assertEquals(1, capture.events.count { it.message == "live.subscribe_denied" }, "the rest is folded into `suppressed`")
        }
        socket.close()
    }
}
