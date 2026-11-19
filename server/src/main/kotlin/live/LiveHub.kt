package ch.nokillswit.live

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.notify.LIVE_HINT_VERSION
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.NotifyListener
import ch.nokillswit.infra.notify.NotifyListenerTimings
import io.ktor.websocket.CloseReason
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger("ch.nokillswit.live.LiveHub")

/** Frames a socket's own writer has not yet sent; past it the socket gets ONE `resync` instead (`live.md` "The hub"). */
const val OUTBOUND_QUEUE_CAPACITY = 64

/** Hints waiting for the actor; past it a hint becomes a gap (one `resync` to every socket) instead of growing the inbox without bound. */
const val MAX_QUEUED_HINTS = 1024

/** How long [LiveHub.shutdown] waits for the sockets to leave after asking them to close 1001 (`live.md` "Shutdown"). */
const val SHUTDOWN_GRACE_MILLIS = 2_000L

private const val SHUTDOWN_POLL_MILLIS = 20L

/** Whether a subscribed room already receives hints, or is still being checked (the subscribe race rule, `live.md` "The hub"). */
internal enum class RoomState { PENDING, ACTIVE }

/**
 * What the hub knows about one authenticated socket. [rooms] belongs to the hub's actor coroutine and is touched by nothing else; the
 * socket's session owns [outbound]'s receiving end (its writer) and [closeRequest]'s waiting end. Only the hub `trySend`s into [outbound].
 */
internal class HubSocket(
    val userId: UInt,
    val isAdmin: Boolean,
    /** The minting access token's `exp` (Unix millis): the socket closes 4001 at it (the next step). */
    val tokenExpiresAtMillis: Long,
    /** The token facts the account sweep re-judges (`accessVerdicts(jti, userId, credentialRevision, roles)`, the next step). */
    val jti: String = "",
    val credentialRevision: Long = 0,
    val roles: Set<String> = emptySet(),
    val outbound: Channel<LiveServerFrame> = Channel(OUTBOUND_QUEUE_CAPACITY),
    /** Completed by the hub to make the session close with that reason (server stopping, a failed account verdict). */
    val closeRequest: CompletableDeferred<CloseReason> = CompletableDeferred(),
) {
    val rooms = HashMap<UInt, RoomState>()
}

internal enum class RegisterResult { OK, USER_CAP, FULL, UNAVAILABLE }

/**
 * The outcome of the first half of a subscribe: [toCheck] are registered PENDING and need the readable check; [already] are
 * subscribed; [capped] are over the limit.
 */
internal class SubscribeBegin(val already: List<UInt>, val toCheck: List<UInt>, val capped: List<UInt>)

private sealed interface Command {
    class Register(val socket: HubSocket, val reply: CompletableDeferred<RegisterResult>) : Command
    class Unregister(val socket: HubSocket) : Command
    class Begin(val socket: HubSocket, val ids: List<UInt>, val reply: CompletableDeferred<SubscribeBegin>) : Command
    class Complete(val socket: HubSocket, val begin: SubscribeBegin, val readable: Set<UInt>) : Command
    class Abort(val socket: HubSocket, val ids: List<UInt>) : Command
    class Unsubscribe(val socket: HubSocket, val ids: List<UInt>) : Command
    class Deliver(val hint: LiveHint) : Command
    class Pong(val socket: HubSocket) : Command
    data object Gap : Command
    data object CloseAll : Command
}

/**
 * The live channel's fan-out (`live.md` "The hub"): ONE actor coroutine with ONE inbox owns every socket's subscriptions and the room ->
 * subscribers index, so nothing is locked and the order of hints is the order the listener read them (commit order). The hub never
 * suspends on a socket: it `trySend`s into the socket's own bounded queue, and a socket that cannot keep up loses its queued events (and
 * any pong) to one `resync`, while the frames that change what the client knows (`ready`, `subscribed`, `revoked`) are kept (the
 * refetch is the backfill; delivery is at-most-once). The inbox is unbounded for COMMANDS (register, unregister, subscribe: never
 * dropped) but hints are admitted only while fewer than [maxQueuedHints] wait for the actor: past that a hint is a gap.
 *
 * This step delivers each hint to the ACTIVE subscribers of its room. The personal frames (`revoked`, the hints for a room the user was
 * just added to), the account sweep and the close at the token's `exp` are the next step's.
 *
 * The hub also owns the [NotifyListener] (started lazily by [ensureStarted] on the first mint): its payloads become hints in
 * the inbox, and its gaps become `resync` to every socket (coalesced through an atomic flag: `onGap` may be called from two threads).
 * The counters ([socketCount], [socketsOf], [pendingAuth]) are read by the admission and mint paths without a lock.
 */
class LiveHub internal constructor(
    private val scope: CoroutineScope,
    private val maxSockets: Int,
    private val maxSocketsPerUser: Int,
    connectionFactory: ConnectionFactory,
    listenerTimings: NotifyListenerTimings = NotifyListenerTimings(),
    private val maxQueuedHints: Int = MAX_QUEUED_HINTS,
) {
    private val inbox = Channel<Command>(Channel.UNLIMITED)
    private val gapPending = AtomicBoolean(false)
    private val consuming = AtomicBoolean(false)
    private val sockets = HashSet<HubSocket>()
    private val socketsByUser = HashMap<UInt, MutableSet<HubSocket>>()
    private val queuedHints = AtomicInteger()
    private val subscribersByRoom = HashMap<UInt, MutableSet<HubSocket>>()
    private val perUser = ConcurrentHashMap<UInt, AtomicInteger>()
    private val total = AtomicInteger()
    private val hintJson = Json { ignoreUnknownKeys = true }

    @Volatile var isStopping = false
        private set

    /** Sockets that upgraded but have not authenticated yet (counted by the session, bounded by `live.maxPendingAuth` at admission). */
    val pendingAuth = AtomicInteger()

    /** Authenticated sockets, the whole process. */
    val socketCount: Int get() = total.get()

    /** Authenticated sockets of [userId]. */
    fun socketsOf(userId: UInt): Int = perUser[userId]?.get() ?: 0

    internal val listener = NotifyListener(connectionFactory, scope, onGap = ::onGap, timings = listenerTimings)

    init {
        scope.launch(CoroutineName("adder-live-hub")) { run() }
    }

    /** Starts the LISTEN session and the payload consumer on the first call; later calls do nothing. */
    fun ensureStarted() {
        listener.ensureStarted()
        if (consuming.compareAndSet(false, true)) {
            scope.launch(CoroutineName("adder-live-hints")) {
                for (payload in listener.payloads) decode(payload)?.let { deliver(it) }
            }
        }
    }

    private fun decode(payload: String): LiveHint? = catchingFailures({
        hintJson.decodeFromString(LiveHint.serializer(), payload).takeIf { it.v == LIVE_HINT_VERSION }
    }) {
        // Class name only: a payload another writer put on the channel is not ours to log.
        log.debug("ignored a live hint it could not read ({})", it.javaClass.simpleName)
        null
    }

    /**
     * The listener's `onGap` (every successful LISTEN and every dropped payload): ONE `resync` to every socket, however many arrive.
     */
    internal fun onGap() {
        if (gapPending.compareAndSet(false, true)) inbox.trySend(Command.Gap)
    }

    internal suspend fun register(socket: HubSocket): RegisterResult {
        val reply = CompletableDeferred<RegisterResult>()
        if (inbox.trySend(Command.Register(socket, reply)).isFailure) return RegisterResult.UNAVAILABLE
        return reply.await()
    }

    internal fun unregister(socket: HubSocket) {
        inbox.trySend(Command.Unregister(socket))
    }

    /** Registers the new ids of a subscribe as PENDING (up to the per-socket limit) and says which need the readable check. */
    internal suspend fun beginSubscribe(socket: HubSocket, ids: List<UInt>): SubscribeBegin {
        val reply = CompletableDeferred<SubscribeBegin>()
        if (inbox.trySend(Command.Begin(socket, ids, reply)).isFailure) return SubscribeBegin(emptyList(), emptyList(), emptyList())
        return reply.await()
    }

    /**
     * Activates the checked ids that are still PENDING and queues the `subscribed` ack in one actor step (no hint overtakes the ack).
     */
    internal fun completeSubscribe(socket: HubSocket, begin: SubscribeBegin, readable: Set<UInt>) {
        inbox.trySend(Command.Complete(socket, begin, readable))
    }

    /** Drops the PENDING registrations of a subscribe whose readable check failed. */
    internal fun abortSubscribe(socket: HubSocket, ids: List<UInt>) {
        inbox.trySend(Command.Abort(socket, ids))
    }

    internal fun unsubscribe(socket: HubSocket, ids: List<UInt>) {
        inbox.trySend(Command.Unsubscribe(socket, ids))
    }

    /** A hint as the listener delivers it: queued while fewer than [maxQueuedHints] wait for the actor, else a gap (bounded inbox). */
    internal fun deliver(hint: LiveHint) {
        if (queuedHints.incrementAndGet() > maxQueuedHints || inbox.trySend(Command.Deliver(hint)).isFailure) {
            queuedHints.decrementAndGet()
            onGap()
        }
    }

    /** Answers a `ping` through the socket's queue rule (so a full queue turns it into the `resync`, never an unordered extra frame). */
    internal fun pong(socket: HubSocket) {
        inbox.trySend(Command.Pong(socket))
    }

    /** Counts a session that has upgraded but not authenticated; false (nothing counted) when [max] such sessions already exist. */
    internal fun claimPending(max: Int): Boolean {
        if (pendingAuth.incrementAndGet() <= max) return true
        pendingAuth.decrementAndGet()
        return false
    }

    /** Refuses further admissions and mints, closes every socket 1001 and waits (bounded) for them to leave. */
    suspend fun shutdown(graceMillis: Long = SHUTDOWN_GRACE_MILLIS) {
        isStopping = true
        inbox.trySend(Command.CloseAll)
        withTimeoutOrNull(graceMillis) {
            while (total.get() > 0) delay(SHUTDOWN_POLL_MILLIS)
        }
    }

    /** Ends the actor and the listener (`ApplicationStopped`); the caller cancels the scope. */
    suspend fun stop() {
        isStopping = true
        inbox.close()
        listener.stop()
    }

    /** A TEST seam: awaited before each command is handled, so a test can hold the actor still. Null in production. */
    @Volatile internal var beforeCommand: (suspend () -> Unit)? = null

    private suspend fun run() {
        for (command in inbox) {
            beforeCommand?.invoke()
            catchingFailures({ handle(command) }) { log.error("live hub command failed ({})", it.javaClass.simpleName) }
        }
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Register -> command.reply.complete(admit(command.socket))
            is Command.Unregister -> remove(command.socket)
            is Command.Begin -> command.reply.complete(begin(command.socket, command.ids))
            is Command.Complete -> complete(command.socket, command.begin, command.readable)
            is Command.Abort -> command.ids.forEach { if (command.socket.rooms[it] == RoomState.PENDING) command.socket.rooms.remove(it) }
            is Command.Unsubscribe -> command.ids.forEach { drop(command.socket, it) }
            is Command.Deliver -> {
                queuedHints.decrementAndGet()
                fanOut(command.hint)
            }
            is Command.Pong -> send(command.socket, LiveServerFrame.Pong)
            Command.Gap -> {
                gapPending.set(false)
                sockets.forEach { send(it, LiveServerFrame.Resync) }
            }
            Command.CloseAll -> sockets.forEach { it.closeRequest.complete(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping")) }
        }
    }

    private fun admit(socket: HubSocket): RegisterResult = when {
        isStopping -> RegisterResult.UNAVAILABLE
        (socketsByUser[socket.userId]?.size ?: 0) >= maxSocketsPerUser -> RegisterResult.USER_CAP
        total.get() >= maxSockets -> RegisterResult.FULL
        else -> {
            sockets.add(socket)
            socketsByUser.getOrPut(socket.userId) { HashSet() }.add(socket)
            total.incrementAndGet()
            perUser.computeIfAbsent(socket.userId) { AtomicInteger() }.incrementAndGet()
            // `ready` is queued by the actor itself, so it is always the socket's first frame.
            send(socket, LiveServerFrame.Ready())
            RegisterResult.OK
        }
    }

    private fun remove(socket: HubSocket) {
        if (!sockets.remove(socket)) return
        socket.rooms.keys.toList().forEach { drop(socket, it) }
        socketsByUser[socket.userId]?.let {
            it.remove(socket)
            if (it.isEmpty()) socketsByUser.remove(socket.userId)
        }
        total.decrementAndGet()
        perUser.computeIfPresent(socket.userId) { _, count -> if (count.decrementAndGet() <= 0) null else count }
    }

    private fun begin(socket: HubSocket, ids: List<UInt>): SubscribeBegin {
        if (socket !in sockets) return SubscribeBegin(emptyList(), emptyList(), emptyList())
        val already = ArrayList<UInt>()
        val toCheck = ArrayList<UInt>()
        val capped = ArrayList<UInt>()
        for (id in ids) {
            when {
                socket.rooms[id] == RoomState.ACTIVE -> already += id
                socket.rooms.size >= MAX_SUBSCRIPTIONS -> capped += id
                else -> {
                    socket.rooms[id] = RoomState.PENDING
                    toCheck += id
                }
            }
        }
        return SubscribeBegin(already, toCheck, capped)
    }

    private fun complete(socket: HubSocket, begin: SubscribeBegin, readable: Set<UInt>) {
        if (socket !in sockets) return
        val activated = ArrayList<UInt>()
        val refused = ArrayList<UInt>()
        for (id in begin.toCheck) {
            // Only an id still PENDING activates: a removal or a deletion processed meanwhile has already dropped it.
            if (id in readable && socket.rooms[id] == RoomState.PENDING) {
                socket.rooms[id] = RoomState.ACTIVE
                subscribersByRoom.getOrPut(id) { HashSet() }.add(socket)
                activated += id
            } else {
                if (socket.rooms[id] == RoomState.PENDING) socket.rooms.remove(id)
                refused += id
            }
        }
        send(socket, LiveServerFrame.Subscribed(begin.already + activated, refused, begin.capped))
    }

    private fun drop(socket: HubSocket, roomId: UInt) {
        socket.rooms.remove(roomId)
        subscribersByRoom[roomId]?.let {
            it.remove(socket)
            if (it.isEmpty()) subscribersByRoom.remove(roomId)
        }
    }

    private fun fanOut(hint: LiveHint) {
        val subscribers = subscribersByRoom[hint.roomId] ?: return
        val event = LiveServerFrame.Event(hint.kind, hint.roomId, hint.threadNumber, hint.messageId)
        subscribers.forEach { send(it, event) }
    }

    /**
     * `trySend`, never a suspension. A full queue loses what a refetch recovers (events, a pending `resync`, a pong) to ONE `resync`, and
     * keeps what changes the client's state (`ready`, `subscribed`, `revoked`); if even that cannot fit, the socket is closed (1013) rather
     * than left with a silent gap.
     */
    private fun send(socket: HubSocket, frame: LiveServerFrame) {
        if (socket.outbound.trySend(frame).isSuccess) return
        val kept = ArrayList<LiveServerFrame>()
        while (true) {
            val queued = socket.outbound.tryReceive().getOrNull() ?: break
            if (queued.changesState()) kept += queued
        }
        if (frame.changesState()) kept += frame
        val requeued = kept.all { socket.outbound.trySend(it).isSuccess } && socket.outbound.trySend(LiveServerFrame.Resync).isSuccess
        if (!requeued) socket.closeRequest.complete(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too slow"))
    }

    private fun LiveServerFrame.changesState(): Boolean =
        this is LiveServerFrame.Ready || this is LiveServerFrame.Subscribed || this is LiveServerFrame.Revoked
}
