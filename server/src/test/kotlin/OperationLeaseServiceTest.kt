package ch.nokillswit

import ch.nokillswit.infra.db.OperationLeaseService
import ch.nokillswit.infra.db.SharedOperation
import io.ktor.server.testing.testApplication
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** PostgreSQL contract for V60's cross-instance, per-operation/user leases. */
class OperationLeaseServiceTest {
    @Test
    fun `a nonpositive lease ttl is rejected before database use`() = testApplication {
        usePostgresTestcontainer()

        assertFailsWith<IllegalArgumentException> { OperationLeaseService(sharedDatabaseForTests(), ttlMillis = 0) }
    }

    @Test
    fun `two stores share a user limit while other users and operations remain independent`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val first = OperationLeaseService(database)
        val second = OperationLeaseService(database)
        val user = TestUsers.seed(uniqueEmail("lease-shared"), "password")
        val other = TestUsers.seed(uniqueEmail("lease-other"), "password")

        val read = assertNotNull(first.tryAcquire(SharedOperation.READ_ALL, user))
        assertNull(second.tryAcquire(SharedOperation.READ_ALL, user), "the same operation and user are shared across stores")
        val otherRead = assertNotNull(second.tryAcquire(SharedOperation.READ_ALL, other))
        val preview = assertNotNull(second.tryAcquire(SharedOperation.LINK_PREVIEW, user))

        first.releaseNonCancellable(read)
        second.releaseNonCancellable(otherRead)
        second.releaseNonCancellable(preview)
    }

    @Test
    fun `an expired crashed owner is replaced and its stale token cannot renew or release the successor`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val first = OperationLeaseService(database, ttlMillis = 40)
        val second = OperationLeaseService(database, ttlMillis = 40)
        val user = TestUsers.seed(uniqueEmail("lease-takeover"), "password")
        val stale = assertNotNull(first.tryAcquire(SharedOperation.READ_ALL, user))

        delay(60)
        val current = assertNotNull(second.tryAcquire(SharedOperation.READ_ALL, user))
        assertNotEquals(stale.ownerId, current.ownerId)
        assertFalse(suspendTransaction(database) { first.renewInCurrentTransaction(stale) })
        first.releaseNonCancellable(stale)
        assertTrue(second.renew(current), "a stale exact-owner release cannot expire the successor")
        second.releaseNonCancellable(current)
    }

    @Test
    fun `a competing acquire refuses without waiting while the current owner fences a write transaction`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val first = OperationLeaseService(database)
        val second = OperationLeaseService(database)
        val user = TestUsers.seed(uniqueEmail("lease-nowait"), "password")
        val lease = assertNotNull(first.tryAcquire(SharedOperation.READ_ALL, user))
        val fenced = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()

        coroutineScope {
            val holder = async {
                suspendTransaction(database) {
                    assertTrue(first.renewInCurrentTransaction(lease))
                    fenced.complete(Unit)
                    finish.await()
                }
            }
            fenced.await()
            val elapsed = measureTimeMillis {
                assertNull(second.tryAcquire(SharedOperation.READ_ALL, user))
            }
            assertTrue(elapsed < 500, "admission waited $elapsed ms behind the fenced transaction")
            finish.complete(Unit)
            holder.await()
        }
        first.releaseNonCancellable(lease)
    }

    @Test
    fun `cancellation cleanup releases the exact lease in a non-cancellable context`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val first = OperationLeaseService(database)
        val second = OperationLeaseService(database)
        val user = TestUsers.seed(uniqueEmail("lease-cancel"), "password")
        val acquired = CompletableDeferred<Unit>()

        coroutineScope {
            val job = launch {
                val lease = assertNotNull(first.tryAcquire(SharedOperation.LINK_PREVIEW, user))
                try {
                    acquired.complete(Unit)
                    awaitCancellation()
                } finally {
                    first.releaseNonCancellable(lease)
                }
            }
            acquired.await()
            job.cancelAndJoin()
        }
        val replacement = assertNotNull(second.tryAcquire(SharedOperation.LINK_PREVIEW, user))
        second.releaseNonCancellable(replacement)
    }

    @Test
    fun `a foreign key failure is compensated and leaves no candidate lease`() = testApplication {
        usePostgresTestcontainer()
        val service = OperationLeaseService(sharedDatabaseForTests())
        val missingUser = UInt.MAX_VALUE

        assertNotNull(
            runCatching { service.tryAcquire(SharedOperation.READ_ALL, missingUser) }.exceptionOrNull(),
            "a lease cannot be stored for a user absent from the referenced users table",
        )
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM operation_leases WHERE operation = ? AND user_id = ?",
            ).use { statement ->
                statement.setString(1, SharedOperation.READ_ALL.storedName)
                statement.setLong(2, missingUser.toLong())
                statement.executeQuery().use { rows -> assertFalse(rows.next(), "compensation must leave no candidate row") }
            }
        }
    }

    @Test
    fun `cancelling an acquire blocked on the lease row compensates without expiring the current owner`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val first = OperationLeaseService(database)
        val second = OperationLeaseService(database)
        val user = TestUsers.seed(uniqueEmail("lease-acquire-cancel"), "password")
        val current = assertNotNull(first.tryAcquire(SharedOperation.READ_ALL, user))
        val blocker = PostgresTestSupport.connect()
        blocker.autoCommit = false
        try {
            blocker.prepareStatement(
                "SELECT owner_id FROM operation_leases WHERE operation = ? AND user_id = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, SharedOperation.READ_ALL.storedName)
                statement.setLong(2, user.toLong())
                statement.executeQuery().use { rows -> assertTrue(rows.next()) }
            }

            coroutineScope {
                val contender = async(Dispatchers.IO) { second.tryAcquire(SharedOperation.READ_ALL, user) }
                try {
                    waitForAcquireRowLock()
                    contender.cancel()
                    delay(50)
                    assertFalse(contender.isCompleted, "non-cancellable compensation still waits behind the row lock")
                } finally {
                    blocker.rollback()
                }
                contender.join()
                assertTrue(contender.isCancelled)
            }
            assertTrue(first.renew(current), "candidate compensation must match its random owner, not the previous owner")
        } finally {
            if (!blocker.isClosed) {
                blocker.rollback()
                blocker.close()
            }
            first.releaseNonCancellable(current)
        }
    }

    private suspend fun waitForAcquireRowLock() = withTimeout(5_000) {
        while (!acquireWaitsOnRowLock()) delay(10)
    }

    private fun acquireWaitsOnRowLock(): Boolean = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() " +
                "AND wait_event_type = 'Lock' AND query LIKE '%INSERT INTO operation_leases%')",
        ).use { statement ->
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }
}
