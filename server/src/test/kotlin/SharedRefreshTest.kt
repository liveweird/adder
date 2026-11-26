package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.MAX_GRACE_REUSES
import ch.nokillswit.auth.RefreshAfterPrecheck
import ch.nokillswit.auth.RefreshAfterPrecheckKey
import ch.nokillswit.auth.RefreshClaim
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.TokenBlocklistServiceKey
import com.auth0.jwt.JWT
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Cross-application HTTP coverage for durable refresh grace and logout ordering. */
class SharedRefreshTest {
    private val users = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        if (users.isNotEmpty()) {
            PostgresTestSupport.connect().use { connection ->
                connection.prepareStatement("DELETE FROM refresh_rotations WHERE user_id = ANY(?)").use { statement ->
                    statement.setArray(1, connection.createArrayOf("bigint", users.map(UInt::toLong).toTypedArray()))
                    statement.executeUpdate()
                }
            }
        }
        users.forEach { TestUsers.softDelete(it) }
    }

    private suspend fun account(label: String): Pair<String, String> {
        val email = uniqueEmail("shared-refresh-$label")
        TestUsers.seed(email, "pw").also { users += it }
        return email to "pw"
    }

    private suspend fun HttpClient.loginPair(email: String, password: String): LoginResponse =
        login(email, password).body()

    private suspend fun HttpClient.refresh(token: String) = postJson("/api/v1/refresh", RefreshRequest(token))

    private suspend fun HttpClient.logout(session: LoginResponse) = post("/api/v1/logout") {
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
        contentType(ContentType.Application.Json)
        setBody(LogoutRequest(session.refreshToken))
    }

    private class ClaimGate : RefreshAfterPrecheck {
        @Volatile var target: String? = null
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()

        override suspend fun reached(jti: String) {
            if (jti == target) {
                entered.complete(Unit)
                proceed.await()
            }
        }
    }

    @Test
    fun `concurrent refreshes on separate applications share one claim and two grace responses`() = testApplication {
        usePostgresTestcontainer()
        val (email, password) = account("aggregate")
        val session = jsonClient().loginPair(email, password)
        val other = TestApplication { configureApp() }
        other.start()
        try {
            val clients = listOf(jsonClient(), other.createClient { testClientDefaults() })
            val responses = coroutineScope {
                List(8) { index -> async { clients[index % clients.size].refresh(session.refreshToken) } }.awaitAll()
            }
            val statuses = responses.map { it.status }
            assertEquals(3, statuses.count { it == HttpStatusCode.OK }, statuses.toString())
            assertEquals(5, statuses.count { it == HttpStatusCode.Unauthorized }, statuses.toString())
            val tokens = responses.filter { it.status == HttpStatusCode.OK }.map { it.body<LoginResponse>().refreshToken }
            assertEquals(tokens.size, tokens.toSet().size)
        } finally {
            other.stop()
        }
    }

    @Test
    fun `a replay keeps its grace after the issuing application stops`() = testApplication {
        usePostgresTestcontainer()
        val (email, password) = account("restart")
        val issuer = TestApplication { configureApp() }
        issuer.start()
        val original: LoginResponse
        try {
            val issuerClient = issuer.createClient { testClientDefaults() }
            original = issuerClient.loginPair(email, password)
            assertEquals(HttpStatusCode.OK, issuerClient.refresh(original.refreshToken).status)
        } finally {
            issuer.stop()
        }

        assertEquals(HttpStatusCode.OK, jsonClient().refresh(original.refreshToken).status)
    }

    @Test
    fun `logout on another application clears shared grace but not a pair already returned before it`() = testApplication {
        usePostgresTestcontainer()
        val (email, password) = account("logout")
        val client = jsonClient()
        val original = client.loginPair(email, password)
        val renewed = client.refresh(original.refreshToken).body<LoginResponse>()
        val other = TestApplication { configureApp() }
        other.start()
        try {
            assertEquals(HttpStatusCode.NoContent, other.createClient { testClientDefaults() }.logout(renewed).status)
        } finally {
            other.stop()
        }

        assertEquals(HttpStatusCode.Unauthorized, client.refresh(original.refreshToken).status, "logout clears the old token's grace")
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.refresh(renewed.refreshToken).status,
            "the exact refresh token supplied to logout is revoked",
        )

        val independent = client.loginPair(email, password)
        val completedBeforeLogout = client.refresh(independent.refreshToken).body<LoginResponse>()
        assertEquals(HttpStatusCode.NoContent, client.logout(independent).status)
        assertEquals(
            HttpStatusCode.OK,
            client.refresh(completedBeforeLogout.refreshToken).status,
            "a pair already returned before logout is the documented non-family-revocation limitation",
        )
    }

    @Test
    fun `a refresh racing logout never restores grace for the logged-out token`() = testApplication {
        usePostgresTestcontainer()
        val (email, password) = account("race")
        val client = jsonClient()
        val session = client.loginPair(email, password)
        val other = TestApplication { configureApp() }
        other.start()
        try {
            val otherClient = other.createClient { testClientDefaults() }
            val outcomes = coroutineScope {
                listOf(
                    async { client.refresh(session.refreshToken).status },
                    async { otherClient.logout(session).status },
                ).awaitAll()
            }
            assertTrue(outcomes[0] == HttpStatusCode.OK || outcomes[0] == HttpStatusCode.Unauthorized, outcomes.toString())
            assertEquals(HttpStatusCode.NoContent, outcomes[1])
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session.refreshToken).status)
        } finally {
            other.stop()
        }
    }

    @Test
    fun `strict revocation after precheck wins before the refresh claim`() = testApplication {
        configureApp()
        val gate = ClaimGate()
        application { attributes.put(RefreshAfterPrecheckKey, gate) }
        startApplication()
        val (email, password) = account("strict-race")
        val client = jsonClient()
        val session = client.loginPair(email, password)
        val decoded = JWT.decode(session.refreshToken)
        val jti = requireNotNull(decoded.id)
        val expiresAt = requireNotNull(decoded.expiresAt).time
        gate.target = jti

        try {
            coroutineScope {
                val response = async { client.refresh(session.refreshToken) }
                withTimeout(5_000) { gate.entered.await() }
                assertTrue(application.attributes[TokenBlocklistServiceKey].revokeIfAbsent(jti, expiresAt))
                gate.proceed.complete(Unit)
                assertEquals(HttpStatusCode.Unauthorized, response.await().status)
            }
        } finally {
            gate.proceed.complete(Unit)
        }
    }

    @Test
    fun `grace consumed after precheck is rejected by the refresh claim`() = testApplication {
        configureApp()
        val gate = ClaimGate()
        application { attributes.put(RefreshAfterPrecheckKey, gate) }
        startApplication()
        val (email, password) = account("grace-race")
        val client = jsonClient()
        val session = client.loginPair(email, password)
        assertEquals(HttpStatusCode.OK, client.refresh(session.refreshToken).status, "establish the durable grace row")
        val decoded = JWT.decode(session.refreshToken)
        val jti = requireNotNull(decoded.id)
        val expiresAt = requireNotNull(decoded.expiresAt).time
        gate.target = jti

        try {
            coroutineScope {
                val response = async { client.refresh(session.refreshToken) }
                withTimeout(5_000) { gate.entered.await() }
                repeat(MAX_GRACE_REUSES) {
                    assertEquals(
                        RefreshClaim.GRACE_REPLAY,
                        application.attributes[TokenBlocklistServiceKey]
                            .claimRefresh(jti, expiresAt, session.userId),
                    )
                }
                gate.proceed.complete(Unit)
                assertEquals(HttpStatusCode.Unauthorized, response.await().status)
            }
        } finally {
            gate.proceed.complete(Unit)
        }
    }
}
