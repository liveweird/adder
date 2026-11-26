package ch.nokillswit

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.plugins.RATE_LIMIT_WINDOW_MILLIS
import ch.nokillswit.plugins.SharedRateLimitStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.ktor.server.testing.testApplication

class SharedRateLimitsTest {
    private val buckets = setOf("test-envelope", "test-race", "test-capacity")

    @AfterTest
    fun cleanUp() {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM http_rate_limit_buckets WHERE bucket_name = ANY(?)").use { statement ->
                statement.setArray(1, connection.createArrayOf("text", buckets.toTypedArray()))
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `fixed window exhausts and first use after expiry starts one exact fresh envelope`() = testApplication {
        usePostgresTestcontainer()
        val store = SharedRateLimitStore(sharedDatabaseForTests())
        val key = "a".repeat(64)

        val first = store.consume("test-envelope", key, limit = 3)
        val second = store.consume("test-envelope", key, limit = 3)
        val third = store.consume("test-envelope", key, limit = 3)
        val refused = store.consume("test-envelope", key, limit = 3)
        assertEquals(listOf(2, 1, 0), listOf(first.remaining, second.remaining, third.remaining))
        assertTrue(first.admitted && second.admitted && third.admitted)
        assertFalse(refused.admitted)
        assertEquals(first.refillAtMillis, refused.refillAtMillis, "rejections do not slide the fixed window")

        expire("test-envelope", key)
        val before = databaseNow()
        val fresh = store.consume("test-envelope", key, limit = 3)
        val after = databaseNow()
        assertTrue(fresh.admitted)
        assertEquals(2, fresh.remaining)
        assertTrue(fresh.serverNowMillis in before..after)
        assertEquals(fresh.serverNowMillis + RATE_LIMIT_WINDOW_MILLIS, fresh.refillAtMillis)
    }

    @Test
    fun `two stores racing one key share one token budget`() = testApplication {
        usePostgresTestcontainer()
        val first = SharedRateLimitStore(sharedDatabaseForTests())
        val second = SharedRateLimitStore(sharedDatabaseForTests())
        val key = "b".repeat(64)

        val decisions = coroutineScope {
            (0 until 30).map { index ->
                async { (if (index % 2 == 0) first else second).consume("test-race", key, limit = 16) }
            }.awaitAll()
        }

        assertEquals(16, decisions.count { it.admitted })
        assertEquals(14, decisions.count { !it.admitted })
        assertEquals((0 until 16).toSet(), decisions.filter { it.admitted }.map { it.remaining }.toSet())
    }

    @Test
    fun `new-key capacity refuses with a real refill deadline and expired cleanup is bounded`() = testApplication {
        usePostgresTestcontainer()
        val baseline = activeCount()
        val store = SharedRateLimitStore(sharedDatabaseForTests(), maxActiveKeys = baseline + 2)
        val first = "c".repeat(64)
        val second = "d".repeat(64)
        val third = "e".repeat(64)
        store.consume("test-capacity", first, limit = 3)
        store.consume("test-capacity", second, limit = 3)

        val refused = store.consume("test-capacity", third, limit = 3)
        assertFalse(refused.admitted)
        assertTrue(refused.capacityRefusal)
        assertTrue(refused.refillAtMillis > refused.serverNowMillis)

        expire("test-capacity", first)
        assertTrue(store.consume("test-capacity", third, limit = 3).admitted, "expired rows do not hold live capacity")
        assertEquals(1, store.pruneExpired(batchSize = 1))
        assertEquals(0, bucketCount("test-capacity", first))
    }

    @Test
    fun `deployment digest is deterministic domain separated and performs no hidden canonicalization`() {
        val first = DeploymentKeyedDigest("secret", "adder.rate-test.v1")
        val second = DeploymentKeyedDigest("secret", "adder.rate-test.v2")
        assertEquals(first.ofCanonical("canonical"), first.ofCanonical("canonical"))
        assertNotEquals(first.ofCanonical("canonical"), second.ofCanonical("canonical"))
        assertNotEquals(first.ofCanonical("A"), first.ofCanonical("a"))
        assertTrue(Regex("[0-9a-f]{64}").matches(first.ofCanonical("canonical")))
    }

    private fun expire(bucket: String, key: String) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "UPDATE http_rate_limit_buckets SET refill_at = " +
                    "floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint - 1 " +
                    "WHERE bucket_name = ? AND key_hash = ?",
            ).use { statement ->
                statement.setString(1, bucket)
                statement.setString(2, key)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun bucketCount(bucket: String, key: String): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM http_rate_limit_buckets WHERE bucket_name = ? AND key_hash = ?",
        ).use { statement ->
            statement.setString(1, bucket)
            statement.setString(2, key)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun activeCount(): Int = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM http_rate_limit_buckets " +
                "WHERE refill_at > floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint",
        ).use { statement -> statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) } }
    }

    private fun databaseNow(): Long = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }
}
