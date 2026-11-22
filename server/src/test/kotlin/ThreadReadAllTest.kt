package ch.nokillswit

import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.threads.ReadMarkerRequest
import ch.nokillswit.threads.SeenChunk
import ch.nokillswit.threads.ThreadNotices
import ch.nokillswit.threads.ThreadReadService
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The thread list's `unread` filter and "Mark all as read" (1.2 A10 to A12, `chat-model.md` "Unread filter and Mark all as read"): the
 * filter is exactly `unreadCount > 0` and combines with the other filters; `POST /rooms/{id}/read-all` raises the caller's own floor
 * and settles their notices of that room, and nobody else's. Rooms, templates and users are SHARED suite state: every test mints UNIQUE
 * rows and removes them. A membership and the first message after it, or a read-all and the message after it, can share a millisecond
 * (an unread message is strictly NEWER than the floor), so the tests wait a few milliseconds between the two.
 */
class ThreadReadAllTest {
    private val rooms = "/api/v1/rooms"
    private val notices = "/api/v1/notifications"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Cast(
        val owner: RoomActor,
        val alice: RoomActor,
        val bob: RoomActor,
        val outsider: RoomActor,
        /** An ADMIN who is NOT a member of the room. */
        val admin: RoomActor,
        val room: UInt,
        val name: String,
    )

    /** A room owned by Olivia with Alice as a member; Bob joins when [bobJoins] is called (a thread may come first). */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String, role: UserRole = UserRole.USER) =
            seedActor("$prefix-$suffix", role, suffix).also { mintedUsers += it.id }
        val owner = actor("own")
        val alice = actor("ali")
        val bob = actor("bob")
        val outsider = actor("out")
        val admin = actor("adm", UserRole.ADMIN)
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val name = "Ra ${UUID.randomUUID().toString().substring(0, 8)}"
        val room = TestRooms.seed(owner.id, template, name).also { mintedRooms += it }
        TestRooms.addMember(room, alice.id)
        delay(SETTLE)
        return Cast(owner, alice, bob, outsider, admin, room, name)
    }

    private suspend fun Cast.bobJoins() {
        TestRooms.addMember(room, bob.id)
        delay(SETTLE)
    }

    private suspend fun HttpClient.page(room: UInt, query: String = ""): ThreadPageResponse =
        get("$rooms/$room/threads$query").body()

    private suspend fun HttpClient.numbers(room: UInt, query: String): List<Int> = page(room, query).items.map { it.number }

    private suspend fun HttpClient.thread(room: UInt, number: Int): ThreadResponse = get("$rooms/$room/threads/$number").body()

    private suspend fun HttpClient.mark(room: UInt, number: Int, message: UInt): HttpResponse =
        putJson("$rooms/$room/threads/$number/read-marker", ReadMarkerRequest(message))

    // The operation has no request body: no JSON content type and no empty entity, as a real client sends it.
    private suspend fun HttpClient.readAll(room: UInt): HttpResponse = post("$rooms/$room/read-all")

    private suspend fun HttpClient.unseenNotices(): Long = get("$notices?seen=false").body<NotificationPageResponse>().total

    private suspend fun HttpClient.roomUnread(name: String): Boolean =
        get("$rooms?name=$name").body<RoomPageResponse>().items.single().unread

    private fun markerRows(room: UInt, user: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM thread_reads WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, user.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** `seen_at` and `read_from` of the member row (0 = since joining). */
    private fun floors(room: UInt, user: UInt): Pair<Long, Long> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT seen_at, read_from FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, user.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getLong(2) }
        }
    }

    /**
     * Six threads. Thread 1 is older than Bob (his floor is past everything in it) but has fresh ACTIVITY, the case a count of messages
     * and a comparison of activity times tell apart; 2 was never opened; 3 was read and then got a mention; 4 was read to the end; 5 is
     * Bob's own; 6 has an unread message and is closed. Bob's unread threads are 2, 3 and 6; Alice's is 5; Olivia, who read nothing,
     * has all.
     */
    private suspend fun ApplicationTestBuilder.populated(prefix: String): Cast {
        val cast = cast(prefix)
        TestThreads.seed(cast.room, cast.alice.id)
        cast.bobJoins()
        val old = checkNotNull(TestThreads.raw(cast.room, 1))
        TestThreads.setStamps(cast.room, 1, old.createdAt, System.currentTimeMillis())
        TestThreads.seed(cast.room, cast.alice.id)
        val third = TestThreads.seed(cast.room, cast.alice.id)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 3, third.messageId).status)
        TestThreads.post(cast.room, 3, cast.alice.id, "<@${cast.bob.id}> one more")
        val fourth = TestThreads.seed(cast.room, cast.alice.id)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 4, fourth.messageId).status)
        TestThreads.seed(cast.room, cast.bob.id)
        TestThreads.seed(cast.room, cast.alice.id)
        TestThreads.closeRaw(cast.room, 6)
        return cast
    }

    @Test
    fun `the unread filter keeps exactly the rows whose count is above zero, and false the rest`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-filter")
        val expected = mapOf(
            cast.bob to setOf(2, 3, 6),
            cast.alice to setOf(5),
            cast.owner to (1..6).toSet(),
        )
        for ((who, unread) in expected) {
            val all = who.client.page(cast.room, "?pageSize=100").items
            assertEquals(unread, all.filter { it.unreadCount > 0 }.map { it.number }.toSet(), "the counts the filter must agree with")
            assertEquals(unread.sorted(), who.client.numbers(cast.room, "?unread=true"))
            assertEquals((1..6).filter { it !in unread }, who.client.numbers(cast.room, "?unread=false"))
            assertEquals(unread.size.toLong(), who.client.page(cast.room, "?unread=true&pageSize=1").total, "total on the same predicate")
            assertEquals((6 - unread.size).toLong(), who.client.page(cast.room, "?unread=false&pageSize=1").total)
            assertEquals((1..6).toList(), who.client.numbers(cast.room, "?unread="), "a blank value filters nothing")
        }
    }

    @Test
    fun `the unread filter combines with closed, status, owner, watching, mentions and paging`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-combine")
        val bob = cast.bob.client
        assertEquals(listOf(6), bob.numbers(cast.room, "?unread=true&closed=true"))
        assertEquals(listOf(2, 3), bob.numbers(cast.room, "?unread=true&closed=false"))
        assertEquals(listOf(6), bob.numbers(cast.room, "?unread=true&status=done"))
        assertEquals(listOf(2, 3), bob.numbers(cast.room, "?unread=true&ownerId=${cast.alice.id}&closed=false"))
        assertEquals(listOf(3), bob.numbers(cast.room, "?unread=true&mentionsMe=true"))
        assertEquals(listOf(2, 6), bob.numbers(cast.room, "?unread=true&mentionsMe=false"))
        assertEquals(emptyList(), bob.numbers(cast.room, "?unread=true&watching=true"), "Bob watches nothing he did not start")
        assertEquals(HttpStatusCode.NoContent, bob.putJson("$rooms/${cast.room}/threads/6/watch", WatchRequest(true)).status)
        assertEquals(listOf(6), bob.numbers(cast.room, "?unread=true&watching=true"))
        assertEquals(listOf(5), bob.numbers(cast.room, "?unread=false&watching=true&ownerId=${cast.bob.id}"))
        val first = bob.page(cast.room, "?unread=true&pageSize=2&sort=number")
        assertEquals(listOf(2, 3), first.items.map { it.number })
        assertEquals(3L, first.total, "the page cuts the rows, not the total")
        assertEquals(listOf(6), bob.page(cast.room, "?unread=true&pageSize=2&page=2&sort=number").items.map { it.number })
        assertEquals(listOf(6, 3, 2), bob.numbers(cast.room, "?unread=true&sort=-number"))
    }

    @Test
    fun `a marker, a read-all and a new message move a row in and out of the filter`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-move")
        val bob = cast.bob.client
        assertEquals(listOf(2, 3, 6), bob.numbers(cast.room, "?unread=true"))
        val last = checkNotNull(TestThreads.rawMessages(TestThreads.raw(cast.room, 2)!!.id).last().id)
        assertEquals(HttpStatusCode.NoContent, bob.mark(cast.room, 2, last).status)
        assertEquals(listOf(3, 6), bob.numbers(cast.room, "?unread=true"), "reading a thread takes it out")
        TestThreads.post(cast.room, 2, cast.alice.id, "back again")
        assertEquals(listOf(2, 3, 6), bob.numbers(cast.room, "?unread=true"), "a new message puts it back")
    }

    @Test
    fun `an administrator outside the room has no read state, a stranger is 404 and a bad value is 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-guard")
        assertEquals(emptyList(), cast.admin.client.numbers(cast.room, "?unread=true"))
        assertEquals((1..6).toList(), cast.admin.client.numbers(cast.room, "?unread=false"))
        assertEquals(0L, cast.admin.client.page(cast.room, "?unread=true").total)
        for (query in listOf("?unread=yes", "?unread=1", "?unread=TRUE", "?unread=true&unread=false")) {
            assertEquals(HttpStatusCode.BadRequest, cast.bob.client.get("$rooms/${cast.room}/threads$query").status, query)
        }
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get("$rooms/${cast.room}/threads?unread=true").status)
        assertEquals(
            HttpStatusCode.NotFound,
            cast.outsider.client.get("$rooms/${cast.room}/threads?unread=maybe").status,
            "a stranger's bad filter is still a 404",
        )
    }

    @Test
    fun `mark all as read clears every count, the room's dot and nothing of anybody else`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-all")
        val bob = cast.bob.client
        assertTrue(bob.roomUnread(cast.name), "the room is new for Bob")
        val markersBefore = markerRows(cast.room, cast.bob.id)
        val aliceFloors = floors(cast.room, cast.alice.id)
        val ownerFloors = floors(cast.room, cast.owner.id)
        val aliceCounts = cast.alice.client.page(cast.room, "?pageSize=100").items.map { it.number to it.unreadCount }
        val ownerCounts = cast.owner.client.page(cast.room, "?pageSize=100").items.map { it.number to it.unreadCount }

        assertEquals(HttpStatusCode.NoContent, bob.readAll(cast.room).status)

        assertEquals(List(6) { 0 }, bob.page(cast.room, "?pageSize=100").items.map { it.unreadCount })
        assertEquals(emptyList(), bob.numbers(cast.room, "?unread=true"))
        assertEquals(6, bob.numbers(cast.room, "?unread=false").size)
        for (number in listOf(2, 3, 6)) {
            val thread = bob.thread(cast.room, number)
            assertEquals(0, thread.unreadCount, "thread $number, closed or not")
            assertNull(thread.firstUnreadMessageId)
        }
        assertEquals(false, bob.roomUnread(cast.name), "the dot goes out")
        assertEquals(markersBefore, markerRows(cast.room, cast.bob.id), "no thread marker moves: the floor does the work")
        assertEquals(aliceFloors, floors(cast.room, cast.alice.id), "somebody else's floors")
        assertEquals(ownerFloors, floors(cast.room, cast.owner.id))
        assertEquals(aliceCounts, cast.alice.client.page(cast.room, "?pageSize=100").items.map { it.number to it.unreadCount })
        assertEquals(ownerCounts, cast.owner.client.page(cast.room, "?pageSize=100").items.map { it.number to it.unreadCount })
        assertEquals(listOf(5), cast.alice.client.numbers(cast.room, "?unread=true"))
    }

    @Test
    fun `mark all as read is idempotent, only moves forward, and a later message or thread is unread again`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-again")
        val bob = cast.bob.client
        assertEquals(HttpStatusCode.NoContent, bob.readAll(cast.room).status)
        val (seen, from) = floors(cast.room, cast.bob.id)
        assertEquals(seen, from, "one instant raised both")
        delay(SETTLE)
        assertEquals(HttpStatusCode.NoContent, bob.readAll(cast.room).status, "nothing was unread: the same answer")
        val (seenAgain, fromAgain) = floors(cast.room, cast.bob.id)
        assertTrue(seenAgain >= seen && fromAgain >= from, "forward only")
        assertEquals(emptyList(), bob.numbers(cast.room, "?unread=true"))

        delay(SETTLE)
        val message = TestThreads.post(cast.room, 4, cast.alice.id, "after the read-all")
        assertEquals(listOf(4), bob.numbers(cast.room, "?unread=true"))
        assertEquals(message, bob.thread(cast.room, 4).firstUnreadMessageId)
        assertEquals(1, bob.thread(cast.room, 4).unreadCount)
        assertTrue(bob.roomUnread(cast.name), "the room is new again")
        delay(SETTLE)
        TestThreads.seed(cast.room, cast.alice.id)
        assertEquals(listOf(4, 7), bob.numbers(cast.room, "?unread=true"), "a thread started afterwards is unread")
    }

    @Test
    fun `mark all as read settles the caller's notices of that room only`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-notices")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val other = TestRooms.seed(cast.owner.id, template, "Rb ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(other, cast.alice.id)
        TestRooms.addMember(other, cast.bob.id)
        delay(SETTLE)
        TestThreads.seed(other, cast.alice.id)
        TestThreads.post(other, 1, cast.owner.id, "<@${cast.bob.id}> over here")
        TestThreads.post(cast.room, 5, cast.bob.id, "<@${cast.alice.id}> yours")
        val bobBefore = cast.bob.client.unseenNotices()
        val aliceBefore = cast.alice.client.unseenNotices()
        assertTrue(bobBefore >= 3, "the room's thread-created and mention notices and the other room's two ($bobBefore)")

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status)

        val inOther = cast.bob.client.get("$notices?seen=false").body<NotificationPageResponse>().items
        assertTrue(inOther.isNotEmpty() && inOther.all { it.roomId == other }, "only the other room's notices are left: $inOther")
        assertTrue(cast.bob.client.get("$notices?seen=true").body<NotificationPageResponse>().total >= 1)
        assertEquals(aliceBefore, cast.alice.client.unseenNotices(), "Alice's notices are not Bob's to settle")
    }

    @Test
    fun `mark all as read answers the member rule, visibility and an archived room as the matrix says`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-authz")
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.readAll(cast.room).status, "an ADMIN outside has no read state")
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.readAll(2_000_000_000u).status, "an unknown room, the ADMIN too")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.readAll(cast.room).status)
            val denied = capture.awaitEvent { it.message == "room.access_denied" && it.hasKeyValue("byUserId", cast.outsider.id.toLong()) }
            assertNotNull(denied)
        }
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.readAll(cast.room).status, "the OWNER is a member like any other")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.postJson("$rooms/${cast.room}/archive", "").status)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status, "view state: an archived room is marked too")
        assertEquals(emptyList(), cast.bob.client.numbers(cast.room, "?unread=true"))
    }

    @Test
    fun `mark all as read shares the read-state bucket and waits for no room lock`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("ra-bucket") // no marker writes in the set-up: they spend the same bucket
        cast.bobJoins()
        TestThreads.seed(cast.room, cast.alice.id)
        TestThreads.holdingRoomLock(cast.room) {
            val elapsed = measureTimeMillis { assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status) }
            assertTrue(elapsed < 1500, "answered at once while the room lock is held ($elapsed ms)")
        }
        repeat(239) { n -> assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status, "write ${n + 2}") }
        assertEquals(HttpStatusCode.TooManyRequests, cast.bob.client.readAll(cast.room).status)
        assertEquals(HttpStatusCode.TooManyRequests, cast.bob.client.postJson("$rooms/${cast.room}/seen", "").status)
        assertEquals(HttpStatusCode.OK, cast.bob.client.get("$rooms/${cast.room}/threads?unread=true").status, "reads are not bucketed")
    }

    @Test
    fun `a member removed while their read-all runs gets 404, never 500`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-race")
        TestThreads.holdingRoomLock(
            cast.room,
            "DELETE FROM room_members WHERE room_id = ? AND user_id = ?",
            cast.room, cast.bob.id,
        ) { raw ->
            coroutineScope {
                // The route's guards pass (the removal is uncommitted); the update then waits on the member row and finds it gone.
                val writing = async { cast.bob.client.readAll(cast.room) }
                awaitLockWait("room_members")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, writing.await().status)
            }
        }
        assertEquals(0, markerRows(cast.room, cast.bob.id))
        // The refused call left nothing behind: no slot is held, so once Bob is a member again his next call is 204, not 429.
        TestRooms.addMember(cast.room, cast.bob.id)
        delay(SETTLE)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status)
    }

    @Test
    fun `a message written while the read-all waits for the member row stays unread`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-during")
        // Bob's own row is held by another transaction, so his read-all (its instant is taken first) waits; a message is written now.
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE room_members SET seen_at = seen_at WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.bob.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val marking = async { cast.bob.client.readAll(cast.room) }
                    awaitLockWait("room_members")
                    delay(SETTLE)
                    val late = TestThreads.post(cast.room, 4, cast.alice.id, "written during the read-all")
                    raw.commit()
                    assertEquals(HttpStatusCode.NoContent, marking.await().status)
                    assertEquals(late, cast.bob.client.thread(cast.room, 4).firstUnreadMessageId, "newer than the instant it took")
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(listOf(4), cast.bob.client.numbers(cast.room, "?unread=true"), "everything older is read")
    }

    /** Raw unseen thread-level notices of [user] in [room] ([count] of them, stamped [createdAt]), past the writer: a pile of any size. */
    private fun pile(room: UInt, user: UInt, thread: UInt, actor: UInt, count: Int, createdAt: Long = 1L) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
                    "SELECT ?, ?, 'THREAD_ASSIGNED', ?, ?, ? FROM generate_series(1, ?)",
            ).use { st ->
                st.setLong(1, user.toLong())
                st.setInt(2, room.toInt())
                st.setLong(3, actor.toLong())
                st.setInt(4, thread.toInt())
                st.setLong(5, createdAt)
                st.setInt(6, count)
                st.executeUpdate()
            }
        }
    }

    private fun unseenIn(room: UInt, user: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE recipient_id = ? AND room_id = ? AND seen_at IS NULL").use { st ->
            st.setLong(1, user.toLong())
            st.setInt(2, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a pile of notices larger than a chunk is settled whole, and the audit line counts it`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-pile")
        val threadId = checkNotNull(TestThreads.raw(cast.room, 1)).id
        TestNotices.clearRoom(cast.room)
        pile(cast.room, cast.bob.id, threadId, cast.alice.id, 2_300)
        pile(cast.room, cast.alice.id, threadId, cast.bob.id, 4)
        assertEquals(2_300, unseenIn(cast.room, cast.bob.id))
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status)
            val line = capture.awaitEvent {
                it.message == "room.read_all" && it.hasKeyValue("byUserId", cast.bob.id.toLong()) &&
                    it.hasKeyValue("roomId", cast.room.toLong())
            }
            assertNotNull(line)
            assertTrue(line.hasKeyValue("settled", 2_300), "the count of what it settled: ${line.keyValuePairs}")
            assertTrue(line.hasKeyValue("complete", true))
        }
        assertEquals(0, unseenIn(cast.room, cast.bob.id), "three chunks of at most 1,000")
        assertEquals(4, unseenIn(cast.room, cast.alice.id), "somebody else's pile")
        // Nothing to settle: no line at all (the bucket allows 240 calls a minute; the trail records what a call DID). The line is
        // written before the response, so none is pending.
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status)
            assertTrue(capture.events.none { it.message == "room.read_all" }, capture.events.map { it.message }.toString())
        }
    }

    @Test
    fun `mark all as read is audited once with ids and a count, and announces nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-audit")
        withAuditCapture { audit ->
            NotifyCapture().use { capture ->
                val hints = capture.during { assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status) }
                assertEquals(emptyList(), hints, "view state: no live hint")
            }
            val lines = audit.events.filter { it.message == "room.read_all" }
            assertEquals(1, lines.size, audit.events.map { it.message }.toString())
            val line = lines.single()
            assertTrue(line.hasKeyValue("byUserId", cast.bob.id.toLong()) && line.hasKeyValue("roomId", cast.room.toLong()))
            val keys = line.keyValuePairs.orEmpty().map { it.key }.toSet()
            assertEquals(setOf("byUserId", "roomId", "settled", "complete"), keys, "ids, a count and a flag only")
            assertTrue(line.hasKeyValue("complete", true))
            assertTrue(audit.events.none { it.message == "authz.denied" || it.message == "room.access_denied" })
        }
    }

    @Test
    fun `a notice made while the read-all waits for the member row stays unseen`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-notice")
        val before = unseenIn(cast.room, cast.bob.id)
        assertTrue(before > 0)
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE room_members SET seen_at = seen_at WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.bob.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val marking = async { cast.bob.client.readAll(cast.room) }
                    awaitLockWait("room_members")
                    delay(SETTLE)
                    // The mention's notice is made AFTER the instant the request took, though before its notice settle runs.
                    TestThreads.post(cast.room, 4, cast.alice.id, "<@${cast.bob.id}> written during the read-all")
                    raw.commit()
                    assertEquals(HttpStatusCode.NoContent, marking.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(1, unseenIn(cast.room, cast.bob.id), "only the notice of the later message is left")
        assertEquals(1L, cast.bob.client.unseenNotices())
    }

    @Test
    fun `a room write is never blocked by a read-all that waits on a notice row`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-nolock")
        // Another transaction holds one of Bob's unseen notice rows: his read-all waits for it inside a chunk, which holds neither the
        // room lock nor his member row, so a status move in the same room (a room write) completes meanwhile.
        val held = PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT min(id) FROM notifications WHERE recipient_id = ? AND room_id = ? AND seen_at IS NULL").use { st ->
                st.setLong(1, cast.bob.id.toLong())
                st.setInt(2, cast.room.toInt())
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE notifications SET seen_at = NULL WHERE id = ?").use { st ->
                st.setInt(1, held)
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val marking = async { cast.bob.client.readAll(cast.room) }
                    awaitLockWait("notifications")
                    val elapsed = measureTimeMillis {
                        val move = cast.alice.client.putJson("$rooms/${cast.room}/threads/2/status", ThreadStatusRequest("monitoring"))
                        assertEquals(HttpStatusCode.NoContent, move.status)
                        assertEquals(HttpStatusCode.OK, cast.owner.client.get("$rooms/${cast.room}/threads/2").status)
                    }
                    assertTrue(elapsed < 1500, "the room write did not wait for the read-all ($elapsed ms)")
                    assertTrue(!marking.isCompleted, "the read-all is still waiting on its notice row")
                    raw.commit()
                    assertEquals(HttpStatusCode.NoContent, marking.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(0, unseenIn(cast.room, cast.bob.id))
    }

    @Test
    fun `a second read-all while one runs is the typed 429 read-busy, and the first still finishes`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-flight")
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE room_members SET seen_at = seen_at WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.bob.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val first = async { cast.bob.client.readAll(cast.room) }
                    awaitLockWait("room_members")
                    withAuditCapture { capture ->
                        val second = cast.bob.client.readAll(cast.room)
                        assertEquals(HttpStatusCode.TooManyRequests, second.status)
                        assertEquals("1", second.headers["Retry-After"])
                        assertEquals("urn:adder:problem:read-busy", second.body<ProblemDetail>().type)
                        val line = capture.awaitEvent { it.message == "read.busy" && it.hasKeyValue("resource", "read-all") }
                        assertNotNull(line)
                        assertTrue(line.hasKeyValue("suppressed", 0L), "the first refusal of the window folds none: ${line.keyValuePairs}")
                        // Coalesced: more refusals inside the minute are still 429, with NO further line.
                        repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, cast.bob.client.readAll(cast.room).status) }
                        assertEquals(1, capture.events.count { it.message == "read.busy" && it.hasKeyValue("resource", "read-all") })
                        // Somebody else's call is not affected by Bob's.
                        assertEquals(HttpStatusCode.NoContent, cast.alice.client.readAll(cast.room).status)
                    }
                    raw.commit()
                    assertEquals(HttpStatusCode.NoContent, first.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status, "the slot was freed")
    }

    /** The service with the REAL notice writer except for the chunk: a stub `ThreadNotices` whose chunk is [onChunk]. */
    private fun serviceWith(onChunk: suspend (after: UInt, limit: Int) -> SeenChunk): ThreadReadService {
        val real: ThreadNotices = TestThreads.noticeWriter
        val stub = object : ThreadNotices by real {
            override suspend fun roomSeenChunk(roomId: UInt, userId: UInt, at: Long, after: UInt, ceiling: UInt, limit: Int): SeenChunk =
                onChunk(after, limit)
        }
        val database = sharedDatabaseForTests()
        return ThreadReadService(database, RoomMemberService(database, TeamService(database)), stub)
    }

    @Test
    fun `a chunk that fails after the floor was raised propagates, frees the slot and a retry settles the rest`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-fail")
        val thread = checkNotNull(TestThreads.raw(cast.room, 1)).id
        pile(cast.room, cast.bob.id, thread, cast.alice.id, 7)
        val before = unseenIn(cast.room, cast.bob.id)
        assertTrue(before >= 7)
        val failing = serviceWith { _, _ -> throw IllegalStateException("the chunk failed") }
        withAuditCapture { capture ->
            val failure = runCatching { failing.markRoomRead(cast.room, cast.bob.id) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "the failure propagates untouched: $failure")
            val line = capture.awaitEvent { it.message == "room.read_all" && it.hasKeyValue("byUserId", cast.bob.id.toLong()) }
            assertNotNull(line)
            assertTrue(line.hasKeyValue("complete", false), "an incomplete call says so: ${line.keyValuePairs}")
            assertTrue(line.hasKeyValue("settled", 0), "with the count so far: ${line.keyValuePairs}")
        }
        assertEquals(before, unseenIn(cast.room, cast.bob.id), "no notice was settled")
        val (seen, from) = floors(cast.room, cast.bob.id)
        assertTrue(from > 0 && seen > 0, "step 1 had committed: the floor is raised")
        assertEquals(emptyList(), cast.bob.client.numbers(cast.room, "?unread=true"), "so every count reads zero already")
        // The slot is free (the next call is not the 429) and a plain retry settles the rest.
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status)
        assertEquals(0, unseenIn(cast.room, cast.bob.id))
    }

    @Test
    fun `a failure inside the floor transaction rolls the floor back and writes no line`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-tx1")
        val before = floors(cast.room, cast.bob.id)
        val real: ThreadNotices = TestThreads.noticeWriter
        val stub = object : ThreadNotices by real {
            override suspend fun roomUnseenCeiling(roomId: UInt, userId: UInt): UInt? = error("the ceiling failed")
        }
        val database = sharedDatabaseForTests()
        val failing = ThreadReadService(database, RoomMemberService(database, TeamService(database)), stub)
        withAuditCapture { capture ->
            val failure = runCatching { failing.markRoomRead(cast.room, cast.bob.id) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "the failure propagates untouched: $failure")
            assertTrue(capture.events.none { it.message == "room.read_all" }, "the floor was never raised, so there is nothing to trace")
        }
        assertEquals(before, floors(cast.room, cast.bob.id), "the floor update rolled back with its transaction")
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status, "the slot was freed")
    }

    @Test
    fun `a call cancelled after the floor was raised still writes its incomplete line`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-cancel")
        val thread = checkNotNull(TestThreads.raw(cast.room, 1)).id
        pile(cast.room, cast.bob.id, thread, cast.alice.id, 3)
        val cancelled = serviceWith { _, _ -> throw CancellationException("the client went away") }
        withAuditCapture { capture ->
            val failure = runCatching { cancelled.markRoomRead(cast.room, cast.bob.id) }.exceptionOrNull()
            assertTrue(failure is CancellationException, "the cancellation propagates untouched: $failure")
            val line = capture.awaitEvent { it.message == "room.read_all" && it.hasKeyValue("byUserId", cast.bob.id.toLong()) }
            assertNotNull(line)
            assertTrue(line.hasKeyValue("complete", false), "a cancelled call says so: ${line.keyValuePairs}")
        }
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.readAll(cast.room).status, "the slot was freed")
    }

    @Test
    fun `the loop goes on while a chunk SELECTED a full one, though it MOVED fewer, and keysets each chunk`() = testApplication {
        usePostgresTestcontainer()
        val cast = populated("ra-loop")
        val thread = checkNotNull(TestThreads.raw(cast.room, 1)).id
        pile(cast.room, cast.bob.id, thread, cast.alice.id, 1)
        val calls = mutableListOf<Pair<UInt, Int>>()
        val answers = ArrayDeque(listOf(SeenChunk(5, 0, 10u), SeenChunk(5, 0, 20u), SeenChunk(3, 3, 30u)))
        val service = serviceWith { after, limit ->
            calls += after to limit
            answers.removeFirst()
        }
        val settled = service.markRoomRead(cast.room, cast.bob.id, chunk = 5)
        assertEquals(3, settled, "only what was MOVED counts")
        assertEquals(listOf(0u to 5, 10u to 5, 20u to 5), calls, "three chunks, each from the previous one's last id")
    }

    private companion object {
        /** Long enough for a later `System.currentTimeMillis()` to be strictly greater (an unread message is NEWER than the floor). */
        const val SETTLE = 5L
    }
}
