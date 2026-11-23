package ch.nokillswit.live

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.notify.LIVE_HINT_VERSION
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.NotifyListener
import ch.nokillswit.infra.notify.NotifyListenerTimings
import io.ktor.websocket.CloseReason
import io.r2dbc.spi.ConnectionFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

private const val SHUTDOWN_POLL_MILLIS = 20L

private sealed interface Command {
    class Register(val socket: HubSocket, val reply: CompletableDeferred<RegisterResult>) : Command
    class Unregister(val socket: HubSocket) : Command
    class Begin(val socket: HubSocket, val ids: List<UInt>, val reply: CompletableDeferred<SubscribeBegin>) : Command
    class Complete(val socket: HubSocket, val begin: SubscribeBegin, val readable: Set<UInt>) : Command
    class Abort(val socket: HubSocket, val ids: List<UInt>) : Command
    class Unsubscribe(val socket: HubSocket, val ids: List<UInt>) : Command
    class Deliver(val hint: LiveHint) : Command
    class Pong(val socket: HubSocket) : Command
    class Snapshot(val reply: CompletableDeferred<List<SocketView>>) : Command
    class RevokeRoom(val socket: HubSocket, val roomId: UInt) : Command
    data object Gap : Command
    class CloseAll(val reason: CloseReason) : Command
}

/**
 * The live channel's fan-out (`live.md` "The hub"): ONE actor coroutine with ONE inbox owns every socket's subscriptions and the room ->
 * subscribers index, so nothing is locked and the order of hints is the order the listener read them (commit order). The hub never
 * suspends on a socket: it `trySend`s into the socket's own bounded queue, and a socket that cannot keep up loses its queued events (and
 * any pong) to one `resync`, while the frames that change what the client knows (`ready`, `subscribed`, `revoked`) are kept (the
 * refetch is the backfill; delivery is at-most-once). The inbox is unbounded for COMMANDS (register, unregister, subscribe: never
 * dropped) but hints are admitted only while fewer than [maxQueuedHints] wait for the actor: past that a hint is a gap.
 *
 * Delivery (`live.md` "Hints"): a hint goes to the ACTIVE subscribers of its room and, for the kinds that name `targetUserIds`, to every
 * socket of each target (subscribed or not); a socket gets AT MOST ONE frame per hint. A removed member's sockets and every socket of a
 * deleted room drop the room and get `revoked` instead (an ADMIN keeps a room they were merely removed from). The account sweep
 * ([LiveSweep]) and the close at the token's `exp` (the session) are what end a socket whose account changed.
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

    /** Sockets that upgraded but have not authenticated yet, in total and per client address (claimed by the session). */
    internal val pending = PendingSlots()

    /** The unauthenticated sockets in total (`live.maxPendingAuth`). */
    val pendingAuth: AtomicInteger get() = pending.total

    /** How long the listener may be down before the channel counts as deaf (a constant of the design; a test shrinks it). */
    @Volatile internal var deafAfterMillis = LISTENER_DEAF_MILLIS

    /** How often the watcher checks [isDeaf]; a test shrinks it. */
    @Volatile internal var deafCheckMillis = DEAF_CHECK_MILLIS

    /**
     * Whether the LISTEN session has been down for [deafAfterMillis]: hints committed meanwhile reach nobody, so a mint answers `503` and
     * the open sockets close 1013 (the client's badge appears and it retries; the reconnect's `ready` refetch is the backfill).
     */
    val isDeaf: Boolean get() = listener.downForMillis()?.let { it >= deafAfterMillis } ?: false

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
            scope.launch(CoroutineName("adder-live-deaf")) {
                while (true) {
                    delay(deafCheckMillis)
                    // Every check while deaf: a socket that registered mid-outage is closed within one period too.
                    if (isDeaf) inbox.trySend(Command.CloseAll(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Live updates unavailable")))
                }
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
        gapObserver?.invoke()
    }

    /** Told of every gap (a lost hint may be a removal): the sweep re-checks memberships ([LiveSweep.start]); must not block. */
    @Volatile internal var gapObserver: (() -> Unit)? = null

    /** Drops [roomId] from [socket] and tells it `revoked` (a membership the sweep found gone), if the room is still ACTIVE there. */
    internal fun revokeRoom(socket: HubSocket, roomId: UInt) {
        inbox.trySend(Command.RevokeRoom(socket, roomId))
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

    /**
     * The authenticated sockets as the actor sees them now, each with a COPY of its ACTIVE rooms (the sweep's input; the actor owns the
     * live sets); empty once the hub is stopped.
     */
    internal suspend fun snapshot(): List<SocketView> {
        val reply = CompletableDeferred<List<SocketView>>()
        if (inbox.trySend(Command.Snapshot(reply)).isFailure) return emptyList()
        return reply.await()
    }

    /** Refuses further admissions and mints, closes every socket 1001 and waits (bounded) for them to leave. */
    suspend fun shutdown(graceMillis: Long = SHUTDOWN_GRACE_MILLIS) {
        isStopping = true
        inbox.trySend(Command.CloseAll(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping")))
        withTimeoutOrNull(graceMillis) {
            while (total.get() > 0) delay(SHUTDOWN_POLL_MILLIS)
        }
    }

    /** Ends the actor and the listener (`backgroundScope`'s `beforeCancel`, on `ApplicationStopping`); the scope is joined after. */
    suspend fun stop() {
        isStopping = true
        inbox.close()
        listener.stop()
    }

    /** A TEST seam: the root job of the scope the hub's coroutines (and, through `configureLive`, the sweep) run in. */
    internal val scopeJob: Job get() = checkNotNull(scope.coroutineContext[Job])

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
            is Command.Pong -> queueFrame(command.socket, LiveServerFrame.Pong)
            is Command.Snapshot -> command.reply.complete(
                sockets.map { s -> SocketView(s, s.rooms.filterValues { it == RoomState.ACTIVE }.keys.toList()) },
            )
            is Command.RevokeRoom -> dropRevoked(command.socket, command.roomId)
            Command.Gap -> {
                gapPending.set(false)
                sockets.forEach { queueFrame(it, LiveServerFrame.Resync) }
            }
            is Command.CloseAll -> sockets.forEach { it.closeRequest.complete(command.reason) }
        }
    }

    private fun dropRevoked(socket: HubSocket, roomId: UInt) {
        if (socket !in sockets || socket.rooms[roomId] != RoomState.ACTIVE) return
        drop(socket, roomId)
        queueFrame(socket, LiveServerFrame.Revoked(roomId))
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
            queueFrame(socket, LiveServerFrame.Ready())
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
        queueFrame(socket, LiveServerFrame.Subscribed(begin.already + activated, refused, begin.capped))
    }

    private fun drop(socket: HubSocket, roomId: UInt) {
        socket.rooms.remove(roomId)
        subscribersByRoom[roomId]?.let {
            it.remove(socket)
            if (it.isEmpty()) subscribersByRoom.remove(roomId)
        }
    }

    private fun fanOut(hint: LiveHint) {
        when (hint.kind) {
            LiveKind.ROOM_DELETED -> roomGone(hint)
            LiveKind.ROOM_MEMBER_REMOVED -> memberRemoved(hint)
            else -> {
                val audience = hint.kind.audience()
                val recipients = LinkedHashSet<HubSocket>()
                if (audience != Audience.TARGETS) subscribersByRoom[hint.roomId]?.let { recipients += it }
                if (audience != Audience.SUBSCRIBERS) recipients += targetSockets(hint)
                val event = eventOf(hint)
                recipients.forEach { queueFrame(it, event) }
            }
        }
    }

    /** `room.deleted`: every subscriber and every member's socket drops the room and is told; a subscribe still being checked is voided. */
    private fun roomGone(hint: LiveHint) {
        val recipients = LinkedHashSet<HubSocket>()
        subscribersByRoom[hint.roomId]?.let { recipients += it }
        recipients += targetSockets(hint)
        // A PENDING socket is in no subscriber set; without this its readable check (made before the delete) could still activate it.
        sockets.forEach { if (it.rooms[hint.roomId] == RoomState.PENDING) it.rooms.remove(hint.roomId) }
        recipients.forEach {
            drop(it, hint.roomId)
            queueFrame(it, LiveServerFrame.Revoked(hint.roomId))
        }
    }

    /**
     * `room.member_removed`: targets that lose access drop the room (ACTIVE or PENDING) and get `revoked`. An ADMIN retains access
     * to public and restricted visible rooms and hears the removal even when unsubscribed so their rail refreshes.
     */
    private fun memberRemoved(hint: LiveHint) {
        val (stillReadable, revoked) = targetSockets(hint).partition { it.isAdmin && !hint.requiresMembership }
        revoked.forEach {
            drop(it, hint.roomId)
            queueFrame(it, LiveServerFrame.Revoked(hint.roomId))
        }
        val recipients = LinkedHashSet<HubSocket>()
        subscribersByRoom[hint.roomId]?.let { recipients += it }
        recipients += stillReadable
        val event = eventOf(hint)
        recipients.forEach { queueFrame(it, event) }
    }

    private fun targetSockets(hint: LiveHint): List<HubSocket> =
        hint.targetUserIds.orEmpty().flatMap { socketsByUser[it].orEmpty() }

    private fun eventOf(hint: LiveHint) = LiveServerFrame.Event(hint.kind, hint.roomId, hint.threadNumber, hint.messageId)
}
