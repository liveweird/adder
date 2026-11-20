package ch.nokillswit.live

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /** Unauthenticated sockets [address] (a [ch.nokillswit.infra.clientAddressKey]) holds now. */
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
