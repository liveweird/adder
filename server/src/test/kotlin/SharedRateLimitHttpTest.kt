package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LoginRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedRateLimitHttpTest {
    private val seeded = mutableListOf<UInt>()
    private val ephemeralRows = mutableSetOf<Pair<String, String>>()

    @AfterTest
    fun cleanUp() = kotlinx.coroutines.runBlocking {
        seeded.forEach { TestUsers.softDelete(it) }
        seeded.clear()
        PostgresTestSupport.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT stored_count FROM http_rate_limit_capacity_guard WHERE singleton FOR UPDATE",
                ).use { statement -> statement.executeQuery().use { rows -> check(rows.next()) } }
                val deleted = connection.prepareStatement(
                    "DELETE FROM http_rate_limit_buckets WHERE bucket_name = ? AND key_hash = ?",
                ).use { statement ->
                    ephemeralRows.forEach { (bucket, key) ->
                        statement.setString(1, bucket)
                        statement.setString(2, key)
                        statement.addBatch()
                    }
                    statement.executeBatch().sum()
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
        ephemeralRows.clear()
    }

    @Test
    fun `two application instances consume one shared login envelope without storing the address`() {
        val namespace = UUID.randomUUID().toString()
        var baseline = emptySet<Pair<String, String>>()
        testApplication {
            configureShared(namespace, "security.rateLimit.loginPerMinute" to "3")
            baseline = rateRows()
            val client = jsonClient()
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.login(uniqueEmail("shared-login"), "x").status) }
        }
        testApplication {
            configureShared(namespace, "security.rateLimit.loginPerMinute" to "3")
            val client = jsonClient()
            val finalAllowed = client.login(uniqueEmail("shared-login"), "x")
            assertEquals(HttpStatusCode.Unauthorized, finalAllowed.status)
            assertEquals("3", finalAllowed.headers["X-RateLimit-Limit"])
            assertEquals("0", finalAllowed.headers["X-RateLimit-Remaining"])
            val reset = assertNotNull(finalAllowed.headers["X-RateLimit-Reset"]).toLong()
            assertTrue(reset * 1_000L >= System.currentTimeMillis())
            val refused = client.login(uniqueEmail("shared-login"), "x")
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertNull(refused.headers["X-RateLimit-Limit"])
            assertNull(refused.headers["X-RateLimit-Remaining"])
            assertNull(refused.headers["X-RateLimit-Reset"])
            assertTrue(assertNotNull(refused.headers[HttpHeaders.RetryAfter]).toInt() in 1..60)
        }

        ephemeralRows += rateRows() - baseline
        assertOpaqueStoredKeys("login")
    }

    @Test
    fun `distinct capacity-refused identities keep one cached limiter per provider`() = withIsolatedTestDatabase { isolated ->
        seedFullActiveCapacity(isolated)
        testApplication {
            configureShared(
                UUID.randomUUID().toString(),
                "http.behindProxy" to "true",
                "security.rateLimit.loginPerMinute" to "1000",
                "postgres.jdbcUrl" to isolated.jdbcUrl,
                "postgres.r2dbcUrl" to isolated.r2dbcUrl,
            )
            val client = jsonClient()
            repeat(20) { index ->
                val response = client.post("/api/v1/login") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.XForwardedFor, "203.0.113.${index + 1}")
                    setBody(LoginRequest(uniqueEmail("capacity-$index"), "wrong"))
                }
                assertEquals(HttpStatusCode.TooManyRequests, response.status)
                assertNull(response.headers["X-RateLimit-Limit"])
                assertNull(response.headers["X-RateLimit-Remaining"])
                assertNull(response.headers["X-RateLimit-Reset"])
                assertTrue(assertNotNull(response.headers[HttpHeaders.RetryAfter]).toInt() in 1..60)
            }
            assertEquals(1, rateLimiterRegistrySize(), "one constant Ktor registry key is used for the login provider")
        }
    }

    @Test
    fun `authenticated action envelope is shared while readiness remains local`() {
        val namespace = UUID.randomUUID().toString()
        val email = uniqueEmail("shared-user")
        var token = ""
        var baseline = emptySet<Pair<String, String>>()
        testApplication {
            configureShared(namespace, "security.rateLimit.readyPerMinute" to "1")
            baseline = rateRows()
            seeded += TestUsers.seed(email, "pw")
            token = jsonClient().login(email, "pw").body<LoginResponse>().token
            val user = tokenClient(token)
            repeat(60) { assertEquals(HttpStatusCode.OK, user.get(DIRECTORY_PATH).status) }
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
            assertEquals(HttpStatusCode.TooManyRequests, jsonClient().get("/api/v1/ready").status)
        }
        testApplication {
            configureShared(namespace, "security.rateLimit.readyPerMinute" to "1")
            val user = tokenClient(token)
            repeat(60) { assertEquals(HttpStatusCode.OK, user.get(DIRECTORY_PATH).status) }
            val refused = user.get(DIRECTORY_PATH)
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertTrue(assertNotNull(refused.headers[HttpHeaders.RetryAfter]).toInt() in 1..60)
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status, "each replica owns its readiness allowance")
        }
        ephemeralRows += rateRows() - baseline
    }

    private suspend fun ApplicationTestBuilder.configureShared(namespace: String, vararg overrides: Pair<String, String>) {
        configureApp(*overrides)
        shareRateLimitNamespace(namespace)
        startApplication()
    }

    private fun ApplicationTestBuilder.tokenClient(token: String): HttpClient = createClient {
        testClientDefaults()
        install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun assertOpaqueStoredKeys(bucket: String) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("SELECT key_hash FROM http_rate_limit_buckets WHERE bucket_name = ?").use { statement ->
                statement.setString(1, bucket)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    do {
                        assertTrue(Regex("[0-9a-f]{64}").matches(rows.getString(1)))
                    } while (rows.next())
                }
            }
        }
    }

    private fun rateRows(): Set<Pair<String, String>> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT bucket_name, key_hash FROM http_rate_limit_buckets").use { statement ->
            statement.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString(1) to rows.getString(2))
                }
            }
        }
    }

    private fun seedFullActiveCapacity(isolated: IsolatedTestDatabase) {
        isolated.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "SELECT stored_count FROM http_rate_limit_capacity_guard WHERE singleton FOR UPDATE",
                ).use { statement -> statement.executeQuery().use { rows -> check(rows.next() && rows.getInt(1) == 0) } }
                val inserted = connection.prepareStatement(
                    "INSERT INTO http_rate_limit_buckets (bucket_name, key_hash, tokens_remaining, refill_at) " +
                        "SELECT 'capacity-fill', lpad(to_hex(value), 64, '0'), 1, " +
                        "floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint + 60000 " +
                        "FROM generate_series(1, 100000) AS series(value)",
                ).use { statement -> statement.executeUpdate() }
                assertEquals(100_000, inserted)
                connection.prepareStatement(
                    "UPDATE http_rate_limit_capacity_guard SET stored_count = ? WHERE singleton",
                ).use { statement -> statement.setInt(1, inserted); assertEquals(1, statement.executeUpdate()) }
                connection.commit()
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ApplicationTestBuilder.rateLimiterRegistrySize(): Int {
        val getter = Class.forName("io.ktor.server.plugins.ratelimit.RateLimitKt")
            .getMethod("getRateLimiterInstancesRegistryKey")
        val key = getter.invoke(null) as AttributeKey<Any>
        return (application.attributes[key] as Map<*, *>).size
    }

    private companion object {
        const val DIRECTORY_PATH = "/api/v1/users/directory?q=shared"
    }
}
