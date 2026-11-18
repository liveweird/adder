package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.live.LIVE_TICKET_TTL_MILLIS
import ch.nokillswit.live.LiveTicketBinding
import ch.nokillswit.live.LiveTicketResponse
import ch.nokillswit.live.LiveTicketsKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/v1/live/tickets` (the route; the store is `LiveTicketsTest`): any signed-in caller gets a 30-second single-use ticket bound
 * to their account and to the access token that asked, behind a per-user bucket and a bounded store. The 401 is the sweep's
 * (`AnonymousAccessTest`).
 */
class LiveTicketTest {
    private val path = "/api/v1/live/tickets"

    private class Caller(val client: HttpClient, val userId: UInt, val token: String) {
        suspend fun mint(): HttpResponse = client.post("/api/v1/live/tickets") { header(HttpHeaders.Authorization, "Bearer $token") }
    }

    /** Seeds an account, signs it in and keeps the raw bearer (the ticket binds its `jti` and `exp`). */
    private suspend fun ApplicationTestBuilder.signedIn(prefix: String, role: UserRole = UserRole.USER): Caller {
        val email = uniqueEmail(prefix)
        val userId = TestUsers.seed(email = email, password = "pw-123456789", role = role)
        val client = jsonClient()
        val token = client.login(email, "pw-123456789").body<LoginResponse>().token
        return Caller(client, userId, token)
    }

    @Test
    fun `a signed-in caller mints a single-use ticket bound to their account and access token`() = testApplication {
        usePostgresTestcontainer()
        val store = application.attributes[LiveTicketsKey]
        for (role in listOf(UserRole.USER, UserRole.ADMIN)) {
            val caller = signedIn("live-ticket-$role", role)
            val before = System.currentTimeMillis()
            val response = caller.mint()
            val after = System.currentTimeMillis()

            assertEquals(HttpStatusCode.OK, response.status, "an exchange, not a creation")
            assertTrue(response.headers[HttpHeaders.CacheControl]?.contains("no-store") == true, "a credential is never cached")
            val body = response.body<LiveTicketResponse>()
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(body.ticket), "43 base64url characters")
            assertTrue(body.expiresAt in (before + LIVE_TICKET_TTL_MILLIS)..(after + LIVE_TICKET_TTL_MILLIS), "valid for 30 seconds")

            val bearer = JWT.decode(caller.token)
            val binding = store.redeem(body.ticket)
            assertEquals(
                LiveTicketBinding(
                    userId = caller.userId,
                    isAdmin = role == UserRole.ADMIN,
                    roles = if (role == UserRole.ADMIN) setOf("ADMIN") else emptySet(),
                    jti = bearer.id,
                    credentialRevision = bearer.getClaim("credentialRevision").asLong(),
                    tokenExpiresAtMillis = bearer.expiresAt.time,
                    expiresAtMillis = body.expiresAt,
                ),
                binding,
                "the ticket is bound to the caller and the very token that asked",
            )
            assertNull(store.redeem(body.ticket), "single use")
        }
    }

    @Test
    fun `a signed access token without an exp can bind nothing a socket could live on - 401, no ticket`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("live-ticket-noexp")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        // The dev secret and the minting shape of Tokens.kt, minus the expiry (the server never mints one; the verifier alone accepts it).
        fun mint(withExp: Boolean): String = JWT.create()
            .withAudience("adder-api")
            .withIssuer("http://0.0.0.0:8087/")
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("email", email)
            .withClaim("userId", id.toLong())
            .withArrayClaim("roles", arrayOf<String>())
            .withClaim("typ", "access")
            .withClaim("credentialRevision", 0L)
            .apply { if (withExp) withExpiresAt(Date(System.currentTimeMillis() + 60_000)) }
            .sign(Algorithm.HMAC256("secret"))

        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, Caller(client, id, mint(withExp = true)).mint().status, "the control: same token WITH an exp")
        assertEquals(HttpStatusCode.Unauthorized, Caller(client, id, mint(withExp = false)).mint().status)
    }

    @Test
    fun `every mint is a fresh ticket - a caller may hold several`() = testApplication {
        usePostgresTestcontainer()
        val caller = signedIn("live-ticket-many")
        val tickets = List(3) { caller.mint().body<LiveTicketResponse>().ticket }
        assertEquals(3, tickets.toSet().size)
        val store = application.attributes[LiveTicketsKey]
        tickets.forEach { assertNotNull(store.redeem(it)) }
    }

    @Test
    fun `the ticket bucket answers 429 past 60 a minute, per user, and audits the rejection`() = testApplication {
        usePostgresTestcontainer()
        val limited = signedIn("live-ticket-bucket")
        val other = signedIn("live-ticket-bucket-other")
        withAuditCapture { capture ->
            repeat(60) { n -> assertEquals(HttpStatusCode.OK, limited.mint().status, "mint ${n + 1}") }
            val rejected = limited.mint()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals(429, rejected.body<ProblemDetail>().status, "the generic 429 problem body")
            assertEquals(HttpStatusCode.OK, other.mint().status, "keyed by the user, not the address")

            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "live-ticket") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.userId.toLong()))
            assertTrue(event.hasKeyValue("path", path))
        }
    }

    @Test
    fun `a full ticket store answers 429 and audits the saturation, then frees up as tickets are redeemed`() = testApplication {
        configureApp("live.maxTickets" to "1")
        startApplication()
        val first = signedIn("live-ticket-full-a")
        val second = signedIn("live-ticket-full-b")
        withAuditCapture { capture ->
            val held = first.mint()
            assertEquals(HttpStatusCode.OK, held.status)

            val rejected = second.mint()
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals("30", rejected.headers[HttpHeaders.RetryAfter], "retry once the held tickets have expired")
            val detail = rejected.body<ProblemDetail>().detail.orEmpty()
            assertTrue("tickets are pending" in detail, "the specific 429, not the generic bucket one")

            val event = capture.awaitEvent { it.message == "live.ticket_saturated" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", second.userId.toLong()))
            assertTrue(event.hasKeyValue("suppressed", 0L), "the first line of a window has nothing folded into it")
            assertTrue(capture.events.none { it.message == "rate_limit.exceeded" }, "saturation is not a bucket rejection")

            // A repeat inside the window folds into that line: one line per user per minute.
            assertEquals(HttpStatusCode.TooManyRequests, second.mint().status)
            assertEquals(1, capture.events.count { it.message == "live.ticket_saturated" })

            assertNotNull(application.attributes[LiveTicketsKey].redeem(held.body<LiveTicketResponse>().ticket))
            assertEquals(HttpStatusCode.OK, second.mint().status, "the slot is free again")
        }
    }

    @Test
    fun `no log line of any logger carries a minted ticket, even across a saturated mint and a redemption`() = testApplication {
        configureApp("live.maxTickets" to "1")
        startApplication()
        val caller = signedIn("live-ticket-logs")
        val rejected = signedIn("live-ticket-logs-other")
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            val ticket = caller.mint().body<LiveTicketResponse>().ticket
            // The saturated mint is the path that logs (`live.ticket_saturated`), so the capture is not vacuous.
            assertEquals(HttpStatusCode.TooManyRequests, rejected.mint().status)
            application.attributes[LiveTicketsKey].redeem(ticket)
            val rendered = capture.events.map { it.rendered() }
            assertTrue(rendered.any { "live.ticket_saturated" in it }, "the capture saw the audit line")
            assertTrue(rendered.none { ticket in it }, "the ticket leaked into a log line")
        } finally {
            capture.detach()
        }
    }

    /** Everything an event can carry, as one string (`ChatLogHygieneTest`'s rendering). */
    private fun ILoggingEvent.rendered(): String = buildString {
        append(loggerName).append(' ').append(formattedMessage).append(' ').append(message)
        argumentArray?.forEach { append(' ').append(it) }
        keyValuePairs?.forEach { append(' ').append(it.key).append('=').append(it.value) }
        mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
        throwableProxy?.let { append(' ').append(ThrowableProxyUtil.asString(it)) }
    }
}
