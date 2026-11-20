package ch.nokillswit.notifications

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.plugins.scrubbedSummary
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.notifications.NotificationSweep")

private const val SHUTDOWN_JOIN_TIMEOUT_MS = 2_000L
private const val PURGE_EVERY_MILLIS = 3_600_000L

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
) {
    /** When the last purge ran, or null before the first; touched by the sweep's own coroutine alone. */
    private var lastPurgeAt: Long? = null

    /**
     * One tick: purges when the last purge is an hour old (or there was none). Answers how many notices went, or null when nothing was
     * due or the purge failed (logged, scrubbed). `internal` for the tests.
     */
    suspend fun tick(): Int? {
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
 * its own scope when `notifications.sweepEnabled` is true (the test harness turns it off, so no booted app purges the shared database)
 * and is cancelled on [ApplicationStopping], before the pool is disposed.
 */
fun Application.configureNotificationSweep() {
    val config = environment.config
    val retentionDays = requireConfigInt(config, "notifications.retentionDays", min = 1, max = 3650)
    val intervalSeconds = requireConfigInt(config, "notifications.sweepIntervalSeconds", min = 10, max = 3600)
    val raw = config.property("notifications.sweepEnabled").getString()
    val enabled = raw.toBooleanStrictOrNull() ?: error("Config \"notifications.sweepEnabled\" must be true or false (was \"$raw\")")
    if (!enabled) return
    monitor.subscribe(ApplicationStarted) { app ->
        val sweep = NotificationSweep(retentionDays * MILLIS_PER_DAY, purge = { app.attributes[NotificationServiceKey].purgeOlderThan(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CompletableDeferred<Int?>()
        app.attributes.put(NotificationSweepKey, first)
        scope.launch {
            first.complete(sweep.tick())
            while (isActive) {
                delay(intervalSeconds * 1000L)
                sweep.tick()
            }
        }
        app.monitor.subscribe(ApplicationStopping) {
            val rootJob = scope.coroutineContext[Job]
            scope.cancel()
            if (rootJob != null) runBlocking { withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT_MS) { rootJob.join() } }
        }
    }
}
