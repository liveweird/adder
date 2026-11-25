package ch.nokillswit

import ch.nokillswit.infra.mail.LogMailer
import ch.nokillswit.infra.mail.Mailer
import ch.nokillswit.notifications.DIGEST_BATCH
import ch.nokillswit.notifications.DIGEST_MAX_ENTRIES
import ch.nokillswit.notifications.DIGEST_MAX_NOTICES_PER_RECIPIENT
import ch.nokillswit.notifications.DIGEST_SCAN_CAP
import ch.nokillswit.notifications.DigestBounds
import ch.nokillswit.notifications.DIGEST_STATEMENT_TIMEOUT_SECONDS
import ch.nokillswit.notifications.peopleToClaim
import ch.nokillswit.notifications.DIGEST_MAX_PER_RUN
import ch.nokillswit.notifications.NotificationChannel
import ch.nokillswit.notifications.NotificationDigest
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.notifications.NotificationSweep
import ch.nokillswit.users.UserRole
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

private const val SECOND = 1_000L
private const val DELAY_SECONDS = 300L
private const val INTERVAL_SECONDS = 900L
private const val DAY = 86_400_000L
private const val APP_URL = "https://adder.example.com"

/**
 * The email digest of unseen notices (`NotificationDigest`, `chat-model.md` "Notifications (V18)"). The digest runs against the shared
 * database with an injected clock and a recording mailer; notices are raw-inserted with chosen `created_at` stamps (a real mention is
 * always made now). Every assertion is about this test's own recipients, found by email: the database is shared suite state, and rooms and
 * users are unique rows removed afterwards.
 */
class NotificationDigestTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Sent(val to: String, val subject: String, val body: String)

    private class RecordingMailer(private val failWith: Exception? = null) : Mailer {
        val sent = CopyOnWriteArrayList<Sent>()
        var attempts = 0

        override suspend fun send(to: String, subject: String, body: String) {
            attempts++
            failWith?.let { throw it }
            sent += Sent(to, subject, body)
        }

        fun to(email: String): List<Sent> = sent.filter { it.to == email }
    }

    private class Person(val id: UInt, val email: String)

    /** A room with an owner (the actor of every notice), a thread whose title and body carry [secret], and the clock's instant. */
    private inner class Setup(
        val owner: Person,
        val room: UInt,
        val roomName: String,
        val number: Int,
        val thread: UInt,
        val title: String,
        val body: String,
    ) {
        /** Content a mail must never carry. */
        val secrets get() = listOf(title, body)

        val now = System.currentTimeMillis()

        fun removeMember(person: Person) {
            sql("DELETE FROM room_members WHERE room_id = ? AND user_id = ?") { st ->
                st.setInt(1, room.toInt())
                st.setLong(2, person.id.toLong())
                st.executeUpdate()
            }
        }

        suspend fun member(language: String = "en", name: String = "Ann", optedIn: Boolean = true): Person {
            val email = uniqueEmail("dg")
            val suffix = UUID.randomUUID().toString().substring(0, 4)
            val id = TestUsers.seed(email, "pw", role = UserRole.USER, name = "$name-$suffix", language = language)
            mintedUsers += id
            TestRooms.addMember(room, id)
            // Email is off by default for every kind (1.1 D7): the people of this class opted in, as a 1.0.0 account never has.
            if (optedIn) TestNotificationPreferences.optInEmail(id)
            return Person(id, email)
        }

        /** A raw notice for [to] made [ageMillis] before [now]; answers its id. */
        fun notice(
            to: Person,
            ageMillis: Long = (DELAY_SECONDS + 1) * SECOND,
            type: String = "THREAD_ASSIGNED",
            messageId: Int? = null,
        ): Long =
            sql("INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id") { st ->
                st.setLong(1, to.id.toLong())
                st.setInt(2, room.toInt())
                st.setString(3, type)
                st.setLong(4, owner.id.toLong())
                if (type == NotificationType.ROOM_ADDED.name) st.setNull(5, java.sql.Types.INTEGER) else st.setInt(5, thread.toInt())
                if (messageId == null) st.setNull(6, java.sql.Types.INTEGER) else st.setInt(6, messageId)
                st.setLong(7, now - ageMillis)
                st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
    }

    private fun <T> sql(statement: String, block: (java.sql.PreparedStatement) -> T): T =
        PostgresTestSupport.connect().use { c: Connection -> c.prepareStatement(statement).use(block) }

    private fun markSeen(id: Long, at: Long) {
        sql("UPDATE notifications SET seen_at = ? WHERE id = ?") { st -> st.setLong(1, at); st.setLong(2, id); st.executeUpdate() }
    }

    private fun ch.qos.logback.classic.spi.ILoggingEvent.everything() = "$formattedMessage $keyValuePairs $throwableProxy"

    private fun emailedAt(id: Long): Long? = sql("SELECT emailed_at FROM notifications WHERE id = ?") { st ->
        st.setLong(1, id)
        st.executeQuery().use { rs -> rs.next(); rs.getLong(1).takeUnless { rs.wasNull() } }
    }

    private suspend fun setup(): Setup {
        val email = uniqueEmail("dg-own")
        val ownerId = TestUsers.seed(email, "pw", role = UserRole.USER, name = "Olga").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val roomName = "Digest ${UUID.randomUUID().toString().substring(0, 8)}"
        val room = TestRooms.seed(ownerId, template, roomName).also { mintedRooms += it }
        val title = "Title SECRET-${UUID.randomUUID()}"
        val body = "Body SECRET-${UUID.randomUUID()}"
        val thread = TestThreads.seed(room, ownerId, title = title, body = body)
        return Setup(Person(ownerId, email), room, roomName, thread.number, thread.id, title, body)
    }

    private fun digest(
        mailer: Mailer?,
        s: Setup,
        now: () -> Long = { s.now },
        appUrl: String? = APP_URL,
        batchSize: Int = DIGEST_BATCH,
        maxPerRun: Int = DIGEST_MAX_PER_RUN,
        afterClaim: suspend () -> Unit = {},
        scanCap: Int = DIGEST_SCAN_CAP,
        statementTimeoutSeconds: Int = DIGEST_STATEMENT_TIMEOUT_SECONDS,
        failMemberRooms: (suspend (UInt) -> List<ch.nokillswit.rooms.RoomSummary>)? = null,
    ) = NotificationDigest(
        database = sharedDatabaseForTests(),
        memberRooms = failMemberRooms ?: TestRooms.service::memberRooms,
        mailer = mailer,
        appUrl = appUrl,
        delayMillis = DELAY_SECONDS * SECOND,
        minIntervalMillis = INTERVAL_SECONDS * SECOND,
        clock = now,
        batchSize = batchSize,
        maxPerRun = maxPerRun,
        afterClaim = afterClaim,
        bounds = DigestBounds(scanCap, statementTimeoutSeconds),
    )

    @Test
    fun `an unseen notice older than the delay is mailed once`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val id = s.notice(ann)
        val mailer = RecordingMailer()
        val digest = digest(mailer, s)

        digest.run()
        digest.run()
        digest(mailer, s).run()

        assertEquals(1, mailer.to(ann.email).size, "mailed once, by this process and by another one")
        assertNotNull(emailedAt(id), "the claim is stamped")
        val mail = mailer.to(ann.email).single()
        assertTrue(mail.body.contains("Olga"), "who")
        assertTrue(mail.body.contains(s.roomName), "the room")
        assertTrue(mail.body.contains("#${s.number}"), "the thread number")
        assertTrue(mail.body.contains("$APP_URL/rooms/${s.room}/threads/${s.number}"), "the link, from the configured origin")
        s.secrets.forEach { assertFalse(mail.body.contains(it) || mail.subject.contains(it), "never the thread title or any message text") }
    }

    @Test
    fun `a seen notice and a younger one are not mailed`() = runBlocking {
        val s = setup()
        val seen = s.member()
        val young = s.member()
        val seenId = s.notice(seen)
        sql("UPDATE notifications SET seen_at = ? WHERE id = ?") { st -> st.setLong(1, s.now); st.setLong(2, seenId); st.executeUpdate() }
        val youngId = s.notice(young, ageMillis = (DELAY_SECONDS - 1) * SECOND)
        val mailer = RecordingMailer()
        var clock = s.now

        digest(mailer, s, now = { clock }).run()
        assertTrue(mailer.to(seen.email).isEmpty() && mailer.to(young.email).isEmpty())
        assertNull(emailedAt(seenId), "a seen notice is never claimed")
        assertNull(emailedAt(youngId), "a young notice is left alone, not claimed")

        clock = s.now + 2 * SECOND
        digest(mailer, s, now = { clock }).run()
        assertEquals(1, mailer.to(young.email).size, "it is mailed once it is old enough")
        assertTrue(mailer.to(seen.email).isEmpty())
    }

    @Test
    fun `many notices of one person are one email`() = runBlocking {
        val s = setup()
        val ann = s.member()
        repeat(3) { s.notice(ann, ageMillis = (DELAY_SECONDS + 10 + it) * SECOND) }
        s.notice(ann, type = "MENTIONED")
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        val mail = mailer.to(ann.email).single()
        assertEquals("Adder: 4 unseen notifications", mail.subject)
        assertEquals(4, mail.body.lines().count { it.startsWith("- ") })
    }

    @Test
    fun `a person mailed within the interval waits, then gets everything in one`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val first = s.notice(ann, ageMillis = (DELAY_SECONDS + 5) * SECOND)
        val mailer = RecordingMailer()
        var clock = s.now
        val digest = digest(mailer, s, now = { clock })

        digest.run()
        assertEquals(1, mailer.to(ann.email).size)
        assertNotNull(emailedAt(first))

        // Two more become due while the person is inside the interval: left unclaimed.
        clock = s.now + 60 * SECOND
        val second = sql("INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
            "VALUES (?, ?, 'THREAD_ASSIGNED', ?, ?, ?) RETURNING id") { st ->
            st.setLong(1, ann.id.toLong()); st.setInt(2, s.room.toInt()); st.setLong(3, s.owner.id.toLong()); st.setInt(4, s.thread.toInt())
            st.setLong(5, clock - (DELAY_SECONDS + 1) * SECOND)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        digest.run()
        assertEquals(1, mailer.to(ann.email).size, "inside the interval: nothing more")
        assertNull(emailedAt(second), "and nothing claimed")

        clock = s.now + INTERVAL_SECONDS * SECOND
        digest.run()
        val mails = mailer.to(ann.email)
        assertEquals(2, mails.size, "the interval is over: one more email")
        assertEquals("Adder: 1 unseen notification", mails.last().subject)
        assertTrue(emailedAt(second) != null)
    }

    @Test
    fun `a switched-off kind is skipped and never mailed later`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val ben = s.member()
        val skipped = s.notice(ann, type = "THREAD_ASSIGNED")
        s.notice(ben, type = "THREAD_ASSIGNED")
        s.notice(ben, type = "MENTIONED")
        for ((user, type) in listOf(ann to "THREAD_ASSIGNED", ben to "THREAD_ASSIGNED")) {
            switchOff(user, type)
        }
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertTrue(mailer.to(ann.email).isEmpty(), "the only kind is off: no email")
        assertNotNull(emailedAt(skipped), "it is claimed, so it is settled")
        val ben1 = mailer.to(ben.email).single()
        assertEquals(1, ben1.body.lines().count { it.startsWith("- ") }, "the other kind still goes")
        assertTrue(ben1.body.contains("mentioned you"))

        TestNotificationPreferences.set(ann.id, NotificationType.THREAD_ASSIGNED, NotificationChannel.EMAIL, enabled = true)
        digest(mailer, s, now = { s.now + INTERVAL_SECONDS * SECOND }).run()
        assertTrue(mailer.to(ann.email).isEmpty(), "switching the kind on again does not resurrect a settled notice")
    }

    @Test
    fun `an opted in room membership digest links to the room`() = runBlocking {
        val s = setup()
        val ann = s.member(optedIn = false)
        TestNotificationPreferences.set(ann.id, NotificationType.ROOM_ADDED, NotificationChannel.EMAIL, enabled = true)
        val notice = s.notice(ann, type = NotificationType.ROOM_ADDED.name)
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        val mail = mailer.to(ann.email).single()
        assertTrue(mail.body.contains("Olga added you to"))
        assertEquals("  $APP_URL/rooms/${s.room}", mail.body.lines().first { it.startsWith("  $APP_URL") })
        assertTrue(emailedAt(notice) != null)
    }

    @Test
    fun `a kind that is off by default is never due, and an opt-in is mailed`() = runBlocking {
        val s = setup()
        val ann = s.member(optedIn = false)
        val ben = s.member(optedIn = false)
        TestNotificationPreferences.set(ben.id, NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL, enabled = true)
        val annIds = NotificationType.entries.map { s.notice(ann, type = it.name) }
        val benClosed = s.notice(ben, type = "THREAD_CLOSED")
        val benReplied = s.notice(ben, type = "THREAD_REPLIED")
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertTrue(mailer.to(ann.email).isEmpty(), "no stored row: the default is off for every kind, so nothing is mailed")
        assertTrue(annIds.all { emailedAt(it) != null }, "and every one of them is settled, so none fills a scan")
        val mail = mailer.to(ben.email).single()
        assertEquals(1, mail.body.lines().count { it.startsWith("- ") }, "only the kind the person opted in to")
        assertTrue(mail.body.contains("closed #${s.number}"))
        assertNotNull(emailedAt(benClosed))
        assertTrue(emailedAt(benReplied) != null, "the other kind is settled, never mailed later")
    }

    @Test
    fun `a switch-off is honoured before the claim and between the claim and the send`() = runBlocking {
        val s = setup()
        val before = s.member()
        val between = s.member()
        val stays = s.member()
        s.notice(before, type = "THREAD_CLOSED")
        s.notice(between, type = "THREAD_CLOSED")
        s.notice(stays, type = "THREAD_CLOSED")
        TestNotificationPreferences.set(before.id, NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL, enabled = false)
        val mailer = RecordingMailer()

        digest(mailer, s, afterClaim = {
            TestNotificationPreferences.set(between.id, NotificationType.THREAD_CLOSED, NotificationChannel.EMAIL, enabled = false)
        }).run()

        assertTrue(mailer.to(before.email).isEmpty(), "switched off before the claim")
        assertTrue(mailer.to(between.email).isEmpty(), "switched off after the claim: the send-time re-check reads the switch")
        assertEquals(1, mailer.to(stays.email).size)
    }

    @Test
    fun `a notice of a kind this build does not know is due, claimed and dropped, never mailed`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val future = s.notice(ann, type = "A_FUTURE_KIND")
        val known = s.notice(ann, type = "THREAD_ASSIGNED")
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertNotNull(emailedAt(future), "it is claimed with the person's other notices (the rule of the rollback notes)")
        val mail = mailer.to(ann.email).single()
        assertEquals(1, mail.body.lines().count { it.startsWith("- ") }, "but it is not an entry of the mail")
        assertEquals("Adder: 1 unseen notification", mail.subject)
        assertTrue(emailedAt(known) != null)
    }

    @Test
    fun `a mail-only notice is mailed like any other`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val id = s.notice(ann)
        sql("UPDATE notifications SET in_app = false WHERE id = ?") { st -> st.setLong(1, id); st.executeUpdate() }
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertEquals(1, mailer.to(ann.email).size, "in_app = false hides it from the bell, not from the digest")
    }

    @Test
    fun `a removed member and a deleted account get nothing`() = runBlocking {
        val s = setup()
        val removed = s.member()
        val deleted = s.member()
        val deletedRoom = s.member()
        val lostAfterClaim = s.member()
        val deletedAfterClaim = s.member()
        val seenAfterClaim = s.member()
        val kept = s.member()
        s.notice(removed)
        s.notice(deleted)
        s.notice(deletedRoom)
        s.notice(lostAfterClaim)
        s.notice(deletedAfterClaim)
        val seenId = s.notice(seenAfterClaim)
        s.notice(kept)
        // Leaving a room deletes the notices of the membership (the schema's leak rule): there is nothing left to claim.
        s.removeMember(removed)
        TestUsers.softDelete(deleted.id)
        val mailer = RecordingMailer()

        digest(mailer, s, afterClaim = {
            // After the claim, before the send: the membership goes, an account is deleted, a notice is seen.
            s.removeMember(lostAfterClaim)
            TestUsers.softDelete(deletedAfterClaim.id)
            markSeen(seenId, s.now)
        }).run()

        for (person in listOf(removed, deleted, lostAfterClaim, deletedAfterClaim, seenAfterClaim)) {
            assertTrue(mailer.to(person.email).isEmpty(), "no email for ${person.email}")
        }
        assertEquals(1, mailer.to(kept.email).size, "the member who stayed is mailed")
    }

    @Test
    fun `a deleted room is not mailed and an archived one is`() = runBlocking {
        val s = setup()
        val ann = s.member()
        s.notice(ann)
        TestRooms.archive(s.room)
        val archived = RecordingMailer()
        digest(archived, s).run()
        assertEquals(1, archived.to(ann.email).size, "an archived room still mails")

        val ben = s.member()
        s.notice(ben)
        sql("UPDATE rooms SET marked_as_deleted = true WHERE id = ?") { st -> st.setInt(1, s.room.toInt()); st.executeUpdate() }
        val deleted = RecordingMailer()
        digest(deleted, s).run()
        assertTrue(deleted.to(ben.email).isEmpty(), "a deleted room is nobody's to mail")
    }

    private fun deleteRoom(room: UInt) {
        sql("UPDATE rooms SET marked_as_deleted = true WHERE id = ?") { st -> st.setInt(1, room.toInt()); st.executeUpdate() }
    }

    @Test
    fun `notices of a deleted room at the head do not hide the live ones behind them`() = runBlocking {
        val dead = setup()
        val live = setup()
        val ann = dead.member()
        TestRooms.addMember(live.room, ann.id)
        val deadIds = List(60) { i -> dead.notice(ann, ageMillis = DAY - 10 * SECOND - i * 10L) }
        val liveIds = List(3) { live.notice(ann) }
        deleteRoom(dead.room)
        val mailer = RecordingMailer()

        val run = digest(mailer, dead).run()

        assertEquals(63, run.claimed)
        val mail = mailer.to(ann.email).single()
        assertEquals("Adder: 3 unseen notifications", mail.subject)
        assertEquals(3, mail.body.lines().count { it.startsWith("- ") }, mail.body)
        assertTrue(mail.body.contains(live.roomName), "the live room is named")
        assertFalse(mail.body.contains(dead.roomName), "the deleted room is not")
        assertFalse(mail.body.contains("more"), "nothing is unlisted")
        assertTrue((deadIds + liveIds).all { emailedAt(it) != null }, "the whole claim stays stamped")
    }

    @Test
    fun `the count of the unlisted excludes notices of a deleted room and notices seen meanwhile`() = runBlocking {
        val live = setup()
        val dead = setup()
        val ann = live.member()
        TestRooms.addMember(dead.room, ann.id)
        val liveIds = List(12) { i -> live.notice(ann, ageMillis = DAY - 10 * SECOND - i * 10L) }
        repeat(40) { dead.notice(ann) }
        deleteRoom(dead.room)
        val mailer = RecordingMailer()

        digest(mailer, live, afterClaim = { markSeen(liveIds.last(), live.now) }).run()

        val mail = mailer.to(ann.email).single()
        assertEquals("Adder: 11 unseen notifications", mail.subject, "12 live, one seen meanwhile, 40 dead not counted")
        assertEquals(DIGEST_MAX_ENTRIES, mail.body.lines().count { it.startsWith("- ") }, mail.body)
        assertTrue(mail.body.lines().contains("...and 1 more"), mail.body)
    }

    @Test
    fun `nothing older than a day is mailed`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val stale = s.notice(ann, ageMillis = DAY + 60 * SECOND)
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertTrue(mailer.to(ann.email).isEmpty())
        assertNull(emailedAt(stale), "not even claimed")
    }

    @Test
    fun `without a mailer nothing is claimed`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val id = s.notice(ann)
        val capture = LogCapture("ch.nokillswit.notifications.NotificationDigest")
        try {
            val digest = digest(null, s)
            assertEquals(0, digest.run().claimed)
            assertEquals(0, digest.run().claimed)
            assertNull(emailedAt(id), "the notice stays unclaimed")
            assertEquals(1, capture.events.count { it.formattedMessage.contains("digest is off") }, "logged once")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a failed send is audited with the error's class only and is not retried`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val id = s.notice(ann)
        val secret = "SMTP-SECRET-${UUID.randomUUID()}"
        val mailer = RecordingMailer(failWith = IllegalStateException("provider said: $secret"))
        withAuditCapture { audit ->
            val logs = LogCapture("ch.nokillswit.notifications.NotificationDigest")
            try {
                val run = digest(mailer, s).run()
                assertEquals(1, run.failed)
                val event = audit.events.single { it.message == "notification.email_failed" && it.hasKeyValue("userId", ann.id.toLong()) }
                assertTrue(event.hasKeyValue("errorType", "IllegalStateException"))
                assertTrue(audit.events.none { it.message == "notification.email_sent" && it.hasKeyValue("userId", ann.id.toLong()) })
                val rendered = (audit.events + logs.events).joinToString(" ") { it.everything() }
                assertFalse(rendered.contains(secret), "the provider's message is never logged")
            } finally {
                logs.detach()
            }
        }
        assertNotNull(emailedAt(id), "the claim stands")
        digest(mailer, s, now = { s.now + INTERVAL_SECONDS * SECOND }).run()
        assertEquals(1, mailer.attempts, "no retry")
    }

    @Test
    fun `a send is audited with the person and the count`() = runBlocking {
        val s = setup()
        val ann = s.member()
        repeat(2) { s.notice(ann, ageMillis = (DELAY_SECONDS + 1 + it) * SECOND) }
        withAuditCapture { audit ->
            digest(RecordingMailer(), s).run()
            val event = audit.events.single { it.message == "notification.email_sent" && it.hasKeyValue("userId", ann.id.toLong()) }
            assertTrue(event.hasKeyValue("count", 2))
        }
    }

    @Test
    fun `two runs at once never mail one notice twice`() = runBlocking {
        val s = setup()
        val people = List(6) { s.member() }
        people.forEach { s.notice(it) }
        val mailer = RecordingMailer()
        val a = digest(mailer, s)
        val b = digest(mailer, s)

        coroutineScope { listOf(async(Dispatchers.Default) { a.run() }, async(Dispatchers.Default) { b.run() }).awaitAll() }

        people.forEach { assertEquals(1, mailer.to(it.email).size, "one notice, one email: ${it.email}") }
    }

    @Test
    fun `a run claims at most one batch`() = runBlocking {
        val s = setup()
        val people = List(5) { s.member() }
        // The oldest eligible notices there are (just under a day), oldest first, so they are the ones a batch of two takes.
        val ids = people.mapIndexed { i, p -> s.notice(p, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        val mailer = RecordingMailer()

        val run = digest(mailer, s, batchSize = 2).run()

        assertEquals(2, run.claimed, "the batch is the run's cap")
        assertEquals(listOf(true, true, false, false, false), ids.map { emailedAt(it) != null }, "the two oldest, no more")
    }

    @Test
    fun `a person in the preferred language gets that language and an unknown one gets English`() = runBlocking {
        val s = setup()
        val pl = s.member(language = "pl")
        val xx = s.member()
        // An unsupported code cannot be created through the service; a stored one (a retired language) is raw.
        sql("UPDATE users SET language = 'xx' WHERE id = ?") { st -> st.setLong(1, xx.id.toLong()); st.executeUpdate() }
        s.notice(pl)
        s.notice(xx)
        val mailer = RecordingMailer()

        digest(mailer, s).run()

        assertEquals("Adder: 1 nieprzeczytane powiadomienie", mailer.to(pl.email).single().subject)
        assertEquals("Adder: 1 unseen notification", mailer.to(xx.email).single().subject)
    }

    @Test
    fun `without a public origin the email carries no link`() = runBlocking {
        val s = setup()
        val ann = s.member()
        s.notice(ann)
        val mailer = RecordingMailer()

        digest(mailer, s, appUrl = null).run()

        assertFalse(mailer.to(ann.email).single().body.contains("http"))
    }

    @Test
    fun `a failing digest never stops the purge`() = runBlocking {
        var purged = 0
        val sweep = NotificationSweep(DAY, purge = { purged++; 0 }, digest = { error("digest down") })

        sweep.tick()

        assertEquals(1, purged, "the purge ran in the same tick")
    }

    @Test
    fun `with the log transport the digest prints no title and no body`() = runBlocking {
        val s = setup()
        val ann = s.member()
        s.notice(ann)
        val mailLog = LogCapture("ch.nokillswit.mail")
        val root = LogCapture(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME)
        try {
            digest(LogMailer(), s).run()
            val lines = (mailLog.events + root.events).joinToString("\n") { it.everything() }
            assertTrue(lines.contains("Outbound email (log transport"), "the mail was logged by the dev transport")
            s.secrets.forEach { assertFalse(lines.contains(it), "no title and no body in any line") }
        } finally {
            mailLog.detach()
            root.detach()
        }
    }

    @Test
    fun `a failed re-check is audited, and the rest of the batch is still mailed`() = runBlocking {
        val s = setup()
        val broken = s.member()
        val fine = s.member()
        s.notice(broken)
        s.notice(fine)
        val mailer = RecordingMailer()
        val failing: suspend (UInt) -> List<ch.nokillswit.rooms.RoomSummary> = { user ->
            if (user == broken.id) error("db said: SECRET-RESOLVE") else TestRooms.service.memberRooms(user)
        }
        withAuditCapture { audit ->
            val run = digest(mailer, s, failMemberRooms = failing).run()
            assertEquals(1, run.failed)
            assertEquals(1, run.sent)
            val event = audit.events.single { it.message == "notification.email_failed" && it.hasKeyValue("userId", broken.id.toLong()) }
            assertTrue(event.hasKeyValue("errorType", "IllegalStateException"))
            assertFalse(audit.events.any { it.keyValuePairs.toString().contains("SECRET-RESOLVE") })
        }
        assertEquals(1, mailer.to(fine.email).size)
        assertTrue(mailer.to(broken.email).isEmpty())
    }

    @Test
    fun `a cancelled run releases the claims of recipients it never tried`() = runBlocking {
        val s = setup()
        val first = s.member()
        val second = s.member()
        val third = s.member()
        val ids = listOf(first, second, third).map { s.notice(it) }
        val mailer = RecordingMailer()

        val job = async(Dispatchers.Default) {
            digest(mailer, s, afterClaim = { throw kotlinx.coroutines.CancellationException("stop") }).run()
        }
        runCatching { job.await() }

        assertEquals(listOf(false, false, false), ids.map { emailedAt(it) != null }, "nothing was attempted, so nothing stays claimed")
        assertTrue(mailer.sent.none { it.to in listOf(first.email, second.email, third.email) })
        digest(mailer, s).run()
        listOf(first, second, third).forEach { assertEquals(1, mailer.to(it.email).size, "mailed by the next run") }
    }

    @Test
    fun `a run cancelled mid-send keeps the claim of that recipient and releases the rest`() = runBlocking {
        val s = setup()
        val first = s.member()
        val second = s.member()
        val firstId = s.notice(first)
        val secondId = s.notice(second)
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stuck = object : Mailer {
            override suspend fun send(to: String, subject: String, body: String) {
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }

        val job = async(Dispatchers.Default) { digest(stuck, s).run() }
        started.await()
        job.cancel()
        runCatching { job.await() }

        assertNotNull(emailedAt(firstId), "a send that may have left stays claimed: at most once")
        assertNull(emailedAt(secondId), "the recipient never attempted is released")
    }

    @Test
    fun `three failures in a row end the run and release the untried claims`() = runBlocking {
        val s = setup()
        val people = List(5) { s.member() }
        val ids = people.mapIndexed { i, p -> s.notice(p, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        val mailer = RecordingMailer(failWith = IllegalStateException("smtp down"))

        val run = digest(mailer, s, batchSize = 5).run()

        assertEquals(3, mailer.attempts, "the run stops after the third failure in a row")
        assertEquals(3, run.failed)
        assertEquals(2, run.released)
        assertEquals(listOf(true, true, true), ids.take(3).map { emailedAt(it) != null }, "the attempted ones stay claimed")
        assertEquals(listOf(false, false), ids.drop(3).map { emailedAt(it) != null }, "the untried ones are released")
    }

    @Test
    fun `a success resets the count of failures`() = runBlocking {
        val s = setup()
        val people = List(5) { s.member() }
        people.forEachIndexed { i, p -> s.notice(p, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        var calls = 0
        val flaky = object : Mailer {
            override suspend fun send(to: String, subject: String, body: String) {
                calls++
                // 1 and 2 fail, 3 succeeds, 4 and 5 fail: never three in a row.
                if (calls != 3) error("blip")
            }
        }

        val run = digest(flaky, s, batchSize = 5).run()

        assertEquals(5, calls)
        assertEquals(0, run.released)
    }

    private fun switchOff(user: Person, type: String) =
        TestNotificationPreferences.set(user.id, NotificationType.valueOf(type), NotificationChannel.EMAIL, enabled = false)

    @Test
    fun `notices of a switched-off kind or a deleted account are settled and never fill a batch`() = runBlocking {
        val s = setup()
        val off = s.member()
        val gone = s.member()
        val live = s.member()
        switchOff(off, "THREAD_ASSIGNED")
        // The oldest notices there are: they would be the whole batch of one, if they could be claimed.
        val offIds = List(5) { i -> s.notice(off, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        val goneId = s.notice(gone, ageMillis = DAY - 20 * SECOND)
        TestUsers.softDelete(gone.id)
        val liveId = s.notice(live, ageMillis = DAY - 30 * SECOND)
        val mailer = RecordingMailer()

        val run = digest(mailer, s, batchSize = 1).run()

        assertEquals(1, run.claimed, "the one slot goes to the person who can be mailed")
        assertEquals(1, mailer.to(live.email).size)
        assertTrue(mailer.to(off.email).isEmpty() && mailer.to(gone.email).isEmpty())
        assertTrue(offIds.all { emailedAt(it) != null }, "the switched-off kind is settled in the claim's transaction")
        assertNotNull(emailedAt(goneId), "a deleted account's notice is settled")
        assertTrue(emailedAt(liveId) != null)
    }

    @Test
    fun `a claim picks per person, oldest first, so one flooded backlog does not crowd out the rest`() = runBlocking {
        val s = setup()
        val flooded = s.member()
        val second = s.member()
        val third = s.member()
        val floodIds = List(DIGEST_MAX_NOTICES_PER_RECIPIENT + 10) { i -> s.notice(flooded, ageMillis = DAY - 10 * SECOND - i * 10L) }
        val secondId = s.notice(second, ageMillis = DAY - 20 * SECOND)
        val thirdId = s.notice(third, ageMillis = DAY - 30 * SECOND)
        val mailer = RecordingMailer()
        val digest = digest(mailer, s, batchSize = 2)

        val first = digest.run()

        val expected = DIGEST_MAX_NOTICES_PER_RECIPIENT + 10 + 1
        assertEquals(expected, first.claimed, "the flooded person's whole backlog and the next person's one")
        assertEquals("Adder: ${DIGEST_MAX_NOTICES_PER_RECIPIENT + 10} unseen notifications", mailer.to(flooded.email).single().subject)
        assertEquals(1, mailer.to(second.email).size)
        assertTrue(mailer.to(third.email).isEmpty() && emailedAt(thirdId) == null, "the third person waits for the next run")
        assertTrue(floodIds.all { emailedAt(it) != null }, "the backlog is stamped with the digest, not left for one more mail a tick")
        assertNotNull(emailedAt(secondId))

        digest.run()
        assertEquals(1, mailer.to(third.email).size, "the flooded person is inside the interval: the slot goes to the next one")
        assertEquals(1, mailer.to(flooded.email).size)
    }

    @Test
    fun `a person with 120 due notices gets one mail of 10 listed and 110 more, and the next run finds nothing for them`() = runBlocking {
        val s = setup()
        val flooded = s.member()
        val ids = List(120) { i -> s.notice(flooded, ageMillis = DAY - 10 * SECOND - i * 10L) }
        val mailer = RecordingMailer()
        val digest = digest(mailer, s)

        val first = digest.run()

        val mail = mailer.to(flooded.email).single()
        assertEquals(120, first.claimed)
        assertEquals("Adder: 120 unseen notifications", mail.subject)
        assertEquals(DIGEST_MAX_ENTRIES, mail.body.lines().count { it.startsWith("- ") }, "ten listed")
        assertTrue(mail.body.lines().contains("...and 110 more"), mail.body)
        assertTrue(ids.all { emailedAt(it) != null }, "the whole backlog is stamped in the same claim")

        val later = s.notice(flooded, ageMillis = (DELAY_SECONDS + 1) * SECOND)
        assertEquals(0, digest.run().claimed, "nothing for them")
        assertNull(emailedAt(later), "inside the interval a newer notice waits for its own mail")
        assertEquals(1, mailer.to(flooded.email).size)
    }

    @Test
    fun `a scan reads at most the cap, oldest first, and the rest waits for a later tick`() = runBlocking {
        val s = setup()
        val flooded = s.member()
        val other = s.member()
        val floodIds = List(6) { i -> s.notice(flooded, ageMillis = DAY - 10 * SECOND - i * 10L) }
        val otherId = s.notice(other, ageMillis = DAY - 20 * SECOND)
        val mailer = RecordingMailer()

        val run = digest(mailer, s, scanCap = 4).run()

        assertEquals(4, run.claimed, "the cap bounds what one claim stamps")
        assertEquals(listOf(true, true, true, true, false, false), floodIds.map { emailedAt(it) != null })
        assertNull(emailedAt(otherId), "a person behind the cap waits for a later tick")
        assertEquals("Adder: 4 unseen notifications", mailer.to(flooded.email).single().subject)
    }

    @Test
    fun `a claim takes the send budget plus a margin of people, not the whole batch`() = runBlocking {
        assertEquals(12, peopleToClaim(batchSize = 500, maxPerRun = 2), "budget 2 plus the minimum margin of 10")
        assertEquals(120, peopleToClaim(batchSize = 500, maxPerRun = 100), "budget 100 plus a fifth")
        assertEquals(3, peopleToClaim(batchSize = 3, maxPerRun = 100), "never more than the batch")
        val s = setup()
        val people = List(15) { s.member() }
        val ids = people.mapIndexed { i, p -> s.notice(p, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        val mailer = RecordingMailer()

        val run = digest(mailer, s, maxPerRun = 2).run()

        assertEquals(12, run.claimed, "2 sends plus the margin, not all 15 people")
        assertEquals(2, run.sent)
        assertEquals(10, run.released, "the margin goes back")
        assertEquals(2, ids.count { emailedAt(it) != null })
    }

    @Test
    fun `a claim past its statement budget is logged scrubbed and the run ends with nothing claimed`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val id = s.notice(ann)
        val mailer = RecordingMailer()
        val capture = LogCapture("ch.nokillswit.notifications.NotificationDigest")
        val blocker = PostgresTestSupport.connect()
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE notifications IN ACCESS EXCLUSIVE MODE") }

            val run = digest(mailer, s, statementTimeoutSeconds = 1).run()

            assertEquals(0, run.claimed)
            assertTrue(mailer.sent.isEmpty())
            val line = capture.events.single { it.formattedMessage.contains("could not claim") }
            assertFalse(line.everything().contains(s.roomName), "scrubbed: no value in the line")
        } finally {
            blocker.rollback()
            blocker.close()
            capture.detach()
        }
        assertNull(emailedAt(id), "nothing was stamped")
        digest(mailer, s).run()
        assertEquals(1, mailer.to(ann.email).size, "the next tick mails it")
    }

    @Test
    fun `a run sends at most the budget and releases the claims beyond it`() = runBlocking {
        val s = setup()
        val people = List(5) { s.member() }
        val ids = people.mapIndexed { i, p -> s.notice(p, ageMillis = DAY - 10 * SECOND - i * SECOND) }
        val mailer = RecordingMailer()

        val run = digest(mailer, s, batchSize = 5, maxPerRun = 2).run()

        assertEquals(2, mailer.attempts, "the budget is the run's cap")
        assertEquals(5, run.claimed)
        assertEquals(2, run.sent)
        assertEquals(3, run.released)
        assertEquals(listOf(true, true, false, false, false), ids.map { emailedAt(it) != null }, "the untried claims are released")

        val next = digest(mailer, s, batchSize = 5, maxPerRun = 2).run()
        assertEquals(2, next.sent, "the next tick carries on with the rest")
        assertEquals(listOf(true, true, true, true, false), ids.map { emailedAt(it) != null })
    }

    @Test
    fun `a recipient skipped at send time is remembered and does not claim a slot again`() = runBlocking {
        val s = setup()
        val ann = s.member()
        val first = s.notice(ann)
        val mailer = RecordingMailer()
        var clock = s.now
        var armed = true
        val digest = digest(mailer, s, now = { clock }, afterClaim = {
            if (armed) { armed = false; markSeen(first, s.now) } // seen between the claim and the send: nothing to mail
        })

        digest.run()
        assertTrue(mailer.to(ann.email).isEmpty())
        val second = s.notice(ann, ageMillis = (DELAY_SECONDS + 1) * SECOND)

        digest.run()
        assertNull(emailedAt(second), "inside the interval the skipped person is not a candidate")
        assertTrue(mailer.to(ann.email).isEmpty())

        clock += INTERVAL_SECONDS * SECOND
        digest.run()
        assertEquals(1, mailer.to(ann.email).size, "after the interval the new notice is mailed")
    }
}
