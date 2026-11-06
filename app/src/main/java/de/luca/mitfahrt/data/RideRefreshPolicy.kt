package de.luca.mitfahrt.data

import java.time.Instant

/** A departed boarding monitor can disappear while independently timed trip stops stay fresh. */
object RideRefreshPolicy {
    fun usesMonitorBaseline(ride: Ride): Boolean {
        if (ride.departure.estimated == null) return false
        if (ride.boarded) return ride.displayTarget().estimated == null && ride.offsetSeconds != 0L
        val boarding = ride.stops.getOrNull(ride.boardingIndex)
        return boarding?.estimatedArrival == null && boarding?.estimatedDeparture == null
    }

    fun needsRefresh(ride: Ride, elapsedSinceRefresh: Long, lastCompletionCheck: Instant?, now: Instant): Boolean =
        !ride.demo && (elapsedSinceRefresh >= 30_000 || ride.complete(now) &&
            ride.terminal.effective(ride.offsetSeconds) != lastCompletionCheck)

    fun canFinish(state: TrackingState, lastCompletionCheck: Instant?, now: Instant): Boolean {
        val ride = state.ride ?: return false
        if (!state.running) return false
        if (!ride.complete(now) || ride.departure.cancelled) return false
        if (ride.demo) return true
        if (ride.terminal.estimated == null && ride.offsetSeconds != 0L &&
            ride.departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } != true) return false
        return !state.lastRefreshFailed && !ride.isStale(now) &&
            ride.terminal.effective(ride.offsetSeconds) == lastCompletionCheck
    }
}
