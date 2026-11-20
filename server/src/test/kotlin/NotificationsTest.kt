package ch.nokillswit

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.notifications.MAX_COUNTED_NOTICES
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.NOTICE_MENTIONED
import ch.nokillswit.threads.NOTICE_THREAD_ASSIGNED
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The notices of a member (`chat-model.md` "Notifications (V18)"): a mention and a hand-over make a row INSIDE the write that caused it,
 * ids only; the caller's list reads it with the names as they are now; "seen" is the recipient's alone; a leaver's notices, a deleted
 * message's and a dropped mention's are gone; a stored kind this build does not know is invisible. Rooms, templates and users are
 * SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class NotificationsTest {
    private val rooms = "/api/v1/rooms"
    private val api = "/api/v1/notifications"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()
    private val mintedTeams = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
        mintedTeams.forEach { TestTeams.service.delete(it) }
    }

    /** A room owned by `owner` with `ann` and `bob` as members, and thread #1 opened by `owner`; `outsider` is no member. */
    private class Cast(
        val owner: RoomActor,
        val ann: RoomActor,
        val bob: RoomActor,
        val outsider: RoomActor,
        val room: UInt,
        val thread: UInt,
    )

    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String) = seedActor("$prefix-$suffix", name = "Name-$suffix").also { mintedUsers += it.id }
        val owner = actor("own")
        val ann = actor("ann")
        val bob = actor("bob")
        val outsider = actor("out")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Not ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, ann.id)
        TestRooms.addMember(room, bob.id)
        val thread = TestThreads.seed(room, owner.id).id
        return Cast(owner, ann, bob, outsider, room, thread)
    }

    private fun token(vararg actors: RoomActor): String = actors.joinToString(" ") { "<@${it.id}>" }

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, id: UInt, body: String) {
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$id", MessageRequest(body)).status)
    }

    private suspend fun HttpClient.notices(query: String = ""): NotificationPageResponse {
        val response = get("$api$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private suspend fun HttpClient.seen(id: UInt): HttpResponse = postJson("$api/$id/seen", "")

    private suspend fun HttpClient.seenAll(): HttpResponse = postJson("$api/seen-all", "")

    private fun count(sql: String, vararg args: Long): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { index, arg -> st.setLong(index + 1, arg) }
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun rowsOf(userId: UInt, roomId: UInt): Int =
        count("SELECT count(*) FROM notifications WHERE recipient_id = ? AND room_id = ?", userId.toLong(), roomId.toLong())

    private fun rowsAbout(messageId: UInt): Int = count("SELECT count(*) FROM notifications WHERE message_id = ?", messageId.toLong())

    private fun seenAt(id: UInt): Long? = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT seen_at FROM notifications WHERE id = ?").use { st ->
            st.setInt(1, id.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1).takeUnless { rs.wasNull() } }
        }
    }

    private fun update(sql: String, text: String, id: Long) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            st.setString(1, text)
            st.setLong(2, id)
            st.executeUpdate()
        }
    }

    @Test
    fun `a role change keeps the member's notices`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-role")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        val members = "$rooms/${cast.room}/members/${cast.bob.id}"
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(members, RoomMemberRoleRequest(RoomRole.OWNER)).status)
        assertEquals(1L, cast.bob.client.notices().total, "a promotion updates the membership row: the notice stays")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(members, RoomMemberRoleRequest(RoomRole.MEMBER)).status)
        assertEquals(1L, cast.bob.client.notices().total, "and so does a demotion")
        assertEquals(1, rowsOf(cast.bob.id, cast.room))
    }

    @Test
    fun `a member added with a team snapshot is notified like any member`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-team")
        val cid = seedActor("nt-team-cid", name = "Name-cid").also { mintedUsers += it.id }
        val team = TestTeams.seed("nt-team-${UUID.randomUUID().toString().substring(0, 8)}").also { mintedTeams += it }
        TestTeams.service.addMember(team, cid.id) { }
        val added = cast.owner.client.postJson("$rooms/${cast.room}/members", RoomMembersAddRequest(teamId = team))
        assertEquals(HttpStatusCode.OK, added.status)
        val posted = cast.ann.client.post(cast.room, 1, "welcome ${token(cid)}")
        assertEquals(posted.id, cid.client.notices().items.single().messageId)
    }

    @Test
    fun `a count never reads past the cap`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-cap")
        val rows = MAX_COUNTED_NOTICES + 5
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
                    "SELECT ?, ?, 'THREAD_ASSIGNED', ?, ?, g FROM generate_series(1, ?) g",
            ).use { st ->
                st.setLong(1, cast.bob.id.toLong())
                st.setInt(2, cast.room.toInt())
                st.setLong(3, cast.ann.id.toLong())
                st.setInt(4, cast.thread.toInt())
                st.setInt(5, rows)
                assertEquals(rows, st.executeUpdate())
            }
        }
        assertEquals(rows, rowsOf(cast.bob.id, cast.room))
        val page = cast.bob.client.notices("?pageSize=100")
        assertEquals(MAX_COUNTED_NOTICES.toLong(), page.total, "the total is capped")
        assertEquals(100, page.items.size)
        assertEquals(MAX_COUNTED_NOTICES.toLong(), cast.bob.client.notices("?wasSeen=false").total, "a filtered count is capped too")
        assertEquals(0L, cast.bob.client.notices("?wasSeen=true").total)
        // The last page inside the cap is read; a page that starts at the cap or past it is empty (no OFFSET runs) and keeps the total.
        assertEquals(100, cast.bob.client.notices("?pageSize=100&page=10").items.size)
        val beyond = cast.bob.client.notices("?pageSize=100&page=11")
        assertTrue(beyond.items.isEmpty())
        assertEquals(MAX_COUNTED_NOTICES.toLong(), beyond.total)
        assertTrue(cast.bob.client.notices("?pageSize=100&page=2000000").items.isEmpty())
    }

    @Test
    fun `seen-all works in chunks and touches nothing newer than its start or anybody else's`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-chunk")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
                    "SELECT ?, ?, 'THREAD_ASSIGNED', ?, ?, g FROM generate_series(1, ?) g",
            ).use { st ->
                for ((who, n) in listOf(cast.bob.id to 5, cast.ann.id to 2)) {
                    st.setLong(1, who.toLong())
                    st.setInt(2, cast.room.toInt())
                    st.setLong(3, cast.owner.id.toLong())
                    st.setInt(4, cast.thread.toInt())
                    st.setInt(5, n)
                    assertEquals(n, st.executeUpdate())
                }
            }
        }
        assertEquals(5, TestThreads.notices.markAllSeen(cast.bob.id, System.currentTimeMillis(), chunk = 2), "three chunks: 2, 2, 1")
        assertEquals(0L, cast.bob.client.notices("?wasSeen=false").total)
        assertEquals(5L, cast.bob.client.notices("?wasSeen=true").total)
        assertEquals(2L, cast.ann.client.notices("?wasSeen=false").total, "somebody else's notices stay unseen")
        assertEquals(0, TestThreads.notices.markAllSeen(cast.bob.id, System.currentTimeMillis(), chunk = 2), "nothing left is zero")
        val annMoved = TestThreads.notices.markAllSeen(cast.ann.id, System.currentTimeMillis(), chunk = 2)
        assertEquals(2, annMoved, "an exact multiple of the chunk ends")
    }

    @Test
    fun `seen-all goes on when a row of a full chunk was seen by somebody else between the select and the update`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-race")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
                    "SELECT ?, ?, 'THREAD_ASSIGNED', ?, ?, g FROM generate_series(1, 5) g",
            ).use { st ->
                st.setLong(1, cast.bob.id.toLong())
                st.setInt(2, cast.room.toInt())
                st.setLong(3, cast.owner.id.toLong())
                st.setInt(4, cast.thread.toInt())
                assertEquals(5, st.executeUpdate())
            }
        }
        var raced = false
        val moved = TestThreads.notices.markAllSeen(cast.bob.id, System.currentTimeMillis(), chunk = 2) { selected ->
            if (!raced) {
                raced = true
                PostgresTestSupport.connect().use { c ->
                    c.prepareStatement("UPDATE notifications SET seen_at = 1 WHERE id = ?").use { st ->
                        st.setInt(1, selected.first().toInt())
                        assertEquals(1, st.executeUpdate())
                    }
                }
            }
        }
        assertEquals(4, moved, "the one somebody else saw is not ours to count, the other four are")
        assertEquals(0L, cast.bob.client.notices("?wasSeen=false").total, "a short update does not end the loop with rows left")
    }

    @Test
    fun `the persisted kind names are the ones the chat declares`() {
        assertEquals(NOTICE_MENTIONED, NotificationType.MENTIONED.name)
        assertEquals(NOTICE_THREAD_ASSIGNED, NotificationType.THREAD_ASSIGNED.name)
    }

    @Test
    fun `a mention notifies each mentioned member once and never the author`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-men")
        // The body names bob twice and the author herself, and an outsider and an unknown id: only bob is notified, once.
        val body = "hi ${token(cast.bob, cast.ann, cast.bob, cast.outsider)} <@2000000000>"
        val posted = cast.ann.client.post(cast.room, 1, body)
        val bob = cast.bob.client.notices()
        val notice = bob.items.single()
        assertEquals(NotificationType.MENTIONED, notice.type)
        assertEquals(cast.ann.id, notice.actorId)
        assertEquals("Name-ann", notice.actorName)
        assertEquals(cast.room, notice.roomId)
        assertEquals(1, notice.threadNumber)
        assertEquals(posted.id, notice.messageId)
        assertEquals(false, notice.wasSeen)
        assertEquals(1L, bob.total)
        assertEquals(0L, cast.ann.client.notices().total, "the author is shown and never notified")
        assertEquals(0L, cast.outsider.client.notices().total, "a non-member is no mention")
        assertEquals(0L, cast.owner.client.notices().total)
        assertEquals(1, rowsAbout(posted.id))
    }

    @Test
    fun `a thread's opening message notifies`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-open")
        val request = ThreadCreateRequest("Opening", "Start with ${token(cast.bob)}", mapOf("severity" to JsonPrimitive("s1")))
        val response = cast.ann.client.postJson("$rooms/${cast.room}/threads", request)
        assertEquals(HttpStatusCode.Created, response.status)
        val thread = response.body<ThreadResponse>()
        val notice = cast.bob.client.notices().items.single()
        assertEquals(thread.number, notice.threadNumber)
        assertEquals("Opening", notice.threadTitle)
        assertEquals(TestThreads.rawMessages(thread.id).single().id, notice.messageId)
    }

    @Test
    fun `an edit notifies only the newly mentioned and removes the notice of a dropped mention`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-edit")
        val message = cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}").id
        assertEquals(1L, cast.bob.client.notices().total)

        cast.ann.client.edit(cast.room, message, "hi ${token(cast.bob, cast.owner)}")
        assertEquals(1L, cast.bob.client.notices().total, "a person already mentioned is not notified again")
        assertEquals(1L, cast.owner.client.notices().total, "the newly mentioned is")

        cast.ann.client.edit(cast.room, message, "hi ${token(cast.owner)}")
        assertEquals(0L, cast.bob.client.notices().total, "a dropped mention takes its notice")
        assertEquals(0, rowsOf(cast.bob.id, cast.room))
        assertEquals(1L, cast.owner.client.notices().total)

        cast.ann.client.edit(cast.room, message, "hello ${token(cast.owner)}")
        assertEquals(1L, cast.owner.client.notices().total, "an edit that keeps the mention notifies nobody")

        cast.ann.client.edit(cast.room, message, "hi ${token(cast.bob)}")
        assertEquals(1L, cast.bob.client.notices().total, "a mention that comes back is a new notice")
    }

    @Test
    fun `a deleted message takes its notices`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-del")
        val own = cast.ann.client.post(cast.room, 1, "mine ${token(cast.bob)}").id
        val moderated = cast.ann.client.post(cast.room, 1, "theirs ${token(cast.bob, cast.owner)}").id
        assertEquals(2L, cast.bob.client.notices().total)

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.delete("$rooms/${cast.room}/messages/$own").status)
        assertEquals(0, rowsAbout(own))
        assertEquals(1L, cast.bob.client.notices().total)

        // A moderator's delete takes them too.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$moderated").status)
        assertEquals(0, rowsAbout(moderated))
        assertEquals(0L, cast.bob.client.notices().total)
        assertEquals(0L, cast.owner.client.notices().total)
    }

    @Test
    fun `a hand-over notifies the new owner, and nobody when a steward takes the thread themself`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-own")
        val handOver = "$rooms/${cast.room}/threads/1/owner"
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.bob.id)).status)
        val notice = cast.bob.client.notices().items.single()
        assertEquals(NotificationType.THREAD_ASSIGNED, notice.type)
        assertEquals(cast.owner.id, notice.actorId)
        assertNull(notice.messageId, "a hand-over has no message")
        assertEquals(1, notice.threadNumber)

        // The same owner again is no write and no notice.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.bob.id)).status)
        assertEquals(1L, cast.bob.client.notices().total)

        // A steward (the room OWNER) takes the thread themself: the move is real, nobody is told.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.owner.id)).status)
        assertEquals(0L, cast.owner.client.notices().total)
        assertEquals(1L, cast.bob.client.notices().total)

        // Handing it on again is a new notice: a hand-over has no message to make it unique.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.bob.id)).status)
        assertEquals(2L, cast.bob.client.notices().total)
    }

    @Test
    fun `the list is the caller's own, newest first, pages and filters by wasSeen`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-list")
        val first = cast.ann.client.post(cast.room, 1, "one ${token(cast.bob)}").id
        val second = cast.ann.client.post(cast.room, 1, "two ${token(cast.bob)}").id
        val third = cast.ann.client.post(cast.room, 1, "three ${token(cast.bob, cast.owner)}").id

        val all = cast.bob.client.notices()
        assertEquals(listOf(third, second, first), all.items.map { it.messageId }, "newest first by default")
        assertEquals(3L, all.total)
        assertEquals(listOf(first, second, third), cast.bob.client.notices("?sort=id").items.map { it.messageId })
        assertEquals(listOf(third, second, first), cast.bob.client.notices("?sort=-createdAt").items.map { it.messageId })
        val firstPage = cast.bob.client.notices("?pageSize=2")
        assertEquals(listOf(third, second), firstPage.items.map { it.messageId })
        assertEquals(3L, firstPage.total)
        assertEquals(listOf(first), cast.bob.client.notices("?pageSize=2&page=2").items.map { it.messageId })
        assertEquals(1L, cast.owner.client.notices().total, "somebody else's notices are not in the list")

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(all.items.first().id).status)
        assertEquals(listOf(third), cast.bob.client.notices("?wasSeen=true").items.map { it.messageId })
        assertEquals(listOf(second, first), cast.bob.client.notices("?wasSeen=false").items.map { it.messageId })
        assertEquals(2L, cast.bob.client.notices("?wasSeen=false").total)
        assertEquals(true, cast.bob.client.notices("?wasSeen=true").items.single().wasSeen)

        val malformed = listOf("?wasSeen=maybe", "?wasSeen=true&wasSeen=false", "?sort=bogus", "?sort=id,-id", "?page=0", "?pageSize=101")
        for (bad in malformed) assertEquals(HttpStatusCode.BadRequest, cast.bob.client.get("$api$bad").status, bad)
    }

    @Test
    fun `an item names the room, the thread and the actor as they are now`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-names")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        val before = cast.bob.client.notices().items.single()
        assertEquals("Name-ann", before.actorName)
        assertTrue(before.threadTitle.startsWith("Thread "), before.threadTitle)

        update("UPDATE users SET \"name\" = ? WHERE id = ?", "Ann Renamed", cast.ann.id.toLong())
        update("UPDATE rooms SET \"name\" = ? WHERE id = ?", "Room Renamed ${cast.room}", cast.room.toLong())
        update("UPDATE threads SET title = ? WHERE id = ?", "Title Renamed", cast.thread.toLong())

        val after = cast.bob.client.notices().items.single()
        assertEquals("Ann Renamed", after.actorName)
        assertEquals("Room Renamed ${cast.room}", after.roomName)
        assertEquals("Title Renamed", after.threadTitle)
        assertEquals(before.id, after.id)
    }

    @Test
    fun `a removed member's notices are gone, for good`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-leave")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        cast.ann.client.post(cast.room, 1, "again ${token(cast.bob)}")
        assertEquals(2, rowsOf(cast.bob.id, cast.room))

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status)
        assertEquals(0, rowsOf(cast.bob.id, cast.room), "the rows went with the membership")
        assertEquals(0L, cast.bob.client.notices().total)

        TestRooms.addMember(cast.room, cast.bob.id)
        assertEquals(0L, cast.bob.client.notices().total, "a person who rejoins starts clean")
        assertEquals(0, rowsOf(cast.bob.id, cast.room))
    }

    @Test
    fun `a deleted room's notices are hidden and an archived room's are listed`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-room")
        val admin = seedActor("nt-room-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")

        TestRooms.archive(cast.room)
        assertEquals(1L, cast.bob.client.notices().total, "an archived room's notice is listed (its link opens it read-only)")

        assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/${cast.room}").status)
        assertEquals(0L, cast.bob.client.notices().total, "a deleted room's notices are hidden")
        assertEquals(1, rowsOf(cast.bob.id, cast.room), "hidden, not deleted: the room is soft-deleted")
    }

    @Test
    fun `an administrator is a recipient only as a member of the room`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-adm")
        val admin = seedActor("nt-adm-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        cast.ann.client.post(cast.room, 1, "hi ${token(admin)}")
        assertEquals(0L, admin.client.notices().total, "an ADMIN outside the room is no member and no recipient")
        assertEquals(0, rowsOf(admin.id, cast.room))

        TestRooms.addMember(cast.room, admin.id)
        cast.ann.client.post(cast.room, 1, "hi again ${token(admin)}")
        assertEquals(1L, admin.client.notices().total, "as a member an ADMIN is notified like anybody")
    }

    @Test
    fun `marking seen is the recipient's alone - another person's id is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-seen")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        val id = cast.bob.client.notices().items.single().id

        assertEquals(HttpStatusCode.NotFound, cast.ann.client.seen(id).status, "somebody else's notice")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.seen(id).status)
        assertEquals(HttpStatusCode.NotFound, cast.bob.client.seen(2_000_000_000u).status, "an unknown notice answers the same")
        assertNull(seenAt(id), "nothing moved")
        assertEquals(false, cast.bob.client.notices().items.single().wasSeen)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(id).status)
        val stamp = assertNotNull(seenAt(id))
        assertEquals(true, cast.bob.client.notices().items.single().wasSeen)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(id).status, "seen again is still 204")
        assertEquals(stamp, seenAt(id), "and changes nothing")
    }

    @Test
    fun `seen-all marks every unseen notice of the caller and nobody else's`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-all")
        cast.ann.client.post(cast.room, 1, "one ${token(cast.bob, cast.owner)}")
        cast.ann.client.post(cast.room, 1, "two ${token(cast.bob)}")
        assertEquals(2L, cast.bob.client.notices("?wasSeen=false").total)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seenAll().status)
        assertEquals(0L, cast.bob.client.notices("?wasSeen=false").total)
        assertEquals(2L, cast.bob.client.notices("?wasSeen=true").total)
        assertEquals(1L, cast.owner.client.notices("?wasSeen=false").total, "somebody else's notice stays unseen")
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seenAll().status, "nothing unseen is still 204")
        assertEquals(HttpStatusCode.NoContent, cast.outsider.client.seenAll().status, "no notices at all is 204 too")
    }

    @Test
    fun `a stored kind this build does not know is invisible, never fatal`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-kind")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
                    "VALUES (?, ?, 'FROM_THE_FUTURE', ?, ?, ?)",
            ).use { st ->
                st.setLong(1, cast.bob.id.toLong())
                st.setInt(2, cast.room.toInt())
                st.setLong(3, cast.ann.id.toLong())
                st.setInt(4, cast.thread.toInt())
                st.setLong(5, System.currentTimeMillis())
                assertEquals(1, st.executeUpdate())
            }
        }
        assertEquals(2, rowsOf(cast.bob.id, cast.room))
        val page = cast.bob.client.notices()
        assertEquals(1L, page.total, "the unknown kind is neither listed nor counted")
        assertEquals(NotificationType.MENTIONED, page.items.single().type)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seenAll().status)
    }

    @Test
    fun `reading a thread marks its notices seen up to that message`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-read")
        val handOver = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.bob.id))
        assertEquals(HttpStatusCode.NoContent, handOver.status)
        val first = cast.ann.client.post(cast.room, 1, "one ${token(cast.bob)}").id
        val second = cast.ann.client.post(cast.room, 1, "two ${token(cast.bob)}").id
        cast.ann.client.post(cast.room, 1, "for the owner ${token(cast.owner)}")
        suspendTransaction(sharedDatabaseForTests()) { TestThreads.notices.threadRead(cast.thread, cast.bob.id, first, 5L) }

        val unseen = cast.bob.client.notices("?wasSeen=false").items
        assertEquals(listOf(second), unseen.map { it.messageId }, "the hand-over and the first mention are seen, the second is not")
        assertEquals(2L, cast.bob.client.notices("?wasSeen=true").total)
        assertEquals(1L, cast.owner.client.notices("?wasSeen=false").total, "somebody else's notice stays unseen")
    }

    @Test
    fun `the notice of a mention is audited with ids only, after the commit`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-audit")
        withAuditCapture { capture ->
            val body = "secret-body-${UUID.randomUUID()} ${token(cast.bob)}"
            val posted = cast.ann.client.post(cast.room, 1, body)
            val event = assertNotNull(
                capture.awaitEvent { it.message == "notification.created" && it.hasKeyValue("messageId", posted.id.toLong()) },
            )
            assertTrue(event.hasKeyValue("byUserId", cast.ann.id.toLong()))
            assertTrue(event.hasKeyValue("roomId", cast.room.toLong()))
            assertTrue(event.hasKeyValue("type", "MENTIONED"))
            assertTrue(event.hasKeyValue("recipientIds", listOf(cast.bob.id.toLong())))
            assertTrue(event.keyValuePairs.none { it.value.toString().contains("secret-body") }, "a body is never in an audit line")

            val move = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.bob.id))
            assertEquals(HttpStatusCode.NoContent, move.status)
            val handOver = assertNotNull(
                capture.awaitEvent { it.message == "notification.created" && it.hasKeyValue("type", "THREAD_ASSIGNED") },
            )
            assertTrue(handOver.hasKeyValue("messageId", null))
            assertTrue(handOver.hasKeyValue("recipientIds", listOf(cast.bob.id.toLong())))
            assertTrue(handOver.hasKeyValue("byUserId", cast.owner.id.toLong()))

            // A post that mentions nobody leaves no line.
            val before = capture.events.count { it.message == "notification.created" }
            cast.ann.client.post(cast.room, 1, "no mention here")
            assertEquals(before, capture.events.count { it.message == "notification.created" })
        }
    }

    @Test
    fun `the 241st read-state write in a minute is 429 and audited`() = testApplication {
        usePostgresTestcontainer()
        val limited = seedActor("nt-bucket").also { mintedUsers += it.id }
        withAuditCapture { capture ->
            repeat(240) { n -> assertEquals(HttpStatusCode.NoContent, limited.client.seenAll().status, "write ${n + 1}") }
            val rejected = limited.client.seenAll()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertNotNull(rejected.headers["Retry-After"])
            val event = assertNotNull(
                capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "read-state") },
            )
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            // The other "seen" write rides the same bucket, before its guards: an unknown id costs a token too, and is 429 now.
            assertEquals(HttpStatusCode.TooManyRequests, limited.client.seen(2_000_000_000u).status)
            assertEquals(HttpStatusCode.OK, limited.client.get(api).status, "the list is not bucketed")
        }
    }
}
