package ch.nokillswit

import ch.nokillswit.infra.outbound.ValidatedPreviewTarget
import ch.nokillswit.notifications.PushResult
import ch.nokillswit.notifications.WebPushTransport
import ch.nokillswit.notifications.browserPushConfig
import com.auth0.jwt.JWT
import io.ktor.server.config.MapApplicationConfig
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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

    private companion object {
        const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-browser-endpoint"

        fun vapidConfig(): Array<Pair<String, String>> {
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val pair = generator.generateKeyPair()
            val public = pair.public as ECPublicKey
            fun coordinate(value: java.math.BigInteger): ByteArray = value.toByteArray().let { bytes ->
                when {
                    bytes.size == 32 -> bytes
                    bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
                    else -> ByteArray(32 - bytes.size) + bytes
                }
            }
            val raw = byteArrayOf(4) + coordinate(public.w.affineX) + coordinate(public.w.affineY)
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return arrayOf(
                "notifications.push.publicKey" to encoder.encodeToString(raw),
                "notifications.push.privateKey" to encoder.encodeToString(pair.private.encoded),
                "notifications.push.subject" to "mailto:push-test@adder.local",
            )
        }
    }
}
