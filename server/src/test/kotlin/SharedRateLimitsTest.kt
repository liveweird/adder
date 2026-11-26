package ch.nokillswit

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.plugins.RATE_LIMIT_WINDOW_MILLIS
import ch.nokillswit.plugins.SHARED_RATE_LIMIT_ADMITTED_WEIGHT
import ch.nokillswit.plugins.SHARED_RATE_LIMIT_REFUSED_WEIGHT
import ch.nokillswit.plugins.SharedRateLimiter
import ch.nokillswit.plugins.SharedRateLimitStore
import io.ktor.server.plugins.ratelimit.RateLimiter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import io.ktor.server.testing.testApplication

class SharedRateLimitsTest {
    private val buckets = setOf("test-envelope", "test-race", "test-capacity", "test-generations", "test-cap-race")

    @AfterTest
    fun cleanUp() {
        PostgresTestSupport.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT stored_count FROM http_rate_limit_capacity_guard WHERE singleton FOR UPDATE",
                ).use { statement -> statement.executeQuery().use { rows -> check(rows.next()) } }
                val deleted = connection.prepareStatement(
                    "DELETE FROM http_rate_limit_buckets WHERE bucket_name = ANY(?)",
                ).use { statement ->
                    statement.setArray(1, connection.createArrayOf("text", buckets.toTypedArray()))
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE http_rate_limit_capacity_guard SET stored_count = stored_count - ? WHERE singleton",
                ).use { statement -> statement.setInt(1, deleted); check(statement.executeUpdate() == 1) }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
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
    fun `new-key capacity refuses with a real refill deadline and expired cleanup is bounded`() = withIsolatedTestDatabase { isolated ->
        testApplication {
            configureIsolated(isolated)
            val store = SharedRateLimitStore(application.attributes[R2dbcDatabaseKey], maxStoredKeys = 2)
            val first = "c".repeat(64)
            val second = "d".repeat(64)
            val third = "e".repeat(64)
            store.consume("test-capacity", first, limit = 3)
            store.consume("test-capacity", second, limit = 3)

            val refused = store.consume("test-capacity", third, limit = 3)
            assertFalse(refused.admitted)
            assertTrue(refused.capacityRefusal)
            assertTrue(refused.refillAtMillis > refused.serverNowMillis)

            expire("test-capacity", first, isolated)
            assertTrue(store.consume("test-capacity", third, limit = 3).admitted, "expired capacity is reclaimed for a new key")
            assertEquals(0, store.pruneExpired(batchSize = 1))
            assertEquals(0, bucketCount("test-capacity", first, isolated))
        }
    }

    @Test
    fun `physical capacity has an exact high water across repeated expired generations`() = withIsolatedTestDatabase { isolated ->
        testApplication {
            configureIsolated(isolated)
            val store = SharedRateLimitStore(application.attributes[R2dbcDatabaseKey], maxStoredKeys = 1)

            repeat(12) { generation ->
                val key = generation.toString(16).padStart(64, '0')
                assertTrue(store.consume("test-generations", key, limit = 1).admitted)
                assertEquals(1, physicalCount(isolated))
                assertEquals(physicalCount(isolated), storedCount(isolated))
                if (generation < 11) expire("test-generations", key, isolated)
            }

            val liveKey = 11.toString(16).padStart(64, '0')
            val exhausted = store.consume("test-generations", liveKey, limit = 1)
            assertFalse(exhausted.admitted, "an existing key keeps its per-key quota at the physical cap")
            assertFalse(exhausted.capacityRefusal)
            val newKey = "f".repeat(64)
            assertTrue(store.consume("test-generations", newKey, limit = 1).capacityRefusal)
            assertEquals(1, physicalCount(isolated))
            assertEquals(physicalCount(isolated), storedCount(isolated))
        }
    }

    @Test
    fun `two stores cannot admit distinct active keys beyond physical capacity`() = withIsolatedTestDatabase { isolated ->
        testApplication {
            configureIsolated(isolated)
            val database = application.attributes[R2dbcDatabaseKey]
            val first = SharedRateLimitStore(database, maxStoredKeys = 1)
            val second = SharedRateLimitStore(database, maxStoredKeys = 1)

            val decisions = coroutineScope {
                listOf(
                    async { first.consume("test-cap-race", "1".repeat(64), limit = 2) },
                    async { second.consume("test-cap-race", "2".repeat(64), limit = 2) },
                ).awaitAll()
            }

            assertEquals(1, decisions.count { it.admitted })
            assertEquals(1, decisions.count { it.capacityRefusal })
            assertEquals(1, physicalCount(isolated))
            assertEquals(physicalCount(isolated), storedCount(isolated))
        }
    }

    @Test
    fun `locked expired capacity retries once then refuses without reclaiming an active row`() =
        withIsolatedTestDatabase { isolated ->
            testApplication {
                configureIsolated(isolated)
                val store = SharedRateLimitStore(application.attributes[R2dbcDatabaseKey], maxStoredKeys = 1)
                val expired = "3".repeat(64)
                val replacement = "4".repeat(64)
                assertTrue(store.consume("test-capacity", expired, limit = 2).admitted)
                expire("test-capacity", expired, isolated)

                isolated.connect().use { holder ->
                    holder.autoCommit = false
                    holder.prepareStatement(
                        "SELECT key_hash FROM http_rate_limit_buckets " +
                            "WHERE bucket_name = 'test-capacity' AND key_hash = ? FOR UPDATE",
                    ).use { statement ->
                        statement.setString(1, expired)
                        statement.executeQuery().use { rows -> assertTrue(rows.next()) }
                    }
                    val refused = withTimeout(1_000) { store.consume("test-capacity", replacement, limit = 2) }
                    assertTrue(refused.capacityRefusal)
                    assertEquals(1, physicalCount(isolated))
                    holder.rollback()
                }

                assertTrue(store.consume("test-capacity", replacement, limit = 2).admitted)
                assertEquals(1, physicalCount(isolated))
                assertEquals(physicalCount(isolated), storedCount(isolated))
            }
        }

    @Test
    fun `missing capacity guard fails closed for admission and cleanup`() = withIsolatedTestDatabase { isolated ->
        testApplication {
            configureIsolated(isolated)
            val store = SharedRateLimitStore(application.attributes[R2dbcDatabaseKey], maxStoredKeys = 1)
            isolated.connect().use { connection ->
                assertEquals(1, connection.createStatement().use { it.executeUpdate("DELETE FROM http_rate_limit_capacity_guard") })
            }

            assertFailsWith<IllegalStateException> { store.consume("test-capacity", "5".repeat(64), limit = 2) }
            assertFailsWith<IllegalStateException> { store.pruneExpired() }
            assertEquals(0, physicalCount(isolated))
        }
    }

    @Test
    fun `background cleanup deletes only its bounded expired batch and settles the counter`() =
        withIsolatedTestDatabase { isolated ->
            testApplication {
                configureIsolated(isolated)
                val store = SharedRateLimitStore(application.attributes[R2dbcDatabaseKey], maxStoredKeys = 3)
                repeat(3) { index ->
                    val key = (index + 6).toString().repeat(64)
                    assertTrue(store.consume("test-capacity", key, limit = 2).admitted)
                    expire("test-capacity", key, isolated)
                }

                assertEquals(2, store.pruneExpired(batchSize = 2))
                assertEquals(1, physicalCount(isolated))
                assertEquals(1, storedCount(isolated))
                assertEquals(1, store.pruneExpired(batchSize = 2))
                assertEquals(0, store.pruneExpired(batchSize = 2))
                assertEquals(0, physicalCount(isolated))
                assertEquals(0, storedCount(isolated))
            }
        }

    @Test
    fun `invalid inputs fail before touching the store and synthetic limiter rejects unknown weights`() = testApplication {
        usePostgresTestcontainer()
        val store = SharedRateLimitStore(sharedDatabaseForTests())
        val key = "a".repeat(64)

        assertFailsWith<IllegalArgumentException> { store.consume("", key, limit = 1) }
        assertFailsWith<IllegalArgumentException> { store.consume("x".repeat(41), key, limit = 1) }
        assertFailsWith<IllegalArgumentException> { store.consume("test-capacity", "not-a-digest", limit = 1) }
        assertFailsWith<IllegalArgumentException> { store.consume("test-capacity", key, limit = 0) }
        assertFailsWith<IllegalArgumentException> { store.consume("test-capacity", key, limit = 1, weight = 0) }
        assertFailsWith<IllegalArgumentException> { store.consume("test-capacity", key, limit = 1, weight = 2) }
        assertFailsWith<IllegalArgumentException> { store.pruneExpired(batchSize = 0) }

        assertIs<RateLimiter.State.Available>(SharedRateLimiter.tryConsume(SHARED_RATE_LIMIT_ADMITTED_WEIGHT))
        assertIs<RateLimiter.State.Exhausted>(SharedRateLimiter.tryConsume(SHARED_RATE_LIMIT_REFUSED_WEIGHT))
        assertFailsWith<IllegalStateException> { SharedRateLimiter.tryConsume(99) }
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

    private fun expire(bucket: String, key: String, isolated: IsolatedTestDatabase? = null) {
        (isolated?.connect() ?: PostgresTestSupport.connect()).use { connection ->
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

    private fun bucketCount(bucket: String, key: String, isolated: IsolatedTestDatabase): Int = isolated.connect().use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM http_rate_limit_buckets WHERE bucket_name = ? AND key_hash = ?",
        ).use { statement ->
            statement.setString(1, bucket)
            statement.setString(2, key)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun physicalCount(isolated: IsolatedTestDatabase): Int = isolated.connect().use { connection ->
        connection.prepareStatement("SELECT count(*) FROM http_rate_limit_buckets").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun storedCount(isolated: IsolatedTestDatabase): Int = isolated.connect().use { connection ->
        connection.prepareStatement("SELECT stored_count FROM http_rate_limit_capacity_guard WHERE singleton").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun databaseNow(): Long = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.configureIsolated(isolated: IsolatedTestDatabase) {
        configureApp("postgres.jdbcUrl" to isolated.jdbcUrl, "postgres.r2dbcUrl" to isolated.r2dbcUrl)
        startApplication()
    }
}
