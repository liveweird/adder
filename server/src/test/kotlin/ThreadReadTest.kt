package ch.nokillswit

import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.threads.ReadMarkerRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Read markers and unread counts (V19, `chat-model.md` "Unread (V19)"): what a member has not read of a thread, the marker that only
 * moves forward and only ever the caller's own, the baseline at joining, the cap of 100, the guard order, and the marker write that
 * takes no room lock. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them. A membership
 * and the first message after it can share a millisecond (an unread message is strictly NEWER than the floor), so the tests wait a few
 * milliseconds between the two.
 */
class ThreadReadTest {
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
        val threadId: UInt,
        /** The opening message of thread #1, by Alice. */
        val opening: UInt,
    )

    /** A room owned by Olivia, with Alice and Bob as members and thread #1 started by Alice AFTER they joined. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String, role: UserRole = UserRole.USER) =
            seedActor("$prefix-$suffix", role, suffix).also { mintedUsers += it.id }
        val owner = actor("own")
        val alice = actor("ali")
        val bob = actor("bob")
        val outsider = actor("out")
        val admin = actor("adm", UserRole.ADMIN)
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val name = "Rd ${UUID.randomUUID().toString().substring(0, 8)}"
        val room = TestRooms.seed(owner.id, template, name).also { mintedRooms += it }
        TestRooms.addMember(room, alice.id)
        TestRooms.addMember(room, bob.id)
        delay(SETTLE)
        val thread = TestThreads.seed(room, alice.id)
        return Cast(owner, alice, bob, outsider, admin, room, name, thread.id, thread.messageId)
    }

    private suspend fun HttpClient.thread(room: UInt, number: Int = 1): ThreadResponse =
        get("$rooms/$room/threads/$number").body()

    private suspend fun HttpClient.listed(room: UInt): ThreadPageResponse = get("$rooms/$room/threads").body()

    private suspend fun HttpClient.unread(room: UInt, number: Int = 1): Int = thread(room, number).unreadCount

    private suspend fun HttpClient.mark(room: UInt, number: Int, message: UInt): HttpResponse =
        putJson("$rooms/$room/threads/$number/read-marker", ReadMarkerRequest(message))

    private suspend fun HttpClient.rawMark(room: UInt, json: String): HttpResponse = request("$rooms/$room/threads/1/read-marker") {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.seen(room: UInt): HttpResponse = postJson("$rooms/$room/seen", "")

    /** The marker of [user] in thread [threadId] straight from the table (null: none). */
    private fun marker(user: UInt, threadId: UInt): Int? = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT last_read_message_id FROM thread_reads WHERE user_id = ? AND thread_id = ?").use { st ->
            st.setLong(1, user.toLong())
            st.setInt(2, threadId.toInt())
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }

    private fun markerRows(room: UInt, user: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM thread_reads WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, user.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a message from someone else is unread until the member reads it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-unread")
        assertEquals(1, cast.bob.client.unread(cast.room), "the opening message, written after Bob joined")
        assertEquals(1, cast.bob.client.listed(cast.room).items.single().unreadCount)
        TestThreads.post(cast.room, 1, cast.alice.id, "two")
        val last = TestThreads.post(cast.room, 1, cast.alice.id, "three")
        assertEquals(3, cast.bob.client.unread(cast.room))
        assertEquals(3, cast.bob.client.listed(cast.room).items.single().unreadCount)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, last).status)
        val read = cast.bob.client.thread(cast.room)
        assertEquals(0, read.unreadCount)
        assertNull(read.firstUnreadMessageId)
        assertEquals(0, cast.bob.client.listed(cast.room).items.single().unreadCount)
        assertEquals(0, cast.alice.client.unread(cast.room), "the author has read what they wrote")
    }

    @Test
    fun `reading up to a message leaves the later ones unread, and names the first of them`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-first")
        val second = TestThreads.post(cast.room, 1, cast.alice.id, "two")
        val third = TestThreads.post(cast.room, 1, cast.alice.id, "three")
        TestThreads.post(cast.room, 1, cast.alice.id, "four")
        val before = cast.bob.client.thread(cast.room)
        assertEquals(4, before.unreadCount)
        assertEquals(cast.opening, before.firstUnreadMessageId)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, second).status)
        val after = cast.bob.client.thread(cast.room)
        assertEquals(2, after.unreadCount)
        assertEquals(third, after.firstUnreadMessageId)
    }

    @Test
    fun `the marker only moves forward`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-forward")
        val second = TestThreads.post(cast.room, 1, cast.alice.id, "two")
        val third = TestThreads.post(cast.room, 1, cast.alice.id, "three")
        val aliceMarker = marker(cast.alice.id, cast.threadId)
        assertEquals(third.toInt(), aliceMarker, "a post moved its author's marker")

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, third).status)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, second).status, "an older one is no error")
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, cast.opening).status)
        assertEquals(third.toInt(), marker(cast.bob.id, cast.threadId))
        assertEquals(0, cast.bob.client.unread(cast.room))
        assertEquals(aliceMarker, marker(cast.alice.id, cast.threadId), "a write moves only the caller's own marker")
        assertNull(marker(cast.owner.id, cast.threadId), "nobody else was given one")
    }

    @Test
    fun `a poster has read their own message and everything before it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-poster")
        TestThreads.post(cast.room, 1, cast.alice.id, "from Alice")
        val own = TestThreads.post(cast.room, 1, cast.bob.id, "from Bob")
        assertEquals(own.toInt(), marker(cast.bob.id, cast.threadId))
        assertEquals(0, cast.bob.client.unread(cast.room), "Bob wrote after the others: all of it is behind him")
        assertEquals(1, cast.alice.client.unread(cast.room), "Alice has not read Bob's message")
        assertEquals(own, cast.alice.client.thread(cast.room).firstUnreadMessageId)
        assertEquals(3, cast.owner.client.unread(cast.room), "the owner never opened the thread: all three messages are unread")
    }

    @Test
    fun `a new member counts only what came after they joined`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-joined")
        TestThreads.post(cast.room, 1, cast.alice.id, "before the newcomer")
        val newcomer = seedActor("rd-joined-new").also { mintedUsers += it.id }
        delay(SETTLE)
        TestRooms.addMember(cast.room, newcomer.id)
        delay(SETTLE)
        assertEquals(0, newcomer.client.unread(cast.room), "everything already written is behind the newcomer")
        assertEquals(0, newcomer.client.listed(cast.room).items.single().unreadCount)
        val after = TestThreads.post(cast.room, 1, cast.alice.id, "after the newcomer")
        val thread = newcomer.client.thread(cast.room)
        assertEquals(1, thread.unreadCount)
        assertEquals(after, thread.firstUnreadMessageId)
    }

    @Test
    fun `a thread started after joining and never opened counts every message`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-never")
        val second = TestThreads.seed(cast.room, cast.alice.id)
        TestThreads.post(cast.room, second.number, cast.alice.id, "two")
        TestThreads.post(cast.room, second.number, cast.alice.id, "three")
        val listed = cast.bob.client.listed(cast.room).items.associate { it.number to it.unreadCount }
        assertEquals(mapOf(1 to 1, second.number to 3), listed)
        assertNull(marker(cast.bob.id, second.id))
        assertEquals(second.messageId, cast.bob.client.thread(cast.room, second.number).firstUnreadMessageId)
    }

    @Test
    fun `the count stops at 100`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-cap")
        val posted = (1..105).map { TestThreads.post(cast.room, 1, cast.alice.id, "message $it") }
        val thread = cast.bob.client.thread(cast.room)
        assertEquals(100, thread.unreadCount, "100 means 100 or more")
        assertEquals(posted[5], thread.firstUnreadMessageId, "the first of the newest 100")
        assertEquals(100, cast.bob.client.listed(cast.room).items.single().unreadCount)
    }

    @Test
    fun `a tombstone still counts`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-tomb")
        val doomed = TestThreads.post(cast.room, 1, cast.alice.id, "to be deleted")
        assertEquals(HttpStatusCode.NoContent, cast.alice.client.delete("$rooms/${cast.room}/messages/$doomed").status)
        assertEquals(2, cast.bob.client.unread(cast.room), "the opening message and the tombstone")
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, doomed).status, "a tombstone is the thread's message")
        assertEquals(0, cast.bob.client.unread(cast.room))
    }

    @Test
    fun `an administrator outside the room has no unread state and cannot set a marker`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-admin")
        TestThreads.post(cast.room, 1, cast.alice.id, "two")
        val thread = cast.admin.client.thread(cast.room)
        assertEquals(0, thread.unreadCount)
        assertNull(thread.firstUnreadMessageId)
        assertEquals(0, cast.admin.client.listed(cast.room).items.single().unreadCount)
        val listed = cast.admin.client.get("$rooms?name=${cast.name}").body<ch.nokillswit.rooms.RoomPageResponse>().items.single()
        assertEquals(false, listed.unread, "an ADMIN outside the room never sees a dot")
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.mark(cast.room, 1, cast.opening).status)
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.seen(cast.room).status)
        assertNull(marker(cast.admin.id, cast.threadId))
    }

    @Test
    fun `a message of another thread, an unknown thread and a non-member are 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-404")
        val other = TestThreads.seed(cast.room, cast.alice.id)
        assertEquals(HttpStatusCode.NotFound, cast.bob.client.mark(cast.room, 1, other.messageId).status, "another thread's message")
        assertEquals(HttpStatusCode.NotFound, cast.bob.client.mark(cast.room, 99, cast.opening).status, "no such thread")
        assertEquals(HttpStatusCode.NotFound, cast.bob.client.mark(cast.room, 1, 2_000_000_000u).status, "no such message")
        assertEquals(HttpStatusCode.NotFound, cast.bob.client.mark(2_000_000_000u, 1, cast.opening).status, "no such room")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.mark(cast.room, 1, cast.opening).status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.seen(cast.room).status)
            val denied = capture.awaitEvent { it.message == "room.access_denied" && it.hasKeyValue("byUserId", cast.outsider.id.toLong()) }
            assertNotNull(denied)
        }
        assertNull(marker(cast.bob.id, cast.threadId), "a refused write left no marker")
        assertNull(marker(cast.outsider.id, cast.threadId))
    }

    @Test
    fun `the guard order is the bucket, visibility, the member rule, then the body`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-order")
        val bad = """{"messageId":0}"""
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.rawMark(cast.room, bad).status, "visibility before the body")
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.rawMark(cast.room, bad).status, "the member rule before the body")
        for (json in listOf(bad, """{"messageId":2147483648}""", """{"messageId":-1}""", """{}""", "not json")) {
            assertEquals(HttpStatusCode.BadRequest, cast.bob.client.rawMark(cast.room, json).status, json)
        }
        assertNull(marker(cast.bob.id, cast.threadId))
    }

    @Test
    fun `leaving removes the markers and rejoining starts clean`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-leave")
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, cast.opening).status)
        assertEquals(1, markerRows(cast.room, cast.bob.id))
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status)
        assertEquals(0, markerRows(cast.room, cast.bob.id), "the membership key took the marker with the row")
        TestThreads.post(cast.room, 1, cast.alice.id, "while Bob was away")
        delay(SETTLE)
        TestRooms.addMember(cast.room, cast.bob.id)
        delay(SETTLE)
        assertEquals(0, cast.bob.client.unread(cast.room), "rejoining starts clean: nothing from before is unread")
        TestThreads.post(cast.room, 1, cast.alice.id, "after Bob came back")
        assertEquals(1, cast.bob.client.unread(cast.room))
    }

    @Test
    fun `a marker write does not wait for the room lock`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-lock")
        TestThreads.holdingRoomLock(cast.room) {
            val elapsed = measureTimeMillis {
                assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, cast.opening).status)
                assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(cast.room).status)
            }
            assertTrue(elapsed < 1500, "answered at once while the room lock is held ($elapsed ms)")
        }
        assertEquals(cast.opening.toInt(), marker(cast.bob.id, cast.threadId))
    }

    @Test
    fun `a member removed while their marker write runs gets 404, never 500`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-race")
        TestThreads.holdingRoomLock(
            cast.room,
            "DELETE FROM room_members WHERE room_id = ? AND user_id = ?",
            cast.room, cast.bob.id,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the removal is uncommitted); the write then waits on the member row, not on the room lock.
                val writing = async { cast.bob.client.mark(cast.room, 1, cast.opening) }
                awaitLockWait("room_members")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, writing.await().status)
            }
        }
        assertEquals(0, markerRows(cast.room, cast.bob.id))
    }

    @Test
    fun `a member removed while their room-seen write runs gets 404, never 500`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-seenrace")
        TestThreads.holdingRoomLock(
            cast.room,
            "DELETE FROM room_members WHERE room_id = ? AND user_id = ?",
            cast.room, cast.bob.id,
        ) { raw ->
            coroutineScope {
                // The route's guards pass (the removal is uncommitted); the update then waits on the member row and finds it gone.
                val writing = async { cast.bob.client.seen(cast.room) }
                awaitLockWait("room_members")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, writing.await().status)
            }
        }
    }

    @Test
    fun `reading a thread marks its notices seen`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-notices")
        val handOver = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.bob.id))
        assertEquals(HttpStatusCode.NoContent, handOver.status)
        val mention = TestThreads.post(cast.room, 1, cast.alice.id, "hello <@${cast.bob.id}>")
        assertEquals(2L, cast.bob.client.get("$notices?wasSeen=false").body<NotificationPageResponse>().total)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.mark(cast.room, 1, mention).status)
        assertEquals(0L, cast.bob.client.get("$notices?wasSeen=false").body<NotificationPageResponse>().total)
        assertEquals(2L, cast.bob.client.get("$notices?wasSeen=true").body<NotificationPageResponse>().total)
    }

    @Test
    fun `the read-marker and room-seen writes share the read-state bucket`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-bucket")
        repeat(240) { n -> assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(cast.room).status, "write ${n + 1}") }
        assertEquals(HttpStatusCode.TooManyRequests, cast.bob.client.mark(cast.room, 1, cast.opening).status)
        assertEquals(HttpStatusCode.TooManyRequests, cast.bob.client.seen(cast.room).status)
        assertEquals(HttpStatusCode.OK, cast.bob.client.get("$rooms/${cast.room}/threads/1").status, "reads are not bucketed")
    }

    @Test
    fun `the upgrade baseline leaves everything already written read, and what comes later unread`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rd-upgrade")
        TestThreads.post(cast.room, 1, cast.alice.id, "written before the upgrade")
        assertEquals(2, cast.bob.client.unread(cast.room), "before the upgrade Bob has the two messages unread")
        assertEquals(true, cast.bob.client.roomListed(cast.name).unread, "and the room is new for him")
        // The migration's own UPDATE, scoped to this room (the shared database holds nobody else's rows we may stamp).
        val file = checkNotNull(javaClass.getResourceAsStream("/db/migration/V19__create_read_state.sql"))
        val migration = file.use { it.readBytes().decodeToString() }
        val baseline = checkNotNull(Regex("UPDATE room_members[^;]*").find(migration)).value + " AND m.room_id = ?"
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(baseline).use { st ->
                st.setInt(1, cast.room.toInt())
                assertEquals(3, st.executeUpdate(), "every member row of the room is stamped once")
            }
        }
        // Exact, no clock involved: every member's floors are the room's own last activity, which every message so far is at or before.
        val activity = TestThreads.roomActivity(cast.room)
        assertEquals(listOf(activity to activity), memberFloors(cast.room, cast.bob.id))
        assertEquals(0, cast.bob.client.unread(cast.room), "everything that existed is read")
        assertEquals(0, cast.bob.client.listed(cast.room).items.single().unreadCount)
        assertEquals(false, cast.bob.client.roomListed(cast.name).unread)
        // Only the write below needs time to pass: a message in the very millisecond of the last one is not NEWER than the floor.
        delay(SETTLE)
        val later = TestThreads.post(cast.room, 1, cast.alice.id, "written after the upgrade")
        assertEquals(1, cast.bob.client.unread(cast.room))
        assertEquals(later, cast.bob.client.thread(cast.room).firstUnreadMessageId)
        assertEquals(true, cast.bob.client.roomListed(cast.name).unread)
    }

    private fun memberFloors(room: UInt, user: UInt): List<Pair<Long, Long>> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT seen_at, read_from FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, user.toLong())
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1) to rs.getLong(2)) } }
        }
    }

    private suspend fun HttpClient.roomListed(name: String): ch.nokillswit.rooms.RoomListItem =
        get("$rooms?name=$name").body<ch.nokillswit.rooms.RoomPageResponse>().items.single()

    private companion object {
        /** Long enough for a later `System.currentTimeMillis()` to be strictly greater (an unread message is NEWER than the floor). */
        const val SETTLE = 5L
    }
}
