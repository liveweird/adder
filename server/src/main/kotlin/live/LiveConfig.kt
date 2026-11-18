package ch.nokillswit.live

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.Application

/** Upper bound of `live.maxTickets`: at roughly 500 bytes an entry, 100,000 tickets are about 50 MB of heap (`live.md` "Bounds"). */
private const val MAX_TICKETS_CEILING = 100_000

/**
 * The `live.*` settings (`application.yaml`), boot-validated through `requireConfigInt`: a malformed or out-of-range value refuses
 * startup, naming the key. Only the bounds a test must shrink are config (`live.md` "Bounds"); the socket step adds its own keys here.
 */
data class LiveConfig(
    /** Unexpired tickets held at once (`live.maxTickets`, 1..100,000); past it a mint is a `429` and `live.ticket_saturated`. */
    val maxTickets: Int,
)

fun Application.readLiveConfig(): LiveConfig = LiveConfig(
    maxTickets = requireConfigInt(environment.config, "live.maxTickets", min = 1, max = MAX_TICKETS_CEILING),
)
