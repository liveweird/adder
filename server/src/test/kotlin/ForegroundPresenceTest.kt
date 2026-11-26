package ch.nokillswit

import ch.nokillswit.infra.presence.PostgresForegroundPresence
import ch.nokillswit.live.LiveLeaseAdmission
import ch.nokillswit.live.LiveSessionLeaseStore
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class ForegroundPresenceTest {
    private var userId: UInt? = null

    @AfterTest
    fun cleanUp() {
        runBlocking {
            userId?.let { id ->
                PostgresTestSupport.connect().use { connection ->
                    connection.prepareStatement("DELETE FROM live_session_leases WHERE user_id = ?").use { statement ->
                        statement.setLong(1, id.toLong())
                        statement.executeUpdate()
                    }
                }
                TestUsers.softDelete(id)
            }
        }
    }

    @Test
    fun `database presence requires a fresh visible live session`() = runBlocking {
        val user = TestUsers.seed(uniqueEmail("presence"), "pw", role = UserRole.USER).also { userId = it }
        val store = LiveSessionLeaseStore(sharedDatabaseForTests(), UUID.randomUUID())
        val lookup = PostgresForegroundPresence(sharedDatabaseForTests())
        val session = UUID.randomUUID()
        val tokenExpiry = System.currentTimeMillis() + 300_000
        assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, session, tokenExpiry, 16))

        assertEquals(emptySet(), lookup.eligibleUserIds(listOf(user)))
        store.setForeground(listOf(session), visible = true)
        assertEquals(setOf(user), lookup.eligibleUserIds(listOf(user, user)))
        store.setForeground(listOf(session), visible = false)
        assertEquals(emptySet(), lookup.eligibleUserIds(listOf(user)))
    }

    @Test
    fun `presence lookup participates in the calling message transaction`() = runBlocking {
        val user = TestUsers.seed(uniqueEmail("presence-tx"), "pw", role = UserRole.USER).also { userId = it }
        val database = sharedDatabaseForTests()
        val store = LiveSessionLeaseStore(database, UUID.randomUUID())
        val lookup = PostgresForegroundPresence(database)
        val session = UUID.randomUUID()
        assertIs<LiveLeaseAdmission.Accepted>(store.admit(user, session, System.currentTimeMillis() + 300_000, 16))

        try {
            suspendTransaction(database) {
                TransactionManager.current().exec(
                    "UPDATE live_session_leases SET foreground_expires_at = token_expires_at WHERE session_id = CAST(? AS uuid)",
                    listOf(UUIDColumnType() to session),
                    explicitStatementType = StatementType.UPDATE,
                )
                assertEquals(setOf(user), lookup.eligibleUserIds(listOf(user)))
                throw RollBackPresence
            }
        } catch (_: RollBackPresenceException) {
            // The outer write rolls back; the nested lookup must not have opened and committed an independent transaction.
        }
        assertEquals(emptySet(), lookup.eligibleUserIds(listOf(user)))
    }

    private object RollBackPresence : RollBackPresenceException()
    private open class RollBackPresenceException : RuntimeException()
}
