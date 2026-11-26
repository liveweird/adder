package ch.nokillswit

import ch.nokillswit.auth.MAX_GRACE_REUSES
import ch.nokillswit.auth.RefreshClaim
import ch.nokillswit.auth.RefreshPrecheck
import ch.nokillswit.auth.RefreshTokenRevocation
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** PostgreSQL contract for refresh claims, shared replay grace and the atomic logout boundary. */
class TokenBlocklistRotationTest {
    private val users = mutableListOf<UInt>()
    private val farFuture get() = System.currentTimeMillis() + 600_000

    @AfterTest
    fun cleanUp() = runBlocking {
        if (users.isNotEmpty()) {
            PostgresTestSupport.connect().use { connection ->
                connection.prepareStatement("DELETE FROM refresh_rotations WHERE user_id = ANY(?)").use { statement ->
                    statement.setArray(1, connection.createArrayOf("bigint", users.map(UInt::toLong).toTypedArray()))
                    statement.executeUpdate()
                }
            }
        }
        users.forEach { TestUsers.softDelete(it) }
    }

    private suspend fun user(label: String): UInt =
        TestUsers.seed(uniqueEmail("refresh-store-$label"), "pw", role = UserRole.USER).also { users += it }

    @Test
    fun `rotation store rejects invalid grace and capacity bounds`() = testApplication {
        usePostgresTestcontainer()

        assertFailsWith<IllegalArgumentException> { newTokenBlocklistService(replayGraceMillis = -1) }
        assertFailsWith<IllegalArgumentException> { newTokenBlocklistService(maxRotationRecords = 0) }
        assertFailsWith<IllegalArgumentException> { newTokenBlocklistService(maxRotationRecords = 10_001) }
    }

    @Test
    fun `revokeIfAbsent inserts once and an upgrade-era row without rotation metadata stays strict`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService()
        val jti = UUID.randomUUID().toString()

        assertEquals(RefreshPrecheck.AVAILABLE, service.refreshPrecheck(jti))
        assertTrue(service.revokeIfAbsent(jti, farFuture))
        assertFalse(service.revokeIfAbsent(jti, farFuture))
        assertEquals(RefreshPrecheck.REVOKED, newTokenBlocklistService().refreshPrecheck(jti))
    }

    @Test
    fun `concurrent stores share one claim and at most two aggregate grace responses`() = testApplication {
        usePostgresTestcontainer()
        val userId = user("aggregate")
        val jti = UUID.randomUUID().toString()
        assertEquals(RefreshClaim.CLAIMED, newTokenBlocklistService().claimRefresh(jti, farFuture, userId))
        assertEquals(RefreshPrecheck.GRACE, newTokenBlocklistService().refreshPrecheck(jti))

        val results = coroutineScope {
            List(8) {
                async(Dispatchers.IO) { newTokenBlocklistService().claimRefresh(jti, farFuture, userId) }
            }.awaitAll()
        }
        assertEquals(MAX_GRACE_REUSES, results.count { it == RefreshClaim.GRACE_REPLAY }, results.toString())
        assertEquals(8 - MAX_GRACE_REUSES, results.count { it == RefreshClaim.REPLAYED }, results.toString())
    }

    @Test
    fun `grace survives the issuing service disappearing and uses PostgreSQL time`() = testApplication {
        usePostgresTestcontainer()
        val userId = user("restart")
        val durable = UUID.randomUUID().toString()
        assertEquals(RefreshClaim.CLAIMED, newTokenBlocklistService().claimRefresh(durable, farFuture, userId))
        assertEquals(RefreshClaim.GRACE_REPLAY, newTokenBlocklistService().claimRefresh(durable, farFuture, userId))

        val expiring = UUID.randomUUID().toString()
        val shortGrace = newTokenBlocklistService(replayGraceMillis = 50)
        assertEquals(RefreshClaim.CLAIMED, shortGrace.claimRefresh(expiring, farFuture, userId))
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("SELECT pg_sleep(0.08)").use { it.executeQuery().close() }
        }
        assertEquals(RefreshPrecheck.REPLAYED, newTokenBlocklistService(replayGraceMillis = 50).refreshPrecheck(expiring))
        assertEquals(RefreshClaim.REPLAYED, shortGrace.claimRefresh(expiring, farFuture, userId))
    }

    @Test
    fun `a full bounded store keeps existing grace and makes the unrecorded rotation strict`() = testApplication {
        usePostgresTestcontainer()
        val firstUser = user("cap-first")
        val secondUser = user("cap-second")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val service = newTokenBlocklistService(maxRotationRecords = 1)

        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(first, farFuture, firstUser))
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(second, farFuture, secondUser))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(first, farFuture, firstUser))
        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(second, farFuture, secondUser))
    }

    @Test
    fun `a full bounded store promptly reuses an expired grace slot while the old revocation stays strict`() = testApplication {
        usePostgresTestcontainer()
        val firstUser = user("expired-cap-first")
        val secondUser = user("expired-cap-second")
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val service = newTokenBlocklistService(replayGraceMillis = 50, maxRotationRecords = 1)

        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(first, farFuture, firstUser))
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("SELECT pg_sleep(0.08)").use { it.executeQuery().close() }
        }

        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(second, farFuture, secondUser))
        assertEquals(
            RefreshClaim.REVOKED,
            service.claimRefresh(first, farFuture, firstUser),
            "reusing rotation metadata must not remove the original token's denylist row",
        )
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(second, farFuture, secondUser))
    }

    @Test
    fun `logout atomically revokes the exact pair and clears both users grace only`() = testApplication {
        usePostgresTestcontainer()
        val refreshUser = user("logout-refresh")
        val accessUser = user("logout-access")
        val unrelatedUser = user("logout-unrelated")
        val refreshOld = UUID.randomUUID().toString()
        val accessOld = UUID.randomUUID().toString()
        val unrelatedOld = UUID.randomUUID().toString()
        val service = newTokenBlocklistService()
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(refreshOld, farFuture, refreshUser))
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(accessOld, farFuture, accessUser))
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(unrelatedOld, farFuture, unrelatedUser))

        val accessJti = UUID.randomUUID().toString()
        val presentedRefresh = UUID.randomUUID().toString()
        val otherAccessToken = UUID.randomUUID().toString()
        val otherRefreshToken = UUID.randomUUID().toString()
        assertTrue(
            service.logoutSession(
                accessUser, accessJti, farFuture,
                RefreshTokenRevocation(refreshUser, presentedRefresh, farFuture),
            ),
        )

        assertTrue(service.isRevoked(accessJti))
        assertTrue(service.isRevoked(presentedRefresh))
        assertFalse(service.isRevoked(otherAccessToken))
        assertFalse(service.isRevoked(otherRefreshToken))
        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(refreshOld, farFuture, refreshUser))
        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(accessOld, farFuture, accessUser))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(unrelatedOld, farFuture, unrelatedUser))
    }

    @Test
    fun `logout revocations and grace clearing roll back together`() = testApplication {
        usePostgresTestcontainer()
        val userId = user("rollback")
        val rotated = UUID.randomUUID().toString()
        val access = UUID.randomUUID().toString()
        val service = newTokenBlocklistService()
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(rotated, farFuture, userId))

        assertFailsWith<IllegalStateException> {
            suspendTransaction(sharedDatabaseForTests()) {
                service.logoutSession(userId, access, farFuture, refresh = null)
                error("roll back")
            }
        }
        assertFalse(service.isRevoked(access))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(rotated, farFuture, userId))
    }

    @Test
    fun `logout with an already revoked access token still clears that users refresh grace`() = testApplication {
        usePostgresTestcontainer()
        val userId = user("logout-already-revoked")
        val rotated = UUID.randomUUID().toString()
        val access = UUID.randomUUID().toString()
        val service = newTokenBlocklistService()
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(rotated, farFuture, userId))
        assertTrue(service.revokeIfAbsent(access, farFuture))

        assertFalse(service.logoutSession(userId, access, farFuture, refresh = null))
        assertTrue(service.isRevoked(access))
        assertEquals(
            RefreshClaim.REVOKED,
            service.claimRefresh(rotated, farFuture, userId),
            "idempotent access revocation must not skip the transactional grace cleanup",
        )
    }
}
