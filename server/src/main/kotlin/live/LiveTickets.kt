package ch.nokillswit.live

import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.maskedSecret
import io.ktor.util.AttributeKey
import java.security.SecureRandom
import java.util.Base64

/** The ticket service, published by `configureLive`; production always gives it the shared PostgreSQL store. */
val LiveTicketsKey = AttributeKey<LiveTickets>("LiveTickets")

/** Test seam installed before `configureLive`; production never publishes it. */
internal val LiveTicketStoreOverrideKey = AttributeKey<LiveTicketStore>("LiveTicketStoreOverride")

/** How long a minted ticket can be redeemed, never beyond its access token's own deadline. */
const val LIVE_TICKET_TTL_MILLIS = 30_000L

data class LiveTicketBinding(
    val userId: UInt,
    val isAdmin: Boolean,
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

internal class LiveTicketDraft(
    val userId: UInt,
    val isAdmin: Boolean,
    val roles: Set<String>,
    val jti: String,
    val credentialRevision: Long,
    val tokenExpiresAtMillis: Long,
)

/** Storage contract kept narrow so pure ticket-shape tests can use a fake without a local production fallback. */
internal interface LiveTicketStore {
    suspend fun issue(digest: String, draft: LiveTicketDraft): Long
    suspend fun redeem(digest: String): LiveTicketBinding?
}

internal class LiveTicketCapacityExceededException : RuntimeException()
internal class LiveTicketStoreUnavailableException(cause: Throwable) : RuntimeException(null, cause, false, false)

class LiveTickets internal constructor(
    private val store: LiveTicketStore,
    private val ticketGenerator: () -> String = ::generateTicket,
) {
    data class IssuedTicket(val ticket: String, val expiresAtMillis: Long) {
        override fun toString(): String = "IssuedTicket(ticket=${maskedSecret(ticket)}, expiresAtMillis=$expiresAtMillis)"
    }

    suspend fun issue(
        userId: UInt,
        isAdmin: Boolean,
        roles: Set<String>,
        jti: String,
        credentialRevision: Long,
        tokenExpiresAtMillis: Long,
    ): IssuedTicket {
        val ticket = ticketGenerator()
        val expiresAt = store.issue(
            sha256Hex(ticket),
            LiveTicketDraft(userId, isAdmin, roles, jti, credentialRevision, tokenExpiresAtMillis),
        )
        return IssuedTicket(ticket, expiresAt)
    }

    suspend fun redeem(ticket: String): LiveTicketBinding? = store.redeem(sha256Hex(ticket))

    private companion object {
        val secureRandom = SecureRandom()
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun generateTicket(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return encoder.encodeToString(bytes)
        }
    }
}
