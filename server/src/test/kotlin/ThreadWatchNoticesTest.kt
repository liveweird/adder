package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.infra.notify.toPayload
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * Watchers and the room are told (1.1 D3, D4, D6; `chat-model.md` "Watching, group mentions and notice kinds (1.1)"): a reply tells the
 * thread's watchers, an opening message the whole room, a status move and a hand-over the watchers; each person once with their most
 * specific reason, never the actor, never somebody who left, whose account is gone or who never was a member. The pure rules are
 * `NoticeAudienceTest`; this is the audience read inside real writes. Rooms, templates and users are SHARED suite state: every test
 * mints UNIQUE rows and removes them.
 */
class ThreadWatchNoticesTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    private val bulk = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        TestRooms.softDeleteUsers(bulk)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    /** Owner (creator, room OWNER, watching), ann and bob (members, not watching), an outsider and an ADMIN who is no member; thread #1. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Pair<PairCast, RoomActor> {
        val cast = seedPairCast(prefix, "Wn", minted, withThread = true)
        val admin = seedActor("$prefix-adm", UserRole.ADMIN, "Name-adm").also { minted.users += it.id }
        return cast to admin
    }

    private suspend fun HttpClient.inbox(): List<Pair<NotificationType, UInt?>> {
        val page: NotificationPageResponse = get("/api/v1/notifications?pageSize=100&sort=id").body()
        return page.items.map { it.type to it.messageId }
    }

    private suspend fun HttpClient.kinds(): List<NotificationType> = inbox().map { it.first }

    private suspend fun HttpClient.watch(room: UInt, watching: Boolean) =
        putJson("$rooms/$room/threads/1/watch", WatchRequest(watching)).also { assertEquals(HttpStatusCode.NoContent, it.status) }

    private suspend fun HttpClient.reply(room: UInt, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/1/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.status(room: UInt, key: String): HttpResponse =
        putJson("$rooms/$room/threads/1/status", ThreadStatusRequest(key)).also { assertEquals(HttpStatusCode.NoContent, it.status) }

    private fun rowsOf(userId: UInt, roomId: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE recipient_id = ? AND room_id = ?").use { st ->
            st.setLong(1, userId.toLong())
            st.setInt(2, roomId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a reply tells the watchers of the thread, never its author nor anybody who does not watch`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-rep")
        val seed = listOf(NotificationType.THREAD_CREATED)
        assertEquals(seed, cast.ann.client.kinds(), "the seeded thread told the room when it opened")
        assertEquals(emptyList(), cast.owner.client.kinds(), "and not its author")

        cast.ann.client.reply(cast.room, "first reply")
        assertEquals(listOf(NotificationType.THREAD_REPLIED), cast.owner.client.kinds(), "the creator watches: told")
        assertEquals(seed, cast.ann.client.kinds(), "the author of the reply is not told")
        assertEquals(seed, cast.bob.client.kinds(), "a member who does not watch is not told")

        cast.bob.client.watch(cast.room, true)
        cast.ann.client.reply(cast.room, "second reply")
        assertEquals(seed + NotificationType.THREAD_REPLIED, cast.bob.client.kinds(), "a watcher is told")
        assertEquals(2, cast.owner.client.kinds().size)

        cast.bob.client.watch(cast.room, false)
        cast.ann.client.reply(cast.room, "third reply")
        assertEquals(seed + NotificationType.THREAD_REPLIED, cast.bob.client.kinds(), "after stopping, the next reply is silent")
        assertEquals(3, cast.owner.client.kinds().size)
    }

    @Test
    fun `an opening message tells every member but its author, a mention first, and nobody outside the room`() = testApplication {
        usePostgresTestcontainer()
        val (cast, admin) = cast("wn-new")
        val body = ThreadCreateRequest("Second", "Hello <@${cast.bob.id}>", mapOf("severity" to JsonPrimitive("s1")))
        assertEquals(HttpStatusCode.Created, cast.ann.client.postJson("$rooms/${cast.room}/threads", body).status)

        assertEquals(
            listOf(NotificationType.THREAD_CREATED, NotificationType.MENTIONED),
            cast.bob.client.kinds(),
            "bob: the seeded thread's, then one notice for the new one, labelled as the mention",
        )
        assertEquals(listOf(NotificationType.THREAD_CREATED), cast.owner.client.kinds(), "the owner is a member: told")
        assertEquals(listOf(NotificationType.THREAD_CREATED), cast.ann.client.kinds(), "the author: only the seeded thread's notice")
        assertEquals(emptyList(), cast.outsider.client.kinds())
        assertEquals(0, rowsOf(admin.id, cast.room), "an ADMIN who is no member is no recipient")
        assertEquals(emptyList(), admin.client.kinds())
    }

    @Test
    fun `a status move tells the watchers by what it did and never the actor`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-stat")
        cast.ann.client.watch(cast.room, true)
        cast.bob.client.watch(cast.room, true)
        val before = cast.ann.client.kinds().size

        cast.bob.client.status(cast.room, "monitoring")
        assertEquals(NotificationType.THREAD_STATUS_CHANGED, cast.ann.client.kinds().last())
        assertEquals(NotificationType.THREAD_STATUS_CHANGED, cast.owner.client.kinds().last())
        assertEquals(before + 1, cast.ann.client.kinds().size)
        assertEquals(1, cast.bob.client.kinds().size, "the mover is not told: only the seeded thread's notice")

        cast.owner.client.status(cast.room, "done")
        assertEquals(NotificationType.THREAD_CLOSED, cast.ann.client.kinds().last())
        assertEquals(NotificationType.THREAD_CLOSED, cast.bob.client.kinds().last())
        assertEquals(listOf(NotificationType.THREAD_STATUS_CHANGED), cast.owner.client.kinds(), "the closer is not told")

        cast.owner.client.status(cast.room, "open")
        assertEquals(NotificationType.THREAD_REOPENED, cast.ann.client.kinds().last())
        // A move to the status it already has is a 204 with no write and no notice.
        val count = cast.ann.client.kinds().size
        cast.owner.client.status(cast.room, "open")
        assertEquals(count, cast.ann.client.kinds().size)
    }

    @Test
    fun `a hand-over tells the new owner assigned and the other watchers owner changed, the actor never`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-own")
        cast.bob.client.watch(cast.room, true)

        cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.ann.id)).also {
            assertEquals(HttpStatusCode.NoContent, it.status)
        }
        assertEquals(NotificationType.THREAD_ASSIGNED, cast.ann.client.kinds().last(), "the new owner (who watched nothing before)")
        assertEquals(NotificationType.THREAD_OWNER_CHANGED, cast.bob.client.kinds().last(), "another watcher")
        assertEquals(emptyList(), cast.owner.client.kinds(), "the actor: the former owner who handed it over")

        // Taking the thread oneself (the room owner is a steward): the others hear it changed; nobody is "assigned".
        cast.owner.client.putJson("$rooms/${cast.room}/threads/1/owner", ThreadOwnerRequest(cast.owner.id)).also {
            assertEquals(HttpStatusCode.NoContent, it.status)
        }
        assertEquals(NotificationType.THREAD_OWNER_CHANGED, cast.ann.client.kinds().last())
        assertEquals(
            1, cast.bob.client.kinds().count { it == NotificationType.THREAD_OWNER_CHANGED },
            "bob still has the first one unseen: the repeat refreshes that row (a hand-over has no message to make it unique)",
        )
        assertEquals(emptyList(), cast.owner.client.kinds())
    }

    @Test
    fun `a member who left, an account that was deleted and an ADMIN outside the room are never told`() = testApplication {
        usePostgresTestcontainer()
        val (cast, admin) = cast("wn-gone")
        cast.ann.client.watch(cast.room, true)
        cast.bob.client.watch(cast.room, true)
        val annRows = rowsOf(cast.ann.id, cast.room)
        val bobRows = rowsOf(cast.bob.id, cast.room)

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.ann.id}").status)
        assertEquals(0, rowsOf(cast.ann.id, cast.room), "the notices went with the membership")
        TestUsers.softDelete(cast.bob.id)

        cast.owner.client.reply(cast.room, "anybody there")
        cast.owner.client.status(cast.room, "monitoring")
        assertEquals(0, rowsOf(cast.ann.id, cast.room), "a removed member hears nothing")
        assertEquals(bobRows, rowsOf(cast.bob.id, cast.room), "a deleted account gets no new notice")
        assertEquals(0, rowsOf(admin.id, cast.room))
        assertTrue(annRows > 0)
    }

    @Test
    fun `one notice per person with the most specific reason`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-one")
        cast.bob.client.watch(cast.room, true)
        val posted = cast.ann.client.reply(cast.room, "<@${cast.owner.id}> and <@${cast.ann.id}> and <@${cast.outsider.id}>")

        val rows = PostgresTestSupport.connect().use { c ->
            val sql = "SELECT recipient_id, notification_type FROM notifications WHERE message_id = ? ORDER BY recipient_id"
            c.prepareStatement(sql).use { st ->
                st.setInt(1, posted.id.toInt())
                st.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1).toUInt() to rs.getString(2) else null }.toList()
                }
            }
        }
        assertEquals(
            listOf(cast.owner.id to "MENTIONED", cast.bob.id to "THREAD_REPLIED").sortedBy { it.first },
            rows,
            "the owner is mentioned AND watches: one MENTIONED; bob only watches; ann (the author) and the outsider (not a member) none",
        )
    }

    @Test
    fun `an edit keeps the notices that stay, replaces a less specific one and relabels or drops a mention`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-edit")
        val posted = cast.ann.client.reply(cast.room, "hi <@${cast.bob.id}>")
        val bobFirst = cast.bob.client.inbox().last()
        assertEquals(NotificationType.MENTIONED to posted.id, bobFirst)
        val ownerReply = cast.owner.client.inbox().last()
        assertEquals(NotificationType.THREAD_REPLIED to posted.id, ownerReply, "the creator watches")
        val ownerNoticeId = noticeId(cast.owner.id, posted.id)
        val bobNoticeId = noticeId(cast.bob.id, posted.id)

        // Adds the owner (a watcher with a reply notice: replaced by a fresh one) and keeps bob (his notice is untouched).
        edit(cast, posted.id, "hi <@${cast.bob.id}> <@${cast.owner.id}>")
        assertEquals(bobNoticeId, noticeId(cast.bob.id, posted.id), "a kept mention keeps its notice")
        assertEquals(NotificationType.MENTIONED, cast.owner.client.inbox().last().first)
        assertNotEquals(ownerNoticeId, noticeId(cast.owner.id, posted.id), "a less specific notice is replaced, so it is fresh")
        assertEquals(1, cast.owner.client.kinds().count { it == NotificationType.MENTIONED })
        assertEquals(0, cast.owner.client.kinds().count { it == NotificationType.THREAD_REPLIED }, "no reply notice beside it")

        // Drops both: the owner watches (falls back to replied, in place); bob does not (his notice goes).
        val ownerMention = noticeId(cast.owner.id, posted.id)
        edit(cast, posted.id, "no mentions now")
        assertEquals(ownerMention, noticeId(cast.owner.id, posted.id), "relabelled in place")
        assertEquals(NotificationType.THREAD_REPLIED, cast.owner.client.inbox().last().first)
        assertEquals(0, rowsAbout(posted.id, cast.bob.id), "a dropped mention of somebody who does not watch is deleted")

        // An edit as such makes no notice for anybody.
        val before = cast.owner.client.kinds().size
        edit(cast, posted.id, "still nothing")
        assertEquals(before, cast.owner.client.kinds().size)
    }

    @Test
    fun `the opening message falls back to the room notice when a mention is dropped`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-open")
        val body = ThreadCreateRequest("Second", "Hello <@${cast.bob.id}>", mapOf("severity" to JsonPrimitive("s1")))
        assertEquals(HttpStatusCode.Created, cast.ann.client.postJson("$rooms/${cast.room}/threads", body).status)
        val opening = TestThreads.rawMessages(TestThreads.raw(cast.room, 2)!!.id).first().id
        assertEquals(NotificationType.MENTIONED, cast.bob.client.inbox().last().first)

        val edited = cast.ann.client.putJson("$rooms/${cast.room}/messages/$opening", MessageRequest("Hello"))
        assertEquals(HttpStatusCode.NoContent, edited.status)
        assertEquals(NotificationType.THREAD_CREATED, cast.bob.client.inbox().last().first, "falls back to 'started a thread'")
        assertEquals(2, cast.bob.client.kinds().size)
    }

    @Test
    fun `a title edit, a delete, a reaction and a pin make no notice`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-quiet")
        cast.bob.client.watch(cast.room, true)
        val posted = cast.ann.client.reply(cast.room, "to be handled")
        val owner = cast.owner.client.kinds().size
        val bob = cast.bob.client.kinds().size

        val thumbsUp = "\uD83D\uDC4D"
        val details = ThreadUpdateRequest("A new title", mapOf("severity" to JsonPrimitive("s2")))
        val titled = cast.ann.client.put("$rooms/${cast.room}/threads/1") {
            header(EXPECTED_REVISION_HEADER, "1")
            contentType(ContentType.Application.Json)
            setBody(details)
        }
        assertEquals(HttpStatusCode.NoContent, titled.status)
        val message = "$rooms/${cast.room}/messages/${posted.id}"
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$message/reaction", ReactionRequest(thumbsUp, true)).status)
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$rooms/${cast.room}/threads/1/pin", PinRequest(true)).status)
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$message/pin", PinRequest(true)).status)
        assertEquals(owner, cast.owner.client.kinds().size, "a title edit, a reaction and a pin tell nobody")
        assertEquals(bob, cast.bob.client.kinds().size)

        // A delete tells nobody either; it takes the reply's own notices with it.
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.delete(message).status)
        assertEquals(owner - 1, cast.owner.client.kinds().size)
        assertEquals(bob - 1, cast.bob.client.kinds().size)
    }

    private fun noticeId(recipient: UInt, messageId: UInt): Long? = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT id FROM notifications WHERE recipient_id = ? AND message_id = ?").use { st ->
            st.setLong(1, recipient.toLong())
            st.setInt(2, messageId.toInt())
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    private fun rowsAbout(messageId: UInt, recipient: UInt): Int = if (noticeId(recipient, messageId) == null) 0 else 1

    private suspend fun edit(cast: PairCast, messageId: UInt, body: String) {
        val response = cast.ann.client.putJson("$rooms/${cast.room}/messages/$messageId", MessageRequest(body))
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `an edit tells only the newly mentioned - a kept mention whose notice is gone stays without one`() =
        testApplication {
            usePostgresTestcontainer()
            val (cast, _) = cast("wn-kept")
            val posted = cast.ann.client.reply(cast.room, "hi <@${cast.bob.id}>")
            assertEquals(1, rowsOf(cast.bob.id, cast.room) - 1, "bob: the seeded thread's notice and this mention")

            // Retention (or anything else) removed the notice; the edit keeps the mention: no new notice (1.1 A34, 0.6 A6).
            PostgresTestSupport.connect().use { c ->
                c.prepareStatement("DELETE FROM notifications WHERE recipient_id = ? AND message_id = ?").use { st ->
                    st.setLong(1, cast.bob.id.toLong())
                    st.setInt(2, posted.id.toInt())
                    st.executeUpdate()
                }
            }
            edit(cast, posted.id, "hi again <@${cast.bob.id}>")
            assertEquals(null, noticeId(cast.bob.id, posted.id))

            // Bob leaves and is added back: his old notices are gone, the mention is still on the message, and an edit that keeps it
            // does not tell him again.
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status)
            TestRooms.addMember(cast.room, cast.bob.id)
            edit(cast, posted.id, "hi once more <@${cast.bob.id}>")
            assertEquals(0, rowsOf(cast.bob.id, cast.room))
        }

    @Test
    fun `a kind switched off on both channels falls back to the next applicable reason`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-fall")
        // The owner (a watcher) switches the mention kind off in the app: a reply that mentions them is a reply notice.
        TestNotificationPreferences.set(cast.owner.id, NotificationType.MENTIONED, NotificationChannel.IN_APP, false)
        cast.ann.client.reply(cast.room, "<@${cast.owner.id}> look")
        assertEquals(listOf(NotificationType.THREAD_REPLIED), cast.owner.client.kinds())

        // On an opening message the fallback is "started a thread"; with that off too, nobody is told.
        val create = { title: String ->
            ThreadCreateRequest(title, "Hello <@${cast.owner.id}>", mapOf("severity" to JsonPrimitive("s1")))
        }
        assertEquals(HttpStatusCode.Created, cast.ann.client.postJson("$rooms/${cast.room}/threads", create("Second")).status)
        assertEquals(NotificationType.THREAD_CREATED, cast.owner.client.kinds().last())
        TestNotificationPreferences.set(cast.owner.id, NotificationType.THREAD_CREATED, NotificationChannel.IN_APP, false)
        val before = cast.owner.client.kinds().size
        assertEquals(HttpStatusCode.Created, cast.ann.client.postJson("$rooms/${cast.room}/threads", create("Third")).status)
        assertEquals(before, cast.owner.client.kinds().size, "every applicable reason is off: no row")
    }

    @Test
    fun `toggling a status ten times leaves each watcher one unseen row, a close and a reopen have their own`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-toggle")
        cast.ann.client.watch(cast.room, true)
        repeat(5) {
            cast.bob.client.status(cast.room, "monitoring")
            cast.bob.client.status(cast.room, "open")
        }
        for (watcher in listOf(cast.owner, cast.ann)) {
            assertEquals(1, watcher.client.kinds().count { it == NotificationType.THREAD_STATUS_CHANGED }, "ten moves, one unseen row")
        }
        cast.owner.client.status(cast.room, "done")
        cast.owner.client.status(cast.room, "open")
        assertEquals(
            listOf(NotificationType.THREAD_STATUS_CHANGED, NotificationType.THREAD_CLOSED, NotificationType.THREAD_REOPENED),
            cast.ann.client.kinds().drop(1),
        )
        // CLOSED to CLOSED through the route is a plain move: the watchers are told "moved", not "closed" again (their bell is cleared
        // first, so a refresh of an unseen row does not hide the kind).
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.postJson("/api/v1/notifications/seen-all", "").status)
        cast.owner.client.status(cast.room, "done")
        cast.owner.client.status(cast.room, "wontdo")
        assertEquals(
            listOf(NotificationType.THREAD_CLOSED, NotificationType.THREAD_STATUS_CHANGED),
            cast.ann.client.kinds().takeLast(2),
        )
    }

    @Test
    fun `an archived room refuses the write and makes no notice and no hint`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-arch")
        cast.bob.client.watch(cast.room, true)
        TestRooms.archive(cast.room)
        val before = listOf(cast.owner, cast.ann, cast.bob).map { rowsOf(it.id, cast.room) }
        NotifyCapture().use { capture ->
            val hints = capture.during {
                val thread = "$rooms/${cast.room}/threads/1"
                assertEquals(HttpStatusCode.Conflict, cast.ann.client.postJson("$thread/messages", MessageRequest("hi")).status)
                assertEquals(HttpStatusCode.Conflict, cast.owner.client.putJson("$thread/status", ThreadStatusRequest("monitoring")).status)
                assertEquals(HttpStatusCode.Conflict, cast.owner.client.putJson("$thread/owner", ThreadOwnerRequest(cast.ann.id)).status)
                val body = ThreadCreateRequest("No", "Nothing", mapOf("severity" to JsonPrimitive("s1")))
                assertEquals(HttpStatusCode.Conflict, cast.ann.client.postJson("$rooms/${cast.room}/threads", body).status)
            }
            assertEquals(emptyList(), hints)
        }
        assertEquals(before, listOf(cast.owner, cast.ann, cast.bob).map { rowsOf(it.id, cast.room) })
    }

    @Test
    fun `a removal racing a notifying write leaves the removed member no notice and no watch, whoever wins the lock`() = testApplication {
        usePostgresTestcontainer()
        val (cast, _) = cast("wn-race")
        cast.bob.client.watch(cast.room, true)
        kotlinx.coroutines.coroutineScope {
            val racers = mutableListOf<kotlinx.coroutines.Deferred<HttpStatusCode>>()
            TestThreads.holdingRoomLock(cast.room) { _ ->
                // Both writes queue behind the lock this connection holds; they run in either order once it is released.
                racers += async { cast.ann.client.postJson("$rooms/${cast.room}/threads/1/messages", MessageRequest("racing")).status }
                racers += async { cast.owner.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status }
                kotlinx.coroutines.delay(400)
            }
            assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.NoContent), racers.map { it.await() })
        }
        assertEquals(0, rowsOf(cast.bob.id, cast.room), "no notice survives the removal, whichever came first")
        assertEquals(0, watchRows(cast.bob.id, cast.room))
    }

    @Test
    fun `an ADMIN outside the room closes a thread all 500 members watch - one hint to 500, one notice each`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("wn-big-own").also { minted.users += it.id }
        val admin = seedActor("wn-big-adm", UserRole.ADMIN).also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val name = "Big ${java.util.UUID.randomUUID().toString().substring(0, 8)}"
        val room = TestRooms.seed(owner.id, template, name).also { minted.rooms += it }
        TestThreads.seed(room, owner.id)
        bulk += TestRooms.seedMembers(room, 499, "wn-big-${java.util.UUID.randomUUID().toString().substring(0, 8)}")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO thread_watches (thread_id, user_id, room_id, created_at) " +
                    "SELECT t.id, m.user_id, m.room_id, 1 FROM threads t JOIN room_members m ON m.room_id = t.room_id " +
                    "WHERE t.room_id = ? ON CONFLICT DO NOTHING",
            ).use { st ->
                st.setInt(1, room.toInt())
                st.executeUpdate()
            }
        }
        NotifyCapture().use { capture ->
            val hints = capture.during {
                val closed = admin.client.putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("done"))
                assertEquals(HttpStatusCode.NoContent, closed.status)
            }
            val notice = hints.single { it.kind == ch.nokillswit.infra.notify.LiveKind.NOTIFICATION_CREATED }
            assertEquals(500, notice.targetUserIds?.size, "all 500 members: the actor is no member, so nobody is left out")
            assertTrue(notice.toPayload().toByteArray().size < ch.nokillswit.infra.notify.MAX_NOTIFY_PAYLOAD_BYTES)
        }
        assertEquals(500, closedNotices(room))
    }

    private fun watchRows(userId: UInt, room: UInt): Int =
        count("SELECT count(*) FROM thread_watches WHERE user_id = ? AND room_id = ?", userId, room)

    private fun closedNotices(room: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = 'THREAD_CLOSED'").use { st ->
            st.setInt(1, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun count(sql: String, userId: UInt, room: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            st.setLong(1, userId.toLong())
            st.setInt(2, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `an edit that adds a mention to the opening message keeps the room notice of a member whose mention kind is switched off`() =
        testApplication {
            usePostgresTestcontainer()
            val (cast, _) = cast("wn-upg")
            val body = ThreadCreateRequest("Second", "Hello all", mapOf("severity" to JsonPrimitive("s1")))
            assertEquals(HttpStatusCode.Created, cast.ann.client.postJson("$rooms/${cast.room}/threads", body).status)
            val opening = TestThreads.rawMessages(TestThreads.raw(cast.room, 2)!!.id).first().id
            TestNotificationPreferences.set(cast.bob.id, NotificationType.MENTIONED, NotificationChannel.IN_APP, false)
            val held = noticeId(cast.bob.id, opening)
            assertEquals(NotificationType.THREAD_CREATED, cast.bob.client.kinds().last())

            // The edit mentions bob and the owner. Owner's mention kind is on: a fresh MENTIONED. Bob's is off: he keeps the room notice,
            // the same row, and is not charged a second time.
            edit(cast, opening, "Hello <@${cast.bob.id}> and <@${cast.owner.id}>")

            assertEquals(held, noticeId(cast.bob.id, opening), "no delete and insert: no fresh unseen row")
            assertEquals(NotificationType.THREAD_CREATED, cast.bob.client.kinds().last())
            assertEquals(NotificationType.MENTIONED, cast.owner.client.kinds().last())
        }
}
