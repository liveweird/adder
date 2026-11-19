package ch.nokillswit.live

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.Application

/** Upper bound of `live.maxTickets`: at roughly 500 bytes an entry, 100,000 tickets are about 50 MB of heap (`live.md` "Bounds"). */
private const val MAX_TICKETS_CEILING = 100_000

/** Upper bounds of the socket keys (`live.md` "Bounds"): a socket is a few KiB of queue and state, so 10,000 stay well inside the heap. */
private const val MAX_SOCKETS_CEILING = 10_000
private const val MAX_SOCKETS_PER_USER_CEILING = 64
private const val MAX_PENDING_AUTH_CEILING = 10_000
private const val MAX_PENDING_PER_ADDRESS_CEILING = 10_000

/** `live.authTimeoutMillis` stays between 100 ms (a test's shrink) and 30 s (a client that cannot mint and send in that time is gone). */
private const val MIN_AUTH_TIMEOUT_MILLIS = 100
private const val MAX_AUTH_TIMEOUT_MILLIS = 30_000

/**
 * The `live.*` settings (`application.yaml`), boot-validated through `requireConfigInt`: a malformed or out-of-range value refuses
 * startup, naming the key. Only the bounds a test must shrink are config (`live.md` "Bounds").
 */
data class LiveConfig(
    /** Unexpired tickets held at once (`live.maxTickets`, 1..100,000); past it a mint is a `429` and `live.ticket_saturated`. */
    val maxTickets: Int,
    /** Authenticated sockets at once, the whole process (`live.maxSockets`, 1..10,000); past it an upgrade and a mint are a `503`. */
    val maxSockets: Int,
    /**
     * Authenticated sockets one user may hold, one per tab (`live.maxSocketsPerUser`, 1..64); past it a mint is a `429`, a redeem 4429.
     */
    val maxSocketsPerUser: Int,
    /** Sockets that have not yet sent a good `auth` frame (`live.maxPendingAuth`, 1..10,000); past it an upgrade is a `503`. */
    val maxPendingAuth: Int,
    /**
     * Of those, how many one client address may hold (`live.maxPendingPerAddress`, 1..10,000; IPv6 by its /64): past it an upgrade is a
     * `503`, so one address cannot exhaust [maxPendingAuth].
     */
    val maxPendingPerAddress: Int,
    /** How long a socket may stay unauthenticated (`live.authTimeoutMillis`, 100..30,000); the deadline closes it 4401. */
    val authTimeoutMillis: Int,
)

fun Application.readLiveConfig(): LiveConfig {
    val config = environment.config
    return LiveConfig(
        maxTickets = requireConfigInt(config, "live.maxTickets", min = 1, max = MAX_TICKETS_CEILING),
        maxSockets = requireConfigInt(config, "live.maxSockets", min = 1, max = MAX_SOCKETS_CEILING),
        maxSocketsPerUser = requireConfigInt(config, "live.maxSocketsPerUser", min = 1, max = MAX_SOCKETS_PER_USER_CEILING),
        maxPendingAuth = requireConfigInt(config, "live.maxPendingAuth", min = 1, max = MAX_PENDING_AUTH_CEILING),
        maxPendingPerAddress = requireConfigInt(
            config, "live.maxPendingPerAddress", min = 1, max = MAX_PENDING_PER_ADDRESS_CEILING,
        ),
        authTimeoutMillis = requireConfigInt(
            config, "live.authTimeoutMillis", min = MIN_AUTH_TIMEOUT_MILLIS, max = MAX_AUTH_TIMEOUT_MILLIS,
        ),
    )
}
