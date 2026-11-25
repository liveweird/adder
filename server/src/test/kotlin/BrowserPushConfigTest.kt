package ch.nokillswit

import ch.nokillswit.notifications.browserPushConfig
import io.ktor.server.config.MapApplicationConfig
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserPushConfigTest {
    @Test
    fun `push is unavailable only when every VAPID setting is absent or blank`() {
        assertNull(MapApplicationConfig().browserPushConfig())
        assertNull(
            MapApplicationConfig(
                "notifications.push.publicKey" to " ",
                "notifications.push.privateKey" to "\t",
                "notifications.push.subject" to "",
            ).browserPushConfig(),
        )
        for (present in listOf(setOf(0), setOf(1), setOf(2), setOf(0, 1), setOf(0, 2), setOf(1, 2))) {
            val values = KEYS.mapIndexed { index, key -> key to if (index in present) "configured" else "" }.toTypedArray()
            val failure = assertFailsWith<IllegalStateException> { MapApplicationConfig(*values).browserPushConfig() }
            assertTrue(failure.message.orEmpty().contains("requires"))
        }
    }

    @Test
    fun `VAPID subject is an absolute contact URI without a fragment`() {
        val keys = vapidConfig(subject = "https://adder.example/contact")
        assertEquals("https://adder.example/contact", assertNotNull(MapApplicationConfig(*keys).browserPushConfig()).subject)
        for (subject in listOf(
            "%",
            "relative/contact",
            "ftp://adder.example/contact",
            "mailto:push@adder.local#team",
            "https://adder.example/contact#team",
            "https:contact",
        )) {
            val failure = assertFailsWith<IllegalStateException> {
                MapApplicationConfig(*vapidConfig(subject = subject)).browserPushConfig()
            }
            assertTrue(failure.message.orEmpty().contains("mailto: or https:"), subject)
        }
    }

    @Test
    fun `public VAPID point rejects malformed encodings shapes and prefixes`() {
        val valid = vapidConfig().toMap()
        val malformed = listOf(
            "%" to "base64url",
            (valid.getValue(KEYS[0]) + "=") to "base64url",
            encode(ByteArray(64) { 1 }) to "65-byte",
            encode(ByteArray(65) { 1 }) to "65-byte",
        )
        for ((publicKey, message) in malformed) {
            val failure = assertFailsWith<IllegalStateException> {
                MapApplicationConfig(
                    KEYS[0] to publicKey,
                    KEYS[1] to valid.getValue(KEYS[1]),
                    KEYS[2] to valid.getValue(KEYS[2]),
                ).browserPushConfig()
            }
            assertTrue(failure.message.orEmpty().contains(message), publicKey)
        }
    }

    @Test
    fun `private VAPID key rejects malformed PKCS8 and non P256 curves`() {
        val valid = vapidConfig().toMap()
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val malformed = listOf(
            "%" to "base64url",
            encode(ByteArray(32) { 1 }) to "PKCS#8",
            encode(p384.private.encoded) to "P-256",
        )
        for ((privateKey, message) in malformed) {
            val failure = assertFailsWith<IllegalStateException> {
                MapApplicationConfig(
                    KEYS[0] to valid.getValue(KEYS[0]),
                    KEYS[1] to privateKey,
                    KEYS[2] to valid.getValue(KEYS[2]),
                ).browserPushConfig()
            }
            assertTrue(failure.message.orEmpty().contains(message), message)
        }
    }
}

private val KEYS = listOf(
    "notifications.push.publicKey",
    "notifications.push.privateKey",
    "notifications.push.subject",
)

internal fun vapidConfig(subject: String = "mailto:push-test@adder.local"): Array<Pair<String, String>> {
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
    return arrayOf(
        KEYS[0] to encode(raw),
        KEYS[1] to encode(pair.private.encoded),
        KEYS[2] to subject,
    )
}

private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
