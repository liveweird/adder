package ch.nokillswit

import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MessagePostRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.WatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The author of a quoted message is told (1.3 D2, `chat-model.md` "Quotes (1.3)" "The notice"): `MESSAGE_QUOTED`, one notice per message
 * and person, never for a self-quote, below a direct mention and above every group mention and a watched reply; an author who is no
 * longer an ACTIVE member is not told and the post still answers `201` (`fk_notifications_member` would answer a `500`); an edit never
 * adds or removes the quote's notice by itself; deleting the quoting message takes the notice, deleting the quoted one leaves it; and a
 * refused wide event still tells the quoted author. The pure rules are `NoticeAudienceTest`; this is the audience read inside real
 * writes. Rooms, templates, users and teams are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class MessageQuoteNoticesTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())
    private val bulk = mutableListOf<UInt>()
    private val teams = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        TestRooms.softDeleteUsers(bulk)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
        teams.forEach { TestTeams.service.delete(it) }
    }

    /** Owner (creator, room OWNER, watching), ann and bob (members, not watching), an outsider; thread #1. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): PairCast = seedPairCast(prefix, "Mq", minted, withThread = true)

    private suspend fun HttpClient.post(room: UInt, body: String, quoted: UInt? = null): MessageResponse {
        val response = postJson("$rooms/$room/threads/1/messages", MessagePostRequest(body, quoted))
        assertEquals(HttpStatusCode.Created, response.status, "post quoting $quoted")
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, messageId: UInt, body: String) =
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$messageId", MessageRequest(body)).status)

    private suspend fun HttpClient.kinds(): List<NotificationType> =
        get("/api/v1/notifications?pageSize=100&sort=id").body<NotificationPageResponse>().items.map { it.type }

    private fun scalar(sql: String, vararg args: Any): Long = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun kindRows(room: UInt, kind: NotificationType): Long =
        scalar("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = ?", room.toInt(), kind.name)

    private fun rowsOf(userId: UInt, room: UInt): Long =
        scalar("SELECT count(*) FROM notifications WHERE recipient_id = ? AND room_id = ?", userId.toLong(), room.toInt())

    /** How many notices [recipient] holds about [messageId]. */
    private fun heldBy(recipient: UInt, messageId: UInt): Long =
        scalar("SELECT count(*) FROM notifications WHERE message_id = ? AND recipient_id = ?", messageId.toInt(), recipient.toLong())

    /** The notice [recipient] holds about [messageId] as (id, kind), or null. */
    private fun noticeAbout(recipient: UInt, messageId: UInt): Pair<Long, NotificationType>? = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT id, notification_type FROM notifications WHERE recipient_id = ? AND message_id = ?").use { st ->
            st.setLong(1, recipient.toLong())
            st.setInt(2, messageId.toInt())
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to NotificationType.valueOf(rs.getString(2)) else null }
        }
    }

    private fun setWatching(user: RoomActor, room: UInt, watching: Boolean) = runBlocking {
        assertEquals(HttpStatusCode.NoContent, user.client.putJson("$rooms/$room/threads/1/watch", WatchRequest(watching)).status)
    }

    private fun sql(statement: String, vararg args: Any?) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(statement).use { st ->
            args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
            st.executeUpdate()
        }
    }

    private fun removeMember(room: UInt, userId: UInt) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, userId.toLong())
            assertEquals(1, st.executeUpdate())
        }
    }

    @Test
    fun `the quoted author is told once and nobody else is - a watcher keeps the reply notice, the quoter and outsiders none`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mqn-once")
            val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob's question")

            withAuditCapture { audit ->
                val posted = cast.ann.client.post(cast.room, "My answer", quoted = bobs)

                assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(cast.bob.id, posted.id)?.second)
                assertEquals(1L, heldBy(cast.bob.id, posted.id))
                assertEquals(NotificationType.THREAD_REPLIED, noticeAbout(cast.owner.id, posted.id)?.second, "the watcher keeps the reply")
                assertNull(noticeAbout(cast.ann.id, posted.id), "the quoter is never told")
                assertNull(noticeAbout(cast.outsider.id, posted.id))
                assertEquals(2L, scalar("SELECT count(*) FROM notifications WHERE message_id = ?", posted.id.toInt()))

                val line = audit.events.single { it.message == "notification.created" }
                assertTrue(line.hasKeyValue("kinds", "MESSAGE_QUOTED:1,THREAD_REPLIED:1"))
                assertTrue(line.hasKeyValue("recipientIds", listOf(cast.bob.id.toLong())), "the quoted author is named: they are addressed")
            }
            assertEquals(listOf(NotificationType.THREAD_CREATED, NotificationType.MESSAGE_QUOTED), cast.bob.client.kinds())
        }

    @Test
    fun `a quote wins over the watched reply of a watcher - the owner quoted by a reply hears the quote`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-watch")
        val opening = TestThreads.rawMessages(cast.thread).first().id

        val posted = cast.ann.client.post(cast.room, "Replying to the opener", quoted = opening)

        assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(cast.owner.id, posted.id)?.second, "the owner watches, and is quoted")
        assertEquals(1L, scalar("SELECT count(*) FROM notifications WHERE message_id = ?", posted.id.toInt()), "one notice, not two")
    }

    @Test
    fun `quoting yourself tells nobody by the quote`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-self")
        val own = cast.ann.client.post(cast.room, "My first")

        val posted = cast.ann.client.post(cast.room, "My second", quoted = own.id)

        assertEquals(0L, kindRows(cast.room, NotificationType.MESSAGE_QUOTED))
        assertNull(noticeAbout(cast.ann.id, posted.id))
        assertEquals(NotificationType.THREAD_REPLIED, noticeAbout(cast.owner.id, posted.id)?.second, "the watcher hears an ordinary reply")
    }

    @Test
    fun `a direct mention of the quoted author wins, and a quote wins over everyone, the watchers, a team and a watched reply`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mqn-prio")
            val carl = seedActor("mqn-prio-carl").also { minted.users += it.id }
            TestRooms.addMember(cast.room, carl.id)
            setWatching(cast.bob, cast.room, true)
            setWatching(carl, cast.room, true)
            val oncall = TestTeams.seedMentionable("mqn", listOf(cast.bob.id, carl.id)).also { teams += it.id }
            val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob says")
            val carls = TestThreads.post(cast.room, 1, carl.id, "Carl says")

            val mention = cast.ann.client.post(cast.room, "<@${cast.bob.id}> ${oncall.token} <@watchers> <@everyone>", quoted = bobs)
            assertEquals(NotificationType.MENTIONED, noticeAbout(cast.bob.id, mention.id)?.second, "named and quoted: the mention wins")
            assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(carl.id, mention.id)?.second, "on the team, not quoted here")

            val groups = cast.ann.client.post(cast.room, "${oncall.token} <@watchers> <@everyone>", quoted = carls)
            assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(carl.id, groups.id)?.second, "team, watcher, member, quoted")
            assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(cast.bob.id, groups.id)?.second, "bob: the team, the next reason")
            assertEquals(NotificationType.WATCHERS_MENTIONED, noticeAbout(cast.owner.id, groups.id)?.second)
            assertEquals(1L, heldBy(carl.id, groups.id))
        }

    @Test
    fun `the kind switched off on both channels falls back to the watched reply, or to no row`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-mute")
        setWatching(cast.bob, cast.room, true)
        TestNotificationPreferences.set(cast.bob.id, NotificationType.MESSAGE_QUOTED, NotificationChannel.IN_APP, false)
        TestNotificationPreferences.set(cast.ann.id, NotificationType.MESSAGE_QUOTED, NotificationChannel.IN_APP, false)
        val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob says")
        val anns = TestThreads.post(cast.room, 1, cast.ann.id, "Ann says")

        val toBob = cast.owner.client.post(cast.room, "to bob", quoted = bobs)
        assertEquals(NotificationType.THREAD_REPLIED, noticeAbout(cast.bob.id, toBob.id)?.second, "bob watches: the next reason")

        val before = rowsOf(cast.ann.id, cast.room)
        cast.owner.client.post(cast.room, "to ann", quoted = anns)
        assertEquals(before, rowsOf(cast.ann.id, cast.room), "ann does not watch: every applicable reason is off")
    }

    @Test
    fun `a quoted author who left the room, or whose account is deleted, is not told and the post still answers 201`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-gone")
        val carl = seedActor("mqn-gone-carl").also { minted.users += it.id }
        TestRooms.addMember(cast.room, carl.id)
        val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob says")
        val carls = TestThreads.post(cast.room, 1, carl.id, "Carl says")
        val carlBefore = rowsOf(carl.id, cast.room)

        removeMember(cast.room, cast.bob.id)
        TestUsers.softDelete(carl.id)

        // The status is asserted by `post` (201): a notice for a non-member would be a 500 through `fk_notifications_member`.
        val toBob = cast.ann.client.post(cast.room, "to the one who left", quoted = bobs)
        val toCarl = cast.ann.client.post(cast.room, "to the deleted account", quoted = carls)

        assertNull(noticeAbout(cast.bob.id, toBob.id))
        assertNull(noticeAbout(carl.id, toCarl.id))
        assertEquals(0L, rowsOf(cast.bob.id, cast.room), "leaving took every row of the room, and the post made none")
        assertEquals(carlBefore, rowsOf(carl.id, cast.room), "none for the deleted account either")
        assertEquals(0L, kindRows(cast.room, NotificationType.MESSAGE_QUOTED))
        assertEquals("to the one who left", cast.ann.client.get("$rooms/${cast.room}/messages/${toBob.id}").body<MessageResponse>().body)
        assertEquals(bobs, toBob.quote?.messageId, "the quote itself is still made")

        // The same on the EDIT path: adding `@everyone` makes the quoted author a candidate again, and they are still not told (the same
        // active-member filter as the post's, else the foreign key would answer a 500; `edit` asserts the 204).
        cast.ann.client.edit(cast.room, toBob.id, "to the one who left <@everyone>")
        cast.ann.client.edit(cast.room, toCarl.id, "to the deleted account <@everyone>")
        assertNull(noticeAbout(cast.bob.id, toBob.id))
        assertNull(noticeAbout(carl.id, toCarl.id))
        assertEquals(0L, rowsOf(cast.bob.id, cast.room))
        assertEquals(carlBefore, rowsOf(carl.id, cast.room))
        assertEquals(NotificationType.EVERYONE_MENTIONED, noticeAbout(cast.owner.id, toBob.id)?.second, "the edit still told the others")
    }

    @Test
    fun `an edit that adds a direct mention makes a fresh MENTIONED, dropping it relabels in place, other mentions leave the row alone`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mqn-edit")
            val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob says")
            val posted = cast.ann.client.post(cast.room, "answer", quoted = bobs)
            val quotedRow = noticeAbout(cast.bob.id, posted.id)
            assertEquals(NotificationType.MESSAGE_QUOTED, quotedRow?.second)

            // An edit that changes no mention leaves the quote's notice as it was: the same row.
            cast.ann.client.edit(cast.room, posted.id, "answer, reworded")
            assertEquals(quotedRow, noticeAbout(cast.bob.id, posted.id))

            // Other mentions: the owner is newly mentioned, bob's row is the same row.
            cast.ann.client.edit(cast.room, posted.id, "answer <@${cast.owner.id}>")
            assertEquals(quotedRow, noticeAbout(cast.bob.id, posted.id), "an edit of other mentions leaves the quote's row alone")
            assertEquals(NotificationType.MENTIONED, noticeAbout(cast.owner.id, posted.id)?.second)

            // A direct mention of the quoted author replaces the quote's notice with a fresh MENTIONED.
            cast.ann.client.edit(cast.room, posted.id, "answer <@${cast.owner.id}> <@${cast.bob.id}>")
            val mentioned = noticeAbout(cast.bob.id, posted.id)
            assertEquals(NotificationType.MENTIONED, mentioned?.second)
            assertNotEquals(quotedRow?.first, mentioned?.first, "a fresh, unseen notice")
            assertEquals(1L, heldBy(cast.bob.id, posted.id))

            // Dropping the token falls back to the quote in place: the same row, never deleted.
            cast.ann.client.edit(cast.room, posted.id, "answer <@${cast.owner.id}>")
            val relabelled = noticeAbout(cast.bob.id, posted.id)
            assertEquals(mentioned?.first, relabelled?.first, "relabelled in place")
            assertEquals(NotificationType.MESSAGE_QUOTED, relabelled?.second)
        }

    @Test
    fun `an edit that adds everyone tells the others and leaves the quote's notice, and a quote switched off is told like any member`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mqn-all")
            val carl = seedActor("mqn-all-carl").also { minted.users += it.id }
            TestRooms.addMember(cast.room, carl.id)
            TestNotificationPreferences.set(carl.id, NotificationType.MESSAGE_QUOTED, NotificationChannel.IN_APP, false)
            val bobs = TestThreads.post(cast.room, 1, cast.bob.id, "Bob says")
            val carls = TestThreads.post(cast.room, 1, carl.id, "Carl says")
            val toBob = cast.ann.client.post(cast.room, "answer", quoted = bobs)
            val toCarl = cast.ann.client.post(cast.room, "answer too", quoted = carls)
            val bobHeld = noticeAbout(cast.bob.id, toBob.id)
            assertNull(noticeAbout(carl.id, toCarl.id), "quotes are switched off for carl and he does not watch")

            cast.ann.client.edit(cast.room, toBob.id, "answer <@everyone>")
            assertEquals(bobHeld, noticeAbout(cast.bob.id, toBob.id), "holds the quote: @everyone does nothing for them")
            assertEquals(NotificationType.EVERYONE_MENTIONED, noticeAbout(carl.id, toBob.id)?.second, "another member is told")

            cast.ann.client.edit(cast.room, toCarl.id, "answer too <@everyone>")
            assertEquals(
                NotificationType.EVERYONE_MENTIONED,
                noticeAbout(carl.id, toCarl.id)?.second,
                "holding nothing, switched off: told like any member, with the first of their reasons that is on",
            )
        }

    @Test
    fun `deleting the quoting message removes the notice, deleting the quoted one keeps it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-del")
        val bobs = cast.bob.client.post(cast.room, "Bob says")
        val first = cast.ann.client.post(cast.room, "first answer", quoted = bobs.id)
        val second = cast.ann.client.post(cast.room, "second answer", quoted = bobs.id)
        assertNotNull(noticeAbout(cast.bob.id, first.id))
        assertNotNull(noticeAbout(cast.bob.id, second.id))

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.delete("$rooms/${cast.room}/messages/${first.id}").status)
        assertNull(noticeAbout(cast.bob.id, first.id), "the quoting message is gone: so is its notice")

        assertEquals(HttpStatusCode.NoContent, cast.bob.client.delete("$rooms/${cast.room}/messages/${bobs.id}").status)
        assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(cast.bob.id, second.id)?.second, "the notice is about the reply")
    }

    @Test
    fun `a refused wide event still tells the quoted author`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val owner = seedActor("mqn-wide-own").also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val room = TestRooms.seed(owner.id, template, "Wide ${UUID.randomUUID().toString().substring(0, 8)}").also { minted.rooms += it }
        TestThreads.seed(room, owner.id)
        val people = TestRooms.seedMembers(room, 25, "mqn-wide-${UUID.randomUUID().toString().substring(0, 8)}").also { bulk += it }
        val quotedAuthor = people[3]
        val theirs = TestThreads.post(room, 1, quotedAuthor, "Quoted words")

        owner.client.post(room, "<@everyone> the first, within the budget")
        val everyone = kindRows(room, NotificationType.EVERYONE_MENTIONED)
        assertEquals(25L, everyone)

        withAuditCapture { audit ->
            owner.client.post(room, "<@everyone> over the budget", quoted = theirs)
            assertEquals(everyone, kindRows(room, NotificationType.EVERYONE_MENTIONED), "the wide event is refused")
            assertEquals(1L, kindRows(room, NotificationType.MESSAGE_QUOTED), "the quoted author is named: they are still told")
            assertTrue(audit.events.any { it.message == "notification.broadcast_budget_exceeded" })
        }
    }

    @Test
    fun `an edit over a refused wide event does not tell the quoted author as if the quote were direct, and withholds them`() =
        testApplication {
            configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
            startApplication()
            val owner = seedActor("mqn-ew-own").also { minted.users += it.id }
            val template = TestThreads.seedTemplate().also { minted.templates += it }
            val room = TestRooms.seed(owner.id, template, "Ew ${UUID.randomUUID().toString().substring(0, 8)}").also { minted.rooms += it }
            TestThreads.seed(room, owner.id)
            val people = TestRooms.seedMembers(room, 25, "mqn-ew-${UUID.randomUUID().toString().substring(0, 8)}").also { bulk += it }
            val quotedAuthor = people[3]
            val theirs = TestThreads.post(room, 1, quotedAuthor, "Quoted words")
            owner.client.post(room, "<@everyone> the first, within the budget") // the one wide event of the day
            val reply = owner.client.post(room, "an answer", quoted = theirs)
            assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(quotedAuthor, reply.id)?.second)
            // The quoted author holds no notice of the reply any more (retention, say): an edit that adds everyone newly addresses them.
            PostgresTestSupport.connect().use { c ->
                c.prepareStatement("DELETE FROM notifications WHERE message_id = ? AND recipient_id = ?").use { st ->
                    st.setInt(1, reply.id.toInt())
                    st.setLong(2, quotedAuthor.toLong())
                    assertEquals(1, st.executeUpdate())
                }
            }

            withAuditCapture { audit ->
                owner.client.edit(room, reply.id, "an answer <@everyone>")
                assertNull(noticeAbout(quotedAuthor, reply.id), "the quote did not change: it is not what makes them direct")
                val held = scalar("SELECT count(*) FROM notifications WHERE message_id = ?", reply.id.toInt())
                assertEquals(0L, held, "the wide edit tells nobody")
                val line = audit.events.single { it.message == "notification.broadcast_budget_exceeded" }
                assertTrue(line.hasKeyValue("withheld", 25), "all 25 are withheld, the quoted author too: ${line.keyValuePairs}")
            }
        }

    @Test
    fun `an edit dropping the mention of a deleted quoted author deletes their notice instead of relabelling it to the quote`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mqn-dmen")
            val carl = seedActor("mqn-dmen-carl").also { minted.users += it.id }
            TestRooms.addMember(cast.room, carl.id)
            val carls = TestThreads.post(cast.room, 1, carl.id, "Carl says")
            val posted = cast.ann.client.post(cast.room, "answer <@${carl.id}>", quoted = carls)
            assertEquals(NotificationType.MENTIONED, noticeAbout(carl.id, posted.id)?.second)

            // Deleted: still a member row, no longer an ACTIVE member, so the quote's filter drops them from the revision (the edit's
            // `inRoom` check; without it the notice would be relabelled to the quote in place).
            TestUsers.softDelete(carl.id)
            cast.ann.client.edit(cast.room, posted.id, "answer")

            assertNull(noticeAbout(carl.id, posted.id))
        }

    @Test
    fun `an edit falls back from a direct mention to a supported cross-thread quote`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mqn-plant")
        TestThreads.seed(cast.room, cast.owner.id, title = "Thread two")
        val bobsElsewhere = TestThreads.post(cast.room, 2, cast.bob.id, "Bob says in thread two")
        // A plain mention: bob holds a MENTIONED notice and the message quotes nothing.
        val posted = cast.ann.client.post(cast.room, "answer <@${cast.bob.id}>")
        assertEquals(NotificationType.MENTIONED, noticeAbout(cast.bob.id, posted.id)?.second)
        // The fixture shortcut makes the same cross-thread reference a quoting post may now write.
        sql("UPDATE messages SET quoted_message_id = ? WHERE id = ?", bobsElsewhere.toLong(), posted.id.toLong())

        // Dropping the stronger direct mention relabels the existing notice to the quote in place.
        val mentioned = noticeAbout(cast.bob.id, posted.id)
        cast.ann.client.edit(cast.room, posted.id, "answer")

        assertEquals(mentioned?.first, noticeAbout(cast.bob.id, posted.id)?.first)
        assertEquals(NotificationType.MESSAGE_QUOTED, noticeAbout(cast.bob.id, posted.id)?.second)
        assertEquals(1L, kindRows(cast.room, NotificationType.MESSAGE_QUOTED))
    }
}
