package ch.nokillswit

import ch.nokillswit.infra.db.NotifyConnectionFactoryKey
import ch.nokillswit.infra.db.notifyConnectionFactory
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.NotifyListener
import ch.nokillswit.infra.notify.NotifyListenerTimings
import ch.nokillswit.infra.notify.toPayload
import io.ktor.server.testing.testApplication
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Statement
import io.r2dbc.spi.Wrapped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The LISTEN connection (`infra/notify/NotifyListener.kt`, `persistence.md` "The LISTEN connection"), with no app, against the shared
 * container: every test builds its own listener over a connection factory under a unique `application_name`, and plays the writer through
 * plain JDBC (`pg_notify` inside a transaction it commits or rolls back).
 */
class NotifyListenerTest {
    private val fast = NotifyListenerTimings(
        heartbeatInterval = 150.milliseconds,
        stepTimeout = 3_000.milliseconds,
        backoffInitial = 50.milliseconds,
        backoffMax = 200.milliseconds,
    )

    private fun options(appName: String): ConnectionFactoryOptions =
        ConnectionFactoryOptions.parse(PostgresTestSupport.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
            .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
            .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, appName)
            .build()

    private fun hint(messageId: UInt) = LiveHint(LiveKind.MESSAGE_POSTED, 7u, threadNumber = 3, messageId = messageId)

    /** A committed (or rolled-back) NOTIFY of [hint] from a plain JDBC session, as a chat write's transaction would emit it. */
    private fun emit(hint: LiveHint, commit: Boolean = true) {
        PostgresTestSupport.connect().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("SELECT pg_notify('adder_live', ?)").use { st ->
                st.setString(1, hint.toPayload())
                st.execute()
            }
            if (commit) conn.commit() else conn.rollback()
        }
    }

    private fun backends(appName: String): Int = PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE application_name = ?").use { st ->
            st.setString(1, appName)
            st.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
        }
    }

    private fun terminate(pid: Int) = PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement("SELECT pg_terminate_backend(?)").use { st ->
            st.setInt(1, pid)
            st.execute()
        }
    }

    private suspend fun awaitUntil(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            delay(25)
        }
    }

    /** Runs [block] with a started listener over [factory] (own scope, stopped and cancelled afterwards). */
    private fun withListener(
        factory: ConnectionFactory,
        timings: NotifyListenerTimings = fast,
        gaps: AtomicInteger = AtomicInteger(),
        block: suspend (NotifyListener) -> Unit,
    ) = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = NotifyListener(factory, scope, { gaps.incrementAndGet() }, timings)
        try {
            listener.ensureStarted()
            awaitUntil("the listener to come up") { listener.isUp }
            withTimeout(60_000) { block(listener) }
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    private suspend fun NotifyListener.next(): String = withTimeout(10_000) { payloads.receive() }

    @Test
    fun `the listener receives a committed hint and never a rolled-back one`() {
        withListener(notifyConnectionFactory(options("adder-test-live-${UUID.randomUUID()}"))) { listener ->
            emit(hint(1u), commit = false)
            emit(hint(2u))
            assertEquals(hint(2u).toPayload(), listener.next())
            // A rolled-back NOTIFY is never delivered, and the next one arrives in commit order.
            emit(hint(3u))
            assertEquals(hint(3u).toPayload(), listener.next())
            assertNull(listener.payloads.tryReceive().getOrNull())
        }
    }

    @Test
    fun `a terminated backend reconnects and reports the gap`() {
        val gaps = AtomicInteger()
        withListener(notifyConnectionFactory(options("adder-test-live-${UUID.randomUUID()}")), gaps = gaps) { listener ->
            val before = assertNotNull(listener.backendPid)
            assertEquals(1, gaps.get(), "the first LISTEN is a gap too, signalled before the listener reports itself up")
            terminate(before)
            awaitUntil("a new session after the termination") { listener.isUp && listener.backendPid != before }
            assertNotEquals(before, listener.backendPid)
            assertEquals(2, gaps.get(), "one reconnect reports exactly one more gap")
            emit(hint(4u))
            assertEquals(hint(4u).toPayload(), listener.next())
        }
    }

    @Test
    fun `a full channel reports a gap instead of blocking`() {
        val gaps = AtomicInteger()
        // The heartbeat is slow here: a heartbeat must not be what keeps the listener alive while its channel is full.
        val timings = fast.copy(channelCapacity = 1, heartbeatInterval = 600_000.milliseconds)
        withListener(notifyConnectionFactory(options("adder-test-live-${UUID.randomUUID()}")), timings, gaps) { listener ->
            val pid = listener.backendPid
            assertEquals(1, gaps.get())
            // Nobody consumes: the first payload fills the channel, the next two are dropped, each one reported.
            emit(hint(1u))
            emit(hint(2u))
            emit(hint(3u))
            awaitUntil("both overflowing payloads to be reported") { gaps.get() == 3 }
            assertTrue(listener.isUp, "an overflow must not take the session down")
            assertEquals(pid, listener.backendPid)
            // The channel kept the first payload; once drained the listener still delivers.
            assertEquals(hint(1u).toPayload(), listener.next())
            emit(hint(5u))
            assertEquals(hint(5u).toPayload(), listener.next())
            assertEquals(3, gaps.get())
        }
    }

    @Test
    fun `a heartbeat that never answers abandons the connection and reconnects`() {
        val gaps = AtomicInteger()
        val real = notifyConnectionFactory(options("adder-test-live-${UUID.randomUUID()}"))
        val stalled = AtomicInteger()
        // Only the first connection's heartbeat stalls (a statement that outlives the step timeout), like a half-open socket would.
        // The healthy sessions run under the same timeout, so the assertions below tolerate a slow connect that costs one more retry.
        val factory = object : ConnectionFactory by real {
            override fun create(): Publisher<out Connection> =
                Mono.from(real.create()).map { if (stalled.getAndIncrement() == 0) StalledHeartbeat(it) else it }
        }
        withListener(factory, fast.copy(stepTimeout = 600.milliseconds), gaps) { listener ->
            awaitUntil("the stalled heartbeat to cost the first session") { gaps.get() >= 2 && listener.isUp }
            assertTrue(stalled.get() >= 2, "the listener reconnected after the stalled heartbeat")
            emit(hint(6u))
            assertEquals(hint(6u).toPayload(), listener.next())
        }
    }

    @Test
    fun `the connection is named adder-live and closes on stop`() = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val appName = "adder-test-live-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = NotifyListener(notifyConnectionFactory(options(appName)), scope, {}, fast)
        try {
            assertEquals(0, backends(appName))
            listener.ensureStarted()
            listener.ensureStarted() // idempotent: still one connection
            awaitUntil("the listener to come up") { listener.isUp }
            assertEquals(1, backends(appName))
            listener.stop()
            assertEquals(false, listener.isUp)
            awaitUntil("the backend to go") { backends(appName) == 0 }
            assertTrue(listener.payloads.tryReceive().isClosed)
            listener.ensureStarted() // a stopped listener stays stopped
            delay(200)
            assertEquals(0, backends(appName))
        } finally {
            listener.stop()
            scope.cancel()
        }
    }

    @Test
    fun `stop racing ensureStarted never leaves a connection behind`() = runBlocking {
        PostgresTestSupport.ensureMigrated()
        val appName = "adder-test-live-${UUID.randomUUID()}"
        val factory = notifyConnectionFactory(options(appName))
        // The scope is never cancelled inside the loop: only stop() may be what ends the listener a racing ensureStarted() launched.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(15) {
                val listener = NotifyListener(factory, scope, {}, fast)
                val starter = async(Dispatchers.Default) { listener.ensureStarted() }
                val stopper = async(Dispatchers.Default) { listener.stop() }
                starter.await()
                stopper.await()
                awaitUntil("no backend left after a racing stop") { backends(appName) == 0 }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the app publishes the listener factory under the pool name with a live suffix`() = testApplication {
        val appName = "adder-test-${UUID.randomUUID()}"
        configureApp("postgres.pool.applicationName" to appName)
        startApplication()
        val connection = application.attributes[NotifyConnectionFactoryKey].create().let { Mono.from(it).awaitSingle() }
        try {
            assertEquals(1, backends("$appName-live"))
        } finally {
            Mono.from(connection.close()).awaitFirstOrNull()
        }
        awaitUntil("the live backend to go") { backends("$appName-live") == 0 }
    }

    /** A connection whose `SELECT 1` heartbeat sleeps for 2 s: the listener's step timeout must cut it. */
    private class StalledHeartbeat(private val delegate: Connection) : Connection by delegate, Wrapped<Connection> {
        override fun unwrap(): Connection = delegate

        override fun createStatement(sql: String): Statement =
            delegate.createStatement(if (sql == "SELECT 1") "SELECT pg_sleep(2)" else sql)
    }
}
