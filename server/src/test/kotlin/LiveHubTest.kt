package ch.nokillswit

import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.live.Audience
import ch.nokillswit.live.HubSocket
import ch.nokillswit.live.LiveHub
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.MAX_QUEUED_HINTS
import ch.nokillswit.live.MAX_SUBSCRIPTIONS
import ch.nokillswit.live.PendingClaim
import ch.nokillswit.live.RegisterResult
import ch.nokillswit.live.SubscribeBegin
import ch.nokillswit.live.audience
import ch.nokillswit.live.queueFrame
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
import kotlin.test.assertFalse
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
        maxQueuedHints: Int = MAX_QUEUED_HINTS,
        block: suspend LiveHub.() -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            runBlocking {
                withTimeout(30_000) { LiveHub(scope, maxSockets, neverConnects, maxQueuedHints = maxQueuedHints).block() }
            }
        } finally {
            scope.cancel()
        }
    }

    private fun socket(userId: UInt = 1u, capacity: Int = 64, admin: Boolean = false) =
        HubSocket(userId, isAdmin = admin, tokenExpiresAtMillis = Long.MAX_VALUE, outbound = Channel(capacity))

    private fun HubSocket.drain(): List<LiveServerFrame> = generateSequence { outbound.tryReceive().getOrNull() }.toList()

    private suspend fun LiveHub.barrier(socket: HubSocket) {
        beginSubscribe(socket, emptyList())
    }

    private suspend fun LiveHub.subscribe(socket: HubSocket, vararg ids: UInt) {
        val begin = beginSubscribe(socket, ids.toList())
        completeSubscribe(socket, begin, ids.toSet())
        barrier(socket)
    }

    private fun hint(room: UInt, kind: LiveKind = LiveKind.ROOM_UPDATED, targets: List<UInt>? = null) =
        LiveHint(kind, room, targetUserIds = targets)

    private fun event(room: UInt, kind: LiveKind) = LiveServerFrame.Event(kind, room)

    @Test
    fun `register queues ready first and enforces the process global cap`() = withHub(maxSockets = 3) {
        val a1 = socket(1u)
        val a2 = socket(1u)
        val a3 = socket(1u)
        assertEquals(RegisterResult.OK, register(a1))
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Ready()), a1.drain(), "ready is the first frame")
        assertEquals(RegisterResult.OK, register(a2))
        assertEquals(RegisterResult.OK, register(a3), "per-user admission is shared through PostgreSQL, not this process actor")
        assertEquals(3, socketsOf(1u))
        assertEquals(RegisterResult.FULL, register(socket(2u)), "all three process slots are taken")
        assertEquals(3, socketCount)

        unregister(a1)
        barrier(a2)
        assertEquals(2, socketsOf(1u))
        assertEquals(2, socketCount)
        unregister(a2)
        unregister(a3)
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
    fun `a reply mutation id reaches every writer tab and no other subscriber`() = withHub {
        val writerTabOne = socket(1u)
        val writerTabTwo = socket(1u)
        val otherUser = socket(2u)
        listOf(writerTabOne, writerTabTwo, otherUser).forEach {
            register(it)
            subscribe(it, 7u)
            it.drain()
        }
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        deliver(
            LiveHint(
                LiveKind.MESSAGE_POSTED,
                7u,
                threadNumber = 3,
                messageId = 99u,
                mutationId = mutationId,
                originUserId = 1u,
            ),
        )
        barrier(writerTabOne)

        val writerEvent = LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, 7u, 3, 99u, mutationId)
        assertEquals(listOf<LiveServerFrame>(writerEvent), writerTabOne.drain())
        assertEquals(listOf<LiveServerFrame>(writerEvent), writerTabTwo.drain())
        assertEquals(
            listOf<LiveServerFrame>(LiveServerFrame.Event(LiveKind.MESSAGE_POSTED, 7u, 3, 99u)),
            otherUser.drain(),
        )

        deliver(
            LiveHint(
                LiveKind.MESSAGE_REACTION_CHANGED,
                7u,
                threadNumber = 3,
                messageId = 99u,
                mutationId = mutationId,
                originUserId = 1u,
            ),
        )
        barrier(writerTabOne)
        val reaction = LiveServerFrame.Event(LiveKind.MESSAGE_REACTION_CHANGED, 7u, 3, 99u, mutationId)
        assertEquals(listOf<LiveServerFrame>(reaction), writerTabOne.drain())
        assertEquals(listOf<LiveServerFrame>(reaction), writerTabTwo.drain())
        assertEquals(
            listOf<LiveServerFrame>(LiveServerFrame.Event(LiveKind.MESSAGE_REACTION_CHANGED, 7u, 3, 99u)),
            otherUser.drain(),
        )

        deliver(
            LiveHint(
                LiveKind.THREAD_OWNER_CHANGED,
                7u,
                threadNumber = 3,
                mutationId = mutationId,
                originUserId = 1u,
            ),
        )
        barrier(writerTabOne)
        val owner = LiveServerFrame.Event(LiveKind.THREAD_OWNER_CHANGED, 7u, 3, mutationId = mutationId)
        assertEquals(listOf<LiveServerFrame>(owner), writerTabOne.drain())
        assertEquals(listOf<LiveServerFrame>(owner), writerTabTwo.drain())
        assertEquals(
            listOf<LiveServerFrame>(LiveServerFrame.Event(LiveKind.THREAD_OWNER_CHANGED, 7u, 3)),
            otherUser.drain(),
        )

        deliver(LiveHint(LiveKind.ROOM_UPDATED, 7u, mutationId = mutationId, originUserId = 1u))
        barrier(writerTabOne)
        val ordinary = listOf<LiveServerFrame>(LiveServerFrame.Event(LiveKind.ROOM_UPDATED, 7u))
        assertEquals(ordinary, writerTabOne.drain(), "other live kinds never expose correlation metadata")
        assertEquals(ordinary, writerTabTwo.drain())
        assertEquals(ordinary, otherUser.drain())
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
    fun `the pending-auth claim never exceeds its caps, however many sessions race for it`() = withHub {
        val claimed = java.util.concurrent.atomic.AtomicInteger()
        val addresses = listOf("a", "b", "c", "d")
        // Total 5, two per address: four addresses race, so the total binds (5 = 2 + 2 + 1) and no address holds more than 2.
        val threads = (1..16).map { n ->
            Thread { repeat(50) { if (pending.claim(addresses[n % 4], 5, 2) == PendingClaim.OK) claimed.incrementAndGet() } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(5, claimed.get())
        assertEquals(5, pendingAuth.get(), "a refused claim counts nothing")
        assertEquals(5, addresses.sumOf { pending.held(it) })
        assertTrue(addresses.all { pending.held(it) <= 2 })
        addresses.forEach { address -> repeat(pending.held(address)) { pending.release(address) } }
        assertEquals(0, pendingAuth.get())
        assertEquals(0, addresses.sumOf { pending.held(it) }, "released slots leave no entry behind")
    }

    @Test
    fun `an address is judged before the total, and a refused claim takes nothing from either`() = withHub {
        assertEquals(PendingClaim.OK, pending.claim("a", 2, 1))
        assertEquals(PendingClaim.ADDRESS_FULL, pending.claim("a", 2, 1))
        assertEquals(PendingClaim.OK, pending.claim("b", 2, 1))
        assertEquals(PendingClaim.FULL, pending.claim("c", 2, 1))
        assertEquals(2, pendingAuth.get())
        assertEquals(0, pending.held("c"))
        pending.release("a")
        assertEquals(PendingClaim.OK, pending.claim("c", 2, 1))
    }

    @Test
    fun `a listener that stays down makes the hub deaf and closes its sockets 1013, late registrants included`() = withHub {
        deafAfterMillis = 100
        deafCheckMillis = 20
        val first = socket(1u)
        register(first)
        assertFalse(isDeaf, "a listener that was never started is idle, not down")
        ensureStarted() // the factory refuses every connect, so the session never comes up
        withTimeout(10_000) { while (!isDeaf) delay(10) }
        assertEquals(1013, withTimeout(10_000) { first.closeRequest.await() }.code)
        val late = socket(2u)
        register(late)
        assertEquals(1013, withTimeout(10_000) { late.closeRequest.await() }.code, "the watcher checks again while the outage lasts")
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

    @Test
    fun `a removed member's sockets drop the room and are told, subscribed or not, and get nothing further`() = withHub {
        val subscribed = socket(1u)
        val otherTab = socket(1u) // the same person, never subscribed
        val pendingTab = socket(1u)
        val bystander = socket(2u)
        listOf(subscribed, otherTab, pendingTab, bystander).forEach { register(it) }
        subscribe(subscribed, 7u)
        subscribe(bystander, 7u)
        beginSubscribe(pendingTab, listOf(7u)) // still being checked when the removal lands
        listOf(subscribed, otherTab, pendingTab, bystander).forEach { it.drain() }

        deliver(hint(7u, LiveKind.ROOM_MEMBER_REMOVED, targets = listOf(1u)))
        deliver(hint(7u))
        barrier(subscribed)
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u)), subscribed.drain(), "one frame, none for the next hint")
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u)), otherTab.drain(), "a socket that never subscribed is told too")
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u)), pendingTab.drain())
        assertEquals(
            listOf(event(7u, LiveKind.ROOM_MEMBER_REMOVED), event(7u, LiveKind.ROOM_UPDATED)), bystander.drain(),
            "the others hear the removal as an event and keep the room",
        )
    }

    @Test
    fun `a hidden room removal wins even while an administrator subscription is pending`() = withHub {
        val s = socket(1u, admin = true)
        register(s)
        s.drain()
        val begin = beginSubscribe(s, listOf(7u)) // PENDING; the readable check (made BEFORE the removal) says readable
        deliver(LiveHint(LiveKind.ROOM_MEMBER_REMOVED, 7u, targetUserIds = listOf(1u), requiresMembership = true))
        completeSubscribe(s, begin, readable = setOf(7u))
        barrier(s)
        assertEquals(
            listOf(LiveServerFrame.Revoked(7u), LiveServerFrame.Subscribed(emptyList(), listOf(7u), emptyList())),
            s.drain(),
            "the room was dropped by the removal: the late check cannot activate it",
        )
        deliver(hint(7u))
        barrier(s)
        assertEquals(emptyList(), s.drain())
    }

    @Test
    fun `a removed ADMIN keeps the room and hears the removal as an event`() = withHub {
        val admin = socket(1u, admin = true)
        val adminOtherTab = socket(1u, admin = true) // not subscribed
        register(admin)
        register(adminOtherTab)
        subscribe(admin, 7u)
        admin.drain()
        adminOtherTab.drain()

        deliver(hint(7u, LiveKind.ROOM_MEMBER_REMOVED, targets = listOf(1u)))
        deliver(hint(7u))
        barrier(admin)
        assertEquals(listOf(event(7u, LiveKind.ROOM_MEMBER_REMOVED), event(7u, LiveKind.ROOM_UPDATED)), admin.drain(), "still subscribed")
        assertEquals(listOf(event(7u, LiveKind.ROOM_MEMBER_REMOVED)), adminOtherTab.drain(), "one frame, no subscription gained")
    }

    @Test
    fun `a deleted room revokes every subscriber and every target, and a socket that is both gets ONE frame`() = withHub {
        val member = socket(1u) // subscriber AND target
        val memberOtherTab = socket(1u) // target only
        val admin = socket(2u, admin = true) // subscriber, not a member
        val pending = socket(3u) // a subscribe in flight
        val bystander = socket(4u)
        listOf(member, memberOtherTab, admin, pending, bystander).forEach { register(it) }
        subscribe(member, 7u)
        subscribe(admin, 7u)
        subscribe(bystander, 8u)
        val begin = beginSubscribe(pending, listOf(7u))
        listOf(member, memberOtherTab, admin, pending, bystander).forEach { it.drain() }

        deliver(hint(7u, LiveKind.ROOM_DELETED, targets = listOf(1u)))
        completeSubscribe(pending, begin, readable = setOf(7u)) // its check ran before the delete committed
        barrier(member)
        val revoked = listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u))
        assertEquals(revoked, member.drain())
        assertEquals(revoked, memberOtherTab.drain())
        assertEquals(revoked, admin.drain(), "an ADMIN loses a deleted room too")
        assertEquals(
            listOf<LiveServerFrame>(LiveServerFrame.Subscribed(emptyList(), listOf(7u), emptyList())), pending.drain(),
            "the in-flight subscribe is voided, not activated",
        )
        assertEquals(emptyList(), bystander.drain())
        deliver(hint(7u))
        barrier(member)
        assertEquals(emptyList(), member.drain())
        assertEquals(emptyList(), admin.drain())
    }

    @Test
    fun `the personal kinds reach every socket of the target, subscribed or not, once`() = withHub {
        val subscribed = socket(1u)
        val otherTab = socket(1u)
        val stranger = socket(2u)
        listOf(subscribed, otherTab, stranger).forEach { register(it) }
        subscribe(subscribed, 7u)
        listOf(subscribed, otherTab, stranger).forEach { it.drain() }

        listOf(LiveKind.ROOM_CREATED, LiveKind.ROOM_MEMBER_ADDED, LiveKind.ROOM_MEMBER_ROLE_CHANGED).forEach { kind ->
            deliver(hint(7u, kind, targets = listOf(1u)))
            barrier(subscribed)
            assertEquals(listOf(event(7u, kind)), subscribed.drain(), "$kind: subscriber and target is ONE frame")
            assertEquals(listOf(event(7u, kind)), otherTab.drain(), "$kind: a socket that never subscribed")
            assertEquals(emptyList(), stranger.drain(), "$kind: not for anyone else")
        }
    }

    @Test
    fun `every kind has an audience`() {
        // The audience of EVERY kind, written out: the hub's `else` branch answers SUBSCRIBERS for any kind, so a table that
        // defaulted would never fail. A new kind must be added here, with the audience it is meant for (`room.deleted` and
        // `room.member_removed` also have their own hub branches; their entry here is the default `audience()` answers).
        val table = mapOf(
            LiveKind.ROOM_CREATED to Audience.TARGETS,
            LiveKind.ROOM_UPDATED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_ARCHIVED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_UNARCHIVED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_DELETED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_MEMBER_ADDED to Audience.SUBSCRIBERS_AND_TARGETS,
            LiveKind.ROOM_MEMBER_ROLE_CHANGED to Audience.SUBSCRIBERS_AND_TARGETS,
            LiveKind.ROOM_MEMBER_REMOVED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_WORKFLOW_UPDATED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_CREATED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_UPDATED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_DELETED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_MERGED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_STATUS_CHANGED to Audience.SUBSCRIBERS,
            LiveKind.THREAD_OWNER_CHANGED to Audience.SUBSCRIBERS,
            LiveKind.MESSAGE_POSTED to Audience.SUBSCRIBERS,
            LiveKind.MESSAGE_EDITED to Audience.SUBSCRIBERS,
            LiveKind.MESSAGE_DELETED to Audience.SUBSCRIBERS,
            LiveKind.MESSAGE_REACTION_CHANGED to Audience.SUBSCRIBERS,
            LiveKind.ROOM_PIN_CHANGED to Audience.SUBSCRIBERS,
            LiveKind.NOTIFICATION_CREATED to Audience.TARGETS,
        )
        assertEquals(LiveKind.entries.toSet(), table.keys, "a kind missing from the table (or one that no longer exists)")
        table.forEach { (kind, audience) -> assertEquals(audience, kind.audience(), "$kind") }
        assertEquals(Audience.entries.toSet(), table.values.toSet(), "every class is used by some kind")
    }

    @Test
    fun `room created goes to its targets only, and a kind with no personal frame ignores its targets`() = withHub {
        val creator = socket(1u)
        val subscriber = socket(2u)
        val target = socket(3u)
        listOf(creator, subscriber, target).forEach { register(it) }
        subscribe(subscriber, 7u)
        listOf(creator, subscriber, target).forEach { it.drain() }

        deliver(hint(7u, LiveKind.ROOM_CREATED, targets = listOf(1u)))
        deliver(hint(7u, LiveKind.ROOM_UPDATED, targets = listOf(3u))) // never carries targets in practice; the hub must not widen on them
        barrier(creator)
        assertEquals(listOf(event(7u, LiveKind.ROOM_CREATED)), creator.drain())
        assertEquals(listOf(event(7u, LiveKind.ROOM_UPDATED)), subscriber.drain(), "a subscriber is not a target of room.created")
        assertEquals(emptyList(), target.drain())
    }

    @Test
    fun `a notice reaches every socket of its target, subscribed or not, and no other socket`() = withHub {
        val target = socket(1u)
        val targetOtherTab = socket(1u)
        val subscriber = socket(2u)
        val stranger = socket(3u)
        listOf(target, targetOtherTab, subscriber, stranger).forEach { register(it) }
        subscribe(target, 7u)
        subscribe(subscriber, 7u)
        listOf(target, targetOtherTab, subscriber, stranger).forEach { it.drain() }

        val notice = LiveHint(LiveKind.NOTIFICATION_CREATED, 7u, threadNumber = 3, messageId = 11u, targetUserIds = listOf(1u))
        val frame = LiveServerFrame.Event(LiveKind.NOTIFICATION_CREATED, 7u, 3, 11u)
        deliver(notice)
        barrier(target)
        assertEquals(listOf(frame), target.drain(), "the target that is also a subscriber gets ONE frame")
        assertEquals(listOf(frame), targetOtherTab.drain(), "a socket of the target that never subscribed")
        assertEquals(emptyList(), subscriber.drain(), "a subscriber of the room is not a target: a notice is personal")
        assertEquals(emptyList(), stranger.drain())
    }

    @Test
    fun `a full queue keeps revoked, the frame that changes what the client knows`() = withHub {
        val s = socket(1u, capacity = 4)
        register(s) // Ready
        subscribe(s, 7u) // Subscribed
        repeat(2) { deliver(hint(7u)) } // fill the queue
        deliver(hint(7u, LiveKind.ROOM_DELETED, targets = listOf(1u))) // overflows
        barrier(s)
        assertEquals(
            listOf(
                LiveServerFrame.Ready(), LiveServerFrame.Subscribed(listOf(7u), emptyList(), emptyList()),
                LiveServerFrame.Revoked(7u), LiveServerFrame.Resync,
            ),
            s.drain(),
            "the events were lost to one resync; the revocation was not",
        )
    }

    @Test
    fun `the snapshot lists the registered sockets with a copy of their ACTIVE rooms`() = withHub {
        val a = socket(1u)
        val b = socket(2u)
        register(a)
        register(b)
        subscribe(a, 7u, 8u)
        beginSubscribe(a, listOf(9u)) // PENDING: not listed
        assertEquals(setOf(a, b), snapshot().map { it.socket }.toSet())
        assertEquals(setOf(7u, 8u), snapshot().first { it.socket == a }.activeRooms.toSet())
        unregister(a)
        assertEquals(listOf(b), snapshot().map { it.socket })
    }

    @Test
    fun `revokeRoom drops an ACTIVE room and tells the socket once, and ignores a room it does not hold`() = withHub {
        val s = socket(1u)
        register(s)
        subscribe(s, 7u)
        s.drain()
        revokeRoom(s, 8u)
        revokeRoom(s, 7u)
        revokeRoom(s, 7u)
        deliver(hint(7u))
        barrier(s)
        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u)), s.drain(), "one frame, and the room is gone")
    }

    @Test
    fun `a stopped hub fails closed for every request reply command`() = withHub {
        stop()
        val s = socket()

        assertEquals(RegisterResult.UNAVAILABLE, register(s))
        val begin = beginSubscribe(s, listOf(7u))
        assertEquals(emptyList(), begin.toCheck)
        assertEquals(emptyList(), snapshot())
        deliver(hint(7u))
    }

    @Test
    fun `commands for a socket that was never registered are harmless`() = withHub {
        val ghost = socket()
        val anchor = socket(2u)
        register(anchor)
        anchor.drain()

        val begin = beginSubscribe(ghost, listOf(7u))
        assertEquals(emptyList(), begin.toCheck)
        completeSubscribe(ghost, SubscribeBegin(emptyList(), listOf(7u), emptyList()), setOf(7u))
        abortSubscribe(ghost, listOf(7u))
        revokeRoom(ghost, 7u)
        barrier(anchor)

        assertEquals(emptyList(), ghost.drain())
    }

    @Test
    fun `a deleted room can target an unsubscribed socket without a subscriber set`() = withHub {
        val target = socket(1u)
        register(target)
        target.drain()

        deliver(hint(7u, LiveKind.ROOM_DELETED, targets = listOf(1u)))
        barrier(target)

        assertEquals(listOf<LiveServerFrame>(LiveServerFrame.Revoked(7u)), target.drain())
    }

    @Test
    fun `closed or zero-capacity socket queues never accept another frame`() = withHub {
        val closed = socket()
        closed.closeRequest.complete(CloseReason(CloseReason.Codes.NORMAL, "done"))
        queueFrame(closed, LiveServerFrame.Ready())
        assertEquals(emptyList(), closed.drain())

        val blocked = socket(capacity = 0)
        queueFrame(blocked, LiveServerFrame.Ready())
        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, blocked.closeRequest.await().code)
    }
}
