package ch.nokillswit

import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationDigest
import ch.nokillswit.notifications.NotificationEmailBudget
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val DAY = 86_400_000L
private const val MINUTE = 60_000L
private const val EVENT = "notification.email_budget_exceeded"

/**
 * The per-actor digest budget (V24, 1.0.0 checkup C-SEC-7; `chat-model.md` "The digest budget"): the first N distinct people one
 * actor's mentions and hand-overs reach in a UTC day stay eligible for the email, later ones get the bell notice and never a mail;
 * the same person counts once; the day rolls over with the injected clock; the audit line is coalesced per actor and day. The writer
 * (`NoticeWriter`) is driven directly (a tiny budget, a settable clock); the boot validation is in `NumericConfigBoundsTest`, the route-level wiring is
 * the last test here.
 * Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows.
 */
class NotificationEmailBudgetTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Person(val id: UInt, val email: String)

    private class Sent(val to: String)

    private class RecordingMailer : Mailer {
        val sent = CopyOnWriteArrayList<Sent>()
        override suspend fun send(to: String, subject: String, body: String) {
            sent += Sent(to)
        }

        fun to(email: String) = sent.count { it.to == email }
    }

    /** A room with [actor] (an ADMIN account unless said otherwise: no exemption) as its owner and thread #1. */
    private inner class Scene(val actor: UInt, val room: UInt, val threadId: UInt, val number: Int) {
        suspend fun people(count: Int): List<Person> = (1..count).map {
            val email = uniqueEmail("eb")
            val id = TestUsers.seed(email, "pw", role = UserRole.USER, name = "Person $it").also { id -> mintedUsers += id }
            TestRooms.addMember(room, id)
            Person(id, email)
        }

        /** The actor posts a fresh message and mentions [recipients] in it, through the notice port, as a mention write does. */
        suspend fun mention(service: NoticeWriter, recipients: List<Person>, by: UInt = actor): List<UInt> {
            val messageId = TestThreads.post(room, number, by)
            return suspendTransaction(sharedDatabaseForTests()) {
                service.mentioned(MessageRef(room, threadId, number, messageId), by, recipients.map { it.id })
            }
        }
    }

    private suspend fun scene(role: UserRole = UserRole.ADMIN): Scene {
        val actor = TestUsers.seed(uniqueEmail("eb-act"), "pw", role = role, name = "Actor").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(actor, template, "Budget ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, actor)
        return Scene(actor, room, thread.id, thread.number)
    }

    private fun service(perDay: Int, clock: () -> Long = System::currentTimeMillis) =
        NoticeWriter(NotificationEmailBudget(perDay, clock))

    /** Every notice of [person]: (seen_at, emailed_at, created_at). */
    private fun notices(person: Person): List<Triple<Long?, Long?, Long>> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT seen_at, emailed_at, created_at FROM notifications WHERE recipient_id = ? ORDER BY id").use { st ->
            st.setLong(1, person.id.toLong())
            st.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) return@generateSequence null
                    val seen = rs.getLong(1).takeUnless { rs.wasNull() }
                    val emailed = rs.getLong(2).takeUnless { rs.wasNull() }
                    Triple(seen, emailed, rs.getLong(3))
                }.toList()
            }
        }
    }

    private fun mailable(person: Person) = notices(person).count { it.first == null && it.second == null }

    private fun budgetRows(actor: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notification_email_budget WHERE actor_id = ?").use { st ->
            st.setLong(1, actor.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun digest(mailer: Mailer, atMillis: Long) = NotificationDigest(
        database = sharedDatabaseForTests(),
        memberRooms = TestRooms.service::memberRooms,
        mailer = mailer,
        appUrl = "https://adder.example.com",
        delayMillis = 5 * MINUTE,
        minIntervalMillis = 15 * MINUTE,
        clock = { atMillis },
    )

    private fun ch.qos.logback.classic.spi.ILoggingEvent.isBudgetEventOf(actor: UInt) =
        message == EVENT && hasKeyValue("actorId", actor.toLong())

    @Test
    fun `under the budget every recipient is mailed`() = runBlocking {
        val s = scene(UserRole.USER)
        val people = s.people(2)
        val service = service(perDay = 2)

        assertEquals(people.map { it.id }.toSet(), s.mention(service, people).toSet())

        people.forEach { assertEquals(1, mailable(it), "eligible for the digest") }
        val mailer = RecordingMailer()
        digest(mailer, System.currentTimeMillis() + 6 * MINUTE).run()
        people.forEach { assertEquals(1, mailer.to(it.email), "mailed") }
    }

    @Test
    fun `over the budget the bell notice exists and is never mailed, and the first overrun is audited once`() = runBlocking {
        val s = scene() // an ADMIN actor: not exempt
        val people = s.people(4)
        val service = service(perDay = 2)

        withAuditCapture { capture ->
            val made = s.mention(service, people)
            assertEquals(people.map { it.id }.toSet(), made.toSet(), "every notice is made: the bell is untouched")
            val event = capture.awaitEvent { it.isBudgetEventOf(s.actor) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("budget", 2))
            assertTrue(event.hasKeyValue("withheld", 2))
            assertTrue(event.hasKeyValue("roomId", s.room.toLong()))
            // Further overruns the same day, in this write's followers and in later writes, are not audited again.
            s.mention(service, people)
            s.mention(service, s.people(1))
            assertEquals(1, capture.events.count { it.isBudgetEventOf(s.actor) }, "coalesced to one line per actor and day")
        }

        assertEquals(
            listOf(2, 2, 0, 0),
            people.map { mailable(it) },
            "the first two stay eligible (two notices each), the others are withheld",
        )
        people.drop(2).forEach { p ->
            val rows = notices(p)
            assertTrue(rows.isNotEmpty() && rows.all { it.first == null }, "a withheld notice is still unseen: the bell shows it")
        }
        val mailer = RecordingMailer()
        val atMillis = System.currentTimeMillis() + 6 * MINUTE
        digest(mailer, atMillis).run()
        digest(mailer, atMillis + 40 * MINUTE).run()
        assertEquals(listOf(1, 1, 0, 0), people.map { mailer.to(it.email) }, "the withheld are never mailed, now or later")
    }

    @Test
    fun `the same recipient counts once however many notices they get`() = runBlocking {
        val s = scene()
        val (a, b, c) = s.people(3)
        val service = service(perDay = 2)

        repeat(3) { s.mention(service, listOf(a)) }
        s.mention(service, listOf(b))
        s.mention(service, listOf(a, b))
        s.mention(service, listOf(c))

        assertEquals(4, mailable(a), "a: four notices, all eligible")
        assertEquals(2, mailable(b), "b: two notices, all eligible")
        assertEquals(0, mailable(c), "the third distinct person is over the budget of two")
        assertEquals(1, notices(c).size, "but has the notice")
    }

    @Test
    fun `the budget is per actor`() = runBlocking {
        val first = scene()
        val second = scene()
        val service = service(perDay = 1)
        val (a, b) = first.people(2)
        val (c, d) = second.people(2)

        first.mention(service, listOf(a, b))
        second.mention(service, listOf(c, d))

        assertEquals(1, mailable(a) + mailable(b))
        assertEquals(1, mailable(c) + mailable(d), "another actor's use of the day does not spend this one's")
    }

    @Test
    fun `the budget resets with the UTC day of the injected clock and is audited again`() = runBlocking {
        val s = scene()
        val (a, b, c) = s.people(3)
        var now = (System.currentTimeMillis() / DAY) * DAY + 3_600_000L
        val service = service(perDay = 1) { now }

        withAuditCapture { capture ->
            s.mention(service, listOf(a, b))
            assertNotNull(capture.awaitEvent { it.isBudgetEventOf(s.actor) })
            now += 23 * 3_600_000L - 1_000L // still the same UTC day
            s.mention(service, listOf(c))
            assertEquals(1, mailable(a) + mailable(b), "one of the first two")
            assertEquals(0, mailable(c), "same day: over the budget")
            assertEquals(1, capture.events.count { it.isBudgetEventOf(s.actor) })

            now += 2_000L // past midnight UTC
            s.mention(service, listOf(c))
            assertEquals(1, mailable(c), "the new day starts with a fresh budget")
            s.mention(service, listOf(a, b))
            val events = capture.events.count { it.isBudgetEventOf(s.actor) }
            assertEquals(2, events, "the overrun of the new day is audited again")
        }
        assertEquals(2, budgetRows(s.actor), "one row per actor and day")
        suspendTransaction(sharedDatabaseForTests()) { NotificationEmailBudget(1) { now }.purgeBefore(now) }
        assertEquals(1, budgetRows(s.actor), "the days before the cutoff's are purged, the current one stays")
    }

    @Test
    fun `one actor writing concurrently never exceeds the budget`() = runBlocking {
        val s = scene()
        val people = s.people(6)
        val service = service(perDay = 2)
        val messages = people.map { TestThreads.post(s.room, s.number, s.actor) }
        // Every write is inside its open transaction before any of them makes its notices, so the six really contend for the row.
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val allInside = CompletableDeferred<Unit>()

        coroutineScope {
            people.indices.map { i ->
                async(Dispatchers.IO) {
                    suspendTransaction(sharedDatabaseForTests()) {
                        if (inside.incrementAndGet() == people.size) allInside.complete(Unit)
                        withTimeout(30_000) { allInside.await() }
                        service.mentioned(MessageRef(s.room, s.threadId, s.number, messages[i]), s.actor, listOf(people[i].id))
                    }
                }
            }.awaitAll()
        }
        assertEquals(people.size, inside.get())

        assertEquals(2, people.sumOf { mailable(it) }, "the actor's row lock serialises the writes: exactly the budget is admitted")
        assertEquals(6, people.sumOf { notices(it).size })
    }

    @Test
    fun `leaving and being added again does not reset the budget`() = runBlocking {
        val s = scene()
        val (a, b) = s.people(2)
        val service = service(perDay = 1)

        s.mention(service, listOf(a))
        assertEquals(1, mailable(a))
        removeMember(s.room, a)
        assertEquals(0, notices(a).size, "the leaver's notices go with the membership")
        s.mention(service, listOf(b))

        assertEquals(0, mailable(b), "the removed person's place in the budget is kept: the second person is over it")
        assertEquals(1, notices(b).size, "and has the bell notice")
        TestRooms.addMember(s.room, a.id)
        s.mention(service, listOf(a))
        assertEquals(1, mailable(a), "the rejoiner is still the admitted one")
    }

    private fun removeMember(room: UInt, person: Person) {
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.setLong(2, person.id.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }
    }

    @Test
    fun `a hand-over spends the same budget as a mention`() = runBlocking {
        val s = scene()
        val (a, b) = s.people(2)
        val service = service(perDay = 1)

        suspendTransaction(sharedDatabaseForTests()) { service.ownerAssigned(s.room, s.threadId, s.number, s.actor, a.id) }
        suspendTransaction(sharedDatabaseForTests()) { service.ownerAssigned(s.room, s.threadId, s.number, s.actor, b.id) }

        assertEquals(1, mailable(a))
        assertEquals(0, mailable(b))
        assertEquals(1, notices(b).size, "the hand-over notice is still made")
        val withheld = notices(b).single()
        assertNull(withheld.first, "unseen: the bell shows it")
        assertEquals(withheld.third, withheld.second, "withheld = settled at its own instant")
    }

    @Test
    fun `a mention over the configured budget makes a bell notice and no mail eligibility through the routes`() = testApplication {
        configureApp("notifications.email.maxRecipientsPerActorPerDay" to "1")
        startApplication()
        val owner = seedActor("eb-http-own").also { mintedUsers += it.id }
        val ann = seedActor("eb-http-ann").also { mintedUsers += it.id }
        val bob = seedActor("eb-http-bob").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Http ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, ann.id)
        TestRooms.addMember(room, bob.id)
        TestThreads.seed(room, owner.id)

        val response = owner.client.postJson("/api/v1/rooms/$room/threads/1/messages", MessageRequest("hi <@${ann.id}> <@${bob.id}>"))
        assertEquals(HttpStatusCode.Created, response.status)

        for (person in listOf(ann, bob)) {
            val page = person.client.get("/api/v1/notifications").body<NotificationPageResponse>()
            assertEquals(1L, page.total, "both have the bell notice")
        }
        val eligible = listOf(ann, bob).sumOf { p ->
            PostgresTestSupport.connect().use { c ->
                c.prepareStatement("SELECT count(*) FROM notifications WHERE recipient_id = ? AND emailed_at IS NULL").use { st ->
                    st.setLong(1, p.id.toLong())
                    st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }
        }
        assertEquals(1, eligible, "only one of the two is eligible for the digest")
    }
}
