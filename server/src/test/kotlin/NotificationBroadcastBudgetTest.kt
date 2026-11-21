package ch.nokillswit

import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationEmailBudget
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.users.UserRole
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val DAY = 86_400_000L
private const val EVENT = "notification.broadcast_budget_exceeded"

/**
 * The wide-event budget (V29, 1.1 Q1 = (a); `chat-model.md` "Watching, group mentions and notice kinds (1.1)", `security.md` "Notices"): an
 * event that would notify more than 20 people counts one against the actor's day; over it the WRITE still succeeds and only the people
 * named directly (a `<@id>` mention, a hand-over) are told; one audit line per actor and day; the day rolls over with the injected clock;
 * concurrent writes of one actor cannot overshoot. The writer is driven directly (a tiny budget, a settable clock); the boot validation
 * is `NumericConfigBoundsTest`, the route-level wiring is the last test here. Rooms, templates and users are SHARED suite state: every
 * test mints UNIQUE rows.
 */
class NotificationBroadcastBudgetTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    /** A room of [people] other members around [actor] (an ADMIN account: no exemption), thread #1 seeded BEFORE they joined. */
    private inner class Scene(val actor: UInt, val room: UInt, val threadId: UInt, val number: Int, val people: List<UInt>) {
        /** [NoticeReason] for each of the first [count] people, as one thread-level event by [by]. */
        suspend fun event(writer: NoticeWriter, reason: NoticeReason, count: Int, by: UInt = actor, extra: List<Notice> = emptyList()) =
            suspendTransaction(sharedDatabaseForTests()) {
                writer.forThread(room, threadId, number, by, extra + people.take(count).map { Notice(it, reason) })
            }

        fun rows(reason: NoticeReason): Int =
            count("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = ?", room.toLong(), reason.name)
    }

    private suspend fun scene(members: Int = 25): Scene {
        val actor = TestUsers.seed(uniqueEmail("bb-act"), "pw", role = UserRole.ADMIN, name = "Actor").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(actor, template, "Broadcast ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, actor)
        val people = TestRooms.seedMembers(room, members, "bb-${UUID.randomUUID().toString().substring(0, 8)}").also { mintedUsers += it }
        return Scene(actor, room, thread.id, thread.number, people)
    }

    private fun writer(perDay: Int, clock: () -> Long = System::currentTimeMillis) =
        NoticeWriter(NotificationEmailBudget(20, broadcastsPerDay = perDay, clock = clock))

    private fun count(sql: String, vararg args: Any): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, arg -> if (arg is Long) st.setLong(i + 1, arg) else st.setString(i + 1, arg.toString()) }
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** The actor's budget row of the day [now] falls on: (wide events admitted, the first refusal's instant or null). */
    private fun budget(actor: UInt, now: Long): Pair<Int, Long?>? = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT broadcasts, broadcasts_exceeded_at FROM notification_email_budget WHERE actor_id = ? AND day = ?"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, actor.toLong())
            st.setInt(2, (now / DAY).toInt())
            st.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) to rs.getLong(2).takeUnless { rs.wasNull() } else null
            }
        }
    }

    @Test
    fun `a wide event is counted and a narrow one is not, up to the budget`() = runBlocking {
        val s = scene()
        val w = writer(perDay = 2)
        val now = System.currentTimeMillis()

        assertEquals(20, s.event(w, NoticeReason.THREAD_REPLIED, count = 20).recipients, "20 people is not wide")
        assertEquals(null, budget(s.actor, now), "a narrow event never touches the counter")
        assertEquals(21, s.event(w, NoticeReason.THREAD_STATUS_CHANGED, count = 21).recipients, "21 people is wide: the first")
        assertEquals(25, s.event(w, NoticeReason.THREAD_CLOSED, count = 25).recipients, "the second")
        assertEquals(2, budget(s.actor, now)?.first)
        assertEquals(null, budget(s.actor, now)?.second, "within the budget nothing was refused")
    }

    @Test
    fun `over the budget the write goes on and only the people named directly are told, one audit line a day`() = runBlocking {
        val s = scene()
        val w = writer(perDay = 1)
        val now = System.currentTimeMillis()
        assertEquals(25, s.event(w, NoticeReason.THREAD_CLOSED, count = 25).recipients)

        withAuditCapture { audit ->
            // Over the budget: the new owner (assigned) is told, the 24 other watchers are not.
            val refused = s.event(
                w, NoticeReason.THREAD_OWNER_CHANGED, count = 25, extra = listOf(Notice(s.people[0], NoticeReason.THREAD_ASSIGNED)),
            )
            assertEquals(1, refused.recipients, "only the hand-over's new owner")
            assertEquals(mapOf(NoticeReason.THREAD_ASSIGNED to 1), refused.kinds)
            assertEquals(0, s.rows(NoticeReason.THREAD_OWNER_CHANGED))

            // A direct mention of a message in a wide audience is told too; the second refusal is not audited again.
            val messageId = TestThreads.post(s.room, s.number, s.actor)
            val mention = suspendTransaction(sharedDatabaseForTests()) {
                val replies = s.people.map { Notice(it, NoticeReason.THREAD_REPLIED) }
                val audience = listOf(Notice(s.people[3], NoticeReason.MENTIONED)) + replies
                w.forMessage(MessageRef(s.room, s.threadId, s.number, messageId, opening = false), s.actor, audience)
            }
            assertEquals(mapOf(NoticeReason.MENTIONED to 1), mention.kinds, "the mentioned member only")

            val lines = audit.events.filter { it.message == EVENT }
            assertEquals(1, lines.size, "one line for the actor's day")
            val line = lines.single()
            assertTrue(line.hasKeyValue("actorId", s.actor.toLong()))
            assertTrue(line.hasKeyValue("roomId", s.room.toLong()))
            assertTrue(line.hasKeyValue("budget", 1))
            assertTrue(line.hasKeyValue("withheld", 24), "the 24 watchers the hand-over left out: ${line.keyValuePairs}")
        }
        val row = assertNotNull(budget(s.actor, now))
        assertEquals(1, row.first, "refused events do not count")
        assertTrue(row.second != null, "and the first refusal is stamped")
    }

    @Test
    fun `the budget is the actor's own and resets at midnight UTC`() = runBlocking {
        val s = scene()
        val other = scene()
        var now = 10 * DAY + 5_000
        val w = writer(perDay = 1) { now }

        assertEquals(25, s.event(w, NoticeReason.THREAD_CLOSED, count = 25).recipients)
        assertEquals(0, s.event(w, NoticeReason.THREAD_REOPENED, count = 25).recipients, "the same actor, the same day: refused")
        assertEquals(25, other.event(w, NoticeReason.THREAD_CLOSED, count = 25).recipients, "another actor has a budget of their own")
        now += DAY
        assertEquals(25, s.event(w, NoticeReason.THREAD_REOPENED, count = 25).recipients, "the next UTC day starts afresh")
        assertEquals(1, budget(s.actor, 10 * DAY)?.first)
        assertEquals(1, budget(s.actor, 11 * DAY)?.first)
    }

    @Test
    fun `concurrent wide events of one actor cannot overshoot the budget`() = runBlocking {
        val s = scene()
        val w = writer(perDay = 3)
        val results = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) { s.event(w, NoticeReason.THREAD_STATUS_CHANGED, count = 25).recipients }
            }.awaitAll()
        }
        assertEquals(3, results.count { it == 25 }, "exactly the budget is admitted: $results")
        assertEquals(5, results.count { it == 0 })
        assertEquals(3, budget(s.actor, System.currentTimeMillis())?.first)
    }

    @Test
    fun `through the routes a wide new thread over the budget is still written, and only the mentioned hear of it`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val author = seedActor("bb-http-aut").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(author.id, template, "Http ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val people = TestRooms.seedMembers(room, 25, "bb-http-${UUID.randomUUID().toString().substring(0, 8)}").also { mintedUsers += it }

        val threads = "/api/v1/rooms/$room/threads"
        val fields = mapOf("severity" to JsonPrimitive("s1"))
        val first = author.client.postJson(threads, ThreadCreateRequest("One", "to all", fields))
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(25, count("SELECT count(*) FROM notifications WHERE room_id = ?", room.toLong()), "the whole room was told")

        val body = "to <@${people[0]}> only"
        val second = author.client.postJson(threads, ThreadCreateRequest("Two", body, fields))
        assertEquals(HttpStatusCode.Created, second.status, "over the budget the write still succeeds")
        assertEquals(26, count("SELECT count(*) FROM notifications WHERE room_id = ?", room.toLong()), "and only the mention is told")
        assertEquals(1, count("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = ?", room.toLong(), "MENTIONED"))
    }

    @Test
    fun `through the status and owner routes a wide event over the budget still writes and tells only the new owner`() = testApplication {
        configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")
        startApplication()
        val author = seedActor("bb-route-aut").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(author.id, template, "Route ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, author.id) // before the members join: the opening message told nobody
        val people = TestRooms.seedMembers(room, 25, "bb-route-${UUID.randomUUID().toString().substring(0, 8)}").also { mintedUsers += it }
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO thread_watches (thread_id, user_id, room_id, created_at) " +
                    "SELECT ?, user_id, room_id, 1 FROM room_members WHERE room_id = ? " +
                    "ON CONFLICT DO NOTHING",
            ).use { st ->
                st.setInt(1, thread.id.toInt())
                st.setInt(2, room.toInt())
                st.executeUpdate()
            }
        }
        fun rows(type: String) =
            count("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = ?", room.toLong(), type)
        val thread1 = "/api/v1/rooms/$room/threads/1"
        fun newest(): Long = PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT coalesce(max(created_at), 0) FROM notifications WHERE room_id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }

        // The first wide event of the day is admitted: the 25 watchers (everybody but the author) are told.
        assertEquals(HttpStatusCode.NoContent, author.client.putJson("$thread1/status", ThreadStatusRequest("monitoring")).status)
        assertEquals(25, rows("THREAD_STATUS_CHANGED"))
        val stamp = newest()

        // The second is refused: the move is written, nobody is told (and no row is refreshed either).
        assertEquals(HttpStatusCode.NoContent, author.client.putJson("$thread1/status", ThreadStatusRequest("open")).status)
        assertEquals("open", TestThreads.raw(room, 1)!!.statusKey)
        assertEquals(25, rows("THREAD_STATUS_CHANGED"))
        assertEquals(stamp, newest(), "a refused event touches no notice")

        // A hand-over over the budget still tells the NEW owner, and none of the 24 other watchers.
        assertEquals(HttpStatusCode.NoContent, author.client.putJson("$thread1/owner", ThreadOwnerRequest(people[0])).status)
        assertEquals(people[0], TestThreads.raw(room, 1)!!.ownerId)
        assertEquals(1, rows("THREAD_ASSIGNED"))
        assertEquals(0, rows("THREAD_OWNER_CHANGED"))
    }

    @Test
    fun `over the budget a named person loses the fallbacks too - only the direct kind can reach them`() = runBlocking {
        val s = scene()
        val w = writer(perDay = 1)
        assertEquals(25, s.event(w, NoticeReason.THREAD_CLOSED, count = 25).recipients)
        val named = s.people[0]
        TestNotificationPreferences.set(named, NotificationType.MENTIONED, NotificationChannel.IN_APP, false)
        val messageId = TestThreads.post(s.room, s.number, s.actor)
        val replies = s.people.drop(1).map { Notice(it, NoticeReason.THREAD_REPLIED) }
        val audience = listOf(Notice(named, NoticeReason.MENTIONED, listOf(NoticeReason.THREAD_REPLIED))) + replies

        val outcome = suspendTransaction(sharedDatabaseForTests()) {
            w.forMessage(MessageRef(s.room, s.threadId, s.number, messageId, opening = false), s.actor, audience)
        }

        assertEquals(0, outcome.recipients, "the mention kind is off for the one named person: not the reply it would fall back to either")
        assertEquals(0, s.rows(NoticeReason.THREAD_REPLIED))
        assertEquals(0, s.rows(NoticeReason.MENTIONED))
    }
}
