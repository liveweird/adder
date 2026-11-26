package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.plugins.RateLimitNamespaceOverrideKey
import ch.nokillswit.plugins.RateLimits
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
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedRateLimitHttpTest {
    @Test
    fun `shared quota preserves compatible headers already supplied by middleware`() = withIsolatedTestDatabase { db ->
        testApplication {
            configureApp(
                "postgres.jdbcUrl" to db.jdbcUrl,
                "postgres.r2dbcUrl" to db.r2dbcUrl,
                "security.rateLimit.loginPerMinute" to "3",
            )
            application {
                attributes.remove(RateLimitNamespaceOverrideKey)
                intercept(ApplicationCallPipeline.Setup) {
                    if (call.request.local.uri == "/test/shared-rate-limit-headers") {
                        call.response.header("X-RateLimit-Limit", "upstream-limit")
                        call.response.header("X-RateLimit-Remaining", "upstream-remaining")
                        call.response.header("X-RateLimit-Reset", "upstream-reset")
                    }
                }
                routing {
                    rateLimit(RateLimitName(RateLimits.LOGIN)) {
                        get("/test/shared-rate-limit-headers") {
                            call.respondText("ok")
                        }
                    }
                }
            }
            startApplication()

            val response = jsonClient().get("/test/shared-rate-limit-headers")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("upstream-limit", response.headers["X-RateLimit-Limit"])
            assertEquals("upstream-remaining", response.headers["X-RateLimit-Remaining"])
            assertEquals("upstream-reset", response.headers["X-RateLimit-Reset"])
        }
    }

    @Test
    fun `two application instances consume one shared login envelope without storing the address`() = withIsolatedTestDatabase { db ->
        testApplication {
            configureReplica(db, "security.rateLimit.loginPerMinute" to "3")
            val client = jsonClient()
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, client.login(uniqueEmail("shared-login"), "x").status) }
        }
        testApplication {
            configureReplica(db, "security.rateLimit.loginPerMinute" to "3")
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

        assertOpaqueStoredKey(db, "login")
    }

    @Test
    fun `distinct capacity-refused identities keep one cached limiter per provider`() = withIsolatedTestDatabase { isolated ->
        seedFullActiveCapacity(isolated)
        testApplication {
            configureReplica(isolated, "http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "1000")
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
    fun `authenticated action envelope is shared while readiness remains local`() = withIsolatedTestDatabase { db ->
        var token = ""
        testApplication {
            configureReplica(db, "security.rateLimit.readyPerMinute" to "1")
            token = jsonClient().login(SEED_ADMIN_EMAIL, "changeme").body<LoginResponse>().token
            val user = tokenClient(token)
            repeat(60) { assertEquals(HttpStatusCode.OK, user.get(DIRECTORY_PATH).status) }
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
            assertEquals(HttpStatusCode.TooManyRequests, jsonClient().get("/api/v1/ready").status)
        }
        testApplication {
            configureReplica(db, "security.rateLimit.readyPerMinute" to "1")
            val user = tokenClient(token)
            repeat(60) { assertEquals(HttpStatusCode.OK, user.get(DIRECTORY_PATH).status) }
            val refused = user.get(DIRECTORY_PATH)
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertTrue(assertNotNull(refused.headers[HttpHeaders.RetryAfter]).toInt() in 1..60)
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status, "each replica owns its readiness allowance")
        }
    }

    private suspend fun ApplicationTestBuilder.configureReplica(db: IsolatedTestDatabase, vararg overrides: Pair<String, String>) {
        configureApp("postgres.jdbcUrl" to db.jdbcUrl, "postgres.r2dbcUrl" to db.r2dbcUrl, *overrides)
        // The scratch DB owns its entire quota namespace, so use the same identity keys as real app replicas.
        application { attributes.remove(RateLimitNamespaceOverrideKey) }
        startApplication()
    }

    private fun ApplicationTestBuilder.tokenClient(token: String): HttpClient = createClient {
        testClientDefaults()
        install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $token") }
    }

    private fun assertOpaqueStoredKey(db: IsolatedTestDatabase, bucket: String) {
        db.connect().use { connection ->
            connection.prepareStatement("SELECT key_hash FROM http_rate_limit_buckets WHERE bucket_name = ?").use { statement ->
                statement.setString(1, bucket)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(Regex("[0-9a-f]{64}").matches(rows.getString(1)))
                    assertFalse(rows.next(), "both replicas used the same quota key")
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
