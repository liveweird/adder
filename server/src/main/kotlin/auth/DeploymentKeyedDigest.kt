package ch.nokillswit.auth

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Domain-separated HMAC for normalized submitted identities. Raw values and reusable token-signing material never enter storage. */
internal class DeploymentKeyedDigest(jwtSecret: String, derivationLabel: String) {
    private val key = hmac(jwtSecret.toByteArray(Charsets.UTF_8), derivationLabel.toByteArray(Charsets.UTF_8))

    fun of(identity: String): String = HexFormat.of().formatHex(
        hmac(key, identity.trim().lowercase().toByteArray(Charsets.UTF_8)),
    )

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
}
