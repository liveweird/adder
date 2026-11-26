package ch.nokillswit

import ch.nokillswit.live.LIVE_SESSION_LEASE_MILLIS
import ch.nokillswit.live.LiveLeaseAdmission
import ch.nokillswit.live.LiveLeaseRefusal
import ch.nokillswit.live.LiveSessionLeaseStore
import ch.nokillswit.infra.presence.PostgresForegroundPresence
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** Shared PostgreSQL live-session cap, renewal fencing, foreground presence and bounded expiry cleanup. */
class LiveSessionLeasesTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() {
        if (users.isEmpty()) return
        PostgresTestSupport.connect().use { connection ->
            val ids = connection.createArrayOf("bigint", users.map(UInt::toLong).toTypedArray())
            connection.prepareStatement("DELETE FROM live_session_leases WHERE user_id = ANY(?)").use { statement ->
                statement.setArray(1, ids)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM live_session_user_guards WHERE user_id = ANY(?)").use { statement ->
                statement.setArray(1, ids)
                statement.executeUpdate()
            }
        }
        runBlocking { users.forEach { TestUsers.softDelete(it) } }
    }

    private suspend fun user(label: String): UInt =
        TestUsers.seed(uniqueEmail(label), "pw", role = UserRole.USER).also { users += it }

    @Test
    fun `two instances racing admission share one sixteen-session user cap`() = runBlocking {
        val user = user("live-lease-cap")
        val first = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val second = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val tokenExpiry = databaseNow() + 300_000

        val results = coroutineScope {
            (0 until 20).map { index ->
                async { (if (index % 2 == 0) first else second).admit(user, UUID.randomUUID(), tokenExpiry, 16) }
            }.awaitAll()
        }

        val accepted = results.filterIsInstance<LiveLeaseAdmission.Accepted>()
        val refused = results.filterIsInstance<LiveLeaseAdmission.Refused>()
        assertEquals(16, accepted.size)
        assertEquals(4, refused.size)
        assertTrue(refused.all { it.reason == LiveLeaseRefusal.CAPACITY })
        assertTrue(accepted.all { it.expiresAtMillis - it.serverNowMillis == LIVE_SESSION_LEASE_MILLIS })
        assertEquals(16, leaseCount(user))
    }

    @Test
    fun `admission uses database time and clamps the socket lease to token expiry`() = runBlocking {
        val user = user("live-lease-clock")
        val store = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val before = databaseNow()
        val tokenExpiry = before + 5_000
        val accepted = assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, UUID.randomUUID(), tokenExpiry, 16))
        val after = databaseNow()

        assertTrue(accepted.serverNowMillis in before..after)
        assertEquals(tokenExpiry, accepted.expiresAtMillis)
        val expired = store.admit(user, UUID.randomUUID(), before - 1, 16)
        assertEquals(LiveLeaseRefusal.TOKEN_EXPIRED, assertIs<LiveLeaseAdmission.Refused>(expired).reason)
        assertEquals(1, leaseCount(user))
    }

    @Test
    fun `capacity hint is non-consuming and admission honors the configured limit`() = runBlocking {
        val user = user("live-lease-configured-cap")
        val store = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val tokenExpiry = databaseNow() + 300_000

        assertTrue(store.hasCapacity(user, 1))
        assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, UUID.randomUUID(), tokenExpiry, 1))
        assertEquals(false, store.hasCapacity(user, 1))
        assertEquals(LiveLeaseRefusal.CAPACITY, assertIs<LiveLeaseAdmission.Refused>(
            store.admit(user, UUID.randomUUID(), tokenExpiry, 1),
        ).reason)
        assertEquals(1, leaseCount(user))
    }

    @Test
    fun `renew foreground and release are instance fenced and foreground remains independent of heartbeat`() = runBlocking {
        val user = user("live-lease-fence")
        val outsider = user("live-lease-scope")
        val first = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val second = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val presence = PostgresForegroundPresence(sharedDatabaseForTests())
        val tokenExpiry = databaseNow() + 300_000
        val one = UUID.randomUUID()
        val two = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertIs<LiveLeaseAdmission.Accepted>(first.admit(user, one, tokenExpiry, 16))
        assertIs<LiveLeaseAdmission.Accepted>(first.admit(user, two, tokenExpiry, 16))
        assertIs<LiveLeaseAdmission.Accepted>(second.admit(outsider, other, tokenExpiry, 16))

        assertEquals(emptyMap(), second.renew(listOf(one, two)).deadlines)
        assertEquals(emptySet(), second.setForeground(listOf(one), visible = true).updatedSessions)
        assertEquals(0, second.release(listOf(one)))

        val visible = first.setForeground(listOf(one, two), visible = true)
        assertEquals(setOf(one, two), visible.updatedSessions)
        assertEquals(visible.serverNowMillis + LIVE_SESSION_LEASE_MILLIS, visible.expiresAtMillis)
        val foregroundBeforeHeartbeat = foregroundDeadline(one)
        assertEquals(setOf(user), presence.eligibleUserIds(listOf(user, user)))
        assertEquals(emptySet(), presence.eligibleUserIds(listOf(outsider)))
        assertEquals(setOf(other), second.setForeground(listOf(other), visible = true).updatedSessions)
        assertEquals(setOf(outsider), presence.eligibleUserIds(listOf(outsider)))

        val renewed = first.renew(listOf(one, two))
        assertEquals(setOf(one, two), renewed.deadlines.keys)
        assertTrue(renewed.deadlines.values.all { it == renewed.serverNowMillis + LIVE_SESSION_LEASE_MILLIS })
        assertEquals(foregroundBeforeHeartbeat, foregroundDeadline(one), "socket heartbeat must not renew foreground presence")
        first.setForeground(listOf(one), visible = false)
        assertEquals(setOf(user), presence.eligibleUserIds(listOf(user)), "the second visible tab keeps the user present")
        first.setForeground(listOf(two), visible = false)
        assertEquals(emptySet(), presence.eligibleUserIds(listOf(user)))

        expire(one)
        assertEquals(setOf(two), first.renew(listOf(one, two)).deadlines.keys, "an expired socket is omitted from the actual renewed set")
        assertEquals(emptySet(), first.setForeground(listOf(one), visible = true).updatedSessions)
        assertEquals(1, first.release(listOf(one)))
        assertEquals(1, first.release(listOf(two)))
        assertEquals(0, first.release(listOf(other)), "another instance owns the outsider's session")
    }

    @Test
    fun `expiry cleanup is bounded ordered and skips a locked oldest lease`() = runBlocking {
        val user = user("live-lease-prune")
        val store = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val sessions = (0 until 4).map { UUID.randomUUID() }
        val tokenExpiry = databaseNow() + 300_000
        sessions.forEach { assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, it, tokenExpiry, 16)) }
        sessions.forEachIndexed { index, session -> expire(session, databaseNow() - 10_000 + index) }

        PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.prepareStatement(
                "SELECT session_id FROM live_session_leases WHERE user_id = ? ORDER BY socket_expires_at, session_id " +
                    "LIMIT 1 FOR UPDATE",
            ).use { statement ->
                statement.setLong(1, user.toLong())
                statement.executeQuery().use { rows -> assertTrue(rows.next()) }
            }
            try {
                assertEquals(2, store.pruneExpired(batchSize = 2))
                assertEquals(2, leaseCount(user), "the locked oldest and one lease beyond the batch remain")
            } finally {
                holder.rollback()
            }
        }

        assertEquals(2, store.pruneExpired(batchSize = 2))
        assertEquals(0, store.pruneExpired(batchSize = 2))
        assertEquals(0, leaseCount(user))
    }

    @Test
    fun `empty and duplicate batches are harmless and pruning rejects an invalid bound`() = runBlocking {
        val user = user("live-lease-batches")
        val store = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val presence = PostgresForegroundPresence(sharedDatabaseForTests())
        val session = UUID.randomUUID()
        assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, session, databaseNow() + 300_000, 16))

        assertEquals(emptyMap(), store.renew(emptyList()).deadlines)
        assertEquals(emptySet(), store.setForeground(emptyList(), visible = true).updatedSessions)
        assertEquals(emptySet(), presence.eligibleUserIds(emptyList()))
        assertEquals(0, store.release(emptyList()))
        assertEquals(1, store.release(listOf(session, session)))
        assertEquals(0, store.release(listOf(session)))
        assertFailsWith<IllegalArgumentException> { store.pruneExpired(batchSize = 0) }
        Unit
    }

    @Test
    fun `migration stores only lease identifiers ownership and deadlines`() {
        PostgresTestSupport.connect().use { connection ->
            val columns = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' " +
                    "AND table_name = 'live_session_leases' ORDER BY ordinal_position",
            ).use { statement ->
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            assertEquals(
                listOf(
                    "session_id", "instance_id", "user_id", "token_expires_at", "socket_expires_at", "foreground_expires_at",
                ),
                columns,
            )
            val indexes = connection.prepareStatement(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'live_session_leases'",
            ).use { statement ->
                statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
            }
            assertEquals(
                setOf(
                    "live_session_leases_pkey",
                    "idx_live_session_leases_user_socket",
                    "idx_live_session_leases_socket_expiry",
                    "idx_live_session_leases_foreground",
                ),
                indexes,
            )
        }
    }

    private fun databaseNow(): Long = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private fun leaseCount(userId: UInt): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM live_session_leases WHERE user_id = ?").use { statement ->
            statement.setLong(1, userId.toLong())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun foregroundDeadline(sessionId: UUID): Long? = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT foreground_expires_at FROM live_session_leases WHERE session_id = ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.executeQuery().use { rows -> rows.next(); rows.getObject(1)?.let { rows.getLong(1) } }
        }
    }

    private fun expire(sessionId: UUID, deadline: Long = databaseNow() - 1) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE live_session_leases SET socket_expires_at = ? WHERE session_id = ?").use { statement ->
                statement.setLong(1, deadline)
                statement.setObject(2, sessionId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }
}
