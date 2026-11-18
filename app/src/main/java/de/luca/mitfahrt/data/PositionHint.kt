package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant

/**
 * One short line about where the assigned bus is, for the ongoing notification. Only a fresh NEW
 * position of the bus belonging to this ride; never an inferred or old position.
 */
object PositionHint {
    fun text(state: TrackingState, now: Instant): String? {
        val ride = state.ride?.takeUnless { it.demo || it.departure.cancelled } ?: return null
        val live = state.liveBus
        val bus = live.vehicle?.takeIf { live.journeyKey == ride.departure.key && it.isFresh(now) &&
            (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle) } ?: return null
        val age = Duration.between(bus.observedAt, now).seconds.coerceAtLeast(0)
        val ageText = if (age < 60) "vor $age s" else "vor ${age / 60} Min"
        if (ride.boarded) return "Bus laut NEW $ageText"
        val boarding = ride.stops.getOrNull(ride.boardingIndex)?.point ?: return "Bus laut NEW $ageText"
        val metres = boarding.distanceTo(bus.point)
        val distance = when {
            metres < 80 -> "am Einstieg"
            metres < 1000 -> "${(metres / 50).toInt() * 50} m entfernt"
            else -> "%.1f km entfernt".format(java.util.Locale.GERMANY, metres / 1000)
        }
        return "Bus $distance · $ageText"
    }
}
