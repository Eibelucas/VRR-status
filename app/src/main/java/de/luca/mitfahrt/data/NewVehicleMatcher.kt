package de.luca.mitfahrt.data

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A line or destination identifies possible vehicles, never a particular departure. */
object NewVehicleMatcher {
    private val berlin = ZoneId.of("Europe/Berlin")
    private const val STOP_DISTANCE_METRES = 75.0

    fun candidates(ride: Ride, vehicles: List<NewVehicle>, now: Instant): List<NewVehicle> {
        val boarding = ride.stops.getOrNull(ride.boardingIndex)?.point
        return vehicles.asSequence().filter { matchesCandidate(ride, it, now) }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<NewVehicle> { sameDirection(ride, it) }
                .thenBy { boarding?.distanceTo(it.point) ?: Double.MAX_VALUE }.thenBy { it.id })
            .toList()
    }

    fun matchesCandidate(ride: Ride, vehicle: NewVehicle, now: Instant): Boolean {
        val boarding = ride.stops.getOrNull(ride.boardingIndex) ?: return false
        if (ride.demo || ride.departure.cancelled || boarding.cancelled || !vehicle.isFresh(now) ||
            !validPoint(vehicle.point)) return false
        val line = NewVehicleParser.normalizeLine(ride.departure.line)
        if (line.isBlank() || line == "0" || line != NewVehicleParser.normalizeLine(vehicle.line)) return false
        return !vehicle.directionReliable || terminalName(vehicle.destination).isEmpty() || sameDirection(ride, vehicle)
    }

    /** Unknown headsigns are not a direction confirmation, even when the line matches. */
    fun sameDirection(ride: Ride, vehicle: NewVehicle): Boolean {
        if (!vehicle.directionReliable) return false
        val actual = terminalName(vehicle.destination)
        if (actual.isEmpty()) return false
        return listOf(ride.departure.destination, ride.stops.lastOrNull()?.name.orEmpty())
            .map(::terminalName).any { it.isNotEmpty() && it == actual }
    }

    /** Only one current vehicle with an independently matching scheduled path is accepted. */
    fun confirmed(ride: Ride, vehicles: List<NewVehicle>, now: Instant): NewVehicle? {
        if (!fresh(ride.fetchedAt, now, 120)) return null
        // Conflicting observations for one vehicle are not resolved by picking an arbitrary row.
        val distinctVehicles = vehicles.groupBy { it.id }.mapNotNull { (_, observations) ->
            observations.distinct().singleOrNull()
        }
        return distinctVehicles.filter { vehicle ->
            vehicle.directionReliable && matchesCandidate(ride, vehicle, now) && scheduleMatches(ride, vehicle, now)
        }.singleOrNull()
    }

    private fun scheduleMatches(ride: Ride, vehicle: NewVehicle, now: Instant): Boolean {
        val routeAt = vehicle.routeObservedAt ?: return false
        if (!fresh(routeAt, now, 90)) return false
        val path = vehicle.routeStops
        if (path.size < 3 || ride.stops.size < 3) return false
        val startSeconds = vehicle.journeyStartSeconds?.takeIf { it in 0 until 86_400 } ?: return false
        val first = ride.stops.first()
        val firstTime = first.departure ?: first.arrival ?: return false
        // A recent position does not make a path from yesterday (or last month) current.
        val endTime = ride.stops.last().let { it.arrival ?: it.departure } ?: return false
        if (Duration.between(firstTime, endTime).seconds !in 0L..86_400L) return false
        val delay = vehicle.delaySeconds?.coerceIn(0, 86_400)?.toLong() ?: 0L
        if (endTime < firstTime || vehicle.observedAt < firstTime.minusSeconds(3_600) ||
            vehicle.observedAt > endTime.plusSeconds(delay + 900)) return false
        val firstLocal = firstTime.atZone(berlin)
        if (!unambiguous(firstTime) || firstLocal.toLocalTime().toSecondOfDay() != startSeconds) return false
        if (!matchesStop(first, path.first(), listOf(firstTime))) return false

        val boarding = ride.stops.getOrNull(ride.boardingIndex) ?: return false
        if (stopKey(boarding.id) != stopKey(ride.departure.stopId) || boarding.departure != ride.departure.planned) return false
        // Preserve order, permitting extra EFA stops or missing intermediate source rows.
        var previousPathIndex = -1
        val matchedStops = mutableSetOf<String>()
        var boardingMatched = false
        for ((index, stop) in ride.stops.withIndex()) {
            if (stop.cancelled) continue
            val times = if (index == ride.boardingIndex) listOf(ride.departure.planned)
                else listOfNotNull(stop.departure, stop.arrival).distinct()
            val found = path.indices.firstOrNull { pathIndex ->
                pathIndex > previousPathIndex && matchesStop(stop, path[pathIndex], times)
            } ?: continue
            previousPathIndex = found
            matchedStops += stopKey(stop.id)
            if (index == ride.boardingIndex) boardingMatched = true
        }
        return boardingMatched && matchedStops.size >= 3
    }

    private fun matchesStop(stop: RideStop, path: NewVehicleStop, times: List<Instant>): Boolean {
        val point = stop.point?.takeIf(::validPoint) ?: return false
        if (!validPoint(path.point) || point.distanceTo(path.point) > STOP_DISTANCE_METRES) return false
        val seconds = path.plannedSeconds?.takeIf { it in 0 until 86_400 } ?: return false
        val day = path.plannedDayOfMonth?.takeIf { it in 1..31 } ?: return false
        return times.any { time ->
            val local = time.atZone(berlin)
            unambiguous(time) && local.dayOfMonth == day && local.toLocalTime().toSecondOfDay() == seconds
        }
    }

    private fun unambiguous(time: Instant): Boolean =
        berlin.rules.getValidOffsets(time.atZone(berlin).toLocalDateTime()).size == 1

    private fun fresh(at: Instant, now: Instant, maximumSeconds: Long): Boolean {
        val age = Duration.between(at, now)
        return age >= Duration.ofSeconds(-15) && age <= Duration.ofSeconds(maximumSeconds)
    }

    private fun validPoint(point: GeoPoint): Boolean = point.lat.isFinite() && point.lon.isFinite() &&
        point.lat in -90.0..90.0 && point.lon in -180.0..180.0 && (point.lat != 0.0 || point.lon != 0.0)

    private fun terminalName(value: String): String {
        val text = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace("ß", "ss")
            .replace(Regex("^(monchengladbach|monchengl\\.?|mg)[,\\s]+"), "")
            .replace(Regex("\\bhauptbahnhof\\b"), "hbf")
            .replace(Regex("\\bbusbahnhof\\b|\\bbusbf\\b\\.?"), "busbf")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        // These two names designate the same MG terminal, not Rheydt or another city's Hbf.
        return if (text == "hbf europaplatz") "hbf" else text
    }
}
