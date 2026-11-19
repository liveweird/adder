package ch.nokillswit

import ch.nokillswit.live.INBOUND_FRAME_LIMIT
import ch.nokillswit.live.INBOUND_WINDOW_MILLIS
import ch.nokillswit.live.InboundRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sliding inbound-frame limit of one socket (`live/LiveInboundRate.kt`), under an injected clock. */
class LiveInboundRateTest {
    @Test
    fun `the 31st frame inside the window is refused, and the window slides`() {
        var now = 1_000_000L
        val rate = InboundRate(clock = { now })
        repeat(INBOUND_FRAME_LIMIT) {
            assertTrue(rate.allow(), "frame ${it + 1}")
            now += 1 // distinct stamps, so the window frees ONE slot at a time
        }
        assertFalse(rate.allow(), "the 31st frame")
        assertFalse(rate.allow(), "a refused frame records nothing, so it stays refused")

        now = 1_000_000L + INBOUND_WINDOW_MILLIS - 1
        assertFalse(rate.allow(), "the oldest frame is still inside the window")
        now = 1_000_000L + INBOUND_WINDOW_MILLIS
        assertTrue(rate.allow(), "the oldest frame left the window")
        assertFalse(rate.allow(), "but only one slot freed")
    }

    @Test
    fun `a steady rate under the limit is never refused`() {
        var now = 0L
        val rate = InboundRate(clock = { now })
        // 3 frames a second for a minute: 30 per 10 s is the limit, 29 fit.
        repeat(180) {
            assertTrue(rate.allow(), "frame $it")
            now += INBOUND_WINDOW_MILLIS / 29
        }
        assertEquals(true, rate.allow())
    }
}
