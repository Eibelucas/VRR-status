package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant

enum class FleetAge { CURRENT, OLDER, GONE }

/** Filters and ages for the fleet map. Only NEW positions; nothing is assigned to a departure here. */
object LiveFleet {
    /** NEW positions are refreshed every few seconds; older than this they are marked. */
    const val OLDER_SECONDS = 45L
    /** Same limit as [NewVehicle.isFresh]: beyond it a position is removed from the live view. */
    const val GONE_SECONDS = 90L

    fun age(vehicle: NewVehicle, now: Instant): FleetAge {
        val seconds = Duration.between(vehicle.observedAt, now).seconds
        return when {
            !vehicle.isFresh(now) || seconds > GONE_SECONDS -> FleetAge.GONE
            seconds > OLDER_SECONDS -> FleetAge.OLDER
            else -> FleetAge.CURRENT
        }
    }

    fun lines(vehicles: List<NewVehicle>): List<String> = vehicles.map { it.line }.distinct()
        .sortedWith(compareBy<String> { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })

    /** Directions of one line as shown on the buses; unreliable displays are grouped as unknown. */
    fun directions(vehicles: List<NewVehicle>, line: String): List<String> = vehicles.filter { it.line == line }
        .map { if (it.directionReliable && it.destination.isNotBlank()) shortName(it.destination) else UNKNOWN }
        .distinct().sortedWith(compareBy<String> { it == UNKNOWN }.thenBy { it })

    const val UNKNOWN = "Richtung unklar"

    fun filter(vehicles: List<NewVehicle>, now: Instant, line: String?, direction: String?, blocked: Set<String> = emptySet(),
               allowed: Set<String> = emptySet()): List<NewVehicle> = vehicles.filter { vehicle ->
        age(vehicle, now) != FleetAge.GONE && (line == null || vehicle.line == line) &&
            (direction == null || (if (direction == UNKNOWN) !vehicle.directionReliable || vehicle.destination.isBlank()
                else vehicle.directionReliable && shortName(vehicle.destination) == direction)) &&
            vehicle.line.lowercase() !in blocked && (allowed.isEmpty() || vehicle.line.lowercase() in allowed)
    }.sortedWith(compareBy<NewVehicle> { it.line.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.line }.thenBy { it.id })

    /** Keeps the last few positions per vehicle, enough for a movement direction. In memory only. */
    fun remember(history: Map<String, List<VehicleObservation>>, vehicles: List<NewVehicle>, now: Instant): Map<String, List<VehicleObservation>> {
        val updated = history.toMutableMap()
        vehicles.forEach { vehicle ->
            val track = updated[vehicle.id].orEmpty()
            if (track.none { it.at == vehicle.observedAt }) updated[vehicle.id] = (track + VehicleObservation(vehicle.point, vehicle.observedAt)).takeLast(6)
        }
        return updated.mapValues { (_, track) -> track.filter { Duration.between(it.at, now).seconds <= VehicleHeading.MAX_AGE_SECONDS } }
            .filterValues { it.isNotEmpty() }.entries.take(300).associate { it.key to it.value }
    }

    fun headings(history: Map<String, List<VehicleObservation>>, vehicles: List<NewVehicle>): Map<String, Double> = vehicles.mapNotNull { vehicle ->
        VehicleHeading.movement(history[vehicle.id].orEmpty(), VehicleObservation(vehicle.point, vehicle.observedAt))?.let { vehicle.id to it }
    }.toMap()
}
