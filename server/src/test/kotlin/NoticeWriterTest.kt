package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationEmailBudget
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MAX_AUDITED_ADDRESSED
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeOutcome
import ch.nokillswit.threads.NoticeReason
import ch.nokillswit.threads.NoticeRevision
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The `ThreadNotices` port as `NoticeWriter` implements it (`chat-model.md` "Notice kinds (V27, V28)"), driven directly with every reason
 * (S5 still makes only two kinds from the routes; the others are made by the audience kernel of the next steps): one notice per person and
 * event (the first reason wins, never the actor), the channels judged at write time, the edit operations, and ONE hint to the people whose
 * notice shows in the app. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows.
 */
class NoticeWriterTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Scene(val actor: UInt, val room: UInt, val threadId: UInt, val number: Int, val people: List<UInt>)

    private suspend fun scene(people: Int): Scene {
        val actor = TestUsers.seed(uniqueEmail("nw-act"), "pw", role = UserRole.USER, name = "Actor").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(actor, template, "Writer ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, actor)
        val ids = (1..people).map { n ->
            TestUsers.seed(uniqueEmail("nw"), "pw", role = UserRole.USER, name = "Person $n").also {
                mintedUsers += it
                TestRooms.addMember(room, it)
            }
        }
        return Scene(actor, room, thread.id, thread.number, ids)
    }

    private fun Scene.ref(messageId: UInt) = MessageRef(room, threadId, number, messageId)

    private suspend fun <T> inWrite(block: suspend () -> T): T = suspendTransaction(sharedDatabaseForTests()) { block() }

    /** (kind, in_app, emailed_at is stamped) of every notice of [userId] in the scene's room. */
    private fun rows(s: Scene, userId: UInt): List<Triple<String, Boolean, Boolean>> = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT notification_type, in_app, emailed_at IS NOT NULL FROM notifications " +
            "WHERE recipient_id = ? AND room_id = ? ORDER BY id"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, userId.toLong())
            st.setInt(2, s.room.toInt())
            st.executeQuery().use { rs ->
                generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)) else null }.toList()
            }
        }
    }

    @Test
    fun `every reason is made as its kind, a person once with the first reason, never the actor`() = runBlocking {
        val s = scene(NoticeReason.entries.size + 1)
        val notices = NoticeReason.entries.mapIndexed { i, reason -> Notice(s.people[i], reason) }
        val noisy = notices + Notice(s.people[0], NoticeReason.THREAD_CLOSED) + Notice(s.actor, NoticeReason.MENTIONED)

        val outcome = inWrite { TestThreads.noticeWriter.forThread(s.room, s.threadId, s.number, s.actor, noisy) }

        assertEquals(NoticeReason.entries.size, outcome.recipients)
        assertEquals(NoticeReason.entries.associateWith { 1 }, outcome.kinds, "one notice per reason, a repeat of a person adds none")
        assertEquals(
            listOf(s.people[NoticeReason.MENTIONED.ordinal], s.people[NoticeReason.THREAD_ASSIGNED.ordinal]),
            outcome.addressed,
            "only the people named themselves are listed for the audit",
        )
        NoticeReason.entries.forEachIndexed { i, reason ->
            assertEquals(listOf(reason.name), rows(s, s.people[i]).map { it.first }, "$reason is its kind")
        }
        assertEquals(emptyList(), rows(s, s.actor), "the actor is never told")
        assertEquals(NoticeOutcome.NONE, inWrite { TestThreads.noticeWriter.forThread(s.room, s.threadId, s.number, s.actor, emptyList()) })
    }

    @Test
    fun `a notice of a message is made once whatever the reason, and a repeat makes none`() = runBlocking {
        val s = scene(1)
        val message = TestThreads.post(s.room, s.number, s.actor)
        val writer = TestThreads.noticeWriter

        val first = inWrite { writer.forMessage(s.ref(message), s.actor, listOf(Notice(s.people[0], NoticeReason.EVERYONE_MENTIONED))) }
        val again = inWrite { writer.forMessage(s.ref(message), s.actor, listOf(Notice(s.people[0], NoticeReason.MENTIONED))) }

        assertEquals(1, first.recipients)
        assertEquals(NoticeOutcome.NONE, again, "one notice per message and person: the unique index (V27) refuses the second kind")
        assertEquals(listOf("EVERYONE_MENTIONED"), rows(s, s.people[0]).map { it.first })
        assertEquals(mapOf(s.people[0] to NoticeReason.EVERYONE_MENTIONED), inWrite { writer.ofMessage(message) })
    }

    @Test
    fun `the channels are judged per recipient and kind`() = runBlocking {
        val s = scene(4)
        val (appOnly, both, mailOnly) = s.people
        val neither = s.people[3]
        val kind = NotificationType.THREAD_REPLIED
        TestNotificationPreferences.set(both, kind, NotificationChannel.EMAIL, enabled = true)
        TestNotificationPreferences.set(mailOnly, kind, NotificationChannel.EMAIL, enabled = true)
        TestNotificationPreferences.set(mailOnly, kind, NotificationChannel.IN_APP, enabled = false)
        TestNotificationPreferences.set(neither, kind, NotificationChannel.IN_APP, enabled = false)
        val message = TestThreads.post(s.room, s.number, s.actor)

        val outcome = inWrite {
            TestThreads.noticeWriter.forMessage(s.ref(message), s.actor, s.people.map { Notice(it, NoticeReason.THREAD_REPLIED) })
        }

        assertEquals(3, outcome.recipients, "a person with both channels off gets no row")
        assertEquals(listOf(Triple("THREAD_REPLIED", true, true)), rows(s, appOnly), "email off: settled at insert")
        assertEquals(listOf(Triple("THREAD_REPLIED", true, false)), rows(s, both), "both on: the bell and the digest")
        assertEquals(listOf(Triple("THREAD_REPLIED", false, false)), rows(s, mailOnly), "the app off, email on: a mail-only row")
        assertEquals(emptyList(), rows(s, neither))
    }

    private fun budgetRows(actor: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notification_email_budget WHERE actor_id = ?").use { st ->
            st.setLong(1, actor.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a person the notice cannot reach gets no row - the app off and the email over the budget`() = runBlocking {
        val s = scene(4)
        val (admitted, overButApp, overNoApp) = s.people
        val kind = NotificationType.THREAD_CLOSED
        for (person in s.people.take(3)) TestNotificationPreferences.set(person, kind, NotificationChannel.EMAIL, enabled = true)
        TestNotificationPreferences.set(admitted, kind, NotificationChannel.IN_APP, enabled = false) // mail-only, inside the budget
        TestNotificationPreferences.set(overNoApp, kind, NotificationChannel.IN_APP, enabled = false) // mail-only, but over the budget
        val writer = NoticeWriter(NotificationEmailBudget(1) { System.currentTimeMillis() })

        val outcome = inWrite {
            val notices = listOf(admitted, overButApp, overNoApp).map { Notice(it, NoticeReason.THREAD_CLOSED) }
            writer.forThread(s.room, s.threadId, s.number, s.actor, notices)
        }

        assertEquals(2, outcome.recipients, "the third has nothing to be reached by")
        assertEquals(listOf(Triple("THREAD_CLOSED", false, false)), rows(s, admitted), "inside the budget: the mail-only row, mailable")
        val bellOnly = listOf(Triple("THREAD_CLOSED", true, true))
        assertEquals(bellOnly, rows(s, overButApp), "over the budget with the app on: the bell notice, settled")
        assertEquals(emptyList(), rows(s, overNoApp), "over the budget with the app off: no row at all")
    }

    @Test
    fun `with no mailer email is never a reason for a row and spends no budget`() = runBlocking {
        val s = scene(2)
        val (appOn, appOff) = s.people
        for (person in s.people) {
            TestNotificationPreferences.set(person, NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL, enabled = true)
        }
        TestNotificationPreferences.set(appOff, NotificationType.THREAD_CLOSED, NotificationChannel.IN_APP, enabled = false)
        val writer = NoticeWriter(NotificationEmailBudget(5) { System.currentTimeMillis() }, mailAvailable = false)

        val outcome = inWrite {
            writer.forThread(s.room, s.threadId, s.number, s.actor, s.people.map { Notice(it, NoticeReason.THREAD_CLOSED) })
        }

        assertEquals(1, outcome.recipients)
        assertEquals(listOf(Triple("THREAD_CLOSED", true, true)), rows(s, appOn), "the bell notice, settled: no digest runs for it")
        assertEquals(emptyList(), rows(s, appOff))
        assertEquals(0, budgetRows(s.actor), "nothing was mailable, so the budget was not touched")
    }

    @Test
    fun `one hint reaches the people whose notice shows in the app and none when nobody's does`() = runBlocking {
        val s = scene(3)
        val (shown, hidden, none) = s.people
        TestNotificationPreferences.set(hidden, NotificationType.THREAD_CLOSED, NotificationChannel.IN_APP, enabled = false)
        TestNotificationPreferences.set(hidden, NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL, enabled = true)
        TestNotificationPreferences.set(none, NotificationType.THREAD_CLOSED, NotificationChannel.IN_APP, enabled = false)

        NotifyCapture().use { capture ->
            val hints = capture.during {
                inWrite {
                    TestThreads.noticeWriter.forThread(
                        s.room, s.threadId, s.number, s.actor, s.people.map { Notice(it, NoticeReason.THREAD_CLOSED) },
                    )
                }
                inWrite {
                    val silenced = listOf(Notice(none, NoticeReason.THREAD_CLOSED))
                    TestThreads.noticeWriter.forThread(s.room, s.threadId, s.number, s.actor, silenced)
                }
            }
            assertEquals(1, hints.size, "one hint per event, none for an event nobody sees")
            assertEquals(LiveKind.NOTIFICATION_CREATED, hints.single().kind)
            assertEquals(listOf(shown), hints.single().targetUserIds, "the mail-only person and the silenced one are not targets")
            assertEquals(s.number, hints.single().threadNumber)
            assertEquals(null, hints.single().messageId, "a thread-level notice names no message")
        }
        assertEquals(1, rows(s, hidden).size, "the mail-only row exists all the same")
    }

    @Test
    fun `an edit removes, relabels in place and adds`() = runBlocking {
        val s = scene(4)
        val (removed, relabelled, kept) = s.people
        val added = s.people[3]
        val message = TestThreads.post(s.room, s.number, s.actor)
        val writer = TestThreads.noticeWriter
        inWrite {
            writer.forMessage(
                s.ref(message),
                s.actor,
                listOf(removed, relabelled, kept).map { Notice(it, NoticeReason.EVERYONE_MENTIONED) },
            )
        }
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE notifications SET seen_at = 5 WHERE message_id = ? AND recipient_id = ?").use { st ->
                st.setInt(1, message.toInt())
                st.setLong(2, relabelled.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }

        val outcome = inWrite {
            writer.reviseMessage(
                s.ref(message),
                s.actor,
                NoticeRevision(
                    add = listOf(Notice(added, NoticeReason.MENTIONED)),
                    relabel = listOf(Notice(relabelled, NoticeReason.THREAD_REPLIED)),
                    remove = listOf(removed),
                ),
            )
        }

        assertEquals(NoticeOutcome(1, mapOf(NoticeReason.MENTIONED to 1), listOf(added)), outcome, "only the added one is MADE")
        assertEquals(
            mapOf(
                relabelled to NoticeReason.THREAD_REPLIED,
                kept to NoticeReason.EVERYONE_MENTIONED,
                added to NoticeReason.MENTIONED,
            ),
            inWrite { writer.ofMessage(message) },
        )
        assertEquals(emptyList(), rows(s, removed), "removed")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT seen_at FROM notifications WHERE message_id = ? AND recipient_id = ?").use { st ->
                st.setInt(1, message.toInt())
                st.setLong(2, relabelled.toLong())
                st.executeQuery().use { rs -> rs.next(); assertEquals(5L, rs.getLong(1), "a relabel keeps the row and its seen state") }
            }
        }
        assertEquals(NoticeOutcome.NONE, inWrite { writer.reviseMessage(s.ref(message), s.actor, NoticeRevision()) })
        inWrite { writer.messageRemoved(message) }
        assertEquals(emptyMap(), inWrite { writer.ofMessage(message) })
    }

    @Test
    fun `a notice of a kind this build does not know is left out of ofMessage`() = runBlocking {
        val s = scene(1)
        val message = TestThreads.post(s.room, s.number, s.actor)
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at) " +
                    "VALUES (?, ?, 'A_FUTURE_KIND', ?, ?, ?, 1)",
            ).use { st ->
                st.setLong(1, s.people[0].toLong())
                st.setInt(2, s.room.toInt())
                st.setLong(3, s.actor.toLong())
                st.setInt(4, s.threadId.toInt())
                st.setInt(5, message.toInt())
                st.executeUpdate()
            }
        }
        assertEquals(emptyMap(), inWrite { TestThreads.noticeWriter.ofMessage(message) })
    }

    @Test
    fun `the audit names at most 21 people addressed by name, however many were told`() = runBlocking {
        val s = scene(MAX_AUDITED_ADDRESSED + 4)
        val message = TestThreads.post(s.room, s.number, s.actor)

        val outcome = inWrite {
            TestThreads.noticeWriter.forMessage(s.ref(message), s.actor, s.people.map { Notice(it, NoticeReason.MENTIONED) })
        }

        assertEquals(s.people.size, outcome.recipients)
        assertEquals(s.people.take(MAX_AUDITED_ADDRESSED), outcome.addressed)
        assertTrue(outcome.kinds.keys == setOf(NoticeReason.MENTIONED))
    }

    @Test
    fun `outcomes add up, an addressed person named once`() {
        val a = NoticeOutcome(2, mapOf(NoticeReason.MENTIONED to 2), listOf(1u, 2u))
        val b = NoticeOutcome(1, mapOf(NoticeReason.MENTIONED to 1, NoticeReason.THREAD_CREATED to 1), listOf(2u))
        assertEquals(NoticeOutcome(3, mapOf(NoticeReason.MENTIONED to 3, NoticeReason.THREAD_CREATED to 1), listOf(1u, 2u)), a + b)
        assertEquals(a, a + NoticeOutcome.NONE)
        assertEquals(a, NoticeOutcome.NONE + a)
    }
}
