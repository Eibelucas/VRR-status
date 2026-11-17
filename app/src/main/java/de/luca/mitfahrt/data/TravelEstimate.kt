package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant

/**
 * Remaining time calculated by the app from a measured position: the timetable's running time
 * from that position to the stop. It is an estimate of the app, never an operator forecast.
 */
data class TravelEstimate(
    /** Seconds until the boarding stop (waiting) or the exit stop (on board). */
    val seconds: Long,
    /** Seconds until the next stop on board, if one lies before the exit. */
    val nextStopSeconds: Long?,
    /** "bus": position of the chosen or assigned vehicle. "gps": the phone's own position. */
    val basis: String,
    val measuredAt: Instant
) {
    val minutes: Int get() = ((seconds + 59) / 60).toInt()
    val nextStopMinutes: Int? get() = nextStopSeconds?.let { ((it + 59) / 60).toInt() }
    val label: String get() = if (basis == "bus") "berechnet aus der Busposition" else "berechnet per GPS"
}

object TravelEstimator {
    private const val MAX_SECONDS = 3 * 3600L

    /**
     * Uses the vehicle the user picked (or that was assigned) while its position is fresh, else
     * the phone's confirmed GPS progress on board. Returns null without a usable position or
     * without timetable times around it; nothing is guessed.
     */
    fun estimate(state: TrackingState, now: Instant, gpsEnabled: Boolean = true): TravelEstimate? {
        val ride = state.ride?.takeUnless { it.demo || it.departure.cancelled } ?: return null
        val key = ride.departure.key
        val reference = RouteReference.of(ride, state.routeShape?.takeIf { it.journeyKey == key }) ?: return null
        val live = state.liveBus
        val vehicle = live.vehicle?.takeIf { live.journeyKey == key && it.isFresh(now) &&
            (live.selectedVehicleId == it.id || live.confirmed || live.auto.holdsVehicle) }
        val vehicleOffset = vehicle?.let { bus ->
            live.routeOffset ?: reference.line.project(bus.point)?.takeIf { it.distance <= reference.lateralTolerance(35.0) }?.offset
        }
        val gps = state.gpsProgress?.takeIf { gpsEnabled && ride.boarded && it.journeyKey == key && GpsStopProgress.confident(it, now) }
        val (offset, basis, at) = when {
            vehicle != null && vehicleOffset != null -> Triple(vehicleOffset, "bus", vehicle.observedAt)
            gps?.offset != null && gps.confirmedAt != null -> Triple(gps.offset, "gps", gps.confirmedAt)
            else -> return null
        }
        val goalIndex = if (ride.boarded) ride.exitTargetIndex() ?: ride.stops.lastIndex else ride.boardingIndex
        val goalOffset = reference.offsetOf(goalIndex) ?: return null
        val here = reference.plannedTimeAt(ride, offset.coerceAtMost(goalOffset)) ?: return null
        val elapsed = Duration.between(at, now).seconds.coerceAtLeast(0)
        fun until(index: Int): Long? {
            val stop = ride.stops.getOrNull(index)?.takeUnless { it.cancelled } ?: return null
            val planned = stop.arrival ?: stop.departure ?: return null
            val stopOffset = reference.offsetOf(index) ?: return null
            if (stopOffset < offset - 30.0 && index != goalIndex) return null
            return (Duration.between(here, planned).seconds - elapsed).coerceAtLeast(0).takeIf { it <= MAX_SECONDS }
        }
        // A waiting passenger's bus that is already past the stop gives no time to the stop.
        if (!ride.boarded && offset > goalOffset + 60.0) return null
        val total = until(goalIndex) ?: return null
        val next = if (!ride.boarded) null else (ride.boardingIndex + 1 until goalIndex)
            .firstOrNull { index -> reference.offsetOf(index)?.let { it > offset + 20.0 } == true && !ride.stops[index].cancelled }
            ?.let(::until)
        return TravelEstimate(total, next, basis, at)
    }
}
