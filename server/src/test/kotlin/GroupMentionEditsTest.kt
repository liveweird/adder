package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.WatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.JsonPrimitive

/**
 * The edge cases of an edit that touches group mentions (`chat-model.md` "Group mentions (V25, V30)", 1.1 A38, A39), over HTTP beside
 * `GroupMentionNoticesTest`: a token-unchanged edit does nothing, a group kind the body no longer earns is relabelled in place, an upgrade
 * deletes the old notice ONLY when its replacement is made (the wide-event budget, the person's switches and the email budget can all
 * refuse it), a relabel fan-out counts against the wide-event budget and leaves a trace, a roster member outside the room is never told,
 * the email budget admits the named first, and the heaviest edit (an upgrade of 499 notices) is timed. Rooms, templates, users and
 * teams are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class GroupMentionEditsTest {
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

    private suspend fun ApplicationTestBuilder.cast(prefix: String): PairCast = seedPairCast(prefix, "Gme", minted, withThread = true)

    private suspend fun team(prefix: String, vararg members: UInt) =
        TestTeams.seedMentionable(prefix, members.toList()).also { teams += it.id }

    private suspend fun HttpClient.reply(room: UInt, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/1/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, messageId: UInt, body: String) =
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$messageId", MessageRequest(body)).status)

    /** Opens a thread and answers its number and its opening message's id. */
    private suspend fun HttpClient.open(room: UInt, body: String): Pair<Int, UInt> {
        val request = ThreadCreateRequest("Opening ${UUID.randomUUID().toString().take(6)}", body, mapOf("severity" to JsonPrimitive("s1")))
        val response = postJson("$rooms/$room/threads", request)
        assertEquals(HttpStatusCode.Created, response.status)
        val thread = response.body<ThreadResponse>()
        return thread.number to TestThreads.rawMessages(thread.id).first().id
    }

    private fun noticeAbout(recipient: UInt, messageId: UInt): Pair<Long, NotificationType>? = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT id, notification_type FROM notifications WHERE recipient_id = ? AND message_id = ?").use { st ->
            st.setLong(1, recipient.toLong())
            st.setInt(2, messageId.toInt())
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to NotificationType.valueOf(rs.getString(2)) else null }
        }
    }

    private fun scalar(sql: String, vararg args: Any): Long = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun groupRowVersions(messageId: UInt): String = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT coalesce(string_agg(xmin::text, ',' ORDER BY group_kind, team_id), '') " +
            "FROM message_group_mentions WHERE message_id = ?"
        c.prepareStatement(sql).use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    @Test
    fun `an edit that keeps the same tokens - the teams in another order - does nothing at all`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gme-same")
        val first = team("same", cast.bob.id)
        val second = team("same", cast.owner.id)
        val posted = cast.ann.client.reply(cast.room, "${first.token} ${second.token} <@everyone> start")
        val held = listOf(cast.owner, cast.bob).map { noticeAbout(it.id, posted.id) }
        val rows = groupRowVersions(posted.id)

        withAuditCapture { audit ->
            cast.ann.client.edit(cast.room, posted.id, "${second.token} ${first.token} <@everyone> reworded")
            assertEquals(held, listOf(cast.owner, cast.bob).map { noticeAbout(it.id, posted.id) }, "no notice touched")
            assertEquals(rows, groupRowVersions(posted.id), "no group row rewritten")
            assertEquals(emptyList(), audit.events.filter { it.message == "notification.created" }, "and nothing audited as made")
        }
    }

    @Test
    fun `everyone swapped for a team on an opening message - the team is told afresh, the rest fall back to started a thread in place`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("gme-swap")
            val oncall = team("swap", cast.bob.id)
            val (_, opening) = cast.ann.client.open(cast.room, "all hands <@everyone>")
            val bobBefore = noticeAbout(cast.bob.id, opening)
            val ownerBefore = noticeAbout(cast.owner.id, opening)
            assertEquals(listOf(NotificationType.EVERYONE_MENTIONED) * 2, listOf(bobBefore?.second, ownerBefore?.second))

            cast.ann.client.edit(cast.room, opening, "just the team ${oncall.token}")

            val bob = noticeAbout(cast.bob.id, opening)
            assertEquals(NotificationType.TEAM_MENTIONED, bob?.second)
            assertNotEquals(bobBefore?.first, bob?.first, "the team member is newly addressed at a more specific reason: a fresh notice")
            val owner = noticeAbout(cast.owner.id, opening)
            assertEquals(ownerBefore?.first, owner?.first, "everyone else: relabelled in place")
            assertEquals(NotificationType.THREAD_CREATED, owner?.second)
        }

    private operator fun <T> List<T>.times(n: Int): List<T> = List(n) { this }.flatten()

    /** A room of the owner and [people] members around a thread #1 seeded BEFORE they joined (it told nobody). */
    private suspend fun ApplicationTestBuilder.fullRoom(prefix: String, people: Int): Triple<RoomActor, UInt, List<UInt>> {
        val owner = seedActor("$prefix-own").also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val room = TestRooms.seed(owner.id, template, "Gme ${UUID.randomUUID().toString().take(8)}").also { minted.rooms += it }
        TestThreads.seed(room, owner.id)
        val members = TestRooms.seedMembers(room, people, "$prefix-${UUID.randomUUID().toString().take(8)}").also { bulk += it }
        return Triple(owner, room, members)
    }

    @Test
    fun `over the wide-event budget an upgrade whose mention kind is switched off keeps the old notice`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val (owner, room, people) = fullRoom("gme-wide", 25)
        val (_, opening) = owner.client.open(room, "start") // the first wide event of the day: admitted
        val held = people.associateWith { noticeAbout(it, opening) }
        assertTrue(held.values.all { it?.second == NotificationType.THREAD_CREATED })
        // The named member switched the mention kind off on both channels: refused over the budget, they must keep what they hold.
        TestNotificationPreferences.set(people[0], NotificationType.MENTIONED, NotificationChannel.IN_APP, false)
        TestNotificationPreferences.set(people[1], NotificationType.MENTIONED, NotificationChannel.IN_APP, true)

        owner.client.edit(room, opening, "start <@everyone> <@${people[0]}> <@${people[1]}>")

        assertEquals(held[people[0]], noticeAbout(people[0], opening), "no replacement could be made: the old notice stays")
        assertEquals(NotificationType.MENTIONED, noticeAbout(people[1], opening)?.second, "a named member with the kind on is upgraded")
        people.drop(2).forEach { assertEquals(held[it], noticeAbout(it, opening), "the budget refused everyone: the old notice stays") }
    }

    @Test
    fun `an upgrade the email budget leaves without any row keeps the old notice`() = testApplication {
        configureApp("notifications.email.maxRecipientsPerActorPerDay" to "1")
        startApplication()
        val cast = cast("gme-mail")
        val (_, opening) = cast.ann.client.open(cast.room, "start")
        val (first, second) = listOf(cast.owner, cast.bob).sortedBy { it.id }
        // Both are email-only for the everyone kind (the app switch off, the email opt-in on), and the budget admits ONE person a day.
        for (person in listOf(first, second)) {
            TestNotificationPreferences.set(person.id, NotificationType.EVERYONE_MENTIONED, NotificationChannel.IN_APP, false)
            TestNotificationPreferences.set(person.id, NotificationType.EVERYONE_MENTIONED, NotificationChannel.EMAIL, true)
        }
        val heldSecond = noticeAbout(second.id, opening)

        cast.ann.client.edit(cast.room, opening, "start <@everyone>")

        val upgraded = noticeAbout(first.id, opening)
        assertEquals(NotificationType.EVERYONE_MENTIONED, upgraded?.second, "the first is admitted: a mail-only notice replaces it")
        assertEquals(heldSecond, noticeAbout(second.id, opening), "over the email budget nothing could replace it: the bell notice stays")
    }

    @Test
    fun `a relabel fan-out of more than twenty is a wide event - over the budget the labels change, no hint, a trace is left`() =
        testApplication {
            configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
            startApplication()
            val (owner, room, people) = fullRoom("gme-rel", 25)
            val (_, opening) = owner.client.open(room, "all <@everyone>") // wide event 1: admitted
            val everyone = "SELECT count(*) FROM notifications WHERE message_id = ? AND notification_type = 'EVERYONE_MENTIONED'"
            assertEquals(25L, scalar(everyone, opening.toInt()))

            NotifyCapture().use { capture ->
                withAuditCapture { audit ->
                    val hints = capture.during { owner.client.edit(room, opening, "all") } // 25 relabels: wide event 2, refused
                    val hinted = hints.filter { it.kind == LiveKind.NOTIFICATION_CREATED }
                    assertEquals(emptyList(), hinted, "the relabelled people are not hinted")
                    val line = audit.events.single { it.message == "notification.created" }
                    assertTrue(line.hasKeyValue("relabelled", 25), "a relabel-only edit leaves a trace")
                    assertTrue(line.hasKeyValue("recipients", 0))
                    assertTrue(audit.events.any { it.message == "notification.broadcast_budget_exceeded" })
                }
            }
            assertEquals(
                25L,
                scalar("SELECT count(*) FROM notifications WHERE message_id = ? AND notification_type = 'THREAD_CREATED'", opening.toInt()),
                "the labels changed all the same: a label must not lie",
            )
            assertNull(noticeAbout(owner.id, opening))
            assertEquals(25, people.count { noticeAbout(it, opening)?.second == NotificationType.THREAD_CREATED })
        }

    @Test
    fun `a relabel fan-out inside the budget hints the people whose bell changed`() = testApplication {
        usePostgresTestcontainer()
        val (owner, room, _) = fullRoom("gme-relok", 25)
        val (_, opening) = owner.client.open(room, "all <@everyone>")
        NotifyCapture().use { capture ->
            val hints = capture.during { owner.client.edit(room, opening, "all") }
            val notice = hints.single { it.kind == LiveKind.NOTIFICATION_CREATED }
            assertEquals(25, notice.targetUserIds?.size)
        }
    }

    @Test
    fun `an edit that adds a team tells its members in the room and never one on the roster who is not`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gme-roster")
        val oncall = team("roster", cast.bob.id, cast.outsider.id)
        val posted = cast.ann.client.reply(cast.room, "hello")

        cast.ann.client.edit(cast.room, posted.id, "hello ${oncall.token}")

        assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(cast.bob.id, posted.id)?.second)
        assertNull(noticeAbout(cast.outsider.id, posted.id))
        val outsiderRows = scalar("SELECT count(*) FROM notifications WHERE recipient_id = ?", cast.outsider.id.toInt())
        assertEquals(0L, outsiderRows, "not one notice, in any room")
    }

    @Test
    fun `the email budget counts the opted-in recipients of a group kind in order of specificity - the named first`() = testApplication {
        configureApp("notifications.email.maxRecipientsPerActorPerDay" to "2")
        startApplication()
        val cast = cast("gme-d8")
        val carl = seedActor("gme-d8-carl").also { minted.users += it.id }
        val dina = seedActor("gme-d8-dina").also { minted.users += it.id }
        TestRooms.addMember(cast.room, carl.id)
        TestRooms.addMember(cast.room, dina.id)
        TestNotices.quietRoom(cast.room)
        val others = listOf(cast.owner, cast.bob, carl, dina)
        others.forEach { TestNotificationPreferences.optInEmail(it.id) }

        val posted = cast.ann.client.reply(cast.room, "<@everyone> <@${dina.id}> please read")

        assertEquals(NotificationType.MENTIONED, noticeAbout(dina.id, posted.id)?.second)
        val all = scalar("SELECT count(*) FROM notifications WHERE message_id = ?", posted.id.toInt())
        assertEquals(4L, all, "everybody has the bell notice")
        val mailSql = "SELECT count(*) FROM notifications WHERE message_id = ? AND recipient_id = ? AND emailed_at IS NULL"
        val mailable = others.filter { scalar(mailSql, posted.id.toInt(), it.id.toLong()) == 1L }
        assertEquals(2, mailable.size, "the budget admits two people a day")
        assertTrue(dina.id in mailable.map { it.id }, "the named person is admitted first, then the first of everyone")
    }

    @Test
    fun `a group-only event is audited with its kinds and no addressed person`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gme-audit")
        withAuditCapture { audit ->
            cast.ann.client.reply(cast.room, "<@everyone> heads up")
            val line = audit.events.single { it.message == "notification.created" }
            assertTrue(line.hasKeyValue("kinds", "EVERYONE_MENTIONED:2"))
            assertTrue(line.hasKeyValue("recipients", 2))
            assertTrue(line.hasKeyValue("recipientIds", emptyList<Long>()), "a group kind is not addressed by name")
            assertTrue(line.hasKeyValue("relabelled", 0))
        }
    }

    @Test
    fun `the heaviest edit - an opening message of a full room gains everyone, 499 notices replaced - is timed`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("gme-heavy-own").also { minted.users += it.id }
        val template = TestThreads.seedTemplate().also { minted.templates += it }
        val room = TestRooms.seed(owner.id, template, "Heavy ${UUID.randomUUID().toString().take(8)}").also { minted.rooms += it }
        bulk += TestRooms.seedMembers(room, 499, "gme-heavy-${UUID.randomUUID().toString().take(8)}")
        val (_, opening) = owner.client.open(room, "start") // told to the 499 members
        assertEquals(499L, scalar("SELECT count(*) FROM notifications WHERE message_id = ?", opening.toInt()))
        val timings = mutableListOf<Long>()
        for (body in listOf("start <@everyone>", "start again", "start <@everyone> once more")) {
            val started = System.nanoTime()
            owner.client.edit(room, opening, body)
            timings += (System.nanoTime() - started) / 1_000_000
        }
        println("WIDE-EVENT edit upgrade/relabel of 499 notices over HTTP ms=$timings (upgrade, relabel, upgrade)")
        assertEquals(
            499L,
            scalar("SELECT count(*) FROM notifications WHERE message_id = ? AND notification_type = 'EVERYONE_MENTIONED'", opening.toInt()),
        )
    }

    @Test
    fun `watchers named in an opening message reached nobody, so adding everyone later tells a watcher like every other member`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("gme-watchopen")
            val (number, opening) = cast.ann.client.open(cast.room, "<@watchers> start")
            assertEquals(
                assertNotNull(noticeAbout(cast.bob.id, opening)).second,
                NotificationType.THREAD_CREATED,
                "the watchers mention reached nobody beyond the room's base notice",
            )
            val watch = cast.bob.client.putJson("$rooms/${cast.room}/threads/$number/watch", WatchRequest(true))
            assertEquals(HttpStatusCode.NoContent, watch.status)

            cast.ann.client.edit(cast.room, opening, "<@watchers> <@everyone> start")

            for (member in listOf(cast.owner, cast.bob)) {
                assertEquals(NotificationType.EVERYONE_MENTIONED, noticeAbout(member.id, opening)?.second, "a watcher like any member")
            }
        }

    @Test
    fun `an edit that swaps everyone for a team over the wide-event budget leaves no everyone notice behind`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val (owner, room, people) = fullRoom("gme-lie", 25)
        val oncall = team("lie", people[0], people[1], people[2])
        val (_, opening) = owner.client.open(room, "all <@everyone>") // the first wide event of the day
        val before = people.associateWith { noticeAbout(it, opening) }

        owner.client.edit(room, opening, "only ${oncall.token}") // 22 relabels: a wide event, refused

        val everyoneRows =
            scalar("SELECT count(*) FROM notifications WHERE message_id = ? AND notification_type = 'EVERYONE_MENTIONED'", opening.toInt())
        assertEquals(0L, everyoneRows, "a label must not lie: nothing says everyone any more")
        for (member in people.take(3)) {
            val now = noticeAbout(member, opening)
            assertEquals(NotificationType.TEAM_MENTIONED, now?.second, "the team's members: relabelled in place")
            assertEquals(before[member]?.first, now?.first, "in place, not a fresh row the budget refused")
        }
        people.drop(3).forEach { assertEquals(NotificationType.THREAD_CREATED, noticeAbout(it, opening)?.second) }
    }

    @Test
    fun `a dropped upgrade whose kind the body no longer earns - the team kind switched off for a non-watcher - loses the notice`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("gme-mute")
            val oncall = team("mute", cast.bob.id)
            val posted = cast.ann.client.reply(cast.room, "<@everyone> first")
            assertEquals(NotificationType.EVERYONE_MENTIONED, noticeAbout(cast.bob.id, posted.id)?.second)
            TestNotificationPreferences.set(cast.bob.id, NotificationType.TEAM_MENTIONED, NotificationChannel.IN_APP, false)

            cast.ann.client.edit(cast.room, posted.id, "just ${oncall.token}")

            assertNull(noticeAbout(cast.bob.id, posted.id), "no reason reaches bob and everyone is gone: nothing is left to say")
        }

    @Test
    fun `an email-only team row the email budget leaves without a row does not leave the old everyone notice standing`() = testApplication {
        configureApp("notifications.email.maxRecipientsPerActorPerDay" to "1")
        startApplication()
        val cast = cast("gme-mailteam")
        val carl = seedActor("gme-mailteam-carl").also { minted.users += it.id }
        TestRooms.addMember(cast.room, carl.id)
        val (first, second) = listOf(cast.bob, carl).sortedBy { it.id }
        val oncall = team("mailteam", first.id, second.id)
        val posted = cast.ann.client.reply(cast.room, "<@everyone> first")
        for (person in listOf(first, second)) {
            TestNotificationPreferences.set(person.id, NotificationType.TEAM_MENTIONED, NotificationChannel.IN_APP, false)
            TestNotificationPreferences.set(person.id, NotificationType.TEAM_MENTIONED, NotificationChannel.EMAIL, true)
        }

        cast.ann.client.edit(cast.room, posted.id, "just ${oncall.token}")

        assertEquals(NotificationType.TEAM_MENTIONED, noticeAbout(first.id, posted.id)?.second, "admitted: a mail-only notice")
        assertNull(noticeAbout(second.id, posted.id), "over the email budget and not in the app: no row, and not the stale everyone one")
    }

    @Test
    fun `one edit is one notification hint, upgrades and relabels together`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gme-onehint")
        val oncall = team("onehint", cast.bob.id)
        val (_, opening) = cast.ann.client.open(cast.room, "all <@everyone>")
        NotifyCapture().use { capture ->
            val hints = capture.during { cast.ann.client.edit(cast.room, opening, "only ${oncall.token}") }
            val notices = hints.filter { it.kind == LiveKind.NOTIFICATION_CREATED }
            assertEquals(1, notices.size, "a fresh team notice for bob and a relabel for the owner: ONE hint")
            assertEquals(setOf(cast.bob.id, cast.owner.id), notices.single().targetUserIds?.toSet())
        }
    }
}
