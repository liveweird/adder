package ch.nokillswit.live

import io.netty.util.NetUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An IPv6 address is counted by its /64, the smallest block a provider hands to one subscriber: 8 of its 16 bytes. */
private const val IPV6_NETWORK_BYTES = 8
private const val IPV4_BYTES = 4

/** An IPv4-mapped IPv6 address (::ffff:a.b.c.d) is ten zero bytes, two 0xff bytes and the IPv4 address. */
private const val IPV4_MAPPED_ZEROS = 10
private const val IPV4_MAPPED_OFFSET = 12
private const val MAPPED_MARK: Byte = -1

/** A key is client-influenced text behind a proxy; cut so a malformed one cannot grow a map entry. */
private const val MAX_ADDRESS_KEY = 64
private val IPV6_LITERAL = Regex("""[0-9a-fA-F:][0-9a-fA-F:.]*""")

/**
 * The key a client address is counted under (`live.maxPendingPerAddress`): an IPv4 address whole, an IPv6 one by its /64 (so a host that
 * rotates through its /64 is still one client), an IPv4-mapped IPv6 address as the IPv4 one. [remoteHost] is what the per-IP `live-connect`
 * bucket keys by (`call.request.origin.remoteHost`, which `XForwardedHeaders` rewrites to the real client behind a proxy), so the two
 * agree about who the client is. Anything that is not an address literal is used as it is (lower case, cut): it still groups itself.
 */
internal fun pendingAddressKey(remoteHost: String): String {
    val host = remoteHost.trim().removePrefix("[").substringBefore("%").substringBefore("]")
    // Netty's parser reads a literal and nothing else. `InetAddress.getByName` would hand any string it cannot parse numerically (".a:b",
    // "a:b") to the DNS resolver, and this text is client-influenced behind a proxy.
    val bytes = if (':' in host && IPV6_LITERAL.matches(host)) NetUtil.createByteArrayFromIpAddressString(host) else null
    if (bytes == null) return remoteHost.trim().lowercase().take(MAX_ADDRESS_KEY)
    if (bytes.size == IPV4_BYTES) return ipv4(bytes, 0)
    // ::ffff:a.b.c.d is the IPv4 address a.b.c.d
    val mapped = (0 until IPV4_MAPPED_ZEROS).all { bytes[it].toInt() == 0 } && bytes[10] == MAPPED_MARK && bytes[11] == MAPPED_MARK
    if (mapped) return ipv4(bytes, IPV4_MAPPED_OFFSET)
    return "v6/" + bytes.take(IPV6_NETWORK_BYTES).joinToString("") { "%02x".format(it) }
}

private fun ipv4(bytes: ByteArray, from: Int): String =
    (from until from + IPV4_BYTES).joinToString(".") { (bytes[it].toInt() and 0xff).toString() }

/** The outcome of [PendingSlots.claim]. */
internal enum class PendingClaim { OK, ADDRESS_FULL, FULL }

/**
 * The sockets that have upgraded but not yet authenticated (`live.md` "Bounds"): a total and a share per client address. Claims are
 * atomic (a refused claim counts nothing), so the caps hold under concurrent upgrades; the admission reads are advisory.
 */
internal class PendingSlots {
    /** Unauthenticated sockets, the whole process. */
    val total = AtomicInteger()
    private val byAddress = ConcurrentHashMap<String, Int>()

    /** Unauthenticated sockets [address] (a [pendingAddressKey]) holds now. */
    fun held(address: String): Int = byAddress[address] ?: 0

    /** Takes one slot for [address] unless it holds [maxPerAddress] already or the process [maxTotal]; the address is judged first. */
    fun claim(address: String, maxTotal: Int, maxPerAddress: Int): PendingClaim {
        var taken = false
        byAddress.compute(address) { _, held ->
            val current = held ?: 0
            if (current < maxPerAddress) {
                taken = true
                current + 1
            } else {
                held
            }
        }
        if (!taken) return PendingClaim.ADDRESS_FULL
        if (total.incrementAndGet() > maxTotal) {
            total.decrementAndGet()
            release(address, countTotal = false)
            return PendingClaim.FULL
        }
        return PendingClaim.OK
    }

    /** Frees a slot [claim] took; the entry goes with its last slot, so the map is bounded by the open unauthenticated sockets. */
    fun release(address: String, countTotal: Boolean = true) {
        byAddress.compute(address) { _, held -> if (held == null || held <= 1) null else held - 1 }
        if (countTotal) total.decrementAndGet()
    }
}
