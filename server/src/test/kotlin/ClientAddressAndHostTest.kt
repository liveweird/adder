package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.plugins.numericClientHost
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import ch.nokillswit.auth.LoginRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.RequestConnectionPoint
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two things `plugins/Http.kt` does with client-chosen or network-derived text before anything else reads it: the per-IP key is the
 * NUMERIC remote address (not the engine's reverse-DNS name), and with `http.corsHosts` set a malformed `Host` is a 400 (Ktor's CORS
 * plugin throws on it, a 500 with an ERROR log).
 */
class ClientAddressAndHostTest {

    /** A property bag standing in for a Ktor connection-point interface (getters read it, setters write it). */
    private inline fun <reified T : Any> pointOf(values: MutableMap<String, Any?>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when {
                method.name.startsWith("get") -> values[method.name.removePrefix("get")]
                method.name.startsWith("set") -> {
                    values[method.name.removePrefix("set")] = args[0]
                    null
                }
                else -> error("unexpected call ${method.name}")
            }
        } as T

    @Test
    fun `the origin's remote host becomes the numeric address even when the engine would resolve a name`() {
        // Netty's getRemoteHost() is InetSocketAddress.getHostName(): a reverse-DNS lookup. A client with PTR records for many
        // addresses would get one bucket key per address (and a /64 would not group). The Netty engine cannot be made to resolve a name
        // here, so the
        // connection point is played by hand; the function is what the Setup interceptor of configureHttp copies into the origin.
        val local = pointOf<RequestConnectionPoint>(
            mutableMapOf("RemoteHost" to "client-17.attacker.example", "RemoteAddress" to "203.0.113.17"),
        )
        assertEquals("client-17.attacker.example", local.remoteHost, "the engine's own remoteHost is the resolved name")
        assertEquals("203.0.113.17", numericClientHost(local))

        val v6 = pointOf<RequestConnectionPoint>(
            mutableMapOf("RemoteHost" to "v6.attacker.example", "RemoteAddress" to "2001:db8:1:2::9"),
        )
        assertEquals("2001:db8:1:2::9", numericClientHost(v6))
    }

    @Test
    fun `with corsHosts set a malformed Host is a 400 problem, never a 500 from the CORS plugin`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        val client = jsonClient()
        // POST /login declares a 400, so the conformance plugin accepts the answer; the Host check is path-independent.
        suspend fun login(host: String?) = client.post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "https://app.example.com")
            if (host != null) header(HttpHeaders.Host, host)
            setBody(LoginRequest(uniqueEmail("host"), "wrong"))
        }
        val capture = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            for (host in listOf("localhost:abc", "localhost:", "localhost:99999999999")) {
                val response = login(host)
                assertEquals(HttpStatusCode.BadRequest, response.status, "Host: $host")
                assertEquals(400, response.body<ProblemDetail>().status)
            }
            assertTrue(capture.events.none { it.level.toString() == "ERROR" }, "no stack trace for a header the client chose")
        } finally {
            capture.detach()
        }
        assertEquals(HttpStatusCode.Unauthorized, login(null).status, "a readable Host reaches the route")
    }

    @Test
    fun `without corsHosts the Host check is not installed`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().get("/api/v1/health") { header(HttpHeaders.Host, "localhost:abc") }
        assertEquals(HttpStatusCode.OK, response.status, "nothing reads the Host port on this route")
    }
}
