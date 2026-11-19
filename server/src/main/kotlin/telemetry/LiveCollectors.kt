package ch.nokillswit.telemetry

import ch.nokillswit.infra.notify.LiveStats
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * The live channel's per-process gauges (`observability.md` "Telemetry"): `live.sockets` always, `live.listener_up` once the listener has
 * started (it starts lazily on the first ticket mint, so before that there is nothing to report, not an outage). No reading when
 * the live module published nothing.
 */
internal fun liveReadings(stats: (() -> LiveStats)?): List<Reading> {
    val live = stats?.invoke() ?: return emptyList()
    return listOfNotNull(
        Reading(TelemetryMetrics.LIVE_SOCKETS, live.sockets.toDouble()),
        live.listenerUp?.let { Reading(TelemetryMetrics.LIVE_LISTENER_UP, if (it) 1.0 else 0.0) },
    )
}

/**
 * `live.notify_queue_usage`, the cluster section's reading: `pg_notification_queue_usage()` is the fraction (0..1) of the NOTIFY queue
 * in use, cluster-wide, so one process reads it per interval under the lock. A catalog function, no table is touched.
 */
internal suspend fun R2dbcTransaction.notifyQueueReadings(): List<Reading> =
    query("SELECT pg_notification_queue_usage()") { row ->
        row.get(0, Double::class.javaObjectType)?.let { Reading(TelemetryMetrics.LIVE_NOTIFY_QUEUE_USAGE, it) }
    }
