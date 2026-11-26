package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cross-origin posture (`plugins/SecurityHeaders.kt`, `plugins/Http.kt`): `Cross-Origin-Opener-Policy` and
 * `Cross-Origin-Resource-Policy` are `same-origin` on every answer (the early Setup-phase ones and Swagger UI included), and the CORS
 * allow-list admits `http` origins in development only: production accepts an `https` origin of a listed host and nothing else.
 */
class CrossOriginPolicyTest {
    private fun HttpResponse.assertIsolated(what: String) {
        assertEquals("same-origin", headers["Cross-Origin-Opener-Policy"], "$what: COOP")
        assertEquals("same-origin", headers["Cross-Origin-Resource-Policy"], "$what: CORP")
        assertEquals(1, headers.getAll("Cross-Origin-Opener-Policy")?.size, "$what: COOP once")
        assertEquals(1, headers.getAll("Cross-Origin-Resource-Policy")?.size, "$what: CORP once")
    }

    @Test
    fun `every answer carries COOP and CORP same-origin - the API, the early answers and Swagger UI`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        val client = jsonClient()
        client.post("/api/v1/logout").assertIsolated("a 401")
        client.get("/api/v1/health").assertIsolated("a 200")
        val encoded = client.post("/api/v1/login") {
            header(HttpHeaders.ContentEncoding, "gzip")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, encoded.status)
        encoded.assertIsolated("the 415")
        val badHost = client.post("/api/v1/login") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.Host, "localhost:abc")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, badHost.status)
        badHost.assertIsolated("the malformed-Host 400")
        // Swagger UI is exempt from the strict CSP only; it is isolated like everything else (no popup, nothing cross-origin).
        val swagger = client.get("/openapi")
        assertNull(swagger.headers["Content-Security-Policy"])
        swagger.assertIsolated("Swagger UI")
    }

    private suspend fun io.ktor.client.HttpClient.preflight(origin: String, requestHeaders: String? = null): HttpResponse =
        options("/api/v1/login") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            requestHeaders?.let { header(HttpHeaders.AccessControlRequestHeaders, it) }
            header(HttpHeaders.XForwardedProto, "https")
        }

    @Test
    fun `development admits an http or https origin of a listed host`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        for (origin in listOf("http://app.example.com", "https://app.example.com")) {
            // The default client: a preflight is not an API operation of the spec, so the conformance client would refuse it.
            val response = client.preflight(origin)
            assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin], origin)
        }
        val mutation = client.preflight("https://app.example.com", "X-Mutation-Id")
        assertEquals(HttpStatusCode.OK, mutation.status)
        assertTrue(
            mutation.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().split(',')
                .any { it.trim().equals("X-Mutation-Id", ignoreCase = true) },
        )
    }

    @Test
    fun `production admits only an https origin of a listed host`() = testApplication {
        withSeedRestored {
            configureApp(
                "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
                "jwt.secret" to strongJwtSecret(),
                "security.encryption.key" to strongEncryptionKey(),
                "mail.transport" to "disabled",
                "http.behindProxy" to "true",
                "http.corsHosts" to "app.example.com",
            )
            serverConfig { developmentMode = false }
            startApplication()
            val client = createClient { followRedirects = false }
            val https = client.preflight("https://app.example.com")
            assertEquals("https://app.example.com", https.headers[HttpHeaders.AccessControlAllowOrigin])
            https.assertIsolated("the production preflight")
            val http = client.preflight("http://app.example.com")
            assertNull(http.headers[HttpHeaders.AccessControlAllowOrigin], "an http origin is not on the production allow-list")
            assertEquals(HttpStatusCode.Forbidden, http.status)
            // The live route in production, end to end: the CORS plugin judges a listed host's `http` origin first (its bodiless 403)
            // and an `https` one reaches the route (a plain GET is 400). `LiveAdmission`'s own https-only branch is pinned in
            // LiveAdmissionTest.
            suspend fun live(origin: String) = client.get("/api/v1/live") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.XForwardedProto, "https")
            }
            assertEquals(HttpStatusCode.BadRequest, live("https://app.example.com").status)
            assertEquals(HttpStatusCode.Forbidden, live("http://app.example.com").status)
        }
    }
}
