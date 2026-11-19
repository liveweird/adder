package ch.nokillswit.live

import ch.nokillswit.audit.audit
import ch.nokillswit.plugins.RateLimitAuditThrottle
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** Room ids one `live.subscribe_denied` line lists. */
internal const val MAX_DENIED_LISTED = 20

/** Distinct origin hosts the `live.refused reason=origin` throttle keeps a window for; the rest share one (a bounded set). */
internal const val MAX_ORIGIN_KEYS = 64
private const val MAX_HOST_KEY = 100
private val ANY_ORIGIN_HOST = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://(\[[0-9a-fA-F:.]+]|[^:/?#@\[\]\s]+)""")

/**
 * The live channel's audit lines (`observability.md`): `live.refused` is COALESCED through [RateLimitAuditThrottle] (one line per reason
 * a minute before authentication, per reason and user after it, with the `suppressed` count), so a flood of refused sockets writes a
 * handful of lines. Two refinements keep one noisy source from hiding another: a refusal that names a `limit` is keyed by it too (a
 * per-address refusal does not silence the global one), and an `origin` refusal by the origin's HOST, in a set of at most
 * [MAX_ORIGIN_KEYS] hosts (past it they share one window), so a probe from one site does not hide a different site's.
 * `live.subscribe_denied` is coalesced per user the same way (the inbound frame rate alone would still allow 3 lines a second per socket)
 * and its unwritten tail is written by [flushDenied] (the sweep tick). The client address is deliberately NOT a field (the
 * `rate_limit.exceeded` rule: behind a proxy it is attacker-chosen text; the access log has it).
 */
internal class LiveAudit(
    private val throttle: RateLimitAuditThrottle = RateLimitAuditThrottle(),
    private val deniedThrottle: RateLimitAuditThrottle = RateLimitAuditThrottle(),
) {
    private val pendingDenied = ConcurrentHashMap<UInt, TreeSet<UInt>>()
    private val originKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun refused(reason: String, userId: UInt? = null, vararg fields: Pair<String, Any?>) {
        val limit = fields.firstOrNull { it.first == "limit" }?.second
        write("live.refused:$reason" + (userId?.let { ":$it" } ?: "") + (limit?.let { ":$it" } ?: ""), reason, userId, fields)
    }

    /** `live.refused reason=origin` for the printable [origin] and why ([OriginVerdict.detail]); the window is per origin host. */
    fun refusedOrigin(origin: String?, verdict: OriginVerdict) {
        val host = origin?.let { ANY_ORIGIN_HOST.find(it)?.groupValues?.get(1) }?.lowercase()?.take(MAX_HOST_KEY) ?: "unparsed"
        val key = if (host in originKeys || (originKeys.size < MAX_ORIGIN_KEYS && originKeys.add(host))) host else "*"
        write("live.refused:origin:$key", "origin", null, arrayOf("origin" to origin, "detail" to verdict.detail))
    }

    private fun write(key: String, reason: String, userId: UInt?, fields: Array<out Pair<String, Any?>>) {
        throttle.admit(key)?.let { suppressed ->
            val identity = if (userId == null) emptyArray() else arrayOf("byUserId" to userId.toLong())
            audit("live.refused", "reason" to reason, *identity, *fields, "suppressed" to suppressed)
        }
    }

    /**
     * COALESCED per user (one line a minute with the `suppressed` count): a client can send a subscribe per frame, and each denied one
     * would otherwise cost a log line. The line lists the UNION of the rooms denied since the last line, at most [MAX_DENIED_LISTED] ids
     * (the first ones seen, ascending within a frame), so a rotating probe is not lost to the window.
     */
    fun subscribeDenied(userId: UInt, roomIds: Collection<UInt>) {
        // The line is decided inside `compute` (atomic per user) but WRITTEN after it: never log while holding the map's lock.
        var due: Pair<List<UInt>, Long>? = null
        pendingDenied.compute(userId) { _, current ->
            val ids = current ?: TreeSet()
            roomIds.sorted().forEach { if (ids.size < MAX_DENIED_LISTED) ids.add(it) }
            val suppressed = deniedThrottle.admit(deniedKey(userId))
            if (suppressed == null) {
                ids
            } else {
                due = ids.toList() to suppressed
                null
            }
        }
        due?.let { (ids, suppressed) -> writeDenied(userId, ids, suppressed) }
    }

    /**
     * Writes the denied rooms (and the `suppressed` count) that no later call has carried out, for every user whose window is due: a
     * probe that stops mid-window would otherwise leave its tail unwritten. Called on the sweep tick, so the tail waits at most a window
     * plus a tick. Also drops the entries it empties, so the map holds only users with something waiting.
     */
    fun flushDenied() {
        pendingDenied.keys.toList().forEach { userId ->
            var due: Pair<List<UInt>, Long>? = null
            pendingDenied.compute(userId) { _, ids ->
                when {
                    ids.isNullOrEmpty() -> null
                    else -> {
                        val suppressed = deniedThrottle.admitIfDue(deniedKey(userId))
                        if (suppressed == null) {
                            ids
                        } else {
                            due = ids.toList() to suppressed
                            null
                        }
                    }
                }
            }
            due?.let { (ids, suppressed) -> writeDenied(userId, ids, suppressed) }
        }
    }

    private fun deniedKey(userId: UInt) = "live.subscribe_denied:$userId"

    private fun writeDenied(userId: UInt, ids: Collection<UInt>, suppressed: Long) {
        audit(
            "live.subscribe_denied", "byUserId" to userId.toLong(), "roomIds" to ids.map { it.toLong() },
            "suppressed" to suppressed,
        )
    }
}
