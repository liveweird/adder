package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedRateLimitHttpTest {
    private val seeded = mutableListOf<UInt>()
    private val ephemeralRows = mutableSetOf<Pair<String, String>>()

    @AfterTest
    fun cleanUp() = kotlinx.coroutines.runBlocking {
        seeded.forEach { TestUsers.softDelete(it) }
        seeded.clear()
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM http_rate_limit_buckets WHERE bucket_name = ? AND key_hash = ?").use { statement ->
                ephemeralRows.forEach { (bucket, key) ->
                    statement.setString(1, bucket)
                    statement.setString(2, key)
                    statement.addBatch()
                }
                statement.executeBatch()
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
            assertEquals("0", finalAllowed.headers["X-RateLimit-Remaining"])
            val refused = client.login(uniqueEmail("shared-login"), "x")
            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            assertTrue(assertNotNull(refused.headers[HttpHeaders.RetryAfter]).toInt() in 1..60)
        }

        ephemeralRows += rateRows() - baseline
        assertOpaqueStoredKeys("login")
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

    private companion object {
        const val DIRECTORY_PATH = "/api/v1/users/directory?q=shared"
    }
}
