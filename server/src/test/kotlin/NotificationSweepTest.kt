package ch.nokillswit

import ch.nokillswit.notifications.NotificationSweep
import ch.nokillswit.notifications.NotificationSweepKey
import ch.nokillswit.users.UserRole
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val DAY_MILLIS = 86_400_000L
private const val HOUR_MILLIS = 3_600_000L

/**
 * The retention of notices (`NotificationSweep`, `chat-model.md` "Notifications (V18)"): a notice older than the retention is deleted at
 * most once an hour, in the sweep's own scope; a failure logs class names only. Rows are raw-inserted with chosen `created_at` stamps
 * (a real mention is always made now). Rooms, templates and users are shared suite state: unique rows, removed afterwards.
 */
class NotificationSweepTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Fixture(val room: UInt, val thread: UInt, val user: UInt)

    private suspend fun fixture(prefix: String): Fixture {
        val user = TestUsers.seed(uniqueEmail(prefix), "pw", role = UserRole.USER, name = "Sweep").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(user, template, "Sweep ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        return Fixture(room, TestThreads.seed(room, user).id, user)
    }

    /** Raw-inserts a notice for the fixture's member made at [createdAt]; answers its id. */
    private fun Fixture.notice(createdAt: Long): Long = PostgresTestSupport.connect().use { c ->
        val sql = "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at) " +
            "VALUES (?, ?, 'THREAD_ASSIGNED', ?, ?, ?) RETURNING id"
        c.prepareStatement(sql).use { st ->
            st.setLong(1, user.toLong())
            st.setInt(2, room.toInt())
            st.setLong(3, user.toLong())
            st.setInt(4, thread.toInt())
            st.setLong(5, createdAt)
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private fun exists(id: Long): Boolean = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) == 1 }
        }
    }

    @Test
    fun `a notice past the retention is purged and a younger one stays`() = runBlocking {
        val f = fixture("sweep-keep")
        val now = System.currentTimeMillis()
        // Inserted in time order (ids follow time, as in production): the retention's search over the primary key relies on it.
        val old = f.notice(now - 91 * DAY_MILLIS)
        val soon = f.notice(now - 90 * DAY_MILLIS + HOUR_MILLIS / 2)
        val young = f.notice(now - 89 * DAY_MILLIS)
        var clock = now
        val sweep = NotificationSweep(90 * DAY_MILLIS, clock = { clock }, purge = TestThreads.notices::purgeOlderThan)

        assertTrue((sweep.tick() ?: 0) >= 1, "the first tick purges")
        assertEquals(false, exists(old))
        assertEquals(true, exists(young))
        assertEquals(true, exists(soon), "not yet old")

        // At most once an hour: the next tick does nothing, even when something has become due.
        clock = now + HOUR_MILLIS - 1
        assertNull(sweep.tick(), "not an hour yet")
        assertEquals(true, exists(soon))
        clock = now + HOUR_MILLIS
        assertTrue((sweep.tick() ?: 0) >= 1, "an hour on, it purges again")
        assertEquals(false, exists(soon))
        assertEquals(true, exists(young), "the younger notice is still younger than the retention")
    }

    @Test
    fun `the purge runs in batches and takes only the old end`() = runBlocking {
        val f = fixture("sweep-batch")
        val now = System.currentTimeMillis()
        val old = List(5) { f.notice(now - (100 + it) * DAY_MILLIS) }
        val young = List(2) { f.notice(now - 10 * DAY_MILLIS - it) }
        val batches = mutableListOf<Int>()
        val deleted = TestThreads.notices.purgeOlderThan(now - 90 * DAY_MILLIS, batchSize = 2) { batches += it }
        assertTrue(deleted >= 5, "at least this test's five went: $deleted")
        assertEquals(deleted, batches.sum())
        assertTrue(batches.size >= 3, "five rows in batches of two are at least three batches: $batches")
        assertTrue(batches.dropLast(1).all { it == 2 }, "every batch but the last is full: $batches")
        old.forEach { assertEquals(false, exists(it)) }
        young.forEach { assertEquals(true, exists(it)) }
        assertEquals(0, TestThreads.notices.purgeOlderThan(now - 90 * DAY_MILLIS, batchSize = 2), "nothing old is left")
    }

    @Test
    fun `a failure logs class names only`() = runBlocking {
        val capture = LogCapture("ch.nokillswit.notifications.NotificationSweep")
        try {
            val secret = "secret-title-${UUID.randomUUID()}"
            val sweep = NotificationSweep(DAY_MILLIS, purge = { throw R2dbcNonTransientResourceException("failed near $secret", "23505") })
            assertNull(sweep.tick(), "a failed purge answers nothing")
            val line = capture.events.single { it.formattedMessage.startsWith("notification retention failed") }
            assertTrue(line.formattedMessage.contains("R2dbcNonTransientResourceException"), line.formattedMessage)
            assertTrue(line.formattedMessage.contains("23505"), "the SQLSTATE is kept")
            assertTrue(capture.events.none { it.formattedMessage.contains(secret) || it.throwableProxy?.message?.contains(secret) == true })
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `normalization and pacing cleanup run every tick without blocking the other work on failure`() = runBlocking {
        val calls = mutableListOf<String>()
        var normalizationRuns = 0
        var pacingRuns = 0
        val sweep = NotificationSweep(
            DAY_MILLIS,
            clock = { 2 * DAY_MILLIS },
            purge = {
                calls += "retention"
                1
            },
            digest = { calls += "email" },
            push = { calls += "push" },
            normalizeUnseen = {
                calls += "unseen"
                check(normalizationRuns++ != 0) { "normalization failed" }
                1
            },
            purgeExpiredPacing = {
                calls += "pacing"
                check(pacingRuns++ != 1) { "pacing cleanup failed" }
            },
        )

        assertEquals(1, sweep.tick())
        assertNull(sweep.tick(), "age retention is not due again")
        assertEquals(listOf("unseen", "pacing", "email", "push", "retention", "unseen", "pacing", "email", "push"), calls)
    }

    @Test
    fun `boot runs it when enabled`() = testApplication {
        configureApp("notifications.sweepEnabled" to "true")
        val f = fixture("sweep-boot")
        val old = f.notice(System.currentTimeMillis() - 91 * DAY_MILLIS)
        startApplication()
        val purged = withTimeout(60_000) { application.attributes[NotificationSweepKey].await() }
        assertTrue(purged != null && purged >= 1, "the first pass purged the old notice: $purged")
        assertEquals(false, exists(old))
    }

    @Test
    fun `the harness leaves the sweep off`() = testApplication {
        configureApp()
        val f = fixture("sweep-off")
        val old = f.notice(System.currentTimeMillis() - 91 * DAY_MILLIS)
        startApplication()
        assertEquals(null, application.attributes.getOrNull(NotificationSweepKey), "no sweep was started")
        assertEquals(true, exists(old))
    }
}
