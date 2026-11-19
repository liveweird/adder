package ch.nokillswit.infra.notify

import io.ktor.util.AttributeKey

/**
 * What the live channel exposes to the telemetry sampler (`observability.md` "Telemetry"): [sockets] is the authenticated sockets of this
 * process, [listenerUp] whether its LISTEN session has reached LISTEN and is up right now, or null while the listener has not started yet
 * (it starts lazily on the first ticket mint, so "never started" is not an outage). A listener that has started but not yet connected is
 * `false`: a sample can catch that window for a moment after the first mint, and a listener that NEVER connects must show as down.
 */
data class LiveStats(val sockets: Int, val listenerUp: Boolean?)

/**
 * Published by `configureLive` (the `PoolStatsKey` shape): `telemetry/` reads it and imports nothing from `live/`, which nothing
 * imports (`PackageBoundaryTest`). Absent when the live module is not loaded.
 */
val LiveStatsKey = AttributeKey<() -> LiveStats>("LiveStats")
