package ch.nokillswit

import ch.nokillswit.auth.ACCESS_VERDICT_CHUNK
import ch.nokillswit.auth.AccessCheck
import ch.nokillswit.auth.AccessVerdict
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `TokenBlocklistService.accessVerdicts` (the live account sweep's batch) against `accessVerdict` (the per-request check), straight at
 * the database: both go through ONE decision function, and this pins that for every verdict they answer the same, in the order asked,
 * across the chunk boundary.
 */
class AccessVerdictsTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { users.forEach { TestUsers.softDelete(it) } }

    private suspend fun seed(role: UserRole = UserRole.USER): UInt =
        TestUsers.seed(uniqueEmail("verdicts"), "pw", role = role).also { users += it }

    private fun update(userId: UInt, set: String) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("UPDATE users SET $set WHERE id = ?").use { st ->
            st.setLong(1, userId.toLong())
            st.executeUpdate()
        }
    }

    @Test
    fun `the batch agrees with the single check for every verdict, in the order asked`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { System.currentTimeMillis() }
        val live = seed()
        val admin = seed(UserRole.ADMIN)
        val gone = seed().also { TestUsers.softDelete(it) }
        val rotated = seed().also { update(it, "credential_revision = credential_revision + 1") }
        val demoted = seed(UserRole.ADMIN).also { update(it, "role = 'USER'") }
        val loggedOut = seed()
        val revokedJti = UUID.randomUUID().toString().also { service.revoke(it, System.currentTimeMillis() + 600_000) }
        val missing = 2_000_000_000u

        fun jti() = UUID.randomUUID().toString()
        val checks = listOf(
            AccessCheck(jti(), live, 0, emptySet()),
            AccessCheck(jti(), admin, 0, setOf("ADMIN")),
            AccessCheck(jti(), gone, 0, emptySet()),
            AccessCheck(jti(), rotated, 0, emptySet()),
            AccessCheck(jti(), demoted, 0, setOf("ADMIN")),
            AccessCheck(revokedJti, loggedOut, 0, emptySet()),
            AccessCheck(jti(), missing, 0, emptySet()),
            AccessCheck(jti(), live, 0, setOf("ADMIN")), // the token claims a role the account does not have
        )
        val expected = checks.map { service.accessVerdict(it.jti, it.userId, it.credentialRevision, it.roles) }
        assertEquals(
            listOf(
                AccessVerdict.LIVE, AccessVerdict.LIVE, AccessVerdict.USER_GONE, AccessVerdict.CREDENTIAL_CHANGED,
                AccessVerdict.ROLES_CHANGED, AccessVerdict.REVOKED, AccessVerdict.USER_GONE, AccessVerdict.ROLES_CHANGED,
            ),
            expected,
            "the single check's own answers (the baseline)",
        )
        assertEquals(expected, service.accessVerdicts(checks))
    }

    @Test
    fun `a batch past the chunk size is judged in several chunks and keeps its order`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { System.currentTimeMillis() }
        val live = seed()
        val gone = seed().also { TestUsers.softDelete(it) }
        // A revoked jti sits in the SECOND chunk (index 1003), where a per-chunk lookup that forgot it would answer LIVE.
        val lateRevoked = UUID.randomUUID().toString().also { service.revoke(it, System.currentTimeMillis() + 600_000) }
        val lateIndex = ACCESS_VERDICT_CHUNK + 3
        val checks = (0 until ACCESS_VERDICT_CHUNK + 5).map {
            AccessCheck(if (it == lateIndex) lateRevoked else UUID.randomUUID().toString(), if (it % 7 == 0) gone else live, 0, emptySet())
        }
        val verdicts = service.accessVerdicts(checks)
        assertEquals(checks.size, verdicts.size)
        checks.forEachIndexed { i, check ->
            val expected = when {
                check.userId == gone -> AccessVerdict.USER_GONE
                i == lateIndex -> AccessVerdict.REVOKED
                else -> AccessVerdict.LIVE
            }
            assertEquals(expected, verdicts[i], "check $i")
        }
        assertEquals(emptyList(), service.accessVerdicts(emptyList()))
    }

    @Test
    fun `a deleted account whose token is also revoked is USER_GONE, in both checks`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { System.currentTimeMillis() }
        val gone = seed().also { TestUsers.softDelete(it) }
        val jti = UUID.randomUUID().toString().also { service.revoke(it, System.currentTimeMillis() + 600_000) }
        assertEquals(AccessVerdict.USER_GONE, service.accessVerdict(jti, gone, 0, emptySet()))
        assertEquals(listOf(AccessVerdict.USER_GONE), service.accessVerdicts(listOf(AccessCheck(jti, gone, 0, emptySet()))))
    }
}
