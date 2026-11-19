package ch.nokillswit.live

/** Inbound frames one socket may send per [INBOUND_WINDOW_MILLIS] (`live.md` "Bounds"); the 31st inside a window closes 1008. */
const val INBOUND_FRAME_LIMIT = 30

/** The sliding window of [INBOUND_FRAME_LIMIT]. */
const val INBOUND_WINDOW_MILLIS = 10_000L

/** A monotonic clock (the wall clock can step back and freeze or flood the window). */
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The inbound frame rate of ONE socket: a sliding log of the last [limit] frame times, so the answer is exact (no fixed-window burst of
 * twice the limit at a boundary) and the memory is a fixed array. Every message the reading loop receives counts, an application `ping`
 * included; WebSocket protocol ping, pong and continuation frames never reach the loop (Ktor answers and reassembles them below it), so
 * they do not count. A refused frame is not recorded.
 * Owned by one session coroutine: not thread-safe.
 */
internal class InboundRate(
    private val limit: Int = INBOUND_FRAME_LIMIT,
    private val windowMillis: Long = INBOUND_WINDOW_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val stamps = LongArray(limit)
    private var oldest = 0
    private var held = 0

    /** Whether one more frame is within the limit; records it when it is. */
    fun allow(): Boolean {
        val now = clock()
        if (held == limit && now - stamps[oldest] < windowMillis) return false
        stamps[oldest] = now
        oldest = (oldest + 1) % limit
        if (held < limit) held++
        return true
    }
}
