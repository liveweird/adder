package ch.nokillswit.infra.outbound

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val MAX_PREVIEW_URL_LENGTH = 2_048
private const val HTTPS_PORT = 443

/** A uniform refusal. The caller owns safe response and audit details; the URL never belongs in logs. */
internal class BlockedPreviewUrlException : RuntimeException("Blocked link preview URL")

/** The canonical URL and the exact vetted IPv4 snapshot the socket transport may use. */
internal data class ValidatedPreviewTarget(val url: HttpUrl, val addresses: List<InetAddress>)

internal fun resolvePreviewTarget(
    raw: String,
    resolver: (String) -> List<InetAddress>,
): ValidatedPreviewTarget {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_PREVIEW_URL_LENGTH) throw BlockedPreviewUrlException()
    val uri = try {
        URI(trimmed)
    } catch (_: URISyntaxException) {
        throw BlockedPreviewUrlException()
    }
    val url = trimmed.toHttpUrlOrNull() ?: throw BlockedPreviewUrlException()
    val invalidSchemeOrPort = !uri.isAbsolute || !url.isHttps || url.port != HTTPS_PORT
    val hasCredentials = uri.userInfo != null || url.username.isNotEmpty() || url.password.isNotEmpty()
    val invalidHost = isAddressLiteral(url.host) || isLocalName(url.host) || !url.host.trimEnd('.').contains('.')
    if (invalidSchemeOrPort || hasCredentials || invalidHost) {
        throw BlockedPreviewUrlException()
    }
    val answers = try {
        resolver(url.host)
    } catch (_: UnknownHostException) {
        throw BlockedPreviewUrlException()
    }
    val ipv4 = answers.filterIsInstance<Inet4Address>()
    if (ipv4.isEmpty() || ipv4.any(InetAddress::isDisallowedPreviewIpv4)) throw BlockedPreviewUrlException()
    // Drop public IPv6 answers deliberately: the deployment egress policy and transport are IPv4-only.
    return ValidatedPreviewTarget(url, ipv4.map { InetAddress.getByAddress(it.address) })
}

internal fun absoluteDnsName(host: String): String = host.trimEnd('.') + "."

private fun isAddressLiteral(host: String): Boolean =
    IPV4_LITERAL.matches(host) || ':' in host

private fun isLocalName(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    return LOCAL_NAMES.any { normalized == it || normalized.endsWith(".$it") }
}

private fun InetAddress.isDisallowedPreviewIpv4(): Boolean {
    val bytes = address
    if (bytes.size != 4) return true
    val value = bytes.fold(0L) { result, byte -> result shl 8 or (byte.toLong() and 0xff) }
    return DISALLOWED_IPV4_RANGES.any { value in it }
}

private fun ipv4Range(first: String, last: String): LongRange = ipv4Value(first)..ipv4Value(last)

private fun ipv4Value(value: String): Long = value.split('.').fold(0L) { result, part -> result shl 8 or part.toLong() }

private val IPV4_LITERAL = Regex("""(?:\d{1,3}\.){3}\d{1,3}""")
private val LOCAL_NAMES = setOf("localhost", "local", "internal", "home.arpa", "invalid", "test", "example", "onion")
private val DISALLOWED_IPV4_RANGES = listOf(
    ipv4Range("0.0.0.0", "0.255.255.255"),
    ipv4Range("10.0.0.0", "10.255.255.255"),
    ipv4Range("100.64.0.0", "100.127.255.255"),
    ipv4Range("127.0.0.0", "127.255.255.255"),
    ipv4Range("169.254.0.0", "169.254.255.255"),
    ipv4Range("172.16.0.0", "172.31.255.255"),
    ipv4Range("192.0.0.0", "192.0.0.255"),
    ipv4Range("192.0.2.0", "192.0.2.255"),
    ipv4Range("192.31.196.0", "192.31.196.255"),
    ipv4Range("192.52.193.0", "192.52.193.255"),
    ipv4Range("192.88.99.0", "192.88.99.255"),
    ipv4Range("192.168.0.0", "192.168.255.255"),
    ipv4Range("192.175.48.0", "192.175.48.255"),
    ipv4Range("198.18.0.0", "198.19.255.255"),
    ipv4Range("198.51.100.0", "198.51.100.255"),
    ipv4Range("203.0.113.0", "203.0.113.255"),
    ipv4Range("224.0.0.0", "255.255.255.255"),
)
