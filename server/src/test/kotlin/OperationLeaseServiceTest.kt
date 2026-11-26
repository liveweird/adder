package ch.nokillswit

import ch.nokillswit.infra.db.OperationLeaseService
import ch.nokillswit.infra.db.SharedOperation
import io.ktor.server.testing.testApplication
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** PostgreSQL contract for V60's cross-instance, per-operation/user leases. */
class OperationLeaseServiceTest {
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
}
