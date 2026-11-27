package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.live.LiveHubKey
import ch.nokillswit.live.LiveServerFrame
import ch.nokillswit.live.LiveSweep
import ch.nokillswit.live.LiveSweepKey
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.rooms.RoomCreateRequest
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ch.nokillswit.auth.hashPassword

/**
 * Who stops hearing what, and when (`live.md` "Hints", "Account standing"), through the real application: a removed member or a deleted
 * room revokes the subscription AT ONCE (the pushed `revoked`), a personal hint reaches the person it is about without a subscription,
 * a socket ends at its token's `exp` (4001) and the 60 s account sweep ends it 4003 when the account changed. Negative cases are proved
 * by ORDER (a marker hint a different socket receives, never a sleep). Every test mints unique rows and removes them.
 */
class LiveRevocationTest {
    private val rooms = "/api/v1/rooms"
    private val users = mutableListOf<UInt>()
    private val roomIds = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(roomIds)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { users += it.id }

    private suspend fun room(
        owner: RoomActor,
        vararg members: RoomActor,
        accessType: RoomAccessType = RoomAccessType.RESTRICTED_HIDDEN,
    ): UInt {
        val template = TestThreads.seedTemplate().also { templates += it }
        val room = TestRooms.seed(owner.id, template, "Rev ${UUID.randomUUID().toString().substring(0, 8)}", accessType = accessType)
            .also { roomIds += it }
        members.forEach { TestRooms.addMember(room, it.id) }
        return room
    }

    /** An authenticated socket subscribed to [room] (the ack consumed). */
    private suspend fun ApplicationTestBuilder.subscribed(who: RoomActor, room: UInt): LiveTestSocket {
        val socket = openAuthenticatedLive(liveClient(), who)
        socket.subscribe(room)
        val ack = socket.nextNonResync()
        assertEquals(LiveServerFrame.Subscribed(listOf(room), emptyList(), emptyList()), ack)
        return socket
    }

    private suspend fun LiveTestSocket.expectClose(code: Short) {
        val reason = assertNotNull(awaitClose(), "the server closed with no reason")
        assertEquals(code, reason.code, "close code (reason: ${reason.message})")
    }

    private fun updateUser(userId: UInt, set: String) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("UPDATE users SET $set WHERE id = ?").use { st ->
            st.setLong(1, userId.toLong())
            st.executeUpdate()
        }
    }

    @Test
    fun `a removed member is revoked and gets no further hints`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("rev-own")
        val member = actor("rev-mem")
        val room = room(owner, member)
        val thread = TestThreads.seed(room, owner.id)
        val ownerSocket = subscribed(owner, room)
        val memberSocket = subscribed(member, room)
        val memberTwo = subscribed(member, room) // a second tab of the same person
        awaitListenerUp(hub)

        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        val removed = owner.client.delete("$rooms/$room/members/${member.id}") {
            header(MUTATION_ID_HEADER, mutationId)
        }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(LiveServerFrame.Revoked(room), memberSocket.nextNonResync())
        assertEquals(LiveServerFrame.Revoked(room), memberTwo.nextNonResync())
        val heard = ownerSocket.nextNonResync()
        assertEquals(
            LiveServerFrame.Event(LiveKind.ROOM_MEMBER_REMOVED, room, mutationId = mutationId),
            heard,
            "the writer hears correlation while the target's revocation stays id-free",
        )

        // The marker: when the owner's socket has the post, the hub has already decided who else gets it.
        val posted = owner.client.postJson("$rooms/$room/threads/${thread.number}/messages", MessageRequest("After the removal"))
        assertEquals(HttpStatusCode.Created, posted.status)
        assertEquals(LiveKind.MESSAGE_POSTED, (ownerSocket.nextNonResync() as LiveServerFrame.Event).kind)
        assertEquals(null, memberSocket.nextFrameOrNull(300), "the removed member hears nothing more")
        assertEquals(null, memberTwo.nextFrameOrNull(300))
        listOf(ownerSocket, memberSocket, memberTwo).forEach { it.close() }
    }

    @Test
    fun `a removed ADMIN keeps the room`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("rev-aown")
        val admin = actor("rev-adm", UserRole.ADMIN)
        val room = room(owner, admin, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        val thread = TestThreads.seed(room, owner.id)
        val ownerSocket = subscribed(owner, room)
        val adminSocket = subscribed(admin, room)
        awaitListenerUp(hub)

        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        val removed = owner.client.delete("$rooms/$room/members/${admin.id}") {
            header(MUTATION_ID_HEADER, mutationId)
        }
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(
            LiveServerFrame.Event(LiveKind.ROOM_MEMBER_REMOVED, room, mutationId = mutationId),
            ownerSocket.nextNonResync(),
        )
        assertEquals(LiveServerFrame.Event(LiveKind.ROOM_MEMBER_REMOVED, room), adminSocket.nextNonResync())
        owner.client.postJson("$rooms/$room/threads/${thread.number}/messages", MessageRequest("Still here"))
        assertEquals(LiveKind.MESSAGE_POSTED, (ownerSocket.nextNonResync() as LiveServerFrame.Event).kind)
        assertEquals(LiveKind.MESSAGE_POSTED, (adminSocket.nextNonResync() as LiveServerFrame.Event).kind)
        ownerSocket.close()
        adminSocket.close()
    }

    @Test
    fun `a removed ADMIN loses a hidden room immediately and cannot subscribe again`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("rev-hidden-own")
        val admin = actor("rev-hidden-adm", UserRole.ADMIN)
        val room = room(owner, admin)
        val socket = subscribed(admin, room)
        awaitListenerUp(application.attributes[LiveHubKey])

        assertEquals(HttpStatusCode.NoContent, owner.client.delete("$rooms/$room/members/${admin.id}").status)
        assertEquals(LiveServerFrame.Revoked(room), socket.nextNonResync())
        socket.subscribe(room)
        assertEquals(LiveServerFrame.Subscribed(emptyList(), listOf(room), emptyList()), socket.nextNonResync())
        socket.close()
    }

    @Test
    fun `a deleted room revokes every subscriber and every member`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("rev-down")
        val member = actor("rev-dmem")
        val admin = actor("rev-dadm", UserRole.ADMIN)
        val room = room(owner, member, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        val ownerSocket = subscribed(owner, room)
        val adminSocket = subscribed(admin, room) // an ADMIN who is not a member
        val memberSocket = openAuthenticatedLive(liveClient(), member) // a member who never subscribed
        awaitListenerUp(hub)

        assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/$room").status)
        listOf(ownerSocket, adminSocket, memberSocket).forEach {
            assertEquals(LiveServerFrame.Revoked(room), it.nextNonResync())
            assertEquals(null, it.nextFrameOrNull(200), "one frame for the deletion")
            it.close()
        }
    }

    @Test
    fun `an added member's socket hears about the room without subscribing`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("rev-aown2")
        val newcomer = actor("rev-new")
        val bystander = actor("rev-by")
        val room = room(owner)
        val first = openAuthenticatedLive(liveClient(), newcomer)
        val second = openAuthenticatedLive(liveClient(), newcomer)
        val other = openAuthenticatedLive(liveClient(), bystander)
        val writer = subscribed(owner, room)
        awaitListenerUp(hub)
        other.settle() // the first LISTEN's resync may have reached it: the silence below is about the hint

        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        val added = owner.client.post("$rooms/$room/members") {
            header(MUTATION_ID_HEADER, mutationId)
            contentType(ContentType.Application.Json)
            setBody(RoomMembersAddRequest(userIds = listOf(newcomer.id)))
        }
        assertEquals(HttpStatusCode.OK, added.status)
        val expected = LiveServerFrame.Event(LiveKind.ROOM_MEMBER_ADDED, room)
        assertEquals(LiveServerFrame.Event(LiveKind.ROOM_MEMBER_ADDED, room, mutationId = mutationId), writer.nextNonResync())
        assertEquals(expected, first.nextNonResync())
        assertEquals(expected, second.nextNonResync(), "every socket of the person")
        val notice = LiveServerFrame.Event(LiveKind.NOTIFICATION_CREATED, room)
        assertEquals(notice, first.nextNonResync())
        assertEquals(notice, second.nextNonResync(), "the bell is also refreshed in every tab")
        assertEquals(null, other.nextFrameOrNull(300), "nobody else")

        // The role change is personal too.
        val promoted = owner.client.putJson("$rooms/$room/members/${newcomer.id}", RoomMemberRoleRequest(RoomRole.OWNER))
        assertEquals(HttpStatusCode.NoContent, promoted.status)
        assertEquals(LiveServerFrame.Event(LiveKind.ROOM_MEMBER_ROLE_CHANGED, room), first.nextNonResync())
        listOf(first, second, other, writer).forEach { it.close() }
    }

    @Test
    fun `a new room reaches the creator's other socket`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val creator = actor("rev-cre")
        val stranger = actor("rev-str")
        val template = TestThreads.seedTemplate().also { templates += it }
        val tab = openAuthenticatedLive(liveClient(), creator)
        val other = openAuthenticatedLive(liveClient(), stranger)
        awaitListenerUp(hub)
        other.settle() // the first LISTEN's resync may have reached it (the flake this fixes): the silence below is about the hint

        val created = creator.client.postJson(rooms, RoomCreateRequest("Rev new ${UUID.randomUUID().toString().substring(0, 8)}", template))
        assertEquals(HttpStatusCode.Created, created.status)
        val room = created.headers["Location"]!!.substringAfterLast('/').toUInt().also { roomIds += it }
        assertEquals(LiveServerFrame.Event(LiveKind.ROOM_CREATED, room), tab.nextNonResync())
        assertEquals(null, other.nextFrameOrNull(300))
        tab.close()
        other.close()
    }

    @Test
    fun `the socket closes 4001 when its access token expires`() = testApplication {
        configureApp("jwt.accessExpiresInSeconds" to "5")
        startApplication()
        val hub = application.attributes[LiveHubKey]
        val user = actor("rev-exp")
        val socket = openAuthenticatedLive(liveClient(), user)
        assertEquals(1, hub.socketsOf(user.id))
        assertEquals(4001, assertNotNull(socket.awaitClose(10_000)).code)
        withTimeout(10_000) { while (hub.socketsOf(user.id) > 0) delay(25) }
    }

    @Test
    fun `the sweep closes a revoked session with 4003 and audits it, once per user`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val sweep = application.attributes[LiveSweepKey]
        val loggedOut = actor("sw-out")
        val deleted = actor("sw-del")
        val rotated = actor("sw-pwd")
        val demoted = actor("sw-adm", UserRole.ADMIN)
        val fine = actor("sw-fine")
        val ws = liveClient()
        val sockets = listOf(loggedOut, deleted, rotated, demoted, fine).associateWith { openAuthenticatedLive(ws, it) }
        val loggedOutTwo = openAuthenticatedLive(ws, loggedOut) // two tabs: still ONE audit line for the user
        val owner = actor("sw-own")
        val room = room(owner, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        sockets.getValue(demoted).subscribe(room) // an ADMIN subscribed to a room they are not in
        assertEquals(LiveServerFrame.Subscribed(listOf(room), emptyList(), emptyList()), sockets.getValue(demoted).nextNonResync())

        updateUser(loggedOut.id, "credential_revision = credential_revision + 1")
        TestUsers.softDelete(deleted.id)
        updateUser(rotated.id, "credential_revision = credential_revision + 1")
        updateUser(demoted.id, "role = 'USER'")

        withAuditCapture { capture ->
            assertEquals(5, sweep.sweepNow())
            listOf(loggedOut, deleted, rotated, demoted).forEach { sockets.getValue(it).expectClose(4003) }
            loggedOutTwo.expectClose(4003)
            val verdicts = mapOf(
                loggedOut to "credential_changed", deleted to "user_gone", rotated to "credential_changed", demoted to "roles_changed",
            )
            verdicts.forEach { (who, verdict) ->
                val line = assertNotNull(
                    capture.awaitEvent { it.message == "live.revoked" && it.hasKeyValue("byUserId", who.id.toLong()) },
                    "live.revoked for $verdict",
                )
                assertTrue(line.hasKeyValue("verdict", verdict))
                assertTrue(line.hasKeyValue("sockets", if (who == loggedOut) 2 else 1))
            }
            assertEquals(4, capture.events.count { it.message == "live.revoked" }, "one line per user")
            assertTrue(capture.events.none { it.message == "live.revoked" && it.hasKeyValue("byUserId", fine.id.toLong()) })
        }

        // The untouched account keeps its socket (and a second sweep has nothing left to close).
        val survivor = sockets.getValue(fine)
        survivor.sendText("""{"type":"ping"}""")
        assertEquals(LiveServerFrame.Pong, survivor.nextNonResync())
        withTimeout(10_000) { while (hub.socketCount > 1) delay(25) }
        assertEquals(0, sweep.sweepNow())
        assertEquals(1, hub.socketsOf(fine.id))
        survivor.close()
    }

    @Test
    fun `the periodic sweep closes a revoked session without being asked`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val user = actor("sw-loop")
        val socket = openAuthenticatedLive(liveClient(), user)
        updateUser(user.id, "credential_revision = credential_revision + 1")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LiveSweep(
                hub, application.attributes[TokenBlocklistServiceKey], application.attributes[RoomServiceKey], intervalMillis = 100,
            ).start(scope)
            socket.expectClose(4003)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `account prompts close role deleted and revised accounts while an unchanged role stays live`() = testApplication {
        usePostgresTestcontainer()
        val roleChanged = actor("prompt-role")
        val deleted = actor("prompt-delete")
        val revised = actor("prompt-revision")
        val unchanged = actor("prompt-same")
        val sockets = listOf(roleChanged, deleted, revised, unchanged).associateWith { openAuthenticatedLive(liveClient(), it) }
        awaitListenerUp(application.attributes[LiveHubKey])

        val roleUser = requireNotNull(TestUsers.service.read(roleChanged.id))
        assertEquals(
            ch.nokillswit.users.UserService.GuardedMutation.DONE,
            TestUsers.service.updateGuarded(roleChanged.id, roleUser.name, roleUser.email, UserRole.ADMIN),
        )
        assertEquals(ch.nokillswit.users.UserService.GuardedMutation.DONE, TestUsers.service.deleteGuarded(deleted.id))
        assertEquals(1, TestUsers.service.updatePassword(revised.id, hashPassword("replacement-123", cost = 4)))
        val same = requireNotNull(TestUsers.service.read(unchanged.id))
        assertEquals(
            ch.nokillswit.users.UserService.GuardedMutation.DONE,
            TestUsers.service.updateGuarded(unchanged.id, same.name, same.email, same.role),
        )

        listOf(roleChanged, deleted, revised).forEach { sockets.getValue(it).expectClose(4003) }
        sockets.getValue(unchanged).sendText("""{"type":"ping"}""")
        assertEquals(LiveServerFrame.Pong, sockets.getValue(unchanged).nextNonResync())
        sockets.getValue(unchanged).close()
    }

    @Test
    fun `logout closes only the access jti used for it`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("prompt-jti")
        val id = TestUsers.seed(email, "password-123", role = UserRole.USER).also { users += it }
        val first = RoomActor(id, authedClient(email, "password-123"))
        val second = RoomActor(id, authedClient(email, "password-123"))
        val firstSocket = openAuthenticatedLive(liveClient(), first)
        val secondSocket = openAuthenticatedLive(liveClient(), second)
        awaitListenerUp(application.attributes[LiveHubKey])

        assertEquals(HttpStatusCode.NoContent, first.client.post("/api/v1/logout").status)
        firstSocket.expectClose(4003)
        secondSocket.sendText("""{"type":"ping"}""")
        assertEquals(LiveServerFrame.Pong, secondSocket.nextNonResync())
        secondSocket.close()
    }

    @Test
    fun `an account change while registration waits is rechecked before ready is written`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val who = actor("prompt-register")
        val ticket = who.client.mintLiveTicket()
        val socket = openLive(liveClient())
        val gate = CompletableDeferred<Unit>()
        hub.beforeCommand = { gate.await() }
        try {
            socket.auth(ticket.ticket)
            delay(100)
            assertEquals(1, TestUsers.service.updatePassword(who.id, hashPassword("replacement-789", cost = 4)))
        } finally {
            hub.beforeCommand = null
            gate.complete(Unit)
        }
        socket.expectClose(4003)
        assertEquals(null, socket.nextFrameOrNull(200), "ready was queued internally but never written")
    }

    private fun deleteMembership(room: UInt, userId: UInt) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
            st.setLong(1, room.toLong())
            st.setLong(2, userId.toLong())
            assertEquals(1, st.executeUpdate())
        }
    }

    @Test
    fun `the sweep revokes lost member access and keeps an ADMIN's visible rooms`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val sweep = application.attributes[LiveSweepKey]
        val owner = actor("lost-own")
        val member = actor("lost-mem")
        val admin = actor("lost-adm", UserRole.ADMIN)
        val room = room(owner, member, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        val hidden = room(owner, admin)
        val memberSocket = subscribed(member, room)
        val adminSocket = subscribed(admin, room) // an ADMIN who is not a member
        val ownerSocket = subscribed(owner, room)
        adminSocket.subscribe(hidden)
        assertEquals(LiveServerFrame.Subscribed(listOf(hidden), emptyList(), emptyList()), adminSocket.nextNonResync())
        awaitListenerUp(hub)

        deleteMembership(room, member.id) // raw: no hint is emitted, exactly like a hint lost in a listener gap
        deleteMembership(hidden, admin.id)
        withAuditCapture { capture ->
            assertEquals(0, sweep.sweepNow(), "no socket closes; only the subscription goes")
            listOf(member, admin).forEach { removed ->
                val line = assertNotNull(capture.awaitEvent {
                    it.message == "live.revoked" && it.hasKeyValue("reason", "membership") &&
                        it.hasKeyValue("byUserId", removed.id.toLong())
                })
                assertTrue(line.hasKeyValue("rooms", 1))
                assertTrue(line.hasKeyValue("sockets", 1))
            }
            assertEquals(2, capture.events.count { it.message == "live.revoked" })
        }
        assertEquals(LiveServerFrame.Revoked(room), memberSocket.nextNonResync())
        assertEquals(LiveServerFrame.Revoked(hidden), adminSocket.nextNonResync())
        listOf(adminSocket, ownerSocket).forEach {
            it.sendText("""{"type":"ping"}""")
            assertEquals(LiveServerFrame.Pong, it.nextNonResync(), "still subscribed, and told nothing")
        }
        listOf(memberSocket, adminSocket, ownerSocket).forEach { it.close() }
    }

    @Test
    fun `a gap re-checks memberships at once`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("gap-own")
        val member = actor("gap-mem")
        val room = room(owner, member)
        val memberSocket = subscribed(member, room)
        awaitListenerUp(hub)

        deleteMembership(room, member.id)
        hub.onGap() // what the listener does after a reconnect: a resync, and the membership re-check
        assertEquals(LiveServerFrame.Revoked(room), memberSocket.nextNonResync())
        memberSocket.close()
    }

    @Test
    fun `a demoted or deleted ADMIN cannot subscribe to NEW rooms, though the existing ones last until the sweep`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("adm-own")
        val demoted = actor("adm-dem", UserRole.ADMIN)
        val deleted = actor("adm-del", UserRole.ADMIN)
        val room = room(owner, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        val other = room(owner, accessType = RoomAccessType.RESTRICTED_VISIBLE)
        val demotedSocket = openAuthenticatedLive(liveClient(), demoted)
        val deletedSocket = openAuthenticatedLive(liveClient(), deleted)
        demotedSocket.subscribe(room)
        assertEquals(LiveServerFrame.Subscribed(listOf(room), emptyList(), emptyList()), demotedSocket.nextNonResync(), "still an ADMIN")

        updateUser(demoted.id, "role = 'USER'")
        TestUsers.softDelete(deleted.id)
        demotedSocket.subscribe(other)
        assertEquals(LiveServerFrame.Subscribed(emptyList(), listOf(other), emptyList()), demotedSocket.nextNonResync())
        deletedSocket.subscribe(room, other)
        assertEquals(
            setOf(room, other), (deletedSocket.nextNonResync() as LiveServerFrame.Subscribed).refused.toSet(),
            "the stored role is read from an ACTIVE user",
        )
        listOf(demotedSocket, deletedSocket).forEach { it.close() }
    }

    @Test
    fun `a failing sweep run, even a stray cancellation, does not end the loop`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val blocklist = application.attributes[TokenBlocklistServiceKey]
        val user = actor("sw-fail")
        val socket = openAuthenticatedLive(liveClient(), user)
        updateUser(user.id, "credential_revision = credential_revision + 1")

        val runs = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LiveSweep(
                hub, blocklist, application.attributes[RoomServiceKey], intervalMillis = 100, retryDelayMillis = 50,
                judge = { checks ->
                    when (runs.incrementAndGet()) {
                        1 -> error("the database went away")
                        2 -> throw kotlinx.coroutines.CancellationException("a cancelled transaction, not the scope")
                        else -> blocklist.accessVerdicts(checks)
                    }
                },
            ).start(scope)
            socket.expectClose(4003)
            assertTrue(runs.get() >= 3, "runs: ${runs.get()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failing verdict query still re-checks the memberships`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val owner = actor("half-own")
        val member = actor("half-mem")
        val room = room(owner, member)
        val memberSocket = subscribed(member, room)
        awaitListenerUp(hub)
        deleteMembership(room, member.id)

        val sweep = LiveSweep(
            hub, application.attributes[TokenBlocklistServiceKey], application.attributes[RoomServiceKey],
            judge = { error("the verdict query is down") },
        )
        val failure = assertFailsWith<IllegalStateException> { sweep.sweepNow() }
        assertEquals("the verdict query is down", failure.message, "the run reports the failure once both halves ran")
        assertEquals(LiveServerFrame.Revoked(room), memberSocket.nextNonResync(), "the membership half ran anyway")
        memberSocket.close()
    }

    @Test
    fun `a failing membership query still closes the revoked sessions`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val loggedOut = actor("half-out")
        val owner = actor("half-own2")
        val room = room(owner)
        val socket = subscribed(owner, room)
        val outSocket = openAuthenticatedLive(liveClient(), loggedOut)
        updateUser(loggedOut.id, "credential_revision = credential_revision + 1")

        val sweep = LiveSweep(
            hub, application.attributes[TokenBlocklistServiceKey], application.attributes[RoomServiceKey],
            lost = { error("the membership query is down") },
        )
        assertFailsWith<IllegalStateException> { sweep.sweepNow() }
        outSocket.expectClose(4003)
        socket.close()
    }

    @Test
    fun `a failed run is retried once after the retry delay, not a whole interval later`() = testApplication {
        usePostgresTestcontainer()
        val hub = application.attributes[LiveHubKey]
        val blocklist = application.attributes[TokenBlocklistServiceKey]
        val user = actor("sw-retry")
        val socket = openAuthenticatedLive(liveClient(), user)
        updateUser(user.id, "credential_revision = credential_revision + 1")

        val calls = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LiveSweep(
                hub, blocklist, application.attributes[RoomServiceKey], intervalMillis = RETRY_TEST_INTERVAL_MILLIS, retryDelayMillis = 50,
                judge = { checks ->
                    calls += System.nanoTime() / 1_000_000
                    if (calls.size == 1) error("a blip") else blocklist.accessVerdicts(checks)
                },
            ).start(scope)
            // The first run waits one interval (the sweep sleeps before its first pass), so the close needs more than the default 5 s wait.
            assertEquals(4003, assertNotNull(socket.awaitClose(2 * RETRY_TEST_INTERVAL_MILLIS)).code)
            assertEquals(2, calls.size, "one failure, one retry")
            // The retry is 50 ms after the failure, the next tick a whole interval: the bound sits far from both, so a loaded runner
            // cannot cross it without the retry having been skipped.
            assertTrue(
                calls[1] - calls[0] < RETRY_TEST_INTERVAL_MILLIS * 2 / 3,
                "retried ${calls[1] - calls[0]} ms later; the interval is $RETRY_TEST_INTERVAL_MILLIS ms",
            )
        } finally {
            scope.cancel()
        }
    }
}

/** The sweep tick of the retry test: long enough that the retry (50 ms later) and the next tick are far apart under load. */
private const val RETRY_TEST_INTERVAL_MILLIS = 3_000L
