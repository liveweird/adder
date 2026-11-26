package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginThrottleKey
import ch.nokillswit.auth.MfaChallengeResponse
import ch.nokillswit.auth.MfaVerifyRequest
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MfaResetRaceTest {
    @Test
    fun `a wrong MFA code already in flight cannot re-lock the account after reset commits`() = testApplication {
        configureApp("security.lockout.threshold" to "1")
        startApplication()
        val email = uniqueEmail("mfa-reset-fence")
        val userId = TestUsers.seed(email, "old-password-123")
        TestUsers.service.setDisabledFeatures(userId, emptySet())
        val client = jsonClient()
        val mail = LogCapture("ch.nokillswit.mail")
        try {
            val (challengeId, code) = pendingCode(client, email, mail)
            val resets = newPasswordResetService()
            val token = assertNotNull(resets.issueToken(userId, 0, email))
            val throttle = application.attributes[LoginThrottleKey]
            val resetPrepared = CompletableDeferred<Unit>()
            val commitReset = CompletableDeferred<Unit>()
            coroutineScope {
                val reset = async {
                    resets.complete(token, hashPassword("new-password-123", cost = 4)) { targetId, identity ->
                        val cleared = throttle.clearInCurrentTransaction(identity, targetId)
                        resetPrepared.complete(Unit)
                        commitReset.await()
                        cleared
                    }
                }
                try {
                    withTimeout(5_000) { resetPrepared.await() }
                    val guess = async {
                        client.postJson("/api/v1/login/mfa", MfaVerifyRequest(challengeId, incorrectCode(code)))
                    }
                    withTimeout(5_000) { while (!guessIsWaiting()) delay(10) }
                    commitReset.complete(Unit)
                    assertNotNull(reset.await())
                    assertEquals(HttpStatusCode.Unauthorized, guess.await().status)
                } finally {
                    commitReset.complete(Unit)
                }
            }
            assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/login", LoginRequest(email, "new-password-123")).status)
        } finally {
            mail.detach()
            discardTestMfaChallenges(listOf(userId))
            TestUsers.softDelete(userId)
        }
    }

    @Test
    fun `a changed email invalidates older correct and wrong codes`() = testApplication {
        configureApp("security.lockout.threshold" to "1")
        startApplication()
        val client = jsonClient()
        val mail = LogCapture("ch.nokillswit.mail")
        val audit = LogCapture("ch.nokillswit.audit")
        val accounts = mutableListOf<UInt>()
        try {
            for (correct in listOf(false, true)) {
                val formerEmail = uniqueEmail("mfa-former-address")
                val userId = TestUsers.seed(formerEmail, "old-password-123", role = UserRole.USER).also { accounts += it }
                TestUsers.service.setDisabledFeatures(userId, emptySet())
                val (challengeId, code) = pendingCode(client, formerEmail, mail)
                assertEquals(UserService.GuardedMutation.DONE, TestUsers.service.updateGuarded(
                    userId, "Changed address", uniqueEmail("mfa-new-address"), UserRole.USER,
                ))
                val submitted = if (correct) code else incorrectCode(code)
                val response = client.postJson("/api/v1/login/mfa", MfaVerifyRequest(challengeId, submitted))
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertNotNull(audit.awaitEvent {
                    it.message == "login.mfa_failure" && it.hasKeyValue("reason", "email_changed") &&
                        it.hasKeyValue("userId", userId.toLong())
                })
                assertTrue(audit.events.none { it.message == "login.lockout" && it.hasKeyValue("userId", userId.toLong()) })
            }
        } finally {
            mail.detach()
            audit.detach()
            discardTestMfaChallenges(accounts)
            accounts.forEach { TestUsers.softDelete(it) }
        }
    }

    private suspend fun pendingCode(client: HttpClient, email: String, mail: LogCapture): Pair<String, String> {
        val response = client.postJson("/api/v1/login", LoginRequest(email, "old-password-123")).body<MfaChallengeResponse>()
        val delivered = assertNotNull(mail.awaitEvent { "To: $email" in it.formattedMessage })
        val code = assertNotNull(Regex("(?m)^\\d{6}$").find(delivered.formattedMessage)?.value)
        return response.challengeId to code
    }

    private fun incorrectCode(code: String): String = if (code == "000000") "000001" else "000000"

    /** The request must reach the throttle or credential lock while the reset transaction still owns both. */
    private fun guessIsWaiting(): Boolean = PostgresTestSupport.connect().use { connection ->
        val sql = "SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND " +
            "(query LIKE 'SELECT pg_advisory_xact_lock%' OR query LIKE '%credential_revision%FOR NO KEY UPDATE%')"
        connection.createStatement().use { statement -> statement.executeQuery(sql).use { it.next() } }
    }
}
