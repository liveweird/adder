package ch.nokillswit

import ch.nokillswit.notifications.ACTOR_RECIPIENT_INTERVAL_MILLIS
import ch.nokillswit.notifications.DigestAttemptStatus
import ch.nokillswit.notifications.DigestBounds
import ch.nokillswit.notifications.NotificationDigestStore
import ch.nokillswit.users.UserRole
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

private const val RECIPIENT_INTERVAL = 900_000L

class NotificationDigestReservationTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        users.forEach { userId ->
            sql("DELETE FROM notification_digest_recipient_pacing WHERE recipient_id = ?") { statement ->
                statement.setLong(1, userId.toLong())
                statement.executeUpdate()
            }
            TestUsers.softDelete(userId)
        }
    }

    private suspend fun user(marker: String): UInt =
        TestUsers.seed(uniqueEmail(marker), "pw", role = UserRole.USER).also(users::add)

    private fun store(now: AtomicLong, capacity: Int = 50_000) = NotificationDigestStore(
        sharedDatabaseForTests(),
        delayMillis = 0,
        bounds = DigestBounds(maxRecipientReservations = capacity, pacingClock = now::get),
    )

    private fun pacing(table: String, recipientId: UInt, actorId: UInt? = null): Long? = sql(
        if (actorId == null) {
            "SELECT attempted_at FROM $table WHERE recipient_id = ?"
        } else {
            "SELECT attempted_at FROM $table WHERE actor_id = ? AND recipient_id = ?"
        },
    ) { statement ->
        var index = 1
        actorId?.let { statement.setLong(index++, it.toLong()) }
        statement.setLong(index, recipientId.toLong())
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
    }

    private fun <T> sql(query: String, block: (PreparedStatement) -> T): T =
        PostgresTestSupport.connect().use { connection -> connection.prepareStatement(query).use(block) }

    @Test
    fun `two stores reserve only one recipient attempt for different actors`() = runBlocking {
        val recipient = user("digest-reserve-recipient")
        val actors = listOf(user("digest-reserve-a"), user("digest-reserve-b"))
        val now = AtomicLong(System.currentTimeMillis())
        val stores = List(2) { store(now) }

        val results = actors.zip(stores).map { (actor, digestStore) ->
            async(Dispatchers.Default) {
                digestStore.reserveAttempt(recipient, setOf(actor), RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS)
            }
        }.awaitAll()

        assertEquals(1, results.count { it.status == DigestAttemptStatus.RESERVED })
        assertEquals(1, results.count { it.status == DigestAttemptStatus.RECIPIENT_PACED })
        assertEquals(now.get(), pacing("notification_digest_recipient_pacing", recipient))
        assertEquals(1, actors.count { pacing("notification_email_pacing", recipient, it) == now.get() })
    }

    @Test
    fun `all paced actors do not consume the recipient interval and a mixed reservation admits only eligible actors`() = runBlocking {
        val recipient = user("digest-mixed-recipient")
        val paced = user("digest-mixed-paced")
        val eligible = user("digest-mixed-eligible")
        val now = AtomicLong(System.currentTimeMillis())
        val digestStore = store(now)

        assertEquals(
            DigestAttemptStatus.RESERVED,
            digestStore.reserveAttempt(recipient, setOf(paced), RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS).status,
        )
        sql("DELETE FROM notification_digest_recipient_pacing WHERE recipient_id = ?") { statement ->
            statement.setLong(1, recipient.toLong())
            statement.executeUpdate()
        }

        val allPaced = digestStore.reserveAttempt(
            recipient,
            setOf(paced),
            RECIPIENT_INTERVAL,
            ACTOR_RECIPIENT_INTERVAL_MILLIS,
        )
        assertEquals(DigestAttemptStatus.ACTORS_PACED, allPaced.status)
        assertNull(pacing("notification_digest_recipient_pacing", recipient))

        val mixed = digestStore.reserveAttempt(
            recipient,
            setOf(paced, eligible),
            RECIPIENT_INTERVAL,
            ACTOR_RECIPIENT_INTERVAL_MILLIS,
        )
        assertEquals(DigestAttemptStatus.RESERVED, mixed.status)
        assertEquals(setOf(eligible), mixed.actorIds)
        assertEquals(now.get(), pacing("notification_digest_recipient_pacing", recipient))
    }

    @Test
    fun `recipient capacity fails closed until bounded expiry cleanup frees a slot`() = runBlocking {
        val first = user("digest-cap-first")
        val second = user("digest-cap-second")
        val actor = user("digest-cap-actor")
        val now = AtomicLong(System.currentTimeMillis())
        val digestStore = store(now, capacity = 1)

        assertEquals(
            DigestAttemptStatus.RESERVED,
            digestStore.reserveAttempt(first, setOf(actor), RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS).status,
        )
        assertEquals(
            DigestAttemptStatus.CAPACITY,
            digestStore.reserveAttempt(second, setOf(actor), RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS).status,
        )

        now.addAndGet(ACTOR_RECIPIENT_INTERVAL_MILLIS)
        assertEquals(2, digestStore.purgeExpiredPacing(RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS, batchSize = 1))
        assertEquals(
            DigestAttemptStatus.RESERVED,
            digestStore.reserveAttempt(second, setOf(actor), RECIPIENT_INTERVAL, ACTOR_RECIPIENT_INTERVAL_MILLIS).status,
        )
    }

    @Test
    fun `a new store observes the shared interval and PostgreSQL clock expiry`() = runBlocking {
        val recipient = user("digest-db-clock-recipient")
        val actor = user("digest-db-clock-actor")
        val firstStore = NotificationDigestStore(sharedDatabaseForTests(), 0, DigestBounds())
        val secondStore = NotificationDigestStore(sharedDatabaseForTests(), 0, DigestBounds())
        val interval = 100L

        assertEquals(
            DigestAttemptStatus.RESERVED,
            firstStore.reserveAttempt(recipient, setOf(actor), interval, interval).status,
        )
        assertEquals(
            DigestAttemptStatus.RECIPIENT_PACED,
            secondStore.reserveAttempt(recipient, setOf(actor), interval, interval).status,
        )
        delay(250)
        assertEquals(
            DigestAttemptStatus.RESERVED,
            secondStore.reserveAttempt(recipient, setOf(actor), interval, interval).status,
        )
    }
}
