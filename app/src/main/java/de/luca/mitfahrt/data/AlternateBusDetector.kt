package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** A detected alternate bus that the user appears to have boarded instead of the tracked ride. */
data class AlternateBusSuggestion(
    val journeyKey: String,
    val vehicle: NewVehicle,
    val departure: Departure,
    val preservesExitTarget: Boolean,
    val exitTargetName: String?,
    val detectedAt: Instant
)

/** Local hysteresis state for alternate-bus detection (kept in memory only). */
data class AlternateBusTrackerState(
    val journeyKey: String? = null,
    val candidateVehicleId: String? = null,
    val confirmations: Int = 0,
    val lastFixAt: Instant? = null,
    val dismissedVehicleIds: Set<String> = emptySet()
)

data class AlternateBusMatch(
    val vehicle: NewVehicle,
    val matchedPairs: Int,
    val movedMeters: Double,
    val averageDistanceMeters: Double
)

data class AlternateBusStepResult(
    val state: AlternateBusTrackerState,
    val confirmed: AlternateBusMatch? = null
)

/**
 * Detects when a user tracking ride A is actually riding a different live NEW bus B.
 *
 * Requires multi-point GPS co-movement (vehicle speed, aligned travel direction, >= 2 time-separated
 * observations matching the phone's track, and >= 2 consecutive confirmations with fresh GPS fixes).
 * Wi-Fi or line numbers alone never trigger a switch.
 */
object AlternateBusDetector {
    const val MAX_ACCURACY = 50f
    const val WINDOW_SECONDS = 150L
    const val MIN_VEHICLE_SPEED = 4.5
    const val MIN_NET_MOVEMENT_METERS = 85.0
    const val MAX_PAIR_GAP_SECONDS = 25.0
    const val BASE_DISTANCE_TOLERANCE_METERS = 45.0
    const val MIN_PAIR_SPAN_SECONDS = 12L
    const val REQUIRED_CONFIRMATIONS = 2

    fun step(
        previous: AlternateBusTrackerState,
        ride: Ride,
        currentVehicle: NewVehicle?,
        currentTrack: List<VehicleObservation>,
        fleet: List<NewVehicle>,
        fleetTracks: Map<String, List<VehicleObservation>>,
        phoneFixes: List<PhoneFix>,
        now: Instant
    ): AlternateBusStepResult {
        val key = ride.departure.key
        val base = if (previous.journeyKey == key) previous else AlternateBusTrackerState(journeyKey = key)
        if (ride.demo) return AlternateBusStepResult(base)

        val fixes = phoneFixes
            .filter { Geo.valid(it.point) && it.accuracy <= MAX_ACCURACY && Duration.between(it.at, now).seconds in -5..WINDOW_SECONDS }
            .sortedBy { it.at }
        val latestFix = fixes.lastOrNull() ?: return AlternateBusStepResult(base)
        if (Duration.between(latestFix.at, now).seconds > 40) return AlternateBusStepResult(base)
        if (!isMovingAtVehicleSpeed(fixes)) {
            return AlternateBusStepResult(base.copy(candidateVehicleId = null, confirmations = 0))
        }

        // If the phone is co-moving with the currently tracked bus, do not suggest another bus.
        if (currentVehicle != null && currentVehicle.isFresh(now)) {
            val trackedObs = mergeObservations(currentTrack, currentVehicle, now)
            if (evaluateCoMovement(currentVehicle, trackedObs, fixes, now) != null) {
                return AlternateBusStepResult(base.copy(candidateVehicleId = null, confirmations = 0))
            }
        }

        val currentLine = NewVehicleParser.normalizeLine(ride.departure.line)
        val matches = fleet.mapNotNull { vehicle ->
            if (!vehicle.isFresh(now) || !Geo.valid(vehicle.point)) return@mapNotNull null
            if (vehicle.id == currentVehicle?.id) return@mapNotNull null
            if (vehicle.id in base.dismissedVehicleIds) return@mapNotNull null
            // Avoid suggesting the exact same line & direction while no current vehicle is assigned yet
            // (AutoMatchEngine already assigns that vehicle to the current ride).
            val sameLine = NewVehicleParser.normalizeLine(vehicle.line) == currentLine
            val sameDirection = sameLine && sameDestination(vehicle.destination, ride.departure.destination)
            if (sameDirection && currentVehicle == null) return@mapNotNull null

            val obs = mergeObservations(fleetTracks[vehicle.id].orEmpty(), vehicle, now)
            evaluateCoMovement(vehicle, obs, fixes, now)
        }.sortedBy { it.averageDistanceMeters }

        val best = matches.firstOrNull()
        if (best == null) {
            return AlternateBusStepResult(base.copy(candidateVehicleId = null, confirmations = 0))
        }
        // Ambiguous: a second different vehicle is nearly as close as the top match.
        val runnerUp = matches.getOrNull(1)
        if (runnerUp != null && runnerUp.averageDistanceMeters - best.averageDistanceMeters < 20.0) {
            return AlternateBusStepResult(base.copy(candidateVehicleId = null, confirmations = 0))
        }

        val isNewFix = base.lastFixAt == null || latestFix.at.isAfter(base.lastFixAt)
        val nextCount = when {
            base.candidateVehicleId != best.vehicle.id -> 1
            isNewFix -> base.confirmations + 1
            else -> base.confirmations
        }
        val nextState = base.copy(
            candidateVehicleId = best.vehicle.id,
            confirmations = nextCount,
            lastFixAt = if (isNewFix) latestFix.at else base.lastFixAt
        )
        return AlternateBusStepResult(
            state = nextState,
            confirmed = if (nextCount >= REQUIRED_CONFIRMATIONS) best else null
        )
    }

    /** Matches a confirmed co-moving vehicle against real VRR departures from the boarding/nearby stops. */
    fun matchDeparture(
        vehicle: NewVehicle,
        departures: List<Departure>,
        currentRideKey: String,
        now: Instant
    ): Departure? {
        val targetLine = NewVehicleParser.normalizeLine(vehicle.line)
        val candidates = departures.filter { dep ->
            !dep.cancelled &&
                dep.key != currentRideKey &&
                NewVehicleParser.normalizeLine(dep.line) == targetLine &&
                Duration.between(now, dep.effective).seconds in -900L..900L
        }
        if (candidates.isEmpty()) return null
        val directionMatched = candidates.filter { sameDestination(vehicle.destination, it.destination) }
        val pool = directionMatched.ifEmpty { candidates }
        return pool.minByOrNull { abs(Duration.between(now, it.effective).seconds) }
    }

    /** Preserves the user's exit target stop on the switched ride if the new bus also serves it. */
    fun applySwitchedRide(previous: Ride, nextRide: Ride, now: Instant): Ride {
        val boarded = nextRide.board("auto", now)
        val target = previous.exitTargetStop() ?: return boarded
        val targetKey = stopKey(target.id)
        val matchIndex = boarded.stops.indices.firstOrNull { index ->
            index > boarded.boardingIndex && !boarded.stops[index].cancelled && stopKey(boarded.stops[index].id) == targetKey
        }
        return if (matchIndex != null) boarded.withExitTarget(matchIndex) else boarded
    }

    private fun mergeObservations(
        track: List<VehicleObservation>,
        vehicle: NewVehicle,
        now: Instant
    ): List<VehicleObservation> {
        val fresh = track.filter { Geo.valid(it.point) && Duration.between(it.at, now).seconds in -15..WINDOW_SECONDS }
        val withLatest = if (fresh.any { it.at == vehicle.observedAt }) fresh
        else fresh + VehicleObservation(vehicle.point, vehicle.observedAt)
        return withLatest.sortedBy { it.at }
    }

    private fun isMovingAtVehicleSpeed(fixes: List<PhoneFix>): Boolean {
        if (fixes.size < 3) return false
        val spanSeconds = Duration.between(fixes.first().at, fixes.last().at).seconds
        if (spanSeconds < MIN_PAIR_SPAN_SECONDS) return false
        val netMeters = fixes.first().point.distanceTo(fixes.last().point)
        if (netMeters < MIN_NET_MOVEMENT_METERS) return false
        var peak = 0.0
        for (i in 1 until fixes.size) {
            val seconds = Duration.between(fixes[i - 1].at, fixes[i].at).toMillis() / 1000.0
            if (seconds < 4.0) continue
            val meters = fixes[i - 1].point.distanceTo(fixes[i].point)
            val noise = (fixes[i - 1].accuracy + fixes[i].accuracy) * 0.5
            val speed = (meters - noise).coerceAtLeast(0.0) / seconds
            if (speed <= 30.0) peak = maxOf(peak, speed)
        }
        return peak >= MIN_VEHICLE_SPEED
    }

    private data class PairSample(
        val observation: VehicleObservation,
        val fix: PhoneFix,
        val gapSeconds: Double,
        val distanceMeters: Double
    )

    private fun evaluateCoMovement(
        vehicle: NewVehicle,
        observations: List<VehicleObservation>,
        fixes: List<PhoneFix>,
        now: Instant
    ): AlternateBusMatch? {
        if (observations.size < 2) return null
        val paired = observations.mapNotNull { obs ->
            if (Duration.between(obs.at, now).seconds !in -15..WINDOW_SECONDS) return@mapNotNull null
            val fix = fixes.minByOrNull { abs(Duration.between(it.at, obs.at).toMillis()) } ?: return@mapNotNull null
            val gap = abs(Duration.between(fix.at, obs.at).toMillis()) / 1000.0
            if (gap > MAX_PAIR_GAP_SECONDS) return@mapNotNull null
            PairSample(obs, fix, gap, obs.point.distanceTo(fix.point))
        }
        if (paired.size < 2) return null
        val span = Duration.between(paired.first().observation.at, paired.last().observation.at).seconds
        if (span < MIN_PAIR_SPAN_SECONDS) return null

        val near = paired.filter { sample ->
            sample.distanceMeters <= sample.fix.accuracy + BASE_DISTANCE_TOLERANCE_METERS + 10.0 * sample.gapSeconds
        }
        if (near.size < 2 || near.size * 3 < paired.size * 2) return null

        val first = near.first()
        val last = near.last()
        val busMove = first.observation.point.distanceTo(last.observation.point)
        val phoneMove = first.fix.point.distanceTo(last.fix.point)
        if (busMove < MIN_NET_MOVEMENT_METERS || phoneMove < MIN_NET_MOVEMENT_METERS) return null

        // Verify travel vectors point in the same direction (reject oncoming buses).
        val cosLat = cos(Math.toRadians(first.fix.point.lat))
        val bx = (last.observation.point.lon - first.observation.point.lon) * cosLat
        val by = last.observation.point.lat - first.observation.point.lat
        val px = (last.fix.point.lon - first.fix.point.lon) * cosLat
        val py = last.fix.point.lat - first.fix.point.lat
        val bLen = hypot(bx, by)
        val pLen = hypot(px, py)
        if (bLen <= 1e-7 || pLen <= 1e-7) return null
        val dot = (bx * px + by * py) / (bLen * pLen)
        if (dot < 0.5) return null

        val avgDist = near.map { it.distanceMeters }.average()
        return AlternateBusMatch(vehicle, near.size, minOf(busMove, phoneMove), avgDist)
    }

    private fun sameDestination(a: String, b: String): Boolean {
        val na = normalizeStopText(a)
        val nb = normalizeStopText(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    private fun normalizeStopText(raw: String): String =
        shortName(raw).lowercase().replace(Regex("[^a-z0-9äöüß]+"), " ").trim()
}
