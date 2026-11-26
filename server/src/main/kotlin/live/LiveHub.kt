package ch.nokillswit.live

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.LiveNotification
import ch.nokillswit.infra.notify.NotifyListener
import ch.nokillswit.infra.notify.NotifyListenerTimings
import ch.nokillswit.infra.notify.decodeLiveNotification
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
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = LoggerFactory.getLogger("ch.nokillswit.live.LiveHub")

private const val SHUTDOWN_POLL_MILLIS = 20L

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
 * deleted room drop the room and get `revoked` instead (an ADMIN keeps a room they were merely removed from). Account rechecks,
 * the periodic [LiveSweep] and the token expiration can end a socket whose credentials changed.
 *
 * The hub also owns the [NotifyListener] (started lazily by [ensureStarted] on the first mint): its payloads become hints in
 * the inbox, and its gaps become `resync` to every socket (coalesced through an atomic flag: `onGap` may be called from two threads).
 * The counters ([socketCount], [socketsOf], [pendingAuth]) are read by the admission and mint paths without a lock.
 */
class LiveHub internal constructor(
    private val scope: CoroutineScope,
    private val maxSockets: Int,
    connectionFactory: ConnectionFactory,
    listenerTimings: NotifyListenerTimings = NotifyListenerTimings(),
    private val maxQueuedHints: Int = MAX_QUEUED_HINTS,
) {
    private val inbox = Channel<LiveHubCommand>(Channel.UNLIMITED)
    private val gapPending = AtomicBoolean(false)
    private val consuming = AtomicBoolean(false)
    private val sockets = HashSet<HubSocket>()
    private val socketsByUser = HashMap<UInt, MutableSet<HubSocket>>()
    private val queuedHints = AtomicInteger()
    private val subscribersByRoom = HashMap<UInt, MutableSet<HubSocket>>()
    private val perUser = ConcurrentHashMap<UInt, AtomicInteger>()
    private val total = AtomicInteger()

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
                for (payload in listener.payloads) {
                    when (val notification = decode(payload)) {
                        is LiveNotification.Room -> deliver(notification.hint)
                        is LiveNotification.Account -> accountObserver?.invoke(notification.userId)
                        null -> Unit
                    }
                }
            }
            scope.launch(CoroutineName("adder-live-deaf")) {
                while (true) {
                    delay(deafCheckMillis)
                    // Every check while deaf: a socket that registered mid-outage is closed within one period too.
                    if (isDeaf) {
                        val reason = CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Live updates unavailable")
                        inbox.trySend(LiveHubCommand.CloseAll(reason))
                    }
                }
            }
        }
    }

    private fun decode(payload: String): LiveNotification? = catchingFailures({
        decodeLiveNotification(payload)
    }) {
        // Class name only: a payload another writer put on the channel is not ours to log.
        log.debug("ignored a live hint it could not read ({})", it.javaClass.simpleName)
        null
    }

    /**
     * The listener's `onGap` (every successful LISTEN and every dropped payload): ONE `resync` to every socket, however many arrive.
     */
    internal fun onGap() {
        if (gapPending.compareAndSet(false, true)) inbox.trySend(LiveHubCommand.Gap)
        gapObserver?.invoke()
    }

    /** Told of every gap (a lost hint may be a removal): the sweep re-checks memberships ([LiveSweep.start]); must not block. */
    @Volatile internal var gapObserver: (() -> Unit)? = null

    /** Receives roomless account prompts from the private channel; it must return immediately. */
    @Volatile internal var accountObserver: ((UInt) -> Unit)? = null

    /** Drops [roomId] from [socket] and tells it `revoked` (a membership the sweep found gone), if the room is still ACTIVE there. */
    internal fun revokeRoom(socket: HubSocket, roomId: UInt) {
        inbox.trySend(LiveHubCommand.RevokeRoom(socket, roomId))
    }

    internal suspend fun register(socket: HubSocket): RegisterResult {
        val reply = CompletableDeferred<RegisterResult>()
        if (inbox.trySend(LiveHubCommand.Register(socket, reply)).isFailure) return RegisterResult.UNAVAILABLE
        return reply.await()
    }

    internal fun unregister(socket: HubSocket) {
        inbox.trySend(LiveHubCommand.Unregister(socket))
    }

    /** Registers the new ids of a subscribe as PENDING (up to the per-socket limit) and says which need the readable check. */
    internal suspend fun beginSubscribe(socket: HubSocket, ids: List<UInt>): SubscribeBegin {
        val reply = CompletableDeferred<SubscribeBegin>()
        if (inbox.trySend(LiveHubCommand.Begin(socket, ids, reply)).isFailure) return SubscribeBegin(emptyList(), emptyList(), emptyList())
        return reply.await()
    }

    /**
     * Activates the checked ids that are still PENDING and queues the `subscribed` ack in one actor step (no hint overtakes the ack).
     */
    internal fun completeSubscribe(socket: HubSocket, begin: SubscribeBegin, readable: Set<UInt>) {
        inbox.trySend(LiveHubCommand.Complete(socket, begin, readable))
    }

    /** Drops the PENDING registrations of a subscribe whose readable check failed. */
    internal fun abortSubscribe(socket: HubSocket, ids: List<UInt>) {
        inbox.trySend(LiveHubCommand.Abort(socket, ids))
    }

    internal fun unsubscribe(socket: HubSocket, ids: List<UInt>) {
        inbox.trySend(LiveHubCommand.Unsubscribe(socket, ids))
    }

    /** A hint as the listener delivers it: queued while fewer than [maxQueuedHints] wait for the actor, else a gap (bounded inbox). */
    internal fun deliver(hint: LiveHint) {
        if (queuedHints.incrementAndGet() > maxQueuedHints || inbox.trySend(LiveHubCommand.Deliver(hint)).isFailure) {
            queuedHints.decrementAndGet()
            onGap()
        }
    }

    /** Answers a `ping` through the socket's queue rule (so a full queue turns it into the `resync`, never an unordered extra frame). */
    internal fun pong(socket: HubSocket) { inbox.trySend(LiveHubCommand.Pong(socket)) }

    /**
     * The authenticated sockets as the actor sees them now, each with a COPY of its ACTIVE rooms (the sweep's input; the actor owns the
     * live sets). A targeted account check uses the user index and omits subscriptions. Empty once the hub is stopped.
     */
    internal suspend fun snapshot(userIds: Set<UInt>? = null): List<SocketView> {
        val reply = CompletableDeferred<List<SocketView>>()
        if (inbox.trySend(LiveHubCommand.Snapshot(userIds, reply)).isFailure) return emptyList()
        return reply.await()
    }

    /** Refuses further admissions and mints, closes every socket 1001 and waits (bounded) for them to leave. */
    suspend fun shutdown(graceMillis: Long = SHUTDOWN_GRACE_MILLIS) {
        isStopping = true
        inbox.trySend(LiveHubCommand.CloseAll(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping")))
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

    private fun handle(command: LiveHubCommand) {
        when (command) {
            is LiveHubCommand.Register -> command.reply.complete(admit(command.socket))
            is LiveHubCommand.Unregister -> remove(command.socket)
            is LiveHubCommand.Begin -> command.reply.complete(begin(command.socket, command.ids))
            is LiveHubCommand.Complete -> complete(command.socket, command.begin, command.readable)
            is LiveHubCommand.Abort -> command.ids.filter { command.socket.rooms[it] == RoomState.PENDING }
                .forEach { command.socket.rooms.remove(it) }
            is LiveHubCommand.Unsubscribe -> command.ids.forEach { drop(command.socket, it) }
            is LiveHubCommand.Deliver -> {
                queuedHints.decrementAndGet()
                fanOut(command.hint)
            }
            is LiveHubCommand.Pong -> queueFrame(command.socket, LiveServerFrame.Pong)
            is LiveHubCommand.Snapshot -> {
                val selected = command.userIds?.flatMap { socketsByUser[it].orEmpty() } ?: sockets
                command.reply.complete(selected.map { socket ->
                    val rooms = if (command.userIds == null) socket.rooms.filterValues { it == RoomState.ACTIVE }.keys.toList()
                    else emptyList()
                    SocketView(socket, rooms)
                })
            }
            is LiveHubCommand.RevokeRoom -> dropRevoked(command.socket, command.roomId)
            LiveHubCommand.Gap -> {
                gapPending.set(false)
                sockets.forEach { queueFrame(it, LiveServerFrame.Resync) }
            }
            is LiveHubCommand.CloseAll -> sockets.forEach { it.closeRequest.complete(command.reason) }
        }
    }

    private fun dropRevoked(socket: HubSocket, roomId: UInt) {
        if (socket !in sockets || socket.rooms[roomId] != RoomState.ACTIVE) return
        drop(socket, roomId)
        queueFrame(socket, LiveServerFrame.Revoked(roomId))
    }

    private fun admit(socket: HubSocket): RegisterResult = when {
        isStopping -> RegisterResult.UNAVAILABLE
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
                recipients.forEach { queueFrame(it, eventOf(hint, it)) }
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
        recipients.forEach { queueFrame(it, eventOf(hint, it)) }
    }

    private fun targetSockets(hint: LiveHint): List<HubSocket> =
        hint.targetUserIds.orEmpty().flatMap { socketsByUser[it].orEmpty() }
}
