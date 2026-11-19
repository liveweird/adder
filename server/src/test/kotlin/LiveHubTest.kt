package ch.nokillswit

import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.live.HubSocket
import ch.nokillswit.live.LiveHub
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.MAX_QUEUED_HINTS
import ch.nokillswit.live.MAX_SUBSCRIPTIONS
import ch.nokillswit.live.RegisterResult
import ch.nokillswit.live.SubscribeBegin
import io.ktor.websocket.CloseReason
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Publisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hub's own rules (`live/LiveHub.kt`) with no database and no application: fake sockets are plain [HubSocket]s whose queues the test
 * drains. The hub's actor is FIFO, so a request/reply command (`beginSubscribe`) is the barrier that proves earlier commands were handled.
 */
class LiveHubTest {
    private val neverConnects = object : ConnectionFactory {
        override fun create(): Publisher<out Connection> = error("the hub tests never start the listener")
        override fun getMetadata(): ConnectionFactoryMetadata = error("not used")
    }

    private fun withHub(
        maxSockets: Int = 10,
        maxPerUser: Int = 2,
        maxQueuedHints: Int = MAX_QUEUED_HINTS,
        block: suspend LiveHub.() -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(30_000) { LiveHub(scope, maxSockets, maxPerUser, neverConnects, maxQueuedHints = maxQueuedHints).block() }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun socket(userId: UInt = 1u, capacity: Int = 64) =
        HubSocket(userId, isAdmin = false, tokenExpiresAtMillis = Long.MAX_VALUE, outbound = Channel(capacity))

    private fun HubSocket.drain(): List<LiveServerFrame> = generateSequence { outbound.tryReceive().getOrNull() }.toList()

    private suspend fun LiveHub.barrier(socket: HubSocket) {
        beginSubscribe(socket, emptyList())
    }

    private suspend fun LiveHub.subscribe(socket: HubSocket, vararg ids: UInt) {
        val begin = beginSubscribe(socket, ids.toList())
        completeSubscribe(socket, begin, ids.toSet())
        barrier(socket)
    }

    private fun hint(room: UInt, kind: LiveKind = LiveKind.ROOM_UPDATED) = LiveHint(kind, room)

    @Test
    fun `register queues ready first and enforces the per-user and the global cap`() = withHub(maxSockets = 3, maxPerUser = 2) {
        val a1 = socket(1u)
        val a2 = socket(1u)
        val a3 = socket(1u)
        assertEquals(RegisterResult.OK, register(a1))
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Ready()), a1.drain(), "ready is the first frame")
        assertEquals(RegisterResult.OK, register(a2))
        assertEquals(RegisterResult.USER_CAP, register(a3), "a third socket of one user")
        assertEquals(2, socketsOf(1u))

        assertEquals(RegisterResult.OK, register(socket(2u)))
        assertEquals(RegisterResult.FULL, register(socket(3u)), "the third of three slots is taken")
        assertEquals(3, socketCount)

        unregister(a1)
        barrier(a2)
        assertEquals(1, socketsOf(1u))
        assertEquals(2, socketCount)
        unregister(a2)
        unregister(a2) // idempotent
        barrier(socket(9u).also { register(it) })
        assertEquals(0, socketsOf(1u))
    }

    @Test
    fun `a hint goes to the ACTIVE subscribers of its room, never to a PENDING or an unsubscribed one`() = withHub {
        val active = socket(1u)
        val elsewhere = socket(2u)
        val pending = socket(3u)
        val dropped = socket(4u)
        listOf(active, elsewhere, pending, dropped).forEach { register(it) }
        subscribe(active, 7u)
        subscribe(elsewhere, 8u)
        beginSubscribe(pending, listOf(7u)) // registered PENDING, never completed: the readable check is still running
        subscribe(dropped, 7u)
        unsubscribe(dropped, listOf(7u))
        val all = listOf(active, elsewhere, pending, dropped)
        all.forEach { it.drain() }

        deliver(LiveHint(LiveKind.MESSAGE_POSTED, 7u, threadNumber = 3, messageId = 99u))
        barrier(active)
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, 7u, 3, 99u)), active.drain())
        assertEquals(emptyList(), elsewhere.drain())
        assertEquals(emptyList(), pending.drain(), "a hint for a PENDING room is dropped: the client's refetch on the ack covers it")
        assertEquals(emptyList(), dropped.drain())
    }

    @Test
    fun `an ack lists what was subscribed, refused and capped, and a room dropped while pending is refused`() = withHub {
        val s = socket()
        register(s)
        s.drain()
        subscribe(s, 5u)
        s.drain()

        val begin = beginSubscribe(s, listOf(5u, 6u, 7u))
        assertEquals(listOf(5u), begin.already)
        assertEquals(listOf(6u, 7u), begin.toCheck)
        unsubscribe(s, listOf(7u)) // a removal processed while the check ran: the room is no longer PENDING
        completeSubscribe(s, begin, readable = setOf(6u, 7u))
        barrier(s)
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Subscribed(listOf(5u, 6u), listOf(7u), emptyList())), s.drain())

        val unreadable = beginSubscribe(s, listOf(8u))
        completeSubscribe(s, unreadable, readable = emptySet())
        barrier(s)
        // The ack answers THIS frame: its own ids, subscribed or refused (the 5 and 6 of earlier frames are not repeated).
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Subscribed(emptyList(), listOf(8u), emptyList())), s.drain())
    }

    @Test
    fun `an abort drops the pending rooms`() = withHub {
        val s = socket()
        register(s)
        s.drain()
        val begin = beginSubscribe(s, listOf(5u))
        abortSubscribe(s, begin.toCheck)
        val again = beginSubscribe(s, listOf(5u))
        assertEquals(listOf(5u), again.toCheck, "the aborted id is checked afresh, not stuck PENDING")
    }

    @Test
    fun `a socket holds at most 128 subscriptions - the rest are capped`() = withHub {
        val s = socket()
        register(s)
        s.drain()
        val first = beginSubscribe(s, (1..MAX_SUBSCRIPTIONS).map { it.toUInt() })
        assertEquals(MAX_SUBSCRIPTIONS, first.toCheck.size)
        completeSubscribe(s, first, first.toCheck.toSet())
        val over: SubscribeBegin = beginSubscribe(s, listOf(500u, 1u))
        assertEquals(listOf(500u), over.capped)
        assertEquals(listOf(1u), over.already)
        assertEquals(emptyList(), over.toCheck)
    }

    @Test
    fun `an overflowing socket's queue is cleared and replaced by ONE resync`() = withHub {
        val slow = socket(capacity = 3)
        val quick = socket(2u)
        register(slow)
        register(quick)
        subscribe(slow, 7u)
        subscribe(quick, 7u)
        slow.drain()
        quick.drain()

        repeat(5) { deliver(hint(7u)) }
        barrier(slow)
        // The slow socket's 3 slots filled at the 3rd hint; the 4th found it full, cleared it and queued one resync; the 5th found room.
        assertEquals(listOf(LiveServerFrame.Resync, LiveServerFrame.Event(LiveKind.ROOM_UPDATED, 7u)), slow.drain())
        assertEquals(5, quick.drain().size, "a healthy socket still gets every hint")
    }

    @Test
    fun `a gap sends one resync to every socket, however many gaps arrive`() = withHub {
        val a = socket(1u)
        val b = socket(2u)
        register(a)
        register(b)
        a.drain()
        b.drain()
        repeat(50) { onGap() }
        barrier(a)
        // The flag coalesces concurrent signals; sequential ones may each be handled, but every socket always gets the same frames.
        val frames = a.drain()
        assertTrue(frames.isNotEmpty() && frames.all { it == LiveServerFrame.Resync }, "$frames")
        assertEquals(frames, b.drain())
    }

    @Test
    fun `shutdown asks every socket to close 1001 and refuses new ones`() = withHub {
        val a = socket(1u)
        register(a)
        shutdown(graceMillis = 100)
        val reason = withTimeout(5_000) { a.closeRequest.await() }
        assertEquals(CloseReason.Codes.GOING_AWAY.code, reason.code)
        assertTrue(isStopping)
        assertEquals(RegisterResult.UNAVAILABLE, register(socket(2u)))
    }

    @Test
    fun `an overflow keeps ready and the subscribed ack, drops the events and queues one resync`() = withHub {
        val s = socket(capacity = 4)
        register(s) // Ready
        subscribe(s, 7u) // Subscribed
        repeat(3) { deliver(hint(7u)) } // two events fill the queue (4 slots), the third overflows
        barrier(s)
        assertEquals(
            listOf(LiveServerFrame.Ready(), LiveServerFrame.Subscribed(listOf(7u), emptyList(), emptyList()), LiveServerFrame.Resync),
            s.drain(),
        )
    }

    @Test
    fun `a queue that cannot even hold the state frames closes the socket instead of leaving a silent gap`() = withHub {
        val s = socket(capacity = 2)
        register(s) // Ready
        subscribe(s, 7u) // Subscribed: the queue is full of frames that must be kept
        deliver(hint(7u))
        barrier(s)
        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, withTimeout(5_000) { s.closeRequest.await() }.code)
    }

    @Test
    fun `hints past the bound become a gap, and the commands queued with them are never dropped`() = withHub(maxQueuedHints = 2) {
        val s = socket(capacity = 16)
        register(s)
        subscribe(s, 7u)
        s.drain()
        val gate = CompletableDeferred<Unit>()
        beforeCommand = { gate.await() } // the actor holds still while hints and a command pile up
        repeat(5) { deliver(hint(7u)) } // 2 are admitted, 3 are refused and turn into ONE gap
        unsubscribe(s, listOf(7u))
        beforeCommand = null
        gate.complete(Unit)
        barrier(s)
        val event = LiveServerFrame.Event(LiveKind.ROOM_UPDATED, 7u)
        assertEquals(listOf(event, event, LiveServerFrame.Resync), s.drain())
        deliver(hint(7u))
        barrier(s)
        assertEquals(emptyList(), s.drain(), "the Unsubscribe was not dropped")
    }

    @Test
    fun `an unregister queued behind a held register removes the socket the register adds`() = withHub {
        val s = socket(1u)
        val gate = CompletableDeferred<Unit>()
        beforeCommand = { gate.await() }
        kotlinx.coroutines.coroutineScope {
            val registering = launch { register(s) }
            delay(200)
            registering.cancel() // the session is cancelled while it waits for the reply...
            unregister(s) // ...and its finally unregisters (the socket was recorded BEFORE the register)
        }
        beforeCommand = null
        gate.complete(Unit)
        barrier(socket(9u).also { register(it) })
        assertEquals(0, socketsOf(1u))
        assertEquals(1, socketCount)
    }

    @Test
    fun `the pending-auth claim never exceeds its cap, however many sessions race for it`() = withHub {
        val claimed = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { repeat(50) { if (claimPending(5)) claimed.incrementAndGet() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(5, claimed.get())
        assertEquals(5, pendingAuth.get(), "a refused claim counts nothing")
    }

    @Test
    fun `a pong goes through the socket's queue`() = withHub {
        val s = socket()
        register(s)
        s.drain()
        pong(s)
        barrier(s)
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Pong), s.drain())
    }
}
