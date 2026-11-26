package ch.nokillswit

import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.live.LIVE_TICKET_EXPIRY_CLEANUP_BATCH
import ch.nokillswit.live.LiveTicketCapacityExceededException
import ch.nokillswit.live.LiveTicketPostgresStore
import ch.nokillswit.live.LiveTicketStoreUnavailableException
import ch.nokillswit.live.LiveTickets
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** PostgreSQL contract for the cross-instance, one-use live ticket store introduced by V53. */
class LiveTicketPostgresStoreTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        deleteLiveTickets(users)
        users.forEach { TestUsers.softDelete(it) }
    }

    private suspend fun user(label: String): UInt =
        TestUsers.seed(uniqueEmail("live-ticket-$label"), "pw", role = UserRole.USER).also { users += it }

    private fun service(
        maxTickets: Int = 10_000,
        ttlMillis: Long = 30_000,
        ticket: String = UUID.randomUUID().toString().replace("-", "").padEnd(43, 'x'),
    ) = LiveTickets(LiveTicketPostgresStore(sharedDatabaseForTests(), maxTickets, ttlMillis), ticketGenerator = { ticket })

    private suspend fun LiveTickets.mint(userId: UInt, tokenExpiresAt: Long = System.currentTimeMillis() + 60_000) =
        issue(userId, isAdmin = true, roles = setOf("ADMIN", "USER"), jti = UUID.randomUUID().toString(),
            credentialRevision = 7, tokenExpiresAtMillis = tokenExpiresAt)

    @Test
    fun `the store refuses invalid capacity and timing bounds`() {
        val database = sharedDatabaseForTests()
        assertFailsWith<IllegalArgumentException> { LiveTicketPostgresStore(database, maxTickets = 0) }
        assertFailsWith<IllegalArgumentException> { LiveTicketPostgresStore(database, maxTickets = 1, ttlMillis = 0) }
        assertFailsWith<IllegalArgumentException> { LiveTicketPostgresStore(database, maxTickets = 1, cleanupBatch = 0) }
    }

    @Test
    fun `a failed database insert never issues a redeemable ticket`() = runBlocking {
        val raw = "missing-account-binding".padEnd(43, 'x')
        val issuer = service(ticket = raw)
        assertFailsWith<LiveTicketStoreUnavailableException> { issuer.mint(UInt.MAX_VALUE) }
        assertNull(service().redeem(raw))
    }

    @Test
    fun `a ticket minted through one store redeems once through another and only its digest is persisted`() = runBlocking {
        val userId = user("cross-store")
        val raw = "shared-ticket-raw-secret".padEnd(43, 'x')
        val issued = service(ticket = raw).mint(userId)

        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "SELECT ticket_hash, user_id, is_admin, roles, credential_revision FROM live_tickets WHERE user_id = ?",
            ).use { statement ->
                statement.setLong(1, userId.toLong())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(sha256Hex(raw), rows.getString(1).trim())
                    assertTrue(raw !in rows.getString(1), "the bearer itself must never be persisted")
                    assertEquals(userId.toLong(), rows.getLong(2))
                    assertTrue(rows.getBoolean(3))
                    assertEquals(setOf("ADMIN", "USER"), (rows.getArray(4).array as Array<*>).toSet())
                    assertEquals(7, rows.getLong(5))
                }
            }
        }

        val otherInstance = LiveTickets(LiveTicketPostgresStore(sharedDatabaseForTests(), maxTickets = 10_000))
        val binding = assertNotNull(otherInstance.redeem(issued.ticket))
        assertEquals(userId, binding.userId)
        assertEquals(setOf("ADMIN", "USER"), binding.roles)
        assertEquals(7, binding.credentialRevision)
        assertNull(service().redeem(issued.ticket), "used and unknown tickets have the same result")
        assertNull(otherInstance.redeem("unknown-ticket"))
    }

    @Test
    fun `the shared capacity guard serializes mints and redemption has exactly one winner`() = runBlocking {
        val userId = user("concurrent")
        val attempts = (1..6).map { index ->
            service(maxTickets = 2, ticket = "concurrent-$index".padEnd(43, 'x'))
        }
        val outcomes = coroutineScope { attempts.map { candidate -> async { runCatching { candidate.mint(userId) } } }.awaitAll() }
        val issued = outcomes.mapNotNull { it.getOrNull() }
        assertEquals(2, issued.size)
        outcomes.filter { it.isFailure }.forEach { assertIs<LiveTicketCapacityExceededException>(it.exceptionOrNull()) }

        val ticket = issued.first().ticket
        val redemptions = coroutineScope {
            List(8) { async { LiveTickets(LiveTicketPostgresStore(sharedDatabaseForTests(), 2)).redeem(ticket) } }.awaitAll()
        }
        assertEquals(1, redemptions.count { it != null })
    }

    @Test
    fun `PostgreSQL clock bounds the ticket by both ttl and access token expiry`() = runBlocking {
        val userId = user("clock")
        val beforeTtl = System.currentTimeMillis()
        val ttlTicket = service(ttlMillis = 250, ticket = "ttl-clock".padEnd(43, 'x')).mint(userId, beforeTtl + 60_000)
        assertTrue(ttlTicket.expiresAtMillis in (beforeTtl + 100)..(System.currentTimeMillis() + 750))

        val tokenDeadline = System.currentTimeMillis() + 300
        val tokenTicket = service(ttlMillis = 60_000, ticket = "token-clock".padEnd(43, 'x')).mint(userId, tokenDeadline)
        assertEquals(tokenDeadline, tokenTicket.expiresAtMillis)
        delay((maxOf(ttlTicket.expiresAtMillis, tokenTicket.expiresAtMillis) - System.currentTimeMillis()).coerceAtLeast(0) + 20)

        val otherStore = LiveTickets(LiveTicketPostgresStore(sharedDatabaseForTests(), 10_000))
        assertNull(otherStore.redeem(ttlTicket.ticket))
        assertNull(otherStore.redeem(tokenTicket.ticket))
    }

    @Test
    fun `mint deletes at most one bounded batch of expired tickets`() = runBlocking {
        val userId = user("cleanup")
        val expired = LIVE_TICKET_EXPIRY_CLEANUP_BATCH + 3
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO live_tickets (ticket_hash, user_id, is_admin, roles, access_jti, credential_revision, " +
                    "token_expires_at, expires_at) VALUES (?, ?, false, ARRAY[]::text[], ?, 0, 0, 0)",
            ).use { statement ->
                repeat(expired) { index ->
                    statement.setString(1, sha256Hex("expired-$userId-$index"))
                    statement.setLong(2, userId.toLong())
                    statement.setString(3, UUID.randomUUID().toString())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        service(ticket = "cleanup-one".padEnd(43, 'x')).mint(userId)
        assertEquals(4, storedCount(userId), "three expired rows plus the newly active ticket remain")
        service(ticket = "cleanup-two".padEnd(43, 'x')).mint(userId)
        assertEquals(2, storedCount(userId), "the next bounded pass removes the remainder")
    }

    private fun storedCount(userId: UInt): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM live_tickets WHERE user_id = ?").use { statement ->
            statement.setLong(1, userId.toLong())
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }
}
