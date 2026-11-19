package ch.nokillswit

import ch.nokillswit.live.LiveAudit
import ch.nokillswit.live.MAX_DENIED_LISTED
import ch.nokillswit.live.MAX_ORIGIN_KEYS
import ch.nokillswit.live.OriginVerdict
import ch.nokillswit.plugins.RateLimitAuditThrottle
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The coalescing of the live channel's audit lines (`live/LiveAudit.kt`) on a fake clock: no application, no database. The audit logger
 * is captured as in every audit test; the throttles are the real [RateLimitAuditThrottle] with an injected clock.
 */
class LiveAuditTest {
    private var now = 0L
    private val window = RateLimitAuditThrottle.DEFAULT_INTERVAL_MS

    private fun audit() = LiveAudit(RateLimitAuditThrottle(clock = { now }), RateLimitAuditThrottle(clock = { now }))

    @Test
    fun `an origin refusal is coalesced per origin host, so one site's probe does not hide another's`() = runBlocking {
        val audit = audit()
        withAuditCapture { capture ->
            repeat(5) { audit.refusedOrigin("http://evil.example", OriginVerdict.FOREIGN) }
            audit.refusedOrigin("https://other.example", OriginVerdict.SCHEME)
            audit.refusedOrigin("http://EVIL.example:8087", OriginVerdict.FOREIGN)
            val lines = capture.events.filter { it.message == "live.refused" }
            assertEquals(2, lines.size, "evil.example once (5 + 1 folded), other.example once")
            assertTrue(lines.any { it.hasKeyValue("origin", "https://other.example") && it.hasKeyValue("detail", "scheme") })
            now += window
            audit.refusedOrigin("http://evil.example", OriginVerdict.FOREIGN)
            val next = capture.events.filter { it.message == "live.refused" }.last()
            assertTrue(next.hasKeyValue("suppressed", 5L), "the folded refusals of the window are counted")
        }
    }

    @Test
    fun `past 64 origin hosts the rest share one window`() = runBlocking {
        val audit = audit()
        withAuditCapture { capture ->
            repeat(MAX_ORIGIN_KEYS + 30) { audit.refusedOrigin("http://host-$it.example", OriginVerdict.FOREIGN) }
            val lines = capture.events.count { it.message == "live.refused" }
            assertEquals(MAX_ORIGIN_KEYS + 1, lines, "64 hosts of their own, then ONE shared window for the other 30")
            audit.refusedOrigin("http://host-3.example", OriginVerdict.FOREIGN)
            assertEquals(lines, capture.events.count { it.message == "live.refused" }, "a known host keeps its own (suppressed) window")
        }
    }

    @Test
    fun `a limit-qualified refusal does not hide a different limit`() = runBlocking {
        val audit = audit()
        withAuditCapture { capture ->
            audit.refused("capacity", null, "limit" to "pending-per-address")
            audit.refused("capacity", null, "limit" to "pending-per-address")
            audit.refused("capacity", null, "limit" to "pending-auth")
            assertEquals(2, capture.events.count { it.message == "live.refused" })
        }
    }

    @Test
    fun `a denied-subscribe line lists at most 20 rooms, and the flush writes the tail a stopped probe left`() = runBlocking {
        val audit = audit()
        withAuditCapture { capture ->
            audit.subscribeDenied(7u, listOf(1u)) // the first call writes at once
            repeat(3) { chunk -> audit.subscribeDenied(7u, ((chunk * 10 + 2)..(chunk * 10 + 11)).map { it.toUInt() }) } // 30 ids, folded
            val first = capture.events.single { it.message == "live.subscribe_denied" }
            assertTrue(first.hasKeyValue("roomIds", listOf(1L)))

            audit.flushDenied()
            assertEquals(1, capture.events.count { it.message == "live.subscribe_denied" }, "the window is not due: nothing is flushed")

            now += window
            audit.flushDenied()
            val tail = capture.events.last { it.message == "live.subscribe_denied" }
            assertEquals(2, capture.events.count { it.message == "live.subscribe_denied" })
            assertTrue(tail.hasKeyValue("suppressed", 3L), "the three folded calls are counted; a flush adds none")
            val ids = assertNotNull(tail.keyValuePairs?.firstOrNull { it.key == "roomIds" }?.value) as List<*>
            assertEquals(MAX_DENIED_LISTED, ids.size, "capped at 20")
            assertEquals((2L..21L).toList(), ids)

            now += window * 2
            audit.flushDenied()
            assertEquals(2, capture.events.count { it.message == "live.subscribe_denied" }, "a flushed tail is written once")
        }
    }

    @Test
    fun `a flush that finds an empty window writes nothing and a flush does not eat the next call's line`() = runBlocking {
        val audit = audit()
        withAuditCapture { capture ->
            audit.flushDenied()
            audit.subscribeDenied(9u, listOf(5u))
            audit.subscribeDenied(9u, listOf(6u)) // folded
            now += window
            audit.flushDenied() // writes [6], restarts the window
            audit.subscribeDenied(9u, listOf(8u)) // inside the new window: folded, not written
            assertEquals(2, capture.events.count { it.message == "live.subscribe_denied" })
            now += window
            audit.subscribeDenied(9u, listOf(9u)) // a due window: [8, 9] written by the call itself
            val last = capture.events.last { it.message == "live.subscribe_denied" }
            assertTrue(last.hasKeyValue("roomIds", listOf(8L, 9L)))
        }
    }
}
