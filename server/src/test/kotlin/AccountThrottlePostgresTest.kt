package ch.nokillswit

import ch.nokillswit.auth.LoginThrottle
import ch.nokillswit.auth.PasswordResetThrottle
import ch.nokillswit.auth.DeploymentKeyedDigest
import ch.nokillswit.auth.PostgresAccountThrottle
import ch.nokillswit.auth.PostgresPasswordResetThrottle
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PostgreSQL contract for the V56 cross-instance account-attempt limits. */
class AccountThrottlePostgresTest {
    private val secret = "7b".repeat(32)

    @BeforeTest
    fun prepare() = clearAttemptState()

    @AfterTest
    fun cleanUp() = clearAttemptState()

    private fun login(
        threshold: Int = 2,
        lockoutMillis: Long = 120,
        maxTracked: Int = 10_000,
        mfaDecayMillis: Long = 500,
    ) = PostgresAccountThrottle(
        sharedDatabaseForTests(), secret, threshold, lockoutMillis, maxTracked,
        mfaMaxLockoutMillis = 10_000, mfaDecayMillis = mfaDecayMillis,
    )

    @Test
    fun `fresh schema applies the immutable V56 migration before V56 point 1`() {
        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE version IN ('56', '56.1') ORDER BY installed_rank",
                ).use { rows ->
                    val versions = buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                    assertEquals(listOf("56", "56.1"), versions)
                }
            }
        }
    }

    @Test
    fun `failures from separate instances share one lock and persist only a keyed digest`() = runBlocking {
        val email = "Raw-Secret-${UUID.randomUUID()}@Example.Test"
        assertEquals(LoginThrottle.FailureResult.RECORDED, login().recordFailure(email).result)
        val locking = login().recordFailure(email)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, locking.result)
        assertEquals(LoginThrottle.Preflight.LOCKED, login().preflight(email.lowercase()))

        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT identity_hash, user_id FROM account_login_attempts").use { rows ->
                    assertTrue(rows.next())
                    val stored = rows.getString(1).trim()
                    assertEquals(64, stored.length)
                    assertFalse(stored.contains("raw-secret", ignoreCase = true))
                    assertFalse(stored.contains("example.test", ignoreCase = true))
                    assertEquals(null, rows.getObject(2), "public-login identities stay unbound")
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `capacity is atomic across instances while known accounts retain their reserve`() = runBlocking {
        val outcomes = coroutineScope {
            listOf("one-${UUID.randomUUID()}@test", "two-${UUID.randomUUID()}@test").map { email ->
                async { login(maxTracked = 1).recordFailure(email).result }
            }.awaitAll()
        }
        assertEquals(
            setOf(LoginThrottle.FailureResult.RECORDED, LoginThrottle.FailureResult.CAPACITY_EXCEEDED),
            outcomes.toSet(),
        )
        val known = "known-${UUID.randomUUID()}@test"
        val knownId = TestUsers.seed(known, "password")
        assertEquals(LoginThrottle.FailureResult.RECORDED, login(maxTracked = 1).recordFailure(known, knownId).result)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, login(maxTracked = 1).recordFailure(known, knownId).result)
    }

    @Test
    fun `database deadlines expire locks and MFA escalation survives the issuing instance`() = runBlocking {
        val email = "mfa-${UUID.randomUUID()}@test"
        val userId = TestUsers.seed(email, "password")
        login().recordFailure(email, userId, mfaCode = true)
        val first = login().recordFailure(email, userId, mfaCode = true)
        assertEquals(1, first.level)
        delay(first.lockMillis + 30)
        assertEquals(LoginThrottle.Preflight.ALLOWED, login().preflight(email, userId))

        login().recordFailure(email, userId, mfaCode = true)
        val second = login().recordFailure(email, userId, mfaCode = true)
        assertEquals(2, second.level)
        assertEquals(first.lockMillis * 2, second.lockMillis)
    }

    @Test
    fun `clear treats state at its exact database deadline as expired but removes its residue`() = runBlocking {
        val email = "expired-${UUID.randomUUID()}@test"
        val hash = DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").of(email)
        PostgresTestSupport.connect().use { connection ->
            val expiresAt = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint",
                ).use { rows ->
                    assertTrue(rows.next())
                    rows.getLong(1)
                }
            }
            connection.prepareStatement(
                """
                INSERT INTO account_login_attempts (
                    identity_hash, failures, locked_until, state_expires_at, code_failed,
                    escalation_level, escalation_anchor, escalation_expires_at, code_guesses, code_expires_at
                ) VALUES (?, 1, ?, ?, TRUE, 1, ?, ?, 1, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, hash)
                for (index in 2..6) statement.setLong(index, expiresAt)
                assertEquals(1, statement.executeUpdate())
            }
        }

        assertFalse(login().clear(email), "a deadline is exclusive: state is not live at the exact expiry instant")
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM account_login_attempts WHERE identity_hash = CAST(? AS char(64))",
            ).use { statement ->
                statement.setString(1, hash)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1), "fully expired storage residue is still removed")
                }
            }
        }
    }

    @Test
    fun `a success racing the locking failure cannot clear a newly active lock`() = runBlocking {
        val email = "race-${UUID.randomUUID()}@test"
        val userId = TestUsers.seed(email, "password")
        login().recordFailure(email, userId)
        val results = coroutineScope {
            listOf(
                async { login().recordFailure(email, userId).result.name },
                async { login().recordSuccess(email, userId).name },
            ).awaitAll()
        }
        if (LoginThrottle.FailureResult.LOCKED_NOW.name in results) {
            assertEquals(LoginThrottle.SuccessResult.LOCKED.name, results.last())
            assertEquals(LoginThrottle.Preflight.LOCKED, login().preflight(email, userId))
        } else {
            assertTrue(LoginThrottle.FailureResult.RECORDED.name in results)
        }
    }

    @Test
    fun `proved reservation follows repeated email changes and rejects a stale captured address`() = runBlocking {
        val firstEmail = "known-move-${UUID.randomUUID()}@test"
        val userId = TestUsers.seed(firstEmail, "password")
        val throttle = login(threshold = 100, maxTracked = 1)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("  ${firstEmail.uppercase()} ", userId).result)

        var currentEmail = firstEmail
        repeat(12) { index ->
            val previous = currentEmail
            currentEmail = "known-move-$index-${UUID.randomUUID()}@test"
            TestUsers.service.updateGuarded(userId, "Test", currentEmail, UserRole.ADMIN)
            assertEquals(
                LoginThrottle.FailureResult.ALREADY_LOCKED,
                throttle.recordFailure(previous, userId).result,
                "a request captured before the rename must not move or mutate the reservation",
            )
            assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(currentEmail, userId).result)
            assertEquals(1, attemptRowCount(userId), "a rename must move, not accumulate, the proved reservation")
        }

        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("SELECT identity_hash, user_id FROM account_login_attempts").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(
                        DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").of(currentEmail),
                        rows.getString(1).trim(),
                    )
                    assertEquals(userId.toLong(), rows.getLong(2))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `a different live user reclaims a former account's stale email binding`() = runBlocking {
        val email = "known-reuse-${UUID.randomUUID()}@test"
        val firstId = TestUsers.seed(email, "password")
        val throttle = login(threshold = 100)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email, firstId).result)
        TestUsers.softDelete(firstId)
        val replacementId = TestUsers.seed(email, "password")

        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email).result)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email, replacementId).result)
        assertEquals(0, attemptRowCount(firstId), "the former user's stale address binding is reclaimed")
        assertEquals(1, attemptRowCount(replacementId))
    }

    @Test
    fun `reset cooldown and capacity are shared and use a separate digest domain`() = runBlocking {
        val email = "reset-${UUID.randomUUID()}@test"
        val first = PostgresPasswordResetThrottle(sharedDatabaseForTests(), secret, 120, 1)
        val second = PostgresPasswordResetThrottle(sharedDatabaseForTests(), secret, 120, 1)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, first.tryAcquire(email))
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, second.tryAcquire(email.uppercase()))
        assertEquals(
            PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED,
            second.tryAcquire("other-${UUID.randomUUID()}@test"),
        )
        delay(150)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, second.tryAcquire(email))

        assertFalse(
            DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").of(email) ==
                DeploymentKeyedDigest(secret, "adder.password-reset-cooldown.v1").of(email),
        )
        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                val resetHash = statement.executeQuery("SELECT identity_hash FROM password_reset_cooldowns LIMIT 1").use { rows ->
                    rows.next()
                    rows.getString(1).trim()
                }
                assertFalse(resetHash.contains("reset", ignoreCase = true))
                assertFalse(resetHash.contains("@test", ignoreCase = true))
            }
        }
    }

    private fun clearAttemptState() {
        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM account_login_attempts")
                statement.executeUpdate("DELETE FROM password_reset_cooldowns")
            }
        }
    }

    private fun attemptRowCount(userId: UInt): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM account_login_attempts WHERE user_id = ?").use { statement ->
            statement.setLong(1, userId.toLong())
            statement.executeQuery().use { rows ->
                assertTrue(rows.next())
                rows.getInt(1)
            }
        }
    }
}
