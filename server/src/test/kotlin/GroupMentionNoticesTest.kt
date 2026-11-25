package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.MAX_NOTIFY_PAYLOAD_BYTES
import ch.nokillswit.infra.notify.toPayload
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationFeedItem
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.WatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * Group mentions tell people (`chat-model.md` "Group mentions (V25, V30)", 1.1 D5, D6): `<@team:id>` tells the team's active roster that
 * is ALSO in the room, `<@watchers>` the thread's watchers, `<@everyone>` the room, each person once with the most specific reason and
 * never the author; a kind switched off on both channels falls back to the next applicable reason; an edit tells only the newly
 * addressed, reading the OLD body's groups as of the edit. The pure rules are `NoticeAudienceTest`; this is the audience read inside
 * real writes, the index and the wire are `GroupMentionsTest`. Rooms, templates, users and teams are SHARED suite state: every test mints
 * UNIQUE rows and removes them.
 */
class GroupMentionNoticesTest {
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
    private suspend fun ApplicationTestBuilder.cast(prefix: String): PairCast = seedPairCast(prefix, "Gm", minted, withThread = true)

    private suspend fun team(prefix: String, vararg members: RoomActor) =
        TestTeams.seedMentionable(prefix, members.map { it.id }).also { teams += it.id }

    private suspend fun HttpClient.kinds(): List<NotificationType> =
        get("/api/v1/notifications?pageSize=100&sort=id").body<NotificationPageResponse>().items.map { it.type }

    private suspend fun HttpClient.feed(): List<NotificationFeedItem> =
        get("/api/v1/notifications/feed?pageSize=100&sort=id").body<PageResponse<NotificationFeedItem>>().items

    private suspend fun HttpClient.reply(room: UInt, body: String, thread: Int = 1): MessageResponse {
        val response = postJson("$rooms/$room/threads/$thread/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, messageId: UInt, body: String) =
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$messageId", MessageRequest(body)).status)

    private suspend fun HttpClient.openThread(room: UInt, title: String, body: String): Int {
        val request = ThreadCreateRequest(title, body, mapOf("severity" to JsonPrimitive("s1")))
        val response = postJson("$rooms/$room/threads", request)
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body<ThreadResponse>().number
    }

    private fun scalar(sql: String, vararg args: Long): Long = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setLong(i + 1, arg) }
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun kindRows(room: UInt, kind: String): Long = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setString(2, kind)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun rowsOf(userId: UInt, room: UInt): Int =
        scalar("SELECT count(*) FROM notifications WHERE recipient_id = ? AND room_id = ?", userId.toLong(), room.toLong()).toInt()

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

    @Test
    fun `a team mention tells the team's members who are in the room - never one who is not, nor the author`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-team")
        val oncall = team("oncall", cast.ann, cast.outsider, cast.owner)

        val posted = cast.owner.client.reply(cast.room, "paging ${oncall.token}")

        assertEquals(listOf(NotificationType.THREAD_CREATED, NotificationType.TEAM_MENTIONED), cast.ann.client.kinds())
        assertEquals(listOf(NotificationType.THREAD_CREATED), cast.bob.client.kinds(), "not on the team")
        assertEquals(emptyList(), cast.owner.client.kinds(), "the author is on the team and is never told")
        assertEquals(emptyList(), cast.outsider.client.kinds(), "on the roster, not in the room: never told")
        assertEquals(0, rowsOf(cast.outsider.id, cast.room))
        assertEquals(noticeAbout(cast.ann.id, posted.id)?.second, NotificationType.TEAM_MENTIONED)
        val notice = cast.ann.client.feed().single { it.messageId == posted.id }
        assertEquals(oncall.name, notice.teamName)
    }

    @Test
    fun `a team notice names only a recipient team and chooses the lowest id regardless of mention order`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-team-name")
        val firstForAnn = team("first", cast.ann)
        val onlyForBob = team("bob-only", cast.bob)
        val laterForAnn = team("later", cast.ann)

        val posted = cast.owner.client.reply(
            cast.room,
            "paging ${laterForAnn.token} ${onlyForBob.token} ${firstForAnn.token}",
        )

        val annNotice = cast.ann.client.feed().single { it.messageId == posted.id }
        val bobNotice = cast.bob.client.feed().single { it.messageId == posted.id }
        assertEquals(firstForAnn.name, annNotice.teamName, "the lowest team id wins, not mention order")
        assertEquals(onlyForBob.name, bobNotice.teamName, "another recipient's team name is never exposed")
    }

    @Test
    fun `a deleted team, a team without a handle and an unknown team are plain text`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-plain")
        val gone = team("gone", cast.ann)
        val handleless = TestTeams.seed("Handleless ${UUID.randomUUID().toString().substring(0, 8)}").also { teams += it }
        TestTeams.service.addMember(handleless, cast.ann.id) {}
        TestTeams.service.delete(gone.id)
        val before = rowsOf(cast.ann.id, cast.room)

        val posted = cast.owner.client.reply(cast.room, "${gone.token} <@team:$handleless> <@team:2147483000>")

        assertEquals(emptyList(), posted.groupMentions)
        assertEquals(before, rowsOf(cast.ann.id, cast.room), "nobody was told")
        assertEquals(0, scalar("SELECT count(*) FROM message_group_mentions WHERE message_id = ?", posted.id.toLong()))
    }

    @Test
    fun `the watchers are told by a reply that names them, the author and the unwatching are not`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-watch")
        setWatching(cast.bob, cast.room, true)

        val posted = cast.ann.client.reply(cast.room, "<@watchers> status please")

        assertEquals(NotificationType.WATCHERS_MENTIONED, cast.owner.client.kinds().last(), "the creator watches")
        assertEquals(NotificationType.WATCHERS_MENTIONED, cast.bob.client.kinds().last())
        assertEquals(listOf(NotificationType.THREAD_CREATED), cast.ann.client.kinds(), "the author is not told")
        assertEquals(emptyList(), cast.outsider.client.kinds())
        assertEquals(listOf("WATCHERS"), posted.groupMentions.map { it.kind.name })
    }

    @Test
    fun `the watchers of a thread nobody else watches, and of an opening message, are nobody`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-nobody")
        setWatching(cast.owner, cast.room, false)
        val before = listOf(cast.owner, cast.bob).map { rowsOf(it.id, cast.room) }

        val posted = cast.ann.client.reply(cast.room, "<@watchers> anyone?")

        assertEquals(before, listOf(cast.owner, cast.bob).map { rowsOf(it.id, cast.room) }, "no watchers: no notice")
        assertEquals(listOf("WATCHERS"), posted.groupMentions.map { it.kind.name }, "the mention is still shown")

        val number = cast.ann.client.openThread(cast.room, "Second", "<@watchers> new")
        assertEquals(NotificationType.THREAD_CREATED, cast.bob.client.kinds().last(), "an opening message: the room's base notice only")
        assertEquals(
            listOf("WATCHERS"),
            cast.ann.client.get("$rooms/${cast.room}/threads/$number/messages").body<ch.nokillswit.threads.MessagePageResponse>().items
                .single().groupMentions.map { it.kind.name },
        )
    }

    @Test
    fun `everyone tells the whole room once, in a reply or an opening message - any member may`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-all")

        cast.ann.client.reply(cast.room, "<@everyone> deploy at five")
        assertEquals(NotificationType.EVERYONE_MENTIONED, cast.owner.client.kinds().last(), "a watcher: everyone, not a plain reply")
        assertEquals(NotificationType.EVERYONE_MENTIONED, cast.bob.client.kinds().last(), "a non-watcher is told too")
        assertEquals(emptyList(), cast.outsider.client.kinds())
        assertEquals(listOf(NotificationType.THREAD_CREATED), cast.ann.client.kinds())

        cast.bob.client.openThread(cast.room, "Second", "<@everyone> new")
        assertEquals(NotificationType.EVERYONE_MENTIONED, cast.ann.client.kinds().last(), "everyone beats 'started a thread'")
        assertEquals(NotificationType.EVERYONE_MENTIONED, cast.owner.client.kinds().last())
        assertEquals(2, cast.bob.client.kinds().size, "bob: the seed's notice and ann's reply, none for his own thread")
    }

    @Test
    fun `a message that names a person, their team, the watchers and everyone tells them once, as the mention`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-prio")
        val oncall = team("prio", cast.bob, cast.owner)
        val posted = cast.ann.client.reply(cast.room, "<@${cast.bob.id}> ${oncall.token} <@watchers> <@everyone>")

        assertEquals(NotificationType.MENTIONED, noticeAbout(cast.bob.id, posted.id)?.second, "named, on the team and in the room")
        assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(cast.owner.id, posted.id)?.second, "on the team and a watcher")
        val sql = "SELECT count(*) FROM notifications WHERE message_id = ? AND recipient_id = ?"
        assertEquals(1L, scalar(sql, posted.id.toLong(), cast.bob.id.toLong()))
        assertNull(noticeAbout(cast.ann.id, posted.id))
        assertEquals(listOf("TEAM", "WATCHERS", "EVERYONE"), posted.groupMentions.map { it.kind.name })
    }

    @Test
    fun `a group kind switched off on both channels falls back to the next reason`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-mute")
        val oncall = team("mute", cast.bob)
        // The owner watches: muting everyone keeps the reply notice. Bob does not watch: muting everyone leaves nothing.
        TestNotificationPreferences.set(cast.owner.id, NotificationType.EVERYONE_MENTIONED, NotificationChannel.IN_APP, false)
        TestNotificationPreferences.set(cast.bob.id, NotificationType.EVERYONE_MENTIONED, NotificationChannel.IN_APP, false)
        val bobBefore = rowsOf(cast.bob.id, cast.room)

        cast.ann.client.reply(cast.room, "<@everyone> all hands")
        assertEquals(NotificationType.THREAD_REPLIED, cast.owner.client.kinds().last())
        assertEquals(bobBefore, rowsOf(cast.bob.id, cast.room), "every applicable reason is off: no row")

        // On an opening message the fallback is 'started a thread'.
        TestNotificationPreferences.set(cast.bob.id, NotificationType.TEAM_MENTIONED, NotificationChannel.IN_APP, false)
        cast.ann.client.openThread(cast.room, "Second", "${oncall.token} please")
        assertEquals(NotificationType.THREAD_CREATED, cast.bob.client.kinds().last())
    }

    @Test
    fun `everyone in a full room is one insert and one hint under the wide-event budget, and only the named over it`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val owner = seedActor("gm-big-own").also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val room = TestRooms.seed(owner.id, template, "Big ${UUID.randomUUID().toString().substring(0, 8)}").also { minted.rooms += it }
        TestThreads.seed(room, owner.id)
        val people = TestRooms.seedMembers(room, 499, "gm-big-${UUID.randomUUID().toString().substring(0, 8)}").also { bulk += it }
        fun everyoneRows() = kindRows(room, "EVERYONE_MENTIONED")

        NotifyCapture().use { capture ->
            val hints = capture.during { owner.client.reply(room, "<@everyone> the first, within the budget") }
            val notice = hints.single { it.kind == LiveKind.NOTIFICATION_CREATED }
            assertEquals(499, notice.targetUserIds?.size, "one hint, every other member a target")
            assertTrue(notice.toPayload().toByteArray().size < MAX_NOTIFY_PAYLOAD_BYTES)
        }
        assertEquals(499, everyoneRows())

        withAuditCapture { audit ->
            val named = people[7]
            val second = owner.client.postJson(
                "$rooms/$room/threads/1/messages", MessageRequest("<@everyone> <@$named> over the budget"),
            )
            assertEquals(HttpStatusCode.Created, second.status, "over the budget the write still succeeds")
            assertEquals(499, everyoneRows(), "nobody is told by name or not, but the named member")
            assertEquals(1L, kindRows(room, "MENTIONED"))
            val line = audit.events.single { it.message == "notification.broadcast_budget_exceeded" }
            assertTrue(line.hasKeyValue("roomId", room.toLong()), "the audit line names the room")
        }
    }

    @Test
    fun `an edit that adds everyone upgrades a watcher's reply notice, removing it falls back in place`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-edit")
        val posted = cast.ann.client.reply(cast.room, "hello")
        val replyNotice = noticeAbout(cast.owner.id, posted.id)
        assertEquals(NotificationType.THREAD_REPLIED, replyNotice?.second)

        cast.ann.client.edit(cast.room, posted.id, "hello <@everyone>")
        val upgraded = noticeAbout(cast.owner.id, posted.id)
        assertEquals(NotificationType.EVERYONE_MENTIONED, upgraded?.second)
        assertNotEquals(replyNotice?.first, upgraded?.first, "a fresh, unseen notice")
        assertEquals(NotificationType.EVERYONE_MENTIONED, noticeAbout(cast.bob.id, posted.id)?.second)
        assertNull(noticeAbout(cast.ann.id, posted.id), "the author")

        cast.ann.client.edit(cast.room, posted.id, "hello")
        val fallen = noticeAbout(cast.owner.id, posted.id)
        assertEquals(upgraded?.first, fallen?.first, "relabelled in place: the same row")
        assertEquals(NotificationType.THREAD_REPLIED, fallen?.second, "they watch")
        assertNull(noticeAbout(cast.bob.id, posted.id), "anybody else loses it")
    }

    @Test
    fun `a kept group token addresses nobody again, but somebody the edit now names is told as the mention`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-kept")
        // Bob switched the everyone kind off, so the first post left him without a notice; the owner holds one.
        TestNotificationPreferences.set(cast.bob.id, NotificationType.EVERYONE_MENTIONED, NotificationChannel.IN_APP, false)
        val posted = cast.ann.client.reply(cast.room, "<@everyone> first")
        val ownerHeld = noticeAbout(cast.owner.id, posted.id)
        assertEquals(NotificationType.EVERYONE_MENTIONED, ownerHeld?.second)
        assertNull(noticeAbout(cast.bob.id, posted.id))

        // The token is kept, a direct mention is added: bob is newly addressed at MENTIONED (the most specific reason), the owner is not.
        cast.ann.client.edit(cast.room, posted.id, "<@everyone> <@${cast.bob.id}> second")
        val bobHeld = noticeAbout(cast.bob.id, posted.id)
        assertEquals(NotificationType.MENTIONED, bobHeld?.second)
        assertEquals(ownerHeld, noticeAbout(cast.owner.id, posted.id), "the kept token addressed the owner already: same row, same kind")

        // A member who joined after the post is in the audience of the kept token, and is not told by an edit.
        val late = seedActor("gm-kept-late").also { minted.users += it.id }
        TestRooms.addMember(cast.room, late.id)
        cast.ann.client.edit(cast.room, posted.id, "<@everyone> <@${cast.bob.id}> third")
        assertNull(noticeAbout(late.id, posted.id))
        assertEquals(bobHeld, noticeAbout(cast.bob.id, posted.id))
    }

    @Test
    fun `an opening message that gains everyone tells each member afresh, losing it relabels them in one statement`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-open")
        val number = cast.ann.client.openThread(cast.room, "Second", "start here")
        val opening = TestThreads.rawMessages(TestThreads.raw(cast.room, number)!!.id).first().id
        val created = noticeAbout(cast.bob.id, opening)
        assertEquals(NotificationType.THREAD_CREATED, created?.second)

        cast.ann.client.edit(cast.room, opening, "start <@everyone>")
        val afresh = listOf(cast.owner, cast.bob).map { noticeAbout(it.id, opening) }
        assertTrue(afresh.all { it?.second == NotificationType.EVERYONE_MENTIONED })
        assertNotEquals(created?.first, afresh.last()?.first)

        cast.ann.client.edit(cast.room, opening, "start")
        val relabelled = listOf(cast.owner, cast.bob).map { noticeAbout(it.id, opening) }
        assertEquals(afresh.map { it?.first }, relabelled.map { it?.first }, "the same rows")
        assertTrue(relabelled.all { it?.second == NotificationType.THREAD_CREATED })
        assertEquals(
            // Every row an UPDATE writes in one transaction carries that statement's command id (`cmin`): one id = one statement.
            1L,
            scalar("SELECT count(DISTINCT cmin::text) FROM notifications WHERE message_id = ? AND in_app", opening.toLong()),
            "ONE batched UPDATE relabelled them",
        )
    }

    @Test
    fun `a team swapped for another tells the new team, keeps the shared member, drops the old one to the base reason`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gm-swap")
        val carl = seedActor("gm-swap-carl").also { minted.users += it.id }
        TestRooms.addMember(cast.room, carl.id)
        val first = team("swapa", cast.owner, cast.bob)
        val second = team("swapb", cast.bob, carl, cast.ann)
        val posted = cast.ann.client.reply(cast.room, first.token)
        val bobHeld = noticeAbout(cast.bob.id, posted.id)
        val ownerHeld = noticeAbout(cast.owner.id, posted.id)
        assertEquals(NotificationType.TEAM_MENTIONED, ownerHeld?.second)

        cast.ann.client.edit(cast.room, posted.id, second.token)

        assertEquals(bobHeld, noticeAbout(cast.bob.id, posted.id), "on both teams: untouched")
        assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(carl.id, posted.id)?.second, "newly addressed")
        val owner = noticeAbout(cast.owner.id, posted.id)
        assertEquals(ownerHeld?.first, owner?.first, "a watcher falls back in place: the same row")
        assertEquals(NotificationType.THREAD_REPLIED, owner?.second)
        assertEquals(second.id, TestGroupRows.teamsOf(posted.id).single())
    }

    @Test
    fun `over the wide-event budget an edit that adds everyone to an opening message keeps every old notice`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val owner = seedActor("gm-edw-own").also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val room = TestRooms.seed(owner.id, template, "Edw ${UUID.randomUUID().toString().substring(0, 8)}").also { minted.rooms += it }
        TestThreads.seed(room, owner.id)
        val people = TestRooms.seedMembers(room, 25, "gm-edw-${UUID.randomUUID().toString().substring(0, 8)}").also { bulk += it }
        val number = owner.client.openThread(room, "Wide", "start")
        val opening = TestThreads.rawMessages(TestThreads.raw(room, number)!!.id).first().id
        val told = scalar("SELECT count(*) FROM notifications WHERE message_id = ?", opening.toLong())
        assertEquals(25L, told, "the first wide event: admitted")
        val held = people.associateWith { noticeAbout(it, opening) }

        owner.client.edit(room, opening, "start <@everyone> <@${people[0]}>")

        assertEquals(NotificationType.MENTIONED, noticeAbout(people[0], opening)?.second, "the named member: upgraded, direct")
        people.drop(1).forEach { assertEquals(held[it], noticeAbout(it, opening), "the budget refused the upgrade: the old notice stays") }
        assertEquals(25L, scalar("SELECT count(*) FROM notifications WHERE message_id = ?", opening.toLong()), "nobody lost their notice")
    }
}
