package ch.nokillswit.infra.notify

import ch.nokillswit.infra.catchingFailures
import io.r2dbc.postgresql.api.Notification
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.Wrapped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import reactor.core.Disposable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("ch.nokillswit.infra.notify.NotifyListener")

/**
 * The listener's tunables. Every value is a constant of the design (`live.md` "Bounds"), a constructor parameter only so a test can shrink
 * it; none is a config key.
 */
data class NotifyListenerTimings(
    /** Payloads buffered between the driver's event loop and the consumer; a payload that finds the channel full is a gap. */
    val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    /** The `SELECT 1` heartbeat period: a half-open TCP connection leaves the listener "up" but deaf, and only a statement notices. */
    val heartbeatInterval: Duration = 30.seconds,
    /** How long the heartbeat, the connect and the `LISTEN` setup may take before the connection is abandoned. */
    val stepTimeout: Duration = 5.seconds,
    /** The first reconnect delay; it doubles up to [backoffMax], each wait jittered to 50-100 %. */
    val backoffInitial: Duration = 1.seconds,
    val backoffMax: Duration = 30.seconds,
)

const val DEFAULT_CHANNEL_CAPACITY = 1024

/** More decorators than any factory stacks (the leaked-socket guard is one); a deeper chain is a bug, not a connection. */
private const val MAX_UNWRAP_DEPTH = 8

/**
 * The process's ONE dedicated, non-pooled LISTEN connection (`.claude/docs/persistence.md` "The LISTEN connection", `live.md` "The
 * listener"). It owns a session on [LIVE_CHANNEL] and hands every NOTIFY payload to the consumer through [payloads].
 *
 * - **It never stops reading.** The driver publishes notifications through a best-effort multicast sink, so a subscriber without demand
 *   silently loses them; the listener subscribes with unbounded demand and copies each payload into a bounded channel with `trySend`.
 *   A stalled LISTENing connection would also fill the cluster's notification queue and make every notifying commit fail.
 * - **A gap is reported, never blocked on.** A payload that finds the channel full is dropped and [onGap] is called (once per drop).
 *   After EVERY successful LISTEN, the first included, [onGap] is called too, before [isUp] turns true: hints committed while no session
 *   was listening (the first connect's own window, an outage) are gone. The consumer answers a gap with a `resync` to every socket.
 *   [onGap] may be called from the driver's event loop and from the listener's own coroutine, possibly at the same time, so it must be
 *   thread-safe, cheap and non-blocking, and must not throw (an atomic flag or a conflated channel `trySend` is the shape).
 * - **It heals itself.** A flux error or completion, or a failed `SELECT 1` heartbeat, closes the session and reconnects with jittered
 *   backoff. WARN once per outage, INFO on recovery, nothing per notification, no audit.
 *
 * [connectionFactory] must yield connections that are (or wrap, through [Wrapped]) an r2dbc-postgresql `PostgresqlConnection`
 * (`NotifyConnectionFactoryKey`). Start it lazily with [ensureStarted] and end it with [stop]; a stopped listener is not restarted.
 */
class NotifyListener(
    private val connectionFactory: ConnectionFactory,
    private val scope: CoroutineScope,
    private val onGap: () -> Unit,
    private val timings: NotifyListenerTimings = NotifyListenerTimings(),
) {
    private val channel = Channel<String>(timings.channelCapacity)
    private val started = AtomicBoolean(false)

    @Volatile private var job: Job? = null

    // Set by stop() BEFORE it reads `job`; ensureStarted() sets `job` BEFORE it reads this: one of the two always sees the other.
    @Volatile private var stopped = false

    @Volatile private var pid: Int? = null

    // Whether the current session got as far as LISTENing (read by the loop after the session ended; one writer, one reader).
    @Volatile private var reachedUp = false

    /** Raw NOTIFY payloads in commit order, for the one consumer (the hub). Closed by [stop]. */
    val payloads: ReceiveChannel<String> get() = channel

    /** Whether [ensureStarted] (or [stop]) has been called: until then the listener is idle, not down. */
    val isStarted: Boolean get() = started.get()

    /** Whether a session is LISTENing right now (the `live.listener_up` gauge). */
    val isUp: Boolean get() = pid != null

    /** The backend pid of the current session, or null while down (a test seam: `pg_terminate_backend`, `pg_stat_activity`). */
    val backendPid: Int? get() = pid

    // When the listener last stopped being up (or was first started): a monotonic stamp, written by the loop and by ensureStarted().
    @Volatile private var downSinceNanos = System.nanoTime()

    /**
     * How long the listener has been started and NOT up, in milliseconds (a deaf hub: hints committed meanwhile are lost to every
     * socket); null while it is up or has never been started (idle, not down). The live hub turns a long outage into a `503` on the
     * ticket mint and a `1013` close of the open sockets.
     */
    fun downForMillis(): Long? =
        if (!started.get() || isUp) null else (System.nanoTime() - downSinceNanos) / NANOS_PER_MILLI

    /** Starts the session loop on the first call; later calls do nothing. */
    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            downSinceNanos = System.nanoTime()
            // LAZY and published before it starts, so a concurrent stop() either sees the job (and cancels it) or this sees `stopped`.
            val launched = scope.launch(CoroutineName("adder-live-listener"), start = CoroutineStart.LAZY) { supervise() }
            job = launched
            if (stopped) launched.cancel() else launched.start()
        }
    }

    /** Ends the loop, closes the connection (bounded) and closes [payloads]; it closes them even if the caller is cancelled meanwhile. */
    suspend fun stop() {
        stopped = true
        started.set(true)
        try {
            job?.cancelAndJoin()
        } finally {
            channel.close()
        }
    }

    private suspend fun supervise() {
        var backoff = timings.backoffInitial
        var outage = false
        var everUp = false
        while (true) {
            reachedUp = false
            val failure = catchingFailures<Throwable?>({ runSession(first = !everUp, recovering = outage) }, { it })
            if (reachedUp) {
                // A session that listened and then dropped starts a new outage and the backoff schedule over.
                everUp = true
                outage = false
                backoff = timings.backoffInitial
            }
            if (!outage) {
                outage = true
                // Class names only: a driver message can carry the host or the URL.
                if (everUp) {
                    log.warn("live listener lost its database connection ({}); reconnecting with backoff", describe(failure))
                } else {
                    log.warn("live listener could not connect ({}); retrying with backoff", describe(failure))
                }
            }
            delay(backoff * (JITTER_FLOOR + Random.nextDouble() * (1 - JITTER_FLOOR)))
            backoff = minOf(backoff * 2, timings.backoffMax)
        }
    }

    /** One session: connect, subscribe, LISTEN, then watch it until it ends. It only ever ends by throwing. */
    private suspend fun runSession(first: Boolean, recovering: Boolean): Nothing {
        val connection = bounded("connect") { Mono.from(connectionFactory.create()).awaitSingle() }
        val ended = CompletableDeferred<SessionEnd>()
        var subscription: Disposable? = null
        try {
            // Subscribe BEFORE LISTEN so no notification can fall between the two; unbounded demand (see the class comment).
            subscription = connection.postgres().notifications.subscribe(
                ::offer,
                { ended.complete(SessionEnd(it)) },
                { ended.complete(SessionEnd(null)) },
            )
            bounded("listen") { connection.execute("LISTEN $LIVE_CHANNEL") }
            val backend = bounded("pid") { connection.backendPid() }
            // The gap BEFORE `isUp`: hints committed before this LISTEN took effect are gone, and `isUp` then means "gap signalled".
            onGap()
            pid = backend
            reachedUp = true
            if (first) log.info("live listener started") else if (recovering) log.info("live listener recovered")
            watch(connection, ended)
        } finally {
            // Only the up -> down transition restarts the clock: a failed reconnect must not make the outage look young.
            if (pid != null) downSinceNanos = System.nanoTime()
            pid = null
            subscription?.dispose()
            withContext(NonCancellable) {
                // A close that hangs on a dead socket must not hold the loop: the leaked-socket guard disposes the channel after it.
                withTimeoutOrNull(timings.stepTimeout) { Mono.from(connection.close()).awaitFirstOrNull() }
            }
        }
    }

    /** The heartbeat loop; returns by throwing when the flux ended or a heartbeat failed. */
    private suspend fun watch(connection: Connection, ended: CompletableDeferred<SessionEnd>): Nothing {
        while (true) {
            val end = withTimeoutOrNull(timings.heartbeatInterval) { ended.await() }
            if (end != null) throw SessionLost("the notification stream ended", end.cause)
            bounded("heartbeat") { connection.execute("SELECT 1") }
        }
    }

    private fun offer(notification: Notification) {
        val payload = notification.parameter ?: return
        val sent = channel.trySend(payload)
        // A closed channel (stop) is not a gap: nobody reads it any more.
        if (sent.isFailure && !sent.isClosed) onGap()
    }

    /** [block] within the step timeout; a timeout is a [SessionLost], not a cancellation (`withTimeout` would be rethrown as one). */
    private suspend fun <T : Any> bounded(step: String, block: suspend () -> T): T =
        withTimeoutOrNull(timings.stepTimeout) { block() } ?: throw SessionLost("$step timed out", null)

    private fun describe(failure: Throwable?): String =
        listOfNotNull(failure, failure?.cause).joinToString(" <- ") { it.javaClass.simpleName }

    private class SessionEnd(val cause: Throwable?)

    /** The session ended (or failed a step); the message names the step only. */
    private class SessionLost(message: String, cause: Throwable?) : RuntimeException(message, cause)

    private companion object {
        const val JITTER_FLOOR = 0.5
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The PostgreSQL connection under any [Wrapped] decorators. */
private fun Connection.postgres(): PostgresqlConnection {
    var current: Any? = this
    // The driver's own connection is `Wrapped` too (it unwraps to its Client): stop at it, never past it.
    var depth = 0
    while (current !is PostgresqlConnection && current is Wrapped<*> && depth++ < MAX_UNWRAP_DEPTH) current = current.unwrap()
    return current as? PostgresqlConnection ?: error("The live listener needs an r2dbc-postgresql connection")
}

/** Runs [sql] to completion on a connection that is only listening: one statement, no parameters, no result rows needed. */
private suspend fun Connection.execute(sql: String) {
    Flux.from(createStatement(sql).execute()).flatMap { it.rowsUpdated }.then().awaitFirstOrNull()
}

private suspend fun Connection.backendPid(): Int =
    Flux.from(createStatement("SELECT pg_backend_pid() AS pid").execute())
        .flatMap { result -> result.map { row, _ -> (row.get("pid") as Number).toInt() } }
        .awaitSingle()
