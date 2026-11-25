package ch.nokillswit.infra.presence

import ch.nokillswit.infra.db.nowMillis
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import io.ktor.util.AttributeKey

val ForegroundPresenceKey = AttributeKey<ForegroundPresence>("ForegroundPresence")

/** Process-local foreground presence; any fresh visible tab makes its user present. */
class ForegroundPresence(private val clock: () -> Long = ::nowMillis, private val leaseMillis: Long = 60_000) {
    class Session internal constructor(internal val id: Long, internal val userId: UInt)
    private data class Lease(val userId: UInt, val visible: Boolean, val renewedAt: Long)

    private val ids = AtomicLong()
    private val leases = ConcurrentHashMap<Long, Lease>()
    @Volatile private var stopping = false

    fun open(userId: UInt): Session = Session(ids.incrementAndGet(), userId)
    fun update(session: Session, visible: Boolean) {
        if (!stopping) leases[session.id] = Lease(session.userId, visible, clock())
    }
    fun close(session: Session) { leases.remove(session.id) }
    fun activeUserIds(): Set<UInt> {
        if (stopping) return emptySet()
        val cutoff = clock() - leaseMillis
        leases.entries.removeIf { it.value.renewedAt < cutoff }
        return leases.values.asSequence().filter { it.visible }.map { it.userId }.toSet()
    }
    fun stop() { stopping = true; leases.clear() }
}
