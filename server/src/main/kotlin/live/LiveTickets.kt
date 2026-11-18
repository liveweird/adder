package ch.nokillswit.live

import ch.nokillswit.infra.maskedSecret
import io.ktor.util.AttributeKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.TreeMap

/** The ticket store, published on `Application.attributes` by `configureLive` (the socket step redeems from it). */
val LiveTicketsKey = AttributeKey<LiveTickets>("LiveTickets")

/** How long a minted ticket can be redeemed (`live.md` "Tickets"): the client opens the socket right after the mint. */
const val LIVE_TICKET_TTL_MILLIS = 30_000L

/**
 * What a redeemed ticket proves: the account and the access token it was minted with. The socket re-judges the token
 * (`TokenBlocklistService.accessVerdict`) at redemption and closes at [tokenExpiresAtMillis]. [expiresAtMillis] is the ticket's own
 * deadline.
 */
data class LiveTicketBinding(
    val userId: UInt,
    val isAdmin: Boolean,
    /** The token's additional-roles claim, exactly as `accessVerdict` compares it. */
    val roles: Set<String>,
    val jti: String,
    val credentialRevision: Long,
    val tokenExpiresAtMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString(): String =
        "LiveTicketBinding(userId=$userId, isAdmin=$isAdmin, roles=$roles, jti=${maskedSecret(jti)}, " +
            "credentialRevision=$credentialRevision, tokenExpiresAtMillis=$tokenExpiresAtMillis, expiresAtMillis=$expiresAtMillis)"
}

/**
 * In-memory store of single-use socket tickets (ported from `auth/MfaChallenges.kt`). A ticket is 32 bytes from [SecureRandom]
 * (43 base64url characters) handed to a signed-in caller; the socket sends it in its FIRST FRAME (never in a URL) and the store
 * answers the [LiveTicketBinding] it was minted for. Only the ticket's SHA-256 is kept, so a heap dump holds no usable ticket, and a
 * lookup is a map equality on the digest (no timing signal on the secret). Redeeming is an atomic remove: a ticket works once; an
 * unknown, used and expired ticket are one answer (null). A ticket expires after [ttlMillis]; expired entries are swept on every mint
 * and the store holds at most [maxTickets], past which [issue] throws [CapacityExceededException].
 *
 * In-memory and per-instance by design (the `MfaChallenges` posture): the deployment runs a single replica, and a restart only
 * invalidates the tickets in flight, so a socket that cannot redeem simply mints again.
 */
class LiveTickets(
    private val maxTickets: Int,
    private val ttlMillis: Long = LIVE_TICKET_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxTickets > 0) { "maxTickets must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    class CapacityExceededException : RuntimeException()

    data class IssuedTicket(val ticket: String, val expiresAtMillis: Long) {
        override fun toString(): String = "IssuedTicket(ticket=${maskedSecret(ticket)}, expiresAtMillis=$expiresAtMillis)"
    }

    private val tickets = mutableMapOf<String, LiveTicketBinding>()
    private val expiryIndex = TreeMap<Long, MutableSet<String>>()
    private val lock = Any()

    /**
     * Mints a ticket for [userId]'s access token, valid for [ttlMillis] but never past [tokenExpiresAtMillis] (a ticket must not outlive
     * the token it was minted with). Throws [CapacityExceededException] when [maxTickets] unexpired tickets are held.
     */
    fun issue(
        userId: UInt,
        isAdmin: Boolean,
        roles: Set<String>,
        jti: String,
        credentialRevision: Long,
        tokenExpiresAtMillis: Long,
    ): IssuedTicket {
        // The randomness and the hash are the slow part: they run before the lock, which only guards the maps.
        val ticket = generateTicket()
        val digest = digestOf(ticket)
        return synchronized(lock) { store(ticket, digest, userId, isAdmin, roles, jti, credentialRevision, tokenExpiresAtMillis) }
    }

    private fun store(
        ticket: String,
        digest: String,
        userId: UInt,
        isAdmin: Boolean,
        roles: Set<String>,
        jti: String,
        credentialRevision: Long,
        tokenExpiresAtMillis: Long,
    ): IssuedTicket {
        val now = clock()
        pruneExpired(now)
        if (tickets.size >= maxTickets) throw CapacityExceededException()
        val expiresAt = minOf(now + ttlMillis, tokenExpiresAtMillis)
        tickets[digest] = LiveTicketBinding(userId, isAdmin, roles, jti, credentialRevision, tokenExpiresAtMillis, expiresAt)
        expiryIndex.getOrPut(expiresAt) { mutableSetOf() }.add(digest)
        return IssuedTicket(ticket, expiresAt)
    }

    /** Consumes [ticket]: its binding the first time within the TTL, null for an unknown, used or expired one. */
    fun redeem(ticket: String): LiveTicketBinding? = synchronized(lock) {
        val binding = removeTicket(digestOf(ticket)) ?: return@synchronized null
        binding.takeIf { it.expiresAtMillis > clock() }
    }

    /** The number of tickets held (expired ones included until the next mint sweeps them); a test seam and a future gauge. */
    fun size(): Int = synchronized(lock) { tickets.size }

    private fun pruneExpired(now: Long) {
        while (expiryIndex.firstEntry()?.key?.let { it <= now } == true) {
            val (expiresAt, digests) = expiryIndex.pollFirstEntry()
            digests.forEach { digest ->
                if (tickets[digest]?.expiresAtMillis == expiresAt) tickets.remove(digest)
            }
        }
    }

    private fun removeTicket(digest: String): LiveTicketBinding? {
        val removed = tickets.remove(digest) ?: return null
        expiryIndex[removed.expiresAtMillis]?.let { digests ->
            digests.remove(digest)
            if (digests.isEmpty()) expiryIndex.remove(removed.expiresAtMillis)
        }
        return removed
    }

    private companion object {
        val secureRandom = SecureRandom()
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        /** 256 bits of opaque, unguessable ticket, 43 base64url characters. */
        fun generateTicket(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return encoder.encodeToString(bytes)
        }

        fun digestOf(ticket: String): String =
            MessageDigest.getInstance("SHA-256").digest(ticket.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
