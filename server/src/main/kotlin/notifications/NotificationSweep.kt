package ch.nokillswit.notifications

import ch.nokillswit.infra.backgroundScope
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.mail.mailAppUrl
import ch.nokillswit.infra.mail.mailer
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.plugins.scrubbedSummary
import ch.nokillswit.rooms.RoomServiceKey
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationSweep")

private const val PURGE_EVERY_MILLIS = 3_600_000L

/** The ceiling of both digest timings: one day (the digest mails nothing older than that anyway). */
private const val MAX_EMAIL_SECONDS = 86_400L

/** The ceiling of `notifications.email.maxPerRun`. */
private const val MAX_EMAIL_PER_RUN = 1000

/**
 * Published once the sweep has started and has run its first pass: how many notices that pass purged, or null when it failed. A TEST seam
 * (`NotificationSweepTest` awaits it); nothing in the application reads it. Absent while `notifications.sweepEnabled` is false.
 */
internal val NotificationSweepKey = AttributeKey<Deferred<Int?>>("NotificationSweep")

/**
 * The retention of notices (`chat-model.md` "Notifications (V18)"): a notice older than [retentionMillis] is deleted, at most once an
 * hour ([PURGE_EVERY_MILLIS], whatever the tick interval), in the sweep's OWN scope and transaction, never inside a chat write (those
 * run under the room lock). The first tick purges. It logs a count and, on failure, CLASS NAMES and the SQLSTATE only
 * ([scrubbedSummary]: a database message can echo a bound value); the rows wait for the next hour. Cancellation is never swallowed.
 */
internal class NotificationSweep(
    private val retentionMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Deletes the notices created before the cutoff (epoch millis) and answers how many went: [NotificationService.purgeOlderThan]. */
    private val purge: suspend (cutoffMillis: Long) -> Int,
    /** The email digest's run ([NotificationDigest.run]), every tick; a no-op where nothing mails (a test, mail disabled). */
    private val digest: suspend () -> Unit = {},
) {
    /** When the last purge ran, or null before the first; touched by the sweep's own coroutine alone. */
    private var lastPurgeAt: Long? = null

    /**
     * One tick: runs the email digest (every tick; a failure is logged, scrubbed, and never stops the purge), then purges when the last
     * purge is an hour old (or there was none). Answers how many notices went, or null when nothing was due or the purge failed (logged,
     * scrubbed). `internal` for the tests.
     */
    suspend fun tick(): Int? {
        catchingFailures(block = { digest() }, onFailure = { e -> log.warn("notification email digest failed: {}", e.scrubbedSummary()) })
        val now = clock()
        val last = lastPurgeAt
        if (last != null && now - last < PURGE_EVERY_MILLIS) return null
        lastPurgeAt = now
        return catchingFailures(
            block = {
                purge(now - retentionMillis).also {
                    if (it > 0) log.info("notification retention: {} notice(s) deleted", it)
                }
            },
            onFailure = { e ->
                log.warn("notification retention failed, the rows stay until the next pass: {}", e.scrubbedSummary())
                null
            },
        )
    }
}

/**
 * The sweep's module: `notifications.*` is validated on every boot, enabled or not; the sweep itself starts on [ApplicationStarted] in
 * its own scope when `notifications.sweepEnabled` is true (the test harness turns it off, so no booted app purges the shared database
 * or mails a notice) and is cancelled and joined on `ApplicationStopping`, before the pool is disposed. Each tick also runs the email
 * digest ([NotificationDigest]); with `mail.transport=disabled` there is no mailer, the digest does nothing and logs it once, and
 * startup is never refused for it. `mail.appUrl` (the origin the password reset uses) builds the digest's links.
 */
fun Application.configureNotificationSweep() {
    val config = environment.config
    val retentionDays = requireConfigInt(config, "notifications.retentionDays", min = 1, max = 3650)
    val intervalSeconds = requireConfigInt(config, "notifications.sweepIntervalSeconds", min = 10, max = 3600)
    val delaySeconds = requireConfigLong(config, "notifications.email.delaySeconds", min = 0, max = MAX_EMAIL_SECONDS)
    val minIntervalSeconds = requireConfigLong(config, "notifications.email.minIntervalSeconds", min = 0, max = MAX_EMAIL_SECONDS)
    // The most mails one run sends: the digest shares the Mailer with MFA codes and password resets, and this budget is what protects them.
    val maxPerRun = requireConfigInt(config, "notifications.email.maxPerRun", min = 1, max = MAX_EMAIL_PER_RUN)
    // A notice is mailed only inside [delay, a day): a delay or a spacing that, with one tick, reaches a day makes every notice either
    // too young or too old, and the digest would silently mail nothing.
    check(maxOf(delaySeconds, minIntervalSeconds) + intervalSeconds < MAX_EMAIL_SECONDS) {
        "Config \"notifications.email.delaySeconds\" and \"notifications.email.minIntervalSeconds\" (the larger one) plus " +
            "\"notifications.sweepIntervalSeconds\" must be under 86400 seconds: nothing older than a day is mailed"
    }
    val raw = config.property("notifications.sweepEnabled").getString()
    val enabled = raw.toBooleanStrictOrNull() ?: error("Config \"notifications.sweepEnabled\" must be true or false (was \"$raw\")")
    if (!enabled) return
    monitor.subscribe(ApplicationStarted) { app ->
        val digest = NotificationDigest(
            database = app.attributes[R2dbcDatabaseKey],
            memberRooms = app.attributes[RoomServiceKey]::memberRooms,
            mailer = app.mailer(),
            appUrl = app.mailAppUrl(),
            delayMillis = delaySeconds * 1000,
            minIntervalMillis = minIntervalSeconds * 1000,
            maxPerRun = maxPerRun,
        )
        val sweep = NotificationSweep(
            retentionDays * MILLIS_PER_DAY,
            purge = { app.attributes[NotificationServiceKey].purgeOlderThan(it) },
            digest = { digest.run() },
        )
        val scope = app.backgroundScope("adder-notification-sweep")
        val first = CompletableDeferred<Int?>()
        app.attributes.put(NotificationSweepKey, first)
        scope.launch {
            first.complete(sweep.tick())
            while (isActive) {
                delay(intervalSeconds * 1000L)
                sweep.tick()
            }
        }
    }
}
