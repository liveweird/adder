package ch.nokillswit

import ch.nokillswit.infra.outbound.BoundedPreviewDnsResolver
import ch.nokillswit.infra.outbound.ValidatedPreviewTarget
import ch.nokillswit.notifications.PushResult
import ch.nokillswit.notifications.WebPushTransport
import ch.nokillswit.notifications.browserPushConfig
import com.auth0.jwt.JWT
import io.ktor.server.config.MapApplicationConfig
import java.io.IOException
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class BrowserPushTransportTest {
    @Test
    fun `an empty bounded Web Push request carries a short lived VAPID token`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        var captured: Request? = null
        val target = ValidatedPreviewTarget(
            ENDPOINT.toHttpUrl(),
            listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))),
        )
        WebPushTransport(
            config = config,
            targetResolver = { _, _ -> target },
            requestSender = { _, request -> captured = request; 201 },
        ).use { transport ->
            assertEquals(PushResult.DELIVERED, transport.send(ENDPOINT))
        }
        val request = assertNotNull(captured)
        assertEquals("POST", request.method)
        assertEquals(0, request.body?.contentLength())
        assertEquals("60", request.header("TTL"))
        assertEquals("normal", request.header("Urgency"))
        val authorization = assertNotNull(request.header("Authorization"))
        assertTrue(authorization.startsWith("vapid t="))
        assertTrue(authorization.endsWith(", k=${config.publicKey}"))
        val token = authorization.substringAfter("vapid t=").substringBefore(", k=")
        val decoded = JWT.decode(token)
        assertEquals(listOf("https://fcm.googleapis.com"), decoded.audience)
        assertEquals("mailto:push-test@adder.local", decoded.subject)
        val remaining = decoded.expiresAt.time - System.currentTimeMillis()
        assertTrue(remaining in 11 * 60 * 60 * 1000L..12 * 60 * 60 * 1000L)
    }

    @Test
    fun `target validation and resolution failures settle one delivery`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        WebPushTransport(
            config = config,
            targetResolver = { _, _ -> error("resolver failed") },
        ).use { transport ->
            assertEquals(PushResult.FAILED, transport.send(ENDPOINT))
        }
    }

    @Test
    fun `provider status classes have stable delivery outcomes`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        val target = validTarget()
        val cases = mapOf(
            200 to PushResult.DELIVERED,
            201 to PushResult.DELIVERED,
            202 to PushResult.DELIVERED,
            404 to PushResult.EXPIRED,
            410 to PushResult.EXPIRED,
            400 to PushResult.FAILED,
            500 to PushResult.FAILED,
        )
        for ((status, expected) in cases) {
            WebPushTransport(
                config = config,
                targetResolver = { _, _ -> target },
                requestSender = { _, _ -> status },
            ).use { transport -> assertEquals(expected, transport.send(ENDPOINT), status.toString()) }
        }
    }

    @Test
    fun `bounded DNS resolution feeds the validated target`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        var resolvedHost: String? = null
        val resolver = BoundedPreviewDnsResolver(startLookup = { host ->
            resolvedHost = host
            CompletableFuture.completedFuture(listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))))
        })
        WebPushTransport(
            config = config,
            dnsResolver = resolver,
            requestSender = { target, _ ->
                assertEquals("fcm.googleapis.com", target.url.host)
                202
            },
        ).use { transport -> assertEquals(PushResult.DELIVERED, transport.send(ENDPOINT)) }
        assertEquals("fcm.googleapis.com.", resolvedHost)
    }

    @Test
    fun `the real pinned client settles a refused provider connection`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        val loopback = ValidatedPreviewTarget(ENDPOINT.toHttpUrl(), listOf(InetAddress.getLoopbackAddress()))
        WebPushTransport(
            config = config,
            timeout = Duration.ofMillis(200),
            targetResolver = { _, _ -> loopback },
        ).use { transport -> assertEquals(PushResult.FAILED, transport.send(ENDPOINT)) }
    }

    @Test
    fun `transport rejects target substitution and preserves cancellation`() = runBlocking {
        val config = checkNotNull(MapApplicationConfig(*vapidConfig()).browserPushConfig())
        val substituted = ValidatedPreviewTarget(
            "https://example.net/push".toHttpUrl(),
            listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))),
        )
        WebPushTransport(config, targetResolver = { _, _ -> substituted }).use { transport ->
            assertEquals(PushResult.FAILED, transport.send(ENDPOINT))
        }
        WebPushTransport(config, targetResolver = { _, _ -> throw IOException("resolver unavailable") }).use { transport ->
            assertEquals(PushResult.FAILED, transport.send(ENDPOINT))
        }
        WebPushTransport(config, targetResolver = { _, _ -> throw CancellationException("cancelled") }).use { transport ->
            assertFailsWith<CancellationException> { transport.send(ENDPOINT) }
        }
        Unit
    }

    private fun validTarget() = ValidatedPreviewTarget(
        ENDPOINT.toHttpUrl(),
        listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))),
    )

    private companion object {
        const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-browser-endpoint"
    }
}
