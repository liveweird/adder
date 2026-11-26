package ch.nokillswit

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.infra.notify.LiveNotification
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.updatePasswordInTransaction
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID

/** Account prompts share the mutation transaction: commit publishes exactly once and rollback publishes nothing. */
class AccountNotifyTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking { users.forEach { TestUsers.softDelete(it) } }

    @Test
    fun `a successful user update and password revision publish account prompts only on commit`() = testApplication {
        usePostgresTestcontainer()
        val id = TestUsers.seed(uniqueEmail("account-notify"), "password-123", role = UserRole.USER).also { users += it }
        val user = requireNotNull(TestUsers.service.read(id))

        NotifyCapture().use { capture ->
            val update = capture.duringNotifications {
                assertEquals(
                    ch.nokillswit.users.UserService.GuardedMutation.DONE,
                    TestUsers.service.updateGuarded(id, user.name, user.email, user.role),
                )
            }
            assertEquals(listOf(LiveNotification.Account(id)), update, "same-role updates are still re-judged")

            val rolledBack = capture.duringNotifications {
                val failure = runCatching {
                    suspendTransaction(sharedDatabaseForTests()) {
                        updatePasswordInTransaction(id, hashPassword("replacement-123", cost = 4), expectedRevision = null)
                        error("force rollback")
                    }
                }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
            }
            assertEquals(emptyList(), rolledBack)

            val committed = capture.duringNotifications {
                assertEquals(1, TestUsers.service.updatePassword(id, hashPassword("replacement-456", cost = 4)))
            }
            assertEquals(listOf(LiveNotification.Account(id)), committed)

            val afterPassword = requireNotNull(TestUsers.service.read(id))
            val bootstrap = capture.duringNotifications {
                assertEquals(
                    1,
                    TestUsers.service.rotatePasswordIfHashMatches(
                        afterPassword.email, afterPassword.passwordHash, hashPassword("replacement-789", cost = 4),
                    ),
                )
            }
            assertEquals(listOf(LiveNotification.Account(id)), bootstrap)

            val beforeReset = requireNotNull(TestUsers.service.read(id))
            val reset = newPasswordResetService()
            val token = requireNotNull(reset.issueToken(id, beforeReset.credentialRevision, beforeReset.email))
            val resetNotifications = capture.duringNotifications {
                assertEquals(id, requireNotNull(reset.complete(token, hashPassword("replacement-abc", cost = 4))).userId)
            }
            assertEquals(listOf(LiveNotification.Account(id)), resetNotifications)

            val blocklist = newTokenBlocklistService()
            val genericRevocation = capture.duringNotifications {
                blocklist.revokeIfAbsent(UUID.randomUUID().toString(), System.currentTimeMillis() + 60_000)
            }
            assertEquals(emptyList(), genericRevocation, "refresh-token revocations stay quiet")
            val accessRevocation = capture.duringNotifications {
                blocklist.revokeAccess(id, UUID.randomUUID().toString(), System.currentTimeMillis() + 60_000)
            }
            assertEquals(listOf(LiveNotification.Account(id)), accessRevocation)
            val rolledBackRevocation = capture.duringNotifications {
                val failure = runCatching {
                    suspendTransaction(sharedDatabaseForTests()) {
                        blocklist.revokeAccess(id, UUID.randomUUID().toString(), System.currentTimeMillis() + 60_000)
                        error("force logout rollback")
                    }
                }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
            }
            assertEquals(emptyList(), rolledBackRevocation, "a rolled-back logout publishes no account prompt")

            val deleted = capture.duringNotifications {
                assertEquals(
                    ch.nokillswit.users.UserService.GuardedMutation.DONE,
                    TestUsers.service.deleteGuarded(id),
                )
            }
            assertEquals(listOf(LiveNotification.Account(id)), deleted)
        }
    }
}
