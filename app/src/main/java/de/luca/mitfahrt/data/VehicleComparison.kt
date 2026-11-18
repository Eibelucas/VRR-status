package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant

enum class AssignmentStatus(val label: String) {
    CONFIRMED("Laut NEW-Fahrplanweg deine Fahrt"),
    CHOSEN("Von dir gewählt"),
    DETECTED("Von Mitfahrt zugeordnet"),
    SUGGESTED("Vorschlag, nicht bestätigt"),
    REJECTED("Von dir ausgeschlossen"),
    WRONG_DIRECTION("Andere Richtung"),
    SAME_LINE("Nur gleiche Linie, nicht zugeordnet")
}

/** One bus of the ride's line as offered for a deliberate choice. */
data class VehicleCompareRow(
    val vehicle: NewVehicle, val status: AssignmentStatus, val directionKnown: Boolean,
    val ageSeconds: Long, val distanceToBoarding: Double?, val heading: Double?
)

/**
 * Lines up the buses that share the ride's line number. A shared line number never counts as an
 * assignment: only NEW's schedule match, an own choice or the local detection change the status.
 */
object VehicleComparison {
    fun rows(ride: Ride, state: LiveBusState, now: Instant): List<VehicleCompareRow> {
        val boarding = ride.stops.getOrNull(ride.boardingIndex)?.point
        val line = NewVehicleParser.normalizeLine(ride.departure.line)
        return state.candidates.filter { it.isFresh(now) && NewVehicleParser.normalizeLine(it.line) == line }.distinctBy { it.id }.map { vehicle ->
            val status = when {
                state.confirmed && state.vehicle?.id == vehicle.id -> AssignmentStatus.CONFIRMED
                state.selectedVehicleId == vehicle.id -> AssignmentStatus.CHOSEN
                state.auto.holdsVehicle && state.auto.vehicleId == vehicle.id -> AssignmentStatus.DETECTED
                vehicle.id in state.auto.rejected -> AssignmentStatus.REJECTED
                state.auto.status == AutoMatchStatus.SUGGESTED && state.auto.vehicleId == vehicle.id -> AssignmentStatus.SUGGESTED
                vehicle.directionReliable && !NewVehicleMatcher.sameDirection(ride, vehicle) && vehicle.destination.isNotBlank() -> AssignmentStatus.WRONG_DIRECTION
                else -> AssignmentStatus.SAME_LINE
            }
            VehicleCompareRow(vehicle, status, vehicle.directionReliable && vehicle.destination.isNotBlank(),
                Duration.between(vehicle.observedAt, now).seconds.coerceAtLeast(0),
                boarding?.distanceTo(vehicle.point)?.takeIf { it.isFinite() },
                state.heading.takeIf { state.vehicle?.id == vehicle.id })
        }.sortedWith(compareBy<VehicleCompareRow> { it.status.ordinal }.thenBy { it.distanceToBoarding ?: Double.MAX_VALUE }.thenBy { it.vehicle.id })
    }

    /** True when the user must choose: no assignment and more than one plausible bus. */
    fun needsChoice(rows: List<VehicleCompareRow>): Boolean =
        rows.none { it.status in setOf(AssignmentStatus.CONFIRMED, AssignmentStatus.CHOSEN, AssignmentStatus.DETECTED) } &&
            rows.count { it.status == AssignmentStatus.SAME_LINE || it.status == AssignmentStatus.SUGGESTED } >= 2
}
