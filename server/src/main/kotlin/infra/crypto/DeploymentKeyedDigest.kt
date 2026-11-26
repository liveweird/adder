package ch.nokillswit.infra.crypto

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Domain-separated HMAC for caller-canonicalized deployment identities; raw values and JWT signing material never enter storage. */
internal class DeploymentKeyedDigest(jwtSecret: String, derivationLabel: String) {
    private val key = hmac(jwtSecret.toByteArray(Charsets.UTF_8), derivationLabel.toByteArray(Charsets.UTF_8))

    /** Digests an already canonical value. This helper deliberately performs no trimming, folding or address parsing. */
    fun ofCanonical(canonicalValue: String): String = HexFormat.of().formatHex(
        hmac(key, canonicalValue.toByteArray(Charsets.UTF_8)),
    )

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)
}
