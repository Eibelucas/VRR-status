package de.luca.mitfahrt.data

import java.time.Duration

/**
 * Direction of travel for a map marker. Taken from the verified line only when the vehicle sits
 * on it and either NEW confirmed this trip or the vehicle's own movement agrees; otherwise from
 * clear movement between two NEW positions. Without that the marker stays neutral (null).
 */
object VehicleHeading {
    /** Two NEW positions this far apart are clearly more than GPS noise (about 35 m each). */
    const val MIN_MOVE = 50.0
    const val MAX_AGE_SECONDS = 180L

    fun movement(history: List<VehicleObservation>, current: VehicleObservation): Double? {
        val earlier = history.filter { it.at.isBefore(current.at) && Duration.between(it.at, current.at).seconds <= MAX_AGE_SECONDS }
            .lastOrNull { it.point.distanceTo(current.point) >= MIN_MOVE } ?: return null
        return Geo.bearing(earlier.point, current.point)
    }

    fun of(snap: Projection?, confirmedTrip: Boolean, history: List<VehicleObservation>, current: VehicleObservation): Double? {
        val moved = movement(history, current)
        if (snap != null && (confirmedTrip || moved != null)) return snap.bearing
        return moved
    }

    /** Small arrows along a verified line section, pointing in the trip's direction. */
    fun arrows(line: RouteLine, from: Double, to: Double, spacing: Double = 400.0, max: Int = 12): List<Pair<GeoPoint, Double>> {
        if (!line.usable || to - from < spacing / 2) return emptyList()
        val step = maxOf(spacing, (to - from) / max)
        return generateSequence(from + step / 2) { it + step }.takeWhile { it < to }.take(max).map { offset ->
            line.pointAt(offset) to Geo.bearing(line.pointAt(offset - 15.0), line.pointAt(offset + 15.0))
        }.toList()
    }
}
