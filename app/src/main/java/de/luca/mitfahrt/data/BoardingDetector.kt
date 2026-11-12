package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

enum class BoardingPhase { WAITING, POSSIBLE_BOARDING, ON_BOARD, POSSIBLE_EXIT, ENDED }

/**
 * Decision state between evaluations. It holds vehicle observations of the assigned bus (public
 * NEW data) and counters, but no phone positions.
 */
data class BoardingState(
    val journeyKey: String? = null,
    val phase: BoardingPhase = BoardingPhase.WAITING,
    val since: Instant? = null,
    /** Evaluations in a row that supported the pending change. */
    val support: Int = 0,
    val supportSince: Instant? = null,
    val lastSupportAt: Instant? = null,
    val lastEvaluatedAt: Instant? = null,
    /** After a manual correction the automatic change in that direction pauses until then. */
    val boardingHoldUntil: Instant? = null,
    val exitHoldUntil: Instant? = null,
    val vehicleId: String? = null,
    val vehicleTrack: List<VehicleObservation> = emptyList(),
    /** Which measurements carried the last change: "bus" (with the NEW position) or "gps". */
    val basis: String? = null,
    /** True if the detected exit lies at the chosen exit stop; only then tracking ends by itself. */
    val exitAtTarget: Boolean = false
)

data class BoardingInput(
    val ride: Ride,
    val reference: RouteReference?,
    /** Recent phone positions, oldest first. Local memory only. */
    val phone: List<PhoneFix>,
    /** Vehicle currently assigned to this ride, if any, with a fresh position. */
    val vehicle: NewVehicle?,
    val wifi: WifiSignal,
    val now: Instant
)

/**
 * Waiting → possible boarding → on board → possible exit → ended.
 *
 * A change needs the same finding in several evaluations with new measurements. Proximity,
 * speed, one position, the timetable or the bus Wi-Fi alone never change the state. Without
 * measurements (location off, tunnel) nothing changes. Thresholds come from typical values in
 * Mönchengladbach (stops 250 to 600 m apart, walking up to about 2 m/s) and are not calibrated
 * on recorded rides.
 */
object BoardingDetector {
    const val MAX_ACCURACY = 50f
    const val WINDOW_SECONDS = 150L
    /** Faster than this between two good fixes is not walking. */
    const val VEHICLE_SPEED = 4.5
    const val WALKING_SPEED = 2.5
    const val MIN_EVALUATION_GAP = 8L
    const val BOARD_CONFIRMATIONS = 2
    const val EXIT_CONFIRMATIONS = 3
    const val MIN_EXIT_SECONDS = 30L
    /** A pending change without new support falls back after this time. */
    const val SUPPORT_TIMEOUT = 90L
    const val HOLD_SECONDS = 300L
    private const val VEHICLE_GPS = 35.0
    private const val PAIR_SECONDS = 20L

    fun initial(ride: Ride, now: Instant): BoardingState = BoardingState(ride.departure.key,
        if (ride.boarded) BoardingPhase.ON_BOARD else BoardingPhase.WAITING, ride.boardedAt ?: now)

    /** Manual "Bin im Bus" / "Doch nicht im Bus" / "Noch im Bus". The detector follows and pauses. */
    fun corrected(state: BoardingState, ride: Ride, now: Instant): BoardingState = when {
        ride.boarded && state.phase in setOf(BoardingPhase.POSSIBLE_EXIT, BoardingPhase.ENDED) ->
            state.copy(phase = BoardingPhase.ON_BOARD, since = now, support = 0, supportSince = null, exitHoldUntil = now.plusSeconds(HOLD_SECONDS))
        ride.boarded -> state.copy(phase = BoardingPhase.ON_BOARD, since = now, support = 0, supportSince = null)
        else -> state.copy(phase = BoardingPhase.WAITING, since = now, support = 0, supportSince = null,
            boardingHoldUntil = now.plusSeconds(HOLD_SECONDS), basis = null)
    }

    fun step(previous: BoardingState?, input: BoardingInput): BoardingState {
        val ride = input.ride
        val now = input.now
        var state = previous?.takeIf { it.journeyKey == ride.departure.key } ?: initial(ride, now)
        // The ride is the source of truth for manual changes made elsewhere (notification, watch).
        if (ride.boarded && state.phase in setOf(BoardingPhase.WAITING, BoardingPhase.POSSIBLE_BOARDING))
            state = state.copy(phase = BoardingPhase.ON_BOARD, since = ride.boardedAt ?: now, support = 0, supportSince = null)
        if (!ride.boarded && state.phase !in setOf(BoardingPhase.WAITING, BoardingPhase.POSSIBLE_BOARDING))
            state = state.copy(phase = BoardingPhase.WAITING, since = now, support = 0, supportSince = null)
        state = withVehicle(state, input.vehicle, now)
        if (ride.demo || ride.departure.cancelled || state.phase == BoardingPhase.ENDED) return state
        if (state.lastEvaluatedAt?.let { Duration.between(it, now).seconds < MIN_EVALUATION_GAP } == true) return state
        state = state.copy(lastEvaluatedAt = now)

        val fixes = input.phone.filter { it.accuracy <= MAX_ACCURACY && Duration.between(it.at, now).seconds in -5..WINDOW_SECONDS }
            .sortedBy { it.at }
        val fresh = fixes.lastOrNull()?.let { Duration.between(it.at, now).seconds <= 40 } == true
        return when (state.phase) {
            BoardingPhase.WAITING, BoardingPhase.POSSIBLE_BOARDING -> {
                if (state.boardingHoldUntil?.isAfter(now) == true) return state
                val basis = if (fresh) boardingEvidence(input, state, fixes) else null
                advance(state, basis != null, now, BoardingPhase.WAITING, BoardingPhase.POSSIBLE_BOARDING, BoardingPhase.ON_BOARD,
                    BOARD_CONFIRMATIONS, 10L, fixes.lastOrNull()?.at).let { if (basis != null) it.copy(basis = basis) else it }
            }
            else -> {
                if (state.exitHoldUntil?.isAfter(now) == true) return state
                val evidence = if (fresh) exitEvidence(input, state, fixes) else null
                advance(state, evidence != null, now, BoardingPhase.ON_BOARD, BoardingPhase.POSSIBLE_EXIT, BoardingPhase.ENDED,
                    EXIT_CONFIRMATIONS, MIN_EXIT_SECONDS, fixes.lastOrNull()?.at)
                    .let { if (evidence != null) it.copy(exitAtTarget = evidence) else it }
            }
        }
    }

    /** Shared hysteresis: support must repeat with new fixes; missing support only times out slowly. */
    private fun advance(state: BoardingState, supported: Boolean, now: Instant, rest: BoardingPhase, pending: BoardingPhase,
                        done: BoardingPhase, confirmations: Int, minSeconds: Long, lastFix: Instant?): BoardingState {
        if (!supported) {
            val stale = state.lastSupportAt?.let { Duration.between(it, now).seconds > SUPPORT_TIMEOUT } != false
            return if (state.phase == pending && stale) state.copy(phase = rest, since = now, support = 0, supportSince = null) else state
        }
        // The same fix evaluated twice is one measurement.
        val newMeasurement = lastFix != null && (state.lastSupportAt == null || lastFix.isAfter(state.lastSupportAt.minusSeconds(1)))
        val count = if (newMeasurement) state.support + 1 else state.support
        val since = state.supportSince ?: now
        val confirmed = count >= confirmations && Duration.between(since, now).seconds >= minSeconds
        return state.copy(phase = if (confirmed) done else pending, support = if (confirmed) 0 else count,
            supportSince = if (confirmed) null else since, lastSupportAt = now,
            since = if (confirmed || state.phase != pending) now else state.since)
    }

    private fun withVehicle(state: BoardingState, vehicle: NewVehicle?, now: Instant): BoardingState {
        val kept = state.vehicleTrack.filter { Duration.between(it.at, now).seconds <= 300 }
        if (vehicle == null || !Geo.valid(vehicle.point)) return state.copy(vehicleTrack = kept)
        val base = if (state.vehicleId == vehicle.id) kept else emptyList()
        val track = if (base.any { it.at == vehicle.observedAt }) base
            else (base + VehicleObservation(vehicle.point, vehicle.observedAt)).sortedBy { it.at }.takeLast(10)
        return state.copy(vehicleId = vehicle.id, vehicleTrack = track)
    }

    private data class Movement(val distance: Double, val seconds: Long, val peak: Double) {
        val speed: Double get() = distance / seconds.coerceAtLeast(1)
    }

    /** Net movement and the fastest plausible segment; single jumps above 30 m/s are ignored. */
    private fun movement(fixes: List<PhoneFix>): Movement? {
        if (fixes.size < 3) return null
        var peak = 0.0
        for (index in 1 until fixes.size) {
            val seconds = Duration.between(fixes[index - 1].at, fixes[index].at).toMillis() / 1000.0
            if (seconds < 4.0) continue
            val metres = fixes[index - 1].point.distanceTo(fixes[index].point)
            val noise = (fixes[index - 1].accuracy + fixes[index].accuracy) * 0.5
            val speed = (metres - noise).coerceAtLeast(0.0) / seconds
            if (speed <= 30.0) peak = maxOf(peak, speed)
        }
        return Movement(fixes.first().point.distanceTo(fixes.last().point),
            Duration.between(fixes.first().at, fixes.last().at).seconds, peak)
    }

    private data class Pairing(val observation: VehicleObservation, val fix: PhoneFix, val gap: Double)

    private fun pairs(track: List<VehicleObservation>, fixes: List<PhoneFix>, now: Instant): List<Pairing> =
        track.filter { Duration.between(it.at, now).seconds in -15..WINDOW_SECONDS }.mapNotNull { observation ->
            val fix = fixes.minByOrNull { abs(Duration.between(it.at, observation.at).toMillis()) } ?: return@mapNotNull null
            val gap = abs(Duration.between(fix.at, observation.at).toMillis()) / 1000.0
            if (gap > PAIR_SECONDS) null else Pairing(observation, fix, gap)
        }

    /**
     * "bus": phone and assigned bus were together at two or more separate NEW measurements while
     * both covered a similar distance at more than walking pace. "gps": no usable bus position,
     * but the phone drove forward along the line past the boarding stop. Returns null otherwise.
     */
    private fun boardingEvidence(input: BoardingInput, state: BoardingState, fixes: List<PhoneFix>): String? {
        val ride = input.ride
        val reference = input.reference ?: return null
        val boardingOffset = reference.offsetOf(ride.boardingIndex) ?: return null
        val recent = fixes.filter { Duration.between(it.at, input.now).seconds <= 90 }
        val move = movement(recent) ?: return null
        if (move.peak < VEHICLE_SPEED) return null // walking along the road, traffic jam or waiting
        val first = reference.line.project(recent.first().point) ?: return null
        val last = reference.line.project(recent.last().point) ?: return null
        val tolerance = reference.lateralTolerance(recent.last().accuracy.toDouble())
        if (first.distance > tolerance + 40.0 || last.distance > tolerance) return null
        val progress = last.offset - first.offset
        if (progress < 80.0) return null // opposite direction or no movement along this line
        if (last.offset < boardingOffset + 60.0) return null // not yet clearly past the boarding stop
        if (first.offset < boardingOffset - 400.0) return null // came from far before the stop: passing through, not boarding here

        val vehicleFresh = input.vehicle?.isFresh(input.now) == true
        if (vehicleFresh) {
            val paired = pairs(state.vehicleTrack, fixes, input.now)
            if (paired.size < 2) return null
            val near = paired.filter { it.observation.point.distanceTo(it.fix.point) <= it.fix.accuracy + VEHICLE_GPS + 12.0 * it.gap }
            // The right bus is somewhere else: this is another vehicle (wrong bus, car, bike).
            if (near.size < 2 || near.size * 3 < paired.size * 2) return null
            val apart = Duration.between(near.first().observation.at, near.last().observation.at).seconds
            val busDistance = near.first().observation.point.distanceTo(near.last().observation.point)
            val phoneDistance = near.first().fix.point.distanceTo(near.last().fix.point)
            if (apart < 15 || busDistance < 80.0 || phoneDistance < busDistance * 0.5 || phoneDistance > busDistance * 1.8) return null
            return "bus"
        }
        // Without a bus position the bar is higher: longer way, past the stop, plausible time.
        if (progress < 250.0 || recent.size < 4 || move.seconds < 25) return null
        if (last.offset < boardingOffset + 200.0) return null
        val expected = ride.liveBoardingTime(input.now)?.at ?: ride.boardingTime().at
        if (input.now.isBefore(expected.minusSeconds(120)) || input.now.isAfter(expected.plusSeconds(2_700))) return null
        return "gps"
    }

    /**
     * Returns whether the exit is at the chosen exit stop, or null without evidence. A standing
     * bus gives none: the phone must walk away from the bus or from the line.
     */
    private fun exitEvidence(input: BoardingInput, state: BoardingState, fixes: List<PhoneFix>): Boolean? {
        val ride = input.ride
        val reference = input.reference ?: return null
        val recent = fixes.filter { Duration.between(it.at, input.now).seconds <= 75 }
        val move = movement(recent) ?: return null
        if (move.seconds < 20 || move.peak >= VEHICLE_SPEED || move.speed > WALKING_SPEED) return null
        val last = recent.last()
        val target = ride.displayTarget().point
        val atTarget = target != null && target.distanceTo(last.point) <= 350.0

        // 1. The assigned bus drives on without the phone.
        if (input.vehicle?.isFresh(input.now) == true) {
            val paired = pairs(state.vehicleTrack, recent, input.now)
            val away = paired.filter { it.observation.point.distanceTo(it.fix.point) > it.fix.accuracy + VEHICLE_GPS + 12.0 * it.gap + 120.0 }
            if (away.size >= 2 && paired.takeLast(2).all { it in away } &&
                away.last().observation.point.distanceTo(away.last().fix.point) >
                away.first().observation.point.distanceTo(away.first().fix.point)) return atTarget
        }
        // 2. The phone walks away from the line.
        val tolerance = reference.lateralTolerance(last.accuracy.toDouble()) + 40.0
        val off = recent.takeLast(3).all { fix -> reference.line.project(fix.point)?.let { it.distance > tolerance } != false }
        if (off && move.distance >= 30.0) return atTarget
        // 3. On foot at the exit stop: real movement at walking pace, not a bus waiting at a light.
        if (atTarget && move.distance >= 40.0 && move.seconds >= 45 && input.vehicle == null) return true
        return null
    }
}

/** How often the phone position is needed. Tight only around a possible boarding or exit. */
data class LocationProfile(val intervalMillis: Long, val minDistance: Float) {
    companion object {
        fun of(ride: Ride?, phase: BoardingPhase?, now: Instant): LocationProfile {
            if (ride == null) return LocationProfile(10_000, 20f)
            if (phase == BoardingPhase.POSSIBLE_BOARDING || phase == BoardingPhase.POSSIBLE_EXIT) return LocationProfile(3_000, 0f)
            if (ride.boarded) {
                val nearExit = ride.displayTarget().effective(ride.offsetSeconds)?.let { Duration.between(now, it).seconds <= 240 } == true
                return if (nearExit) LocationProfile(3_000, 0f) else LocationProfile(5_000, 10f)
            }
            val until = Duration.between(now, ride.boardingTime().at).seconds
            return when {
                until > 600 -> LocationProfile(20_000, 30f)
                until > 180 -> LocationProfile(8_000, 12f)
                else -> LocationProfile(3_000, 0f)
            }
        }
    }
}
