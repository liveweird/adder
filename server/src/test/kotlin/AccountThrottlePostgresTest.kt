package ch.nokillswit

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.validation.canonicalEmail
import ch.nokillswit.auth.LoginThrottle
import ch.nokillswit.auth.PasswordResetThrottle
import ch.nokillswit.auth.PostgresAccountThrottle
import ch.nokillswit.auth.PostgresPasswordResetThrottle
import ch.nokillswit.infra.db.connectPooledDatabase
import io.r2dbc.spi.ConnectionFactoryOptions
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/** PostgreSQL contract for the V56 cross-instance account-attempt limits. */
class AccountThrottlePostgresTest {
    private val secret = "7b".repeat(32)

    @Test
    fun `fresh schema applies the immutable V56 migration before V56 point 1`() = withIsolatedStore {
        connect().use { connection ->
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
    fun `failures from separate instances share one lock and persist only a keyed digest`() = withIsolatedStore {
        val email = "Raw-Secret-${UUID.randomUUID()}@Example.Test"
        assertEquals(LoginThrottle.FailureResult.RECORDED, login().recordFailure(email).result)
        val locking = login().recordFailure(email)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, locking.result)
        assertEquals(LoginThrottle.Preflight.LOCKED, login().preflight(email.lowercase()))

        connect().use { connection ->
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
    fun `capacity is atomic across instances while known accounts retain their reserve`() = withIsolatedStore {
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
        val knownId = seedUser(known)
        assertEquals(LoginThrottle.FailureResult.RECORDED, login(maxTracked = 1).recordFailure(known, knownId).result)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, login(maxTracked = 1).recordFailure(known, knownId).result)
    }

    @Test
    fun `database deadlines expire locks and MFA escalation survives the issuing instance`() = withIsolatedStore {
        val email = "mfa-${UUID.randomUUID()}@test"
        val userId = seedUser(email)
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
    fun `clear treats state at its exact database deadline as expired but removes its residue`() = withIsolatedStore {
        val email = "expired-${UUID.randomUUID()}@test"
        val hash = DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").ofCanonical(canonicalEmail(email))
        connect().use { connection ->
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
        connect().use { connection ->
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
    fun `a success racing the locking failure cannot clear a newly active lock`() = withIsolatedStore {
        val email = "race-${UUID.randomUUID()}@test"
        val userId = seedUser(email)
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
    fun `proved reservation follows repeated email changes and rejects a stale captured address`() = withIsolatedStore {
        val firstEmail = "known-move-${UUID.randomUUID()}@test"
        val userId = seedUser(firstEmail)
        val throttle = login(threshold = 100, maxTracked = 1)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("  ${firstEmail.uppercase()} ", userId).result)

        var currentEmail = firstEmail
        repeat(12) { index ->
            val previous = currentEmail
            currentEmail = "known-move-$index-${UUID.randomUUID()}@test"
            renameUser(userId, currentEmail)
            assertEquals(
                LoginThrottle.FailureResult.ALREADY_LOCKED,
                throttle.recordFailure(previous, userId).result,
                "a request captured before the rename must not move or mutate the reservation",
            )
            assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(currentEmail, userId).result)
            assertEquals(1, attemptRowCount(userId), "a rename must move, not accumulate, the proved reservation")
        }

        connect().use { connection ->
            connection.prepareStatement("SELECT identity_hash, user_id FROM account_login_attempts").use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(
                        DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").ofCanonical(currentEmail),
                        rows.getString(1).trim(),
                    )
                    assertEquals(userId.toLong(), rows.getLong(2))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `a new owner of an email inherits neither the former owner's live lock nor MFA escalation`() = withIsolatedStore {
        val email = "known-reuse-${UUID.randomUUID()}@test"
        val firstId = seedUser(email)
        val throttle = login(threshold = 2)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email, firstId, mfaCode = true).result)
        val formerLock = throttle.recordFailure(email, firstId, mfaCode = true)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, formerLock.result)
        assertEquals(1, formerLock.level)
        softDelete(firstId)
        val replacementId = seedUser(email)

        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight(email, replacementId))
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email, replacementId, mfaCode = true).result)
        val replacementLock = throttle.recordFailure(email, replacementId, mfaCode = true)
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, replacementLock.result)
        assertEquals(1, replacementLock.level, "the replacement account starts its own MFA ladder")
        assertEquals(0, attemptRowCount(firstId), "the former user's stale address binding is reclaimed")
        assertEquals(1, attemptRowCount(replacementId))
    }

    @Test
    fun `proved account preserves the anonymous address window accumulated before proof`() = withIsolatedStore {
        val email = "known-anonymous-${UUID.randomUUID()}@test"
        val userId = seedUser(email)
        val throttle = login(threshold = 2)

        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email).result)
        assertEquals(
            LoginThrottle.FailureResult.LOCKED_NOW,
            throttle.recordFailure(email, userId).result,
            "binding an unbound address row must not give the proved account a fresh window",
        )
        assertEquals(1, attemptRowCount(userId))
    }

    @Test
    fun `reset cooldown and capacity are shared and use a separate digest domain`() = withIsolatedStore {
        val email = "reset-${UUID.randomUUID()}@test"
        val first = PostgresPasswordResetThrottle(database, secret, 120, 1)
        val second = PostgresPasswordResetThrottle(database, secret, 120, 1)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, first.tryAcquire(email))
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, second.tryAcquire(email.uppercase()))
        assertEquals(
            PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED,
            second.tryAcquire("other-${UUID.randomUUID()}@test"),
        )
        delay(150)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, second.tryAcquire(email))

        assertFalse(
            DeploymentKeyedDigest(secret, "adder.account-login-attempt.v1").ofCanonical(canonicalEmail(email)) ==
                DeploymentKeyedDigest(secret, "adder.password-reset-cooldown.v1").ofCanonical(canonicalEmail(email)),
        )
        connect().use { connection ->
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

    private fun withIsolatedStore(test: suspend IsolatedStore.() -> Unit): Unit = withIsolatedTestDatabase { fixture ->
        runBlocking {
            val options = ConnectionFactoryOptions.parse(fixture.r2dbcUrl)
                .mutate()
                .option(ConnectionFactoryOptions.USER, PostgresTestSupport.user)
                .option(ConnectionFactoryOptions.PASSWORD, PostgresTestSupport.password)
                .build()
            val (database, pool) = connectPooledDatabase(options, 2, 0, Duration.ofSeconds(10), Duration.ofMinutes(1))
            try {
                IsolatedStore(fixture, database).test()
            } finally {
                pool.dispose()
            }
        }
    }

    private inner class IsolatedStore(
        private val fixture: IsolatedTestDatabase,
        val database: R2dbcDatabase,
    ) {
        fun connect() = fixture.connect()

        fun login(
            threshold: Int = 2,
            lockoutMillis: Long = 120,
            maxTracked: Int = 10_000,
            mfaDecayMillis: Long = 500,
        ) = PostgresAccountThrottle(
            database, secret, threshold, lockoutMillis, maxTracked,
            mfaMaxLockoutMillis = 10_000, mfaDecayMillis = mfaDecayMillis,
        )

        fun seedUser(email: String): UInt = connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO users (\"name\", email, password_hash, \"role\") VALUES ('Test', ?, 'x', 'ADMIN') RETURNING id",
            ).use { statement ->
                statement.setString(1, email)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getLong(1).toUInt()
                }
            }
        }

        fun renameUser(userId: UInt, email: String) {
            connect().use { connection ->
                connection.prepareStatement("UPDATE users SET email = ? WHERE id = ?").use { statement ->
                    statement.setString(1, email)
                    statement.setLong(2, userId.toLong())
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        fun softDelete(userId: UInt) {
            connect().use { connection ->
                connection.prepareStatement("UPDATE users SET marked_as_deleted = TRUE WHERE id = ?").use { statement ->
                    statement.setLong(1, userId.toLong())
                    check(statement.executeUpdate() == 1)
                }
            }
        }

        fun attemptRowCount(userId: UInt): Int = connect().use { connection ->
            connection.prepareStatement("SELECT count(*) FROM account_login_attempts WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId.toLong())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getInt(1)
                }
            }
        }
    }
}
