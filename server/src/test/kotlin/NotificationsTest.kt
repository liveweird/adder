package ch.nokillswit

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.notifications.MAX_COUNTED_NOTICES
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationFeedItem
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.THREAD_NOTIFICATION_TYPES
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.WatchRequest
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
import kotlin.test.assertFailsWith
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

    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    /**
     * A room owned by `owner` with `ann` and `bob` as members, and thread #1 opened by `owner`; `outsider` is no member. The notices
     * seeding the thread made (the room is told of a new thread, 1.1 D4) and the creator's watch are cleared: these tests count what
     * THEY make.
     */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): PairCast =
        seedPairCast(prefix, "Not", minted, withThread = true).also { TestNotices.quietRoom(it.room) }

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

    private suspend fun HttpClient.feed(query: String = ""): PageResponse<NotificationFeedItem> {
        val response = get("$api/feed$query")
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
        assertEquals(MAX_COUNTED_NOTICES.toLong(), cast.bob.client.notices("?seen=false").total, "a filtered count is capped too")
        assertEquals(0L, cast.bob.client.notices("?seen=true").total)
        // The last page inside the cap is read; a page that starts at the cap or past it is empty (no OFFSET runs) and keeps the total.
        assertEquals(100, cast.bob.client.notices("?pageSize=100&page=10").items.size)
        val beyond = cast.bob.client.notices("?pageSize=100&page=11")
        assertTrue(beyond.items.isEmpty())
        assertEquals(MAX_COUNTED_NOTICES.toLong(), beyond.total)
        assertTrue(cast.bob.client.notices("?pageSize=100&page=2000000").items.isEmpty())
    }

    @Test
    fun `grouped feed collapses replies before pagination and its ceiling preserves a newer reply`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-group")
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.putJson("$rooms/${cast.room}/threads/1/watch", WatchRequest(true)).status,
        )
        assertEquals(
            0L,
            cast.owner.client.feed("?seen=false&groupReplies=true&type=THREAD_REPLIED").total,
            "an empty grouped partition returns before its page read",
        )
        val first = cast.ann.client.post(cast.room, 1, "first")
        cast.ann.client.post(cast.room, 1, "second")
        cast.ann.client.post(cast.room, 1, "third")

        assertEquals(3L, cast.owner.client.feed("?seen=false").total, "legacy feed remains raw")
        val snapshot = cast.owner.client.feed("?seen=false&groupReplies=true&pageSize=1")
        assertEquals(1L, snapshot.total, "the displayed group is counted before pagination")
        val group = snapshot.items.single()
        assertEquals(NotificationType.THREAD_REPLIED, group.type)
        assertEquals(3, group.replyCount)
        assertEquals(first.id, group.messageId, "the group opens its oldest included reply")
        val floor = assertNotNull(group.replyFloorId)
        val ceiling = assertNotNull(group.replyCeilingId)

        val newer = cast.ann.client.post(cast.room, 1, "new after the drawer snapshot")
        val marked = cast.owner.client.postJson(
            "$api/reply-groups/${cast.room}/1/seen",
            ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
        )
        assertEquals(HttpStatusCode.NoContent, marked.status)
        val remaining = cast.owner.client.feed("?seen=false&groupReplies=true").items.single()
        assertEquals(1, remaining.replyCount)
        assertEquals(newer.id, remaining.messageId, "a reply newer than the snapshot stays unseen")
        val seenHistory = cast.owner.client.feed("?seen=true&groupReplies=true")
        assertEquals(3L, seenHistory.total, "seen history bypasses grouping")
        assertTrue(seenHistory.items.all { it.replyCount == null && it.replyFloorId == null && it.replyCeilingId == null })

        cast.ann.client.post(cast.room, 1, "addressed ${token(cast.owner)}")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                """UPDATE notifications
                   SET created_at = CASE WHEN notification_type = 'MENTIONED' THEN 2000000 ELSE 1000000 END
                   WHERE recipient_id = ? AND room_id = ? AND seen_at IS NULL""".trimIndent(),
            ).use { st ->
                st.setLong(1, cast.owner.id.toLong())
                st.setInt(2, cast.room.toInt())
                assertEquals(2, st.executeUpdate())
            }
        }
        val addressed = cast.owner.client.feed("?seen=false&groupReplies=true&type=MENTIONED")
        assertEquals(1L, addressed.total, "a type set without replies bypasses grouping")
        assertEquals(NotificationType.MENTIONED, addressed.items.single().type)
        val byCreated = cast.owner.client.feed("?seen=false&groupReplies=true&sort=createdAt")
        assertEquals(
            listOf(NotificationType.THREAD_REPLIED, NotificationType.MENTIONED),
            byCreated.items.map { it.type },
            "the grouped projection applies ascending createdAt order across ordinary rows and reply groups",
        )
        val beyond = cast.owner.client.feed("?seen=false&groupReplies=true&pageSize=100&page=11")
        assertTrue(beyond.items.isEmpty(), "a grouped page starting at the cap returns before its page read")
        assertEquals(2L, beyond.total, "the pre-pagination displayed total is retained")

        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
            ).status,
            "clearing an already-cleared snapshot is idempotent",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/0/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
            ).status,
            "zero is outside the room id contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${UInt.MAX_VALUE}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
            ).status,
            "a room id above int32 is rejected before conversion",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/0/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
            ).status,
            "zero is outside the thread number contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/${UInt.MAX_VALUE}/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, ceiling),
            ).status,
            "a path value outside the documented int32 range is rejected before conversion",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(0u, ceiling),
            ).status,
            "zero is outside the snapshot floor contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, UInt.MAX_VALUE),
            ).status,
            "the snapshot ceiling also stays inside the int32 wire contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(floor, 0u),
            ).status,
            "zero is outside the snapshot ceiling contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(UInt.MAX_VALUE, ceiling),
            ).status,
            "the snapshot floor also stays inside the int32 wire contract",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(ceiling, floor),
            ).status,
            "an inverted snapshot range is rejected",
        )
        assertFailsWith<IllegalArgumentException> {
            TestThreads.notices.markReplyGroupSeen(cast.owner.id, cast.room, 1, 0u, ceiling, System.currentTimeMillis())
        }
        assertFailsWith<IllegalArgumentException> {
            TestThreads.notices.markReplyGroupSeen(cast.owner.id, cast.room, 1, ceiling, floor, System.currentTimeMillis())
        }
    }

    @Test
    fun `grouped feed defers replies beyond its raw window and group seen keysets across chunks`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-group-window")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                """WITH made AS (
                       INSERT INTO messages (thread_id, author_id, body, created_at)
                       SELECT ?, ?, 'bulk reply ' || g, 1000000 + g FROM generate_series(1, 5001) g
                       RETURNING id, created_at
                   )
                   INSERT INTO notifications
                       (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at, in_app)
                   SELECT ?, ?, 'THREAD_REPLIED', ?, ?, id, created_at, TRUE FROM made""".trimIndent(),
            ).use { st ->
                st.setInt(1, cast.thread.toInt())
                st.setLong(2, cast.ann.id.toLong())
                st.setLong(3, cast.owner.id.toLong())
                st.setInt(4, cast.room.toInt())
                st.setLong(5, cast.ann.id.toLong())
                st.setInt(6, cast.thread.toInt())
                assertEquals(5001, st.executeUpdate())
            }
        }
        val bounds = PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                """SELECT min(id), max(id) FROM notifications
                   WHERE recipient_id = ? AND room_id = ? AND thread_id = ?
                     AND notification_type = 'THREAD_REPLIED' AND seen_at IS NULL""".trimIndent(),
            ).use { st ->
                st.setLong(1, cast.owner.id.toLong())
                st.setInt(2, cast.room.toInt())
                st.setInt(3, cast.thread.toInt())
                st.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    rs.getInt(1).toUInt() to rs.getInt(2).toUInt()
                }
            }
        }

        val recent = cast.owner.client.feed("?seen=false&groupReplies=true").items.single()
        assertEquals(5000, recent.replyCount)
        assertEquals(bounds.first + 1u, recent.replyFloorId, "the oldest raw reply is outside the newest-5000 window")
        assertEquals(bounds.second, recent.replyCeilingId)
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.postJson(
                "$api/reply-groups/${cast.room}/1/seen",
                ch.nokillswit.notifications.NotificationReplyGroupSeenRequest(
                    assertNotNull(recent.replyFloorId),
                    assertNotNull(recent.replyCeilingId),
                ),
            ).status,
        )

        val deferred = cast.owner.client.feed("?seen=false&groupReplies=true").items.single()
        assertEquals(1, deferred.replyCount)
        assertEquals(bounds.first, deferred.replyFloorId)
        assertEquals(bounds.first, deferred.replyCeilingId)
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE id = ? AND seen_at IS NULL", bounds.first.toLong()))
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
        assertEquals(0L, cast.bob.client.notices("?seen=false").total)
        assertEquals(5L, cast.bob.client.notices("?seen=true").total)
        assertEquals(2L, cast.ann.client.notices("?seen=false").total, "somebody else's notices stay unseen")
        assertEquals(0, TestThreads.notices.markAllSeen(cast.bob.id, System.currentTimeMillis(), chunk = 2), "nothing left is zero")
        val annMoved = TestThreads.notices.markAllSeen(cast.ann.id, System.currentTimeMillis(), chunk = 2)
        assertEquals(2, annMoved, "an exact multiple of the chunk ends")
    }

    @Test
    fun `seen-all locks a chunk's rows as it selects them, so nobody can see or delete them before its update`() = testApplication {
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
        val refused = mutableListOf<String?>()
        val moved = TestThreads.notices.markAllSeen(cast.bob.id, System.currentTimeMillis(), chunk = 2) { selected ->
            // Somebody else tries to see the first row of the chunk in the gap: its row is locked, so the attempt cannot even start.
            PostgresTestSupport.connect().use { c ->
                c.createStatement().use { it.execute("SET lock_timeout = '150ms'") }
                try {
                    c.prepareStatement("UPDATE notifications SET seen_at = 1 WHERE id = ?").use { st ->
                        st.setInt(1, selected.first().toInt())
                        st.executeUpdate()
                    }
                    refused.add(null)
                } catch (e: java.sql.SQLException) {
                    refused.add(e.sqlState)
                }
            }
        }
        assertEquals(List<String?>(3) { "55P03" }, refused.toList(), "three chunks, each locked against a second writer")
        assertEquals(5, moved, "nothing was lost to a gap")
        assertEquals(0L, cast.bob.client.notices("?seen=false").total)
    }

    @Test
    fun `the persisted kind names are the reasons the chat declares, in the same order`() {
        assertEquals(NoticeReason.entries.map { it.name }, THREAD_NOTIFICATION_TYPES.map { it.name })
        assertEquals(13, NotificationType.entries.size)
        assertEquals("MESSAGE_QUOTED", NotificationType.entries[1].name, "right after MENTIONED: the order is the priority")
    }

    @Test
    fun `every kind is on in the app and off by email by default`() {
        for (type in NotificationType.entries) {
            assertTrue(type.defaultOn(NotificationChannel.IN_APP), "$type is on in the app")
            assertEquals(false, type.defaultOn(NotificationChannel.EMAIL), "$type is off by email, the two 1.0 kinds included")
        }
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
        assertEquals(false, notice.seen)
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

        // A steward (the room OWNER) takes the thread themself: the move is real, they are told nothing and "assigned" to nobody; bob,
        // who watches it since it was handed to him, hears that the owner changed (1.1 D3).
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.owner.id)).status)
        assertEquals(0L, cast.owner.client.notices().total)
        assertEquals(
            listOf(NotificationType.THREAD_OWNER_CHANGED, NotificationType.THREAD_ASSIGNED),
            cast.bob.client.notices().items.map { it.type },
        )

        // Handing it on again while the notice is unseen REFRESHES it (a hand-over has no message to make it unique): still one row of the
        // kind, now the newest. Once seen, the next hand-over is a new notice.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.bob.id)).status)
        assertEquals(2L, cast.bob.client.notices().total)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seenAll().status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.owner.id)).status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(handOver, ThreadOwnerRequest(cast.bob.id)).status)
        assertEquals(4L, cast.bob.client.notices().total)
    }

    @Test
    fun `the list is the caller's own, newest first, pages and filters by seen`() = testApplication {
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
        assertEquals(listOf(third), cast.bob.client.notices("?seen=true").items.map { it.messageId })
        assertEquals(listOf(second, first), cast.bob.client.notices("?seen=false").items.map { it.messageId })
        assertEquals(2L, cast.bob.client.notices("?seen=false").total)
        assertEquals(true, cast.bob.client.notices("?seen=true").items.single().seen)

        val malformed = listOf("?seen=maybe", "?seen=true&seen=false", "?sort=bogus", "?sort=id,-id", "?page=0", "?pageSize=101")
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
        assertEquals(false, cast.bob.client.notices().items.single().seen)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(id).status)
        val stamp = assertNotNull(seenAt(id))
        assertEquals(true, cast.bob.client.notices().items.single().seen)
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(id).status, "seen again is still 204")
        assertEquals(stamp, seenAt(id), "and changes nothing")
    }

    @Test
    fun `seen-all marks every unseen notice of the caller and nobody else's`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-all")
        cast.ann.client.post(cast.room, 1, "one ${token(cast.bob, cast.owner)}")
        cast.ann.client.post(cast.room, 1, "two ${token(cast.bob)}")
        assertEquals(2L, cast.bob.client.notices("?seen=false").total)

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seenAll().status)
        assertEquals(0L, cast.bob.client.notices("?seen=false").total)
        assertEquals(2L, cast.bob.client.notices("?seen=true").total)
        assertEquals(1L, cast.owner.client.notices("?seen=false").total, "somebody else's notice stays unseen")
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
        // Bob watches the thread since it was handed to him: a reply that mentions somebody else is a reply notice for him.
        val third = cast.ann.client.post(cast.room, 1, "for the owner ${token(cast.owner)}").id
        suspendTransaction(sharedDatabaseForTests()) { TestThreads.noticeWriter.threadRead(cast.thread, cast.bob.id, first, 5L) }

        val unseen = cast.bob.client.notices("?seen=false").items
        assertEquals(listOf(third, second), unseen.map { it.messageId }, "the hand-over and the first mention are seen, the rest are not")
        assertEquals(2L, cast.bob.client.notices("?seen=true").total)
        assertEquals(1L, cast.owner.client.notices("?seen=false").total, "somebody else's notice stays unseen")
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
            assertTrue(event.hasKeyValue("recipients", 1))
            assertTrue(event.hasKeyValue("kinds", "MENTIONED:1"))
            assertTrue(event.hasKeyValue("recipientIds", listOf(cast.bob.id.toLong())))
            assertTrue(event.keyValuePairs.none { it.value.toString().contains("secret-body") }, "a body is never in an audit line")

            // A post that mentions nobody leaves no line (nobody watches the thread yet).
            val before = capture.events.count { it.message == "notification.created" }
            cast.ann.client.post(cast.room, 1, "no mention here")
            assertEquals(before, capture.events.count { it.message == "notification.created" })

            val move = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.bob.id))
            assertEquals(HttpStatusCode.NoContent, move.status)
            val handOver = assertNotNull(
                capture.awaitEvent { it.message == "notification.created" && it.hasKeyValue("kinds", "THREAD_ASSIGNED:1") },
            )
            assertTrue(handOver.hasKeyValue("messageId", null))
            assertTrue(handOver.hasKeyValue("recipientIds", listOf(cast.bob.id.toLong())))
            assertTrue(handOver.hasKeyValue("byUserId", cast.owner.id.toLong()))
        }
    }

    private class StoredNotice(val inApp: Boolean, val emailedAt: Long?, val createdAt: Long, val seenAt: Long?)

    private fun stored(userId: UInt, room: UInt): List<StoredNotice> = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT in_app, emailed_at, created_at, seen_at FROM notifications WHERE recipient_id = ? AND room_id = ? ORDER BY id"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, userId.toLong())
            st.setLong(2, room.toLong())
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) return@generateSequence null
                    StoredNotice(
                        rs.getBoolean(1),
                        rs.getLong(2).takeUnless { rs.wasNull() },
                        rs.getLong(3),
                        rs.getLong(4).takeUnless { rs.wasNull() },
                    )
                }.toList()
            }
        }
    }

    @Test
    fun `the channels are judged when the notice is made - email off settles it, in-app off hides it, both off makes none`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("nt-chan")
            val mention = NotificationType.MENTIONED

            // Defaults (the app on, email off): the row shows and is settled, `emailed_at = created_at`, so the digest never scans it.
            cast.ann.client.post(cast.room, 1, "default ${token(cast.bob)}")
            val byDefault = stored(cast.bob.id, cast.room).single()
            assertTrue(byDefault.inApp)
            assertEquals(byDefault.createdAt, byDefault.emailedAt, "email off: settled at its own instant")
            assertEquals(1L, cast.bob.client.notices("?seen=false").total)

            // Email opted in: eligible for the digest (emailed_at stays null).
            TestNotificationPreferences.set(cast.bob.id, mention, NotificationChannel.EMAIL, enabled = true)
            cast.ann.client.post(cast.room, 1, "mailed ${token(cast.bob)}")
            assertNull(stored(cast.bob.id, cast.room).last().emailedAt, "email on: the digest may claim it")

            // The app off and email on: a mail-only row. Never listed, counted or cleared by the bell, never hinted.
            TestNotificationPreferences.set(cast.bob.id, mention, NotificationChannel.IN_APP, enabled = false)
            NotifyCapture().use { capture ->
                val hints = capture.during { cast.ann.client.post(cast.room, 1, "mail only ${token(cast.bob)}") }
                assertEquals(listOf(LiveKind.MESSAGE_POSTED), hints.map { it.kind }, "no notice hint for a notice the bell never shows")
            }
            val mailOnly = stored(cast.bob.id, cast.room).last()
            assertEquals(false, mailOnly.inApp)
            assertNull(mailOnly.emailedAt)
            val listed = cast.bob.client.notices()
            assertEquals(2L, listed.total, "the two in-app notices only")
            assertEquals(2L, cast.bob.client.notices("?seen=false").total)
            assertEquals(2, TestThreads.notices.markAllSeen(cast.bob.id, 7L), "seen-all leaves the mail-only row to the digest")
            assertNull(stored(cast.bob.id, cast.room).last().seenAt)

            // Both off: no row at all, and no hint.
            TestNotificationPreferences.set(cast.bob.id, mention, NotificationChannel.EMAIL, enabled = false)
            NotifyCapture().use { capture ->
                val hints = capture.during { cast.ann.client.post(cast.room, 1, "silent ${token(cast.bob)}") }
                assertEquals(listOf(LiveKind.MESSAGE_POSTED), hints.map { it.kind })
            }
            assertEquals(3, stored(cast.bob.id, cast.room).size, "no fourth row")
            // The switch covers notices made from then on: switching the app back on does not resurrect the missing one.
            TestNotificationPreferences.set(cast.bob.id, mention, NotificationChannel.IN_APP, enabled = true)
            assertEquals(3, stored(cast.bob.id, cast.room).size)
        }

    @Test
    fun `a mail-only notice can be marked seen by its recipient, and is the recipient's alone`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-mailonly")
        TestNotificationPreferences.set(cast.bob.id, NotificationType.MENTIONED, NotificationChannel.EMAIL, enabled = true)
        TestNotificationPreferences.set(cast.bob.id, NotificationType.MENTIONED, NotificationChannel.IN_APP, enabled = false)
        cast.ann.client.post(cast.room, 1, "mail only ${token(cast.bob)}")
        val id = PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT id FROM notifications WHERE recipient_id = ? AND NOT in_app").use { st ->
                st.setLong(1, cast.bob.id.toLong())
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1).toUInt() }
            }
        }
        assertEquals(0L, cast.bob.client.notices().total, "the bell never lists it")

        assertEquals(HttpStatusCode.NotFound, cast.ann.client.seen(id).status, "somebody else's id is a 404, in_app or not")
        assertNull(seenAt(id))
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.seen(id).status)
        assertTrue(seenAt(id) != null, "seen: the digest will not mail it")
    }

    @Test
    fun `the 241st notification list read in a minute is 429 on its own bucket and audited`() = testApplication {
        usePostgresTestcontainer()
        val limited = seedActor("nt-readbucket").also { mintedUsers += it.id }
        withAuditCapture { capture ->
            repeat(240) { n -> assertEquals(HttpStatusCode.OK, limited.client.get(api).status, "read ${n + 1}") }
            val rejected = limited.client.get(api)
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertNotNull(rejected.headers["Retry-After"])
            val event = assertNotNull(
                capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "notification-read") },
            )
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            assertEquals(HttpStatusCode.NoContent, limited.client.seenAll().status, "the seen writes ride read-state, not this one")
        }
    }

    @Test
    fun `the type filter is any-of over the known kinds and an unknown or repeated-beyond-bound value is 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("nt-type")
        cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob)}")
        val handOver = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.bob.id))
        assertEquals(HttpStatusCode.NoContent, handOver.status)
        assertEquals(2L, cast.bob.client.notices().total)

        assertEquals(1L, cast.bob.client.notices("?type=MENTIONED").total)
        assertEquals(1L, cast.bob.client.notices("?type=THREAD_ASSIGNED").total)
        assertEquals(2L, cast.bob.client.notices("?type=MENTIONED&type=THREAD_ASSIGNED&type=THREAD_CLOSED").total, "any-of")
        assertEquals(0L, cast.bob.client.notices("?type=THREAD_CLOSED").total, "a known kind nobody made")
        assertEquals(1L, cast.bob.client.notices("?type=MENTIONED&type=MENTIONED").total, "a repeated name collapses")
        assertEquals(2L, cast.bob.client.notices("?type=").total, "a blank value is no filter")
        assertEquals(1L, cast.bob.client.notices("?type=MENTIONED&seen=false").total, "it combines with the other filters")

        for (bad in listOf("?type=NO_SUCH_KIND", "?type=mentioned", "?type=MENTIONED&type=NO_SUCH_KIND")) {
            assertEquals(HttpStatusCode.BadRequest, cast.bob.client.get("$api$bad").status, bad)
        }
        val tooMany = THREAD_NOTIFICATION_TYPES.joinToString("&") { "type=${it.name}" } + "&type=EXTRA"
        assertEquals(HttpStatusCode.BadRequest, cast.bob.client.get("$api?$tooMany").status, "more distinct values than there are kinds")
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
            assertEquals(HttpStatusCode.OK, limited.client.get(api).status, "the list has its own bucket")
        }
    }
}
