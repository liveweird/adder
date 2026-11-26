package ch.nokillswit

import ch.nokillswit.infra.notify.LIVE_CHANNEL
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.LiveNotification
import ch.nokillswit.infra.notify.decodeLiveNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.postgresql.PGConnection

/**
 * A plain JDBC `LISTEN adder_live` session for tests (the production listener is the 0.4.0 S2 step): it records what the server
 * NOTIFYs, so a test asserts which hints a write emitted. PostgreSQL delivers a NOTIFY on commit, in commit order, and the HTTP answer of a
 * write is sent after its commit, so [during] can bracket a run of writes with a SENTINEL hint of its own: everything received before the
 * sentinel was emitted by the steps, in order, and nothing the steps emitted can arrive after it. That is how "emits nothing" is proven
 * without sleeping. Use it in a `use { }`; hints are compared whole (kind, room, thread number, message id, targets).
 */
class NotifyCapture : AutoCloseable {
    private val connection = PostgresTestSupport.connect().also { it.createStatement().use { st -> st.execute("LISTEN $LIVE_CHANNEL") } }
    private val pg: PGConnection = connection.unwrap(PGConnection::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val received = mutableListOf<LiveNotification>()

    private fun take(timeoutMillis: Int) {
        val batch = if (timeoutMillis > 0) pg.getNotifications(timeoutMillis) else pg.notifications
        batch?.forEach { received += checkNotNull(decodeLiveNotification(it.parameter)) }
    }

    /** Emits the sentinel (a `room.updated` for room 0 with an impossible message id) outside any app transaction. */
    private fun sendSentinel() {
        connection.prepareStatement("SELECT pg_notify(?, ?)").use { st ->
            st.setString(1, LIVE_CHANNEL)
            st.setString(2, json.encodeToString(LiveHint.serializer(), SENTINEL))
            st.execute()
        }
    }

    /** The hints [steps] emitted, in commit order (the backlog before it is dropped, the sentinel is not part of the answer). */
    suspend fun during(steps: suspend () -> Unit): List<LiveHint> = duringNotifications(steps).map {
        checkNotNull((it as? LiveNotification.Room)?.hint) { "Expected a room hint, got $it" }
    }

    /** Every internal notification [steps] emitted, including roomless account prompts. */
    suspend fun duringNotifications(steps: suspend () -> Unit): List<LiveNotification> {
        withContext(Dispatchers.IO) {
            take(0)
            received.clear()
        }
        steps()
        return withContext(Dispatchers.IO) {
            sendSentinel()
            val deadline = System.nanoTime() + SENTINEL_WAIT_NANOS
            while (SENTINEL_NOTIFICATION !in received && System.nanoTime() < deadline) take(POLL_MILLIS)
            check(SENTINEL_NOTIFICATION in received) { "The sentinel hint never arrived: the LISTEN session is not receiving" }
            received.takeWhile { it != SENTINEL_NOTIFICATION }.also { received.clear() }
        }
    }

    override fun close() = connection.close()

    private companion object {
        val SENTINEL = LiveHint(LiveKind.ROOM_UPDATED, 0u, messageId = UInt.MAX_VALUE)
        val SENTINEL_NOTIFICATION = LiveNotification.Room(SENTINEL)
        const val POLL_MILLIS = 100
        const val SENTINEL_WAIT_NANOS = 10_000_000_000L
    }
}
