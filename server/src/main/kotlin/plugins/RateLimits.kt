package ch.nokillswit.plugins

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.clientAddressKey
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.db.nowMillis
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** The name of the bucket a call last rode, stamped by its `requestKey`; read by the 429 audit in ErrorHandling.kt. */
internal val RateLimitBucketKey = AttributeKey<String>("RateLimitBucket")

/**
 * The authenticated user id the PER-USER telemetry bucket keyed the call by, stamped by its `requestKey`;
 * absent on the per-IP buckets. The 429 audit carries it as `byUserId` and throttles per user: it is the server-minted JWT claim
 * of a verified token, never client-chosen text.
 */
internal val RateLimitUserKey = AttributeKey<UInt>("RateLimitUser")

/**
 * Coalesces the `rate_limit.exceeded` audit event (checkup 2C15): an unauthenticated flood must not write one audit line
 * per rejected request. At most ONE event per key per [intervalMs]; [admit] returns the number of rejections folded
 * into the previous window (the event's `suppressed` field) when the caller should emit now, or null when this rejection
 * is folded into the current window. The key is the bucket name for a per-IP bucket (a handful of fixed names, never the
 * client) and `bucket:userId` for a per-user bucket (only a verified, signed-in account can reach it, so the key space is the
 * number of accounts), so memory is bounded no matter who floods; thread-safe via [ConcurrentHashMap.compute]'s per-key atomicity.
 */
internal class RateLimitAuditThrottle(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = ::nowMillis,
) {
    private class Window(var lastEmitMs: Long, var suppressed: Long)

    private val windows = ConcurrentHashMap<String, Window>()

    fun admit(bucket: String): Long? {
        var emit: Long? = null
        windows.compute(bucket) { _, window ->
            val now = clock()
            when {
                window == null -> Window(now, 0).also { emit = 0 }
                now - window.lastEmitMs >= intervalMs -> window.also {
                    emit = it.suppressed
                    it.lastEmitMs = now
                    it.suppressed = 0
                }
                else -> window.also { it.suppressed++ }
            }
        }
        return emit
    }

    /**
     * For a FLUSH of a coalesced event whose caller has nothing new to count: the number folded into the window when [bucket] has a window
     * that is due (the window restarts), else null WITHOUT counting a rejection. A bucket never seen has nothing to flush.
     */
    fun admitIfDue(bucket: String): Long? {
        var emit: Long? = null
        windows.computeIfPresent(bucket) { _, window ->
            val now = clock()
            if (now - window.lastEmitMs >= intervalMs) {
                emit = window.suppressed
                window.lastEmitMs = now
                window.suppressed = 0
            }
            window
        }
        return emit
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}

/**
 * Every token bucket the routes ride (per client IP, the live-connect included, except [TELEMETRY], [DIRECTORY], [ROOM_NAME],
 * [ROOM_MEMBERS], [CHAT_POST], [CHAT_WRITE], [CHAT_READ], [READ_STATE], [LIVE_TICKET], [SEARCH] and [PASSWORD_CHANGE]: per signed-in
 * user), named in one place
 * so a
 * feature module never has to know another's constant (the auth module used to import the try-it bucket's name — a layering
 * inversion the checkup removed). Route files reference these names; only this plugin installs them.
 */
object RateLimits {
    const val LOGIN = "login"
    const val REFRESH = "refresh"
    const val PASSWORD_RESET = "password-reset"
    const val MFA = "mfa"
    /** `POST /api/v1/password-reset/confirm` - the link's token exchange, 10/min per IP in every mode (the [MFA] idiom). */
    const val PASSWORD_RESET_CONFIRM = "password-reset-confirm"
    /** `GET /api/v1/ready` — the one anonymous route that takes a pooled DB connection per call (checkup D7-2, A16). */
    const val READY = "ready"
    /**
     * Both `GET /api/v1/telemetry/…` reads share ONE bucket per SIGNED-IN USER (the JWT's `userId`, never the client address: a NAT'd
     * office is not one bucket and changing IP buys nothing): 30/min in every mode. The panel polls `current` once a minute and
     * fetches a history per expanded section, a handful of calls a minute; the bound is for a runaway client, since each call is a
     * few `ops` aggregations.
     */
    const val TELEMETRY = "telemetry"
    /**
     * `GET /api/v1/users/directory` — the one read any signed-in user can use to list accounts (id, name, email), so it rides a
     * per-USER bucket (the [TELEMETRY] idiom): 120/min in every mode. A member picker types a handful of lookups a minute (debounced,
     * a page each). With the route's required `q` and 20-row pages the bucket slows, and the coalesced `rate_limit.exceeded` event
     * records, a bulk harvest by a signed-in user; it does not prevent one (the user's decision D2 accepts the disclosure).
     */
    const val DIRECTORY = "directory"
    /**
     * `POST /api/v1/rooms` and `PUT /api/v1/rooms/{id}` share ONE bucket per signed-in user: they are the two writes that put a name
     * into the room-name namespace (a room reserves its name for every other user), and a rename answers 409 on a clash exactly as a
     * create does, so a separate or absent bucket on the rename would be a free name-probing oracle for a room's existence. 30/min in
     * every mode (the [TELEMETRY] idiom). Creating or renaming a room is a deliberate, occasional act; the bound is for a runaway client
     * or a script squatting or probing names. A name clash is also audited (`room.name_conflict`).
     */
    const val ROOM_NAME = "room-name"
    /**
     * `POST /api/v1/rooms/{id}/members` - the add reveals which ids are inactive (the 400 names them) and lets a manager enrol any
     * active user id, so it rides a per-USER bucket: 60/min in every mode. A deliberate add is a handful a minute (a person, a team);
     * the bound slows an id-sweep through a room the caller owns.
     */
    const val ROOM_MEMBERS = "room-members"
    /**
     * `POST /api/v1/rooms/{id}/threads` and `POST /api/v1/rooms/{id}/threads/{number}/messages` share ONE bucket per signed-in user:
     * the two writes that put speech into a room. 120/min in every mode (the [DIRECTORY] idiom): a person types a few messages a
     * minute, so the bound is for a runaway client or a script flooding a room. Edits, deletes and metadata writes stay unbucketed
     * (guard-gated, one row each). The bucket sits BEFORE the guards, so a refused or invalid POST spends a token too.
     */
    const val CHAT_POST = "chat-post"
    /**
     * The thread and message writes that are not a POST share ONE bucket per signed-in user: the thread details PUT, the status PUT,
     * the owner PUT, the message edit (PUT), the message DELETE, the reaction PUT and the thread and message pin PUTs. Each takes the
     * room's write lock (`rooms/RoomLock.kt`) and a pooled connection for as long as it waits, so one member's script could otherwise
     * queue writers behind a room and hold the pool. 240/min in every mode (the [CHAT_POST] idiom, twice its figure): a person edits,
     * moves and deletes a few times a minute at the very most (4 a second is a script, not a hand), while the SPA's own bursts (a status
     * move and an owner hand-over together) stay far under.
     * The bucket sits BEFORE the guards, so a refused or invalid write spends a token too.
     */
    const val CHAT_WRITE = "chat-write"
    /**
     * `GET /api/v1/rooms/{id}/threads` and `GET /api/v1/rooms/{id}/tags` share ONE bucket per signed-in user: 300/min in every mode. These
     * are the reads that scan a room's rows (a `field` filter is a `jsonb` containment per row, the tag list groups the room's tag rows),
     * and they are the ones the live channel makes every member refetch. Sized against the SPA's own rate, not a guess: a live hint
     * refetches the room's thread list at most once per 3 s (`web/src/utils/liveKeys.ts`, `AGGREGATE_MIN_GAP_MS`), and the tag list
     * rides the same prefix while the filter panel is open, so a tab in a room that is busy without pause makes 20 + 20 = 40 requests a
     * minute; a person on three tabs, paging and filtering by hand besides (a few a second at the very most), stays under 200. 300 is
     * 5/s: above any real use with room to spare, so a normal member in a busy room is never refused, and a script is. The bucket sits
     * BEFORE the guards, so a refused or invalid read spends a token too. The per-request deadline (`threads.readTimeoutSeconds`) is
     * the other half.
     */
    const val CHAT_READ = "chat-read"
    /**
     * The writes that only move the caller's OWN view of what they have read share ONE bucket per signed-in user: the two notification
     * "seen" POSTs, the thread read marker and the room "seen" write (`threads/ThreadReadRoutes.kt`, `rooms/RoomMemberRoutes.kt`) and
     * the notification-preferences PUT. 240/min in every mode (the [CHAT_WRITE] figure): the SPA marks a
     * notice seen on a click and acknowledges what its reader scrolled to a few times a minute, so 4 a second is a script. They take
     * no room lock and emit no hint, so the bound is for a runaway client, not for the room. The bucket sits BEFORE the guards, so a
     * refused or unknown-id write spends a token too (an id sweep is throttled).
     */
    const val READ_STATE = "read-state"
    /**
     * `POST /api/v1/live/tickets` - the single-use ticket a tab exchanges for its live-update WebSocket - rides a per-USER bucket (the
     * [TELEMETRY] idiom): 60/min in every mode. A tab mints one per (re)connect, so a person needs a handful an hour; a flapping
     * reconnect loop or a script is what the bound stops, before it can fill the in-memory ticket store (`live/LiveTickets.kt`).
     */
    const val LIVE_TICKET = "live-ticket"
    /**
     * `GET /api/v1/rooms/{id}/search` (and, from the cross-room search, `GET /api/v1/search`) share ONE bucket per signed-in user:
     * 60/min in every mode. A search is the one read that can cost the database real work (a word that matches nothing is read out of
     * every message of the caller's rooms), so it is throttled per user BEFORE the guards: a person submits a search now and then (it is
     * submitted, not as-you-type), and the bound is for a script, or for probing foreign room ids through the 404. The request deadline
     * (`search.timeoutSeconds`) and the process-wide concurrency cap (`search.maxConcurrent`) are the other halves.
     */
    const val SEARCH = "search"
    /**
     * `PUT /api/v1/users/{id}/password` rides a per-USER bucket (the [TELEMETRY] idiom): 5/min in every mode. The self change verifies
     * `currentPassword` with a bcrypt per request, so without a bound a stolen session guesses the password at dispatcher speed and any
     * user can occupy the small default dispatcher; the per-account lockout (`auth/LoginThrottle.kt`, a wrong current password counts
     * like a failed login) is the real brute-force defence, the bucket is the per-request one. A person changes a password a few times
     * a YEAR, an ADMIN resetting others a few a minute at the very most. The bucket sits BEFORE the guards, so a refused or invalid
     * change spends a token too.
     */
    const val PASSWORD_CHANGE = "password-change"
    /**
     * `GET /api/v1/live` - the WebSocket upgrade - rides a per-IP bucket (default 600/min, `security.rateLimit.liveConnectPerMinute`, a
     * number pins it). The route is public at the HTTP layer (the socket authenticates in its first frame), so the client address is
     * the only key there is; a tab opens one socket per (re)connect, so even a NAT'd office stays far under it, while a script cannot
     * open sockets faster than the unauthenticated-socket cap and the 5 s auth deadline would let it hold them.
     */
    const val LIVE_CONNECT = "live-connect"

    const val DEFAULT_REFRESH_PER_MINUTE = 30
    const val DEFAULT_LIVE_CONNECT_PER_MINUTE = 600
    /**
     * 60/min per IP: the kubelet probes `/ready` every 5 s per container (12/min, `k8s/app-deployment.yaml`; the e2e global setup
     * polls every 2 s = 30/min), so real probers stay at a fifth to a half
     * of it, while one host can no longer hold the 20-connection pool busy with a flood of anonymous round trips.
     */
    const val DEFAULT_READY_PER_MINUTE = 60
    private const val MAX_LIMIT_PER_MINUTE = 1_000_000
    private const val TELEMETRY_PER_MINUTE = 30
    private const val DIRECTORY_PER_MINUTE = 120
    private const val ROOM_NAME_PER_MINUTE = 30
    private const val ROOM_MEMBERS_PER_MINUTE = 60
    private const val CHAT_POST_PER_MINUTE = 120
    private const val CHAT_WRITE_PER_MINUTE = 240
    private const val CHAT_READ_PER_MINUTE = 300
    private const val READ_STATE_PER_MINUTE = 240
    private const val LIVE_TICKET_PER_MINUTE = 60
    private const val SEARCH_PER_MINUTE = 60
    private const val PASSWORD_CHANGE_PER_MINUTE = 5
    private const val MFA_PER_MINUTE = 10
    private const val PASSWORD_RESET_CONFIRM_PER_MINUTE = 10
    private const val LOGIN_PER_MINUTE_PRODUCTION = 10
    private const val LOGIN_PER_MINUTE_DEVELOPMENT = 1000
    private const val RESET_PER_MINUTE_PRODUCTION = 5
    private const val RESET_PER_MINUTE_DEVELOPMENT = 100

    /** Blank follows the mode; a number pins the bucket in either mode (the `http.exposeOpenApi` idiom). */
    internal fun Application.configuredLimit(key: String, default: Int): Int =
        if (environment.config.propertyOrNull(key)?.getString().isNullOrBlank()) {
            default
        } else {
            // Boot-validated: a non-numeric or non-positive limit (0 blocks every request) refuses startup.
            requireConfigInt(environment.config, key, min = 1, max = MAX_LIMIT_PER_MINUTE)
        }

    internal fun Application.loginLimit() = configuredLimit(
        "security.rateLimit.loginPerMinute",
        if (developmentMode) LOGIN_PER_MINUTE_DEVELOPMENT else LOGIN_PER_MINUTE_PRODUCTION,
    )
    internal fun Application.refreshLimit() = configuredLimit("security.rateLimit.refreshPerMinute", DEFAULT_REFRESH_PER_MINUTE)
    internal fun Application.liveConnectLimit() =
        configuredLimit("security.rateLimit.liveConnectPerMinute", DEFAULT_LIVE_CONNECT_PER_MINUTE)
    internal fun Application.readyLimit() = configuredLimit("security.rateLimit.readyPerMinute", DEFAULT_READY_PER_MINUTE)
    internal fun Application.passwordResetLimit() = configuredLimit(
        "security.rateLimit.passwordResetPerMinute",
        if (developmentMode) RESET_PER_MINUTE_DEVELOPMENT else RESET_PER_MINUTE_PRODUCTION,
    )
    internal fun telemetryLimit() = TELEMETRY_PER_MINUTE
    internal fun directoryLimit() = DIRECTORY_PER_MINUTE
    internal fun roomNameLimit() = ROOM_NAME_PER_MINUTE
    internal fun roomMembersLimit() = ROOM_MEMBERS_PER_MINUTE
    internal fun chatPostLimit() = CHAT_POST_PER_MINUTE
    internal fun chatWriteLimit() = CHAT_WRITE_PER_MINUTE
    internal fun chatReadLimit() = CHAT_READ_PER_MINUTE
    internal fun readStateLimit() = READ_STATE_PER_MINUTE
    internal fun liveTicketLimit() = LIVE_TICKET_PER_MINUTE
    internal fun searchLimit() = SEARCH_PER_MINUTE
    internal fun passwordChangeLimit() = PASSWORD_CHANGE_PER_MINUTE
    internal fun mfaLimit() = MFA_PER_MINUTE
    internal fun passwordResetConfirmLimit() = PASSWORD_RESET_CONFIRM_PER_MINUTE
}

/**
 * The buckets (see application.yaml `security.rateLimit` and security.md): per IP, except the per-USER telemetry, directory,
 * room-name, room-members, chat-post, chat-write, chat-read, read-state, live-ticket, search and password-change buckets.
 * Login and password-reset follow the mode — production keeps them tight, development lifts them so one host
 * driving the e2e suite is not throttled (the per-account lockout and the per-email throttle are the
 * abuse defences in both modes); refresh, ready, MFA and the password-reset confirm are the same in every mode. Installed here, before
 * the feature modules, so any route may ride any bucket regardless of module order.
 */
fun Application.configureRateLimits() {
    val buckets = with(RateLimits) {
        mapOf(
            LOGIN to loginLimit(),
            REFRESH to refreshLimit(),
            PASSWORD_RESET to passwordResetLimit(),
            MFA to mfaLimit(),
            PASSWORD_RESET_CONFIRM to passwordResetConfirmLimit(),
            READY to readyLimit(),
            LIVE_CONNECT to liveConnectLimit(),
        )
    }
    val perUserBuckets = with(RateLimits) {
        mapOf(
            TELEMETRY to telemetryLimit(),
            DIRECTORY to directoryLimit(),
            ROOM_NAME to roomNameLimit(),
            ROOM_MEMBERS to roomMembersLimit(),
            CHAT_POST to chatPostLimit(),
            CHAT_WRITE to chatWriteLimit(),
            CHAT_READ to chatReadLimit(),
            READ_STATE to readStateLimit(),
            LIVE_TICKET to liveTicketLimit(),
            SEARCH to searchLimit(),
            PASSWORD_CHANGE to passwordChangeLimit(),
        )
    }
    install(RateLimit) {
        buckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call ->
                    // The plugin gives its 429 no handle on the bucket, but it computes this key right before
                    // rejecting, so the name stamped here is the rejecting bucket's (the `rate_limit.exceeded` audit).
                    call.attributes.put(RateLimitBucketKey, name)
                    call.clientAddressKey()
                }
            }
        }
        // The per-USER buckets (telemetry, directory, room-name, room-members, chat-post, chat-write, chat-read, read-state, live-ticket,
        // search, password-change).
        // The routes mount them INSIDE `authenticate {}` (telemetry/TelemetryRoutes.kt, users/UserRoutes.kt, rooms/RoomRoutes.kt,
        // threads/ThreadRoutes.kt, live/LiveRoutes.kt, threads/ThreadSearchRoutes.kt, notifications/NotificationRoutes.kt), so this key
        // runs only for a verified token:
        // an anonymous call is a 401 that never reaches it and never creates a limiter. The plugin keeps one limiter
        // per key and drops it after its refill period, so memory is bounded by the accounts active within a minute.
        perUserBuckets.forEach { (name, limit) ->
            register(RateLimitName(name)) {
                rateLimiter(limit = limit, refillPeriod = 60.seconds)
                requestKey { call ->
                    call.attributes.put(RateLimitBucketKey, name)
                    val userId = call.caller().userId
                    call.attributes.put(RateLimitUserKey, userId)
                    userId
                }
            }
        }
    }
}
