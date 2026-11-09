package de.luca.mitfahrt.data

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** A phone position. Kept in memory on the device only; never stored or sent anywhere. */
data class PhoneFix(val point: GeoPoint, val accuracy: Float, val at: Instant)

/** Bounded, in-memory window of recent phone positions. */
class PhoneTrack(private val maxAgeSeconds: Long = 300, private val maxFixes: Int = 120) {
    private val fixes = ArrayDeque<PhoneFix>()

    @Synchronized fun add(fix: PhoneFix): Boolean {
        if (!Geo.valid(fix.point) || !fix.accuracy.isFinite() || fix.accuracy <= 0f) return false
        val last = fixes.lastOrNull()
        if (last != null && !fix.at.isAfter(last.at)) return false
        fixes.addLast(fix)
        while (fixes.size > maxFixes) fixes.removeFirst()
        while (fixes.isNotEmpty() && Duration.between(fixes.first().at, fix.at).seconds > maxAgeSeconds) fixes.removeFirst()
        return true
    }

    @Synchronized fun recent(now: Instant, seconds: Long = maxAgeSeconds): List<PhoneFix> =
        fixes.filter { Duration.between(it.at, now).seconds in -5..seconds }

    @Synchronized fun clear() = fixes.clear()
}

/** One reported NEW position of a vehicle. */
data class VehicleObservation(val point: GeoPoint, val at: Instant)

/** Recent NEW observations per vehicle; only distinct timestamps count as separate measurements. */
class VehicleHistory(private val maxPerVehicle: Int = 10, private val maxAgeSeconds: Long = 300) {
    private val tracks = linkedMapOf<String, MutableList<VehicleObservation>>()

    @Synchronized fun record(vehicles: Collection<NewVehicle>, now: Instant) {
        vehicles.forEach { vehicle ->
            if (!Geo.valid(vehicle.point)) return@forEach
            val track = tracks.getOrPut(vehicle.id) { mutableListOf() }
            if (track.none { it.at == vehicle.observedAt }) {
                track += VehicleObservation(vehicle.point, vehicle.observedAt)
                track.sortBy { it.at }
            }
            while (track.size > maxPerVehicle) track.removeAt(0)
        }
        tracks.values.forEach { track -> track.removeAll { Duration.between(it.at, now).seconds > maxAgeSeconds } }
        tracks.entries.removeAll { it.value.isEmpty() }
        while (tracks.size > 200) tracks.remove(tracks.keys.first())
    }

    @Synchronized fun track(id: String): List<VehicleObservation> = tracks[id]?.toList().orEmpty()
    @Synchronized fun clear() = tracks.clear()
}

data class MatchReason(val text: String, val points: Int)

/** A transparent point score. It is a ranking, not a probability. */
data class ScoredVehicle(
    val vehicle: NewVehicle,
    val score: Int,
    val reasons: List<MatchReason>,
    /** Hard reason why this vehicle cannot be the ride, or null. */
    val excluded: String? = null,
    /** Not enough independent measurements for an automatic decision. */
    val insufficient: Boolean = false,
    /** Along-route position, if the vehicle could be placed on the line. */
    val offset: Double? = null
)

enum class MatchMode { WAITING, RIDING }

/**
 * Point rules. Thresholds were chosen from the geometry of real MG bus operation:
 * consecutive stops are mostly 250–600 m apart, buses of one line run 10–20 min apart,
 * consumer GPS is typically 5–30 m accurate in streets and NEW positions are a few seconds old.
 * Tests cover the situations listed in the task; real-world tuning still needs field data.
 */
object VehicleScoring {
    /** Within this many metres of the boarding stop a vehicle counts as "at the stop". */
    private const val AT_STOP = 80.0
    /** A vehicle this far past the boarding stop has left it. */
    private const val PASSED = 150.0
    private const val MIN_PROGRESS = 40.0
    private const val PAIR_SECONDS = 20L
    private const val VEHICLE_GPS_METRES = 35.0
    private const val WINDOW_SECONDS = 180L

    fun waiting(ride: Ride, reference: RouteReference?, vehicle: NewVehicle, history: List<VehicleObservation>,
                now: Instant, scheduleConfirmed: Boolean): ScoredVehicle {
        val reasons = mutableListOf<MatchReason>()
        if (scheduleConfirmed) reasons += MatchReason("NEW-Fahrplanweg passt zu dieser Fahrt", 100)
        directionReason(ride, vehicle)?.let(reasons::add)
        val boardingOffset = reference?.offsetOf(ride.boardingIndex)
        if (reference == null || boardingOffset == null) {
            return ScoredVehicle(vehicle, reasons.sumOf { it.points }, reasons + MatchReason("Keine Haltestellenlage bekannt", 0),
                insufficient = !scheduleConfirmed)
        }
        val window = boardingOffset + PASSED * 2
        val projection = reference.line.project(vehicle.point, 0.0, window)
        if (projection == null || projection.distance > reference.lateralTolerance()) {
            val later = reference.line.project(vehicle.point, window, reference.line.length)
            val passed = later != null && later.distance <= reference.lateralTolerance()
            return ScoredVehicle(vehicle, 0, reasons, excluded = if (passed) "bereits am Einstieg vorbei" else "abseits des Linienverlaufs",
                offset = later?.offset?.takeIf { passed })
        }
        val toBoarding = boardingOffset - projection.offset
        if (toBoarding < -PASSED) return ScoredVehicle(vehicle, 0, reasons, excluded = "bereits am Einstieg vorbei", offset = projection.offset)
        if (reference.exact) reasons += MatchReason("auf dem Linienverlauf",
            if (projection.distance <= 40.0) 15 else 5)
        if (abs(toBoarding) <= AT_STOP) reasons += MatchReason("am Einstieg", 10)

        val earlier = history.filter { Duration.between(it.at, vehicle.observedAt).seconds in 15..WINDOW_SECONDS }
            .minByOrNull { it.at }
        var measurements = 1
        if (earlier != null) {
            val old = reference.line.project(earlier.point, 0.0, window)
            if (old != null && old.distance <= reference.lateralTolerance()) {
                measurements++
                val moved = projection.offset - old.offset
                when {
                    moved >= MIN_PROGRESS -> reasons += MatchReason("nähert sich entlang der Strecke", 20)
                    moved <= -MIN_PROGRESS -> reasons += MatchReason("bewegt sich gegen die Fahrtrichtung", -45)
                    else -> reasons += MatchReason("steht", 0)
                }
            }
        }
        nextStopReason(ride, reference, projection.offset, vehicle)?.let(reasons::add)

        val expectedDelay = ride.liveBoardingTime(now)?.let { live ->
            val planned = ride.stops.getOrNull(ride.boardingIndex)?.let { it.departure ?: it.arrival } ?: ride.departure.planned
            live.at.epochSecond - planned.epochSecond
        }
        reference.plannedTimeAt(ride, projection.offset)?.let { scheduled ->
            val lateness = now.epochSecond - scheduled.epochSecond
            if (expectedDelay != null) {
                val difference = abs(lateness - expectedDelay)
                reasons += when {
                    difference <= 90 -> MatchReason("Position passt zur Live-Zeit", 30)
                    difference <= 180 -> MatchReason("Position passt ungefähr zur Live-Zeit", 15)
                    difference <= 300 -> MatchReason("Position weicht von der Live-Zeit ab", 0)
                    else -> MatchReason("Position passt zeitlich nicht", -30)
                }
            } else {
                reasons += when {
                    abs(lateness) <= 300 -> MatchReason("Position passt zum Fahrplan", 5)
                    abs(lateness) > 900 -> MatchReason("Position weit vom Fahrplan entfernt", -20)
                    else -> MatchReason("Position nur grob zum Fahrplan passend", 0)
                }
            }
        }
        val reportedDelay = vehicle.delaySeconds
        if (reportedDelay != null && expectedDelay != null) {
            val difference = abs(reportedDelay - expectedDelay)
            if (difference <= 120) reasons += MatchReason("NEW-Verspätung passt zur VRR-Zeit", 15)
            else if (difference > 300) reasons += MatchReason("NEW-Verspätung passt nicht zur VRR-Zeit", -25)
        }
        return ScoredVehicle(vehicle, reasons.sumOf { it.points }, reasons,
            insufficient = measurements < 2 && !scheduleConfirmed, offset = projection.offset)
    }

    /**
     * Compares movement of phone and vehicle over a sliding window. Every vehicle observation is
     * paired with the phone fix closest in time; the allowance grows with both accuracies and
     * with the time offset between the two measurements.
     */
    fun riding(ride: Ride, reference: RouteReference?, vehicle: NewVehicle, history: List<VehicleObservation>,
               phone: List<PhoneFix>, now: Instant, scheduleConfirmed: Boolean): ScoredVehicle {
        val reasons = mutableListOf<MatchReason>()
        if (scheduleConfirmed) reasons += MatchReason("NEW-Fahrplanweg passt zu dieser Fahrt", 30)
        directionReason(ride, vehicle)?.let(reasons::add)
        val observations = (history + VehicleObservation(vehicle.point, vehicle.observedAt)).distinctBy { it.at }
            .filter { Duration.between(it.at, now).seconds in -15..WINDOW_SECONDS }.sortedBy { it.at }
        val fixes = phone.filter { it.accuracy <= 60f && Duration.between(it.at, now).seconds in -5..WINDOW_SECONDS }.sortedBy { it.at }
        if (fixes.isEmpty() || Duration.between(fixes.last().at, now).seconds > 45) {
            return ScoredVehicle(vehicle, reasons.sumOf { it.points }, reasons + MatchReason("keine aktuelle Handyposition", 0), insufficient = true)
        }
        data class Pair(val observation: VehicleObservation, val fix: PhoneFix, val residual: Double)
        val pairs = observations.mapIndexedNotNull { index, observation ->
            val fix = fixes.minByOrNull { abs(Duration.between(it.at, observation.at).seconds) } ?: return@mapIndexedNotNull null
            val gap = abs(Duration.between(fix.at, observation.at).toMillis()) / 1000.0
            if (gap > PAIR_SECONDS) return@mapIndexedNotNull null
            val neighbour = observations.getOrNull(index - 1) ?: observations.getOrNull(index + 1)
            val speed = neighbour?.let { other ->
                val seconds = abs(Duration.between(other.at, observation.at).toMillis()) / 1000.0
                if (seconds >= 1.0) other.point.distanceTo(observation.point) / seconds else null
            }?.coerceAtMost(25.0) ?: 10.0
            val allowance = fix.accuracy + VEHICLE_GPS_METRES + speed * gap
            Pair(observation, fix, observation.point.distanceTo(fix.point) - allowance)
        }
        if (pairs.size < 2) {
            return ScoredVehicle(vehicle, reasons.sumOf { it.points }, reasons + MatchReason("zu wenige gleichzeitige Messungen", 0), insufficient = true)
        }
        val close = pairs.count { it.residual <= 0.0 }
        reasons += MatchReason("$close von ${pairs.size} Messungen nah beieinander", (40.0 * close / pairs.size).toInt())
        if (pairs.any { it.residual > 150.0 }) reasons += MatchReason("zeitweise deutlich entfernt", -40)

        val first = pairs.first()
        val last = pairs.last()
        val vehicleDistance = first.observation.point.distanceTo(last.observation.point)
        val phoneDistance = first.fix.point.distanceTo(last.fix.point)
        val seconds = Duration.between(first.observation.at, last.observation.at).seconds.coerceAtLeast(1)
        when {
            vehicleDistance >= 300.0 -> reasons += MatchReason("gemeinsam ${vehicleDistance.toInt()} m zurückgelegt", 25)
            vehicleDistance >= 150.0 -> reasons += MatchReason("gemeinsam ${vehicleDistance.toInt()} m zurückgelegt", 10)
        }
        if (vehicleDistance >= 100.0) {
            val ratio = phoneDistance / vehicleDistance
            reasons += if (ratio in 0.6..1.6) MatchReason("ähnliche Strecke", 10) else MatchReason("unterschiedliche Strecke", -20)
        }
        if (vehicleDistance >= 80.0 && phoneDistance >= 80.0) {
            val angle = Geo.angle(Geo.bearing(first.observation.point, last.observation.point), Geo.bearing(first.fix.point, last.fix.point))
            reasons += when {
                angle <= 35.0 -> MatchReason("gleiche Bewegungsrichtung", 15)
                angle > 90.0 -> MatchReason("andere Bewegungsrichtung", -40)
                else -> MatchReason("ähnliche Richtung", 0)
            }
        }
        val phoneSpeed = phoneDistance / seconds
        val vehicleSpeed = vehicleDistance / seconds
        if (seconds >= 45 && vehicleSpeed > 4.0 && phoneSpeed < 2.2) reasons += MatchReason("Handy bewegt sich wie zu Fuß", -50)

        reference?.let { ref ->
            val projection = ref.line.project(vehicle.point)
            if (projection != null && projection.distance <= ref.lateralTolerance()) {
                if (ref.exact) reasons += MatchReason("auf dem Linienverlauf", 10)
            } else if (ref.exact) reasons += MatchReason("abseits des Linienverlaufs", -15)
        }
        val moving = vehicleDistance >= 100.0
        return ScoredVehicle(vehicle, reasons.sumOf { it.points }, reasons, insufficient = !moving && !scheduleConfirmed)
    }

    private fun directionReason(ride: Ride, vehicle: NewVehicle): MatchReason? = when {
        NewVehicleMatcher.sameDirection(ride, vehicle) -> MatchReason("Richtung passt", 15)
        !vehicle.directionReliable || vehicle.destination.isBlank() -> MatchReason("Richtung nicht eindeutig gemeldet", 0)
        else -> null
    }

    private fun nextStopReason(ride: Ride, reference: RouteReference, offset: Double, vehicle: NewVehicle): MatchReason? {
        val name = vehicle.nextStopName?.let(::normalizeName)?.takeIf { it.isNotEmpty() } ?: return null
        val matching = ride.stops.indices.filter { normalizeName(ride.stops[it].name) == name }
        if (matching.isEmpty()) return null
        val ahead = matching.filter { (reference.offsetOf(it) ?: -1.0) >= offset - 50.0 }
        return when {
            ahead.any { it <= ride.boardingIndex } -> MatchReason("NEW meldet passenden nächsten Halt", 10)
            ahead.isNotEmpty() -> MatchReason("NEW meldet einen Halt nach deinem Einstieg", -20)
            else -> null
        }
    }

    private fun normalizeName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("ß", "ss")
        .replace(Regex("^(monchengladbach|monchengl\\.?|mg)[,\\s]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

enum class AutoMatchStatus { OFF, MANUAL, NO_DATA, SEARCHING, AMBIGUOUS, SUGGESTED, ASSIGNED, RECHECKING }
enum class AutoMatchBasis { APPROACH, CO_MOVEMENT, SCHEDULE }

data class AutoMatchOptions(val enabled: Boolean = false, val askFirst: Boolean = false) {
    companion object { val OFF = AutoMatchOptions() }
}

data class AutoCandidate(val id: String, val score: Int, val reasons: List<String>)

/** Decision state carried between refreshes. Contains vehicle ids only, no positions. */
data class AutoMatchState(
    val status: AutoMatchStatus = AutoMatchStatus.OFF,
    val vehicleId: String? = null,
    val basis: AutoMatchBasis? = null,
    val lastSupport: Instant? = null,
    val misses: Int = 0,
    val pendingId: String? = null,
    val pendingCount: Int = 0,
    val pendingSince: Instant? = null,
    val pendingObservedAt: Instant? = null,
    val choices: List<String> = emptyList(),
    val rejected: Set<String> = emptySet(),
    val ranking: List<AutoCandidate> = emptyList(),
    val note: String? = null
) {
    val holdsVehicle: Boolean get() = vehicleId != null && status in setOf(AutoMatchStatus.ASSIGNED, AutoMatchStatus.RECHECKING)
}

object AutoMatchEngine {
    const val WAITING_ASSIGN = 50
    const val RIDING_ASSIGN = 60
    const val RIDING_ASK = 40
    const val WAITING_KEEP = 20
    const val RIDING_KEEP = 25
    const val PLAUSIBLE = 25
    const val MARGIN = 25
    /** Consecutive evaluations with new NEW measurements before an automatic choice. */
    const val CONFIRMATIONS = 2
    const val MIN_CONFIRM_SECONDS = 15L
    /** A held vehicle is re-checked this long before another one may be taken. */
    const val GRACE_SECONDS = 60L
    const val GRACE_MISSES = 2

    fun step(previous: AutoMatchState, scored: List<ScoredVehicle>, mode: MatchMode, now: Instant,
             options: AutoMatchOptions, manual: Boolean): AutoMatchState {
        val ranking = scored.sortedByDescending { it.score }.take(5).map { candidate ->
            AutoCandidate(candidate.vehicle.id, candidate.score,
                (listOfNotNull(candidate.excluded) + candidate.reasons.filter { it.points != 0 }.map { it.text }).take(4))
        }
        if (!options.enabled) return AutoMatchState(status = AutoMatchStatus.OFF, rejected = previous.rejected)
        if (manual) return previous.copy(status = AutoMatchStatus.MANUAL, vehicleId = null, basis = null,
            pendingId = null, pendingCount = 0, choices = emptyList(), ranking = ranking, note = null)
        val assign = if (mode == MatchMode.RIDING) RIDING_ASSIGN else WAITING_ASSIGN
        val keep = if (mode == MatchMode.RIDING) RIDING_KEEP else WAITING_KEEP
        val basis = if (mode == MatchMode.RIDING) AutoMatchBasis.CO_MOVEMENT else AutoMatchBasis.APPROACH
        val usable = scored.filter { it.excluded == null && it.vehicle.id !in previous.rejected }
            .sortedWith(compareByDescending<ScoredVehicle> { it.score }.thenBy { it.vehicle.id })
        val base = previous.copy(ranking = ranking, choices = emptyList(), note = null)

        previous.vehicleId?.takeIf { previous.holdsVehicle }?.let { heldId ->
            val held = usable.firstOrNull { it.vehicle.id == heldId }
            if (held != null && held.score >= keep) {
                return base.copy(status = AutoMatchStatus.ASSIGNED, lastSupport = now, misses = 0,
                    basis = if (held.score >= 100 && mode == MatchMode.WAITING) AutoMatchBasis.SCHEDULE else previous.basis)
            }
            val misses = previous.misses + 1
            val sinceSupport = previous.lastSupport?.let { Duration.between(it, now).seconds } ?: Long.MAX_VALUE
            if (misses < GRACE_MISSES || sinceSupport < GRACE_SECONDS) {
                return base.copy(status = AutoMatchStatus.RECHECKING, misses = misses,
                    note = "Zuordnung wird erneut geprüft")
            }
            return pick(base.copy(vehicleId = null, basis = null, misses = 0, pendingId = null, pendingCount = 0,
                note = "Bisherige Zuordnung nicht mehr bestätigt"), usable, assign, mode, basis, now, options)
        }
        return pick(base.copy(vehicleId = null, basis = null, misses = 0), usable, assign, mode, basis, now, options)
    }

    private fun pick(state: AutoMatchState, usable: List<ScoredVehicle>, assign: Int, mode: MatchMode,
                     basis: AutoMatchBasis, now: Instant, options: AutoMatchOptions): AutoMatchState {
        if (usable.isEmpty()) return state.copy(status = AutoMatchStatus.NO_DATA, pendingId = null, pendingCount = 0,
            note = state.note ?: "Kein passendes Fahrzeug mit aktuellen Daten")
        val best = usable.first()
        val second = usable.getOrNull(1)
        val clear = second == null || best.score - second.score >= MARGIN
        if (best.score >= assign && clear && !best.insufficient) {
            val same = state.pendingId == best.vehicle.id
            val newMeasurement = !same || state.pendingObservedAt == null || best.vehicle.observedAt.isAfter(state.pendingObservedAt)
            val count = if (same) state.pendingCount + if (newMeasurement) 1 else 0 else 1
            val since = if (same) state.pendingSince ?: now else now
            val stable = count >= CONFIRMATIONS && Duration.between(since, now).seconds >= MIN_CONFIRM_SECONDS
            val pending = state.copy(pendingId = best.vehicle.id, pendingCount = count, pendingSince = since,
                pendingObservedAt = best.vehicle.observedAt)
            if (!stable) return pending.copy(status = AutoMatchStatus.SEARCHING, note = state.note ?: "Fahrzeug wird bestätigt")
            val chosenBasis = if (best.score >= 100 && mode == MatchMode.WAITING) AutoMatchBasis.SCHEDULE else basis
            return if (options.askFirst) pending.copy(status = AutoMatchStatus.SUGGESTED, vehicleId = best.vehicle.id, basis = chosenBasis)
            else pending.copy(status = AutoMatchStatus.ASSIGNED, vehicleId = best.vehicle.id, basis = chosenBasis,
                lastSupport = now, misses = 0)
        }
        val reset = state.copy(pendingId = null, pendingCount = 0, pendingSince = null, pendingObservedAt = null)
        if (mode == MatchMode.RIDING && best.score >= RIDING_ASK && (second == null || best.score - second.score >= MARGIN / 2) && !best.insufficient) {
            return reset.copy(status = AutoMatchStatus.SUGGESTED, vehicleId = best.vehicle.id, basis = basis)
        }
        val plausible = usable.filter { it.score >= PLAUSIBLE }
        if (plausible.size >= 2) return reset.copy(status = AutoMatchStatus.AMBIGUOUS,
            choices = plausible.take(3).map { it.vehicle.id }, note = "Mehrere Fahrzeuge kommen infrage")
        return reset.copy(status = AutoMatchStatus.SEARCHING,
            note = state.note ?: if (best.insufficient) "Zu wenige Messungen für eine sichere Erkennung" else null)
    }

    /** Recorded "Nein" answers keep a vehicle out of automatic choices for this journey. */
    fun reject(state: AutoMatchState, id: String): AutoMatchState = state.copy(
        rejected = (state.rejected + id).toList().takeLast(20).toSet(),
        vehicleId = state.vehicleId?.takeUnless { it == id },
        status = if (state.vehicleId == id) AutoMatchStatus.SEARCHING else state.status,
        pendingId = state.pendingId?.takeUnless { it == id }, pendingCount = if (state.pendingId == id) 0 else state.pendingCount)
}
