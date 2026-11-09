package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class GpsProgressStatus { INACTIVE, TRACKING, OFF_ROUTE, ON_FOOT }

data class OffsetSample(val offset: Double, val at: Instant)

/**
 * Progress of a boarded ride measured from local phone positions (and, if reliably assigned,
 * the vehicle's own NEW position). Times are never used to mark a stop as passed.
 */
data class GpsProgressState(
    val journeyKey: String? = null,
    val active: Boolean = false,
    /** Highest stop index passed according to position measurements. */
    val passedIndex: Int = -1,
    /** Stops where a stationary period was measured. Passing alone does not claim a stop. */
    val dwellIndexes: Set<Int> = emptySet(),
    val offset: Double? = null,
    val lastFixAt: Instant? = null,
    /** Last measurement that was accepted on the line. */
    val confirmedAt: Instant? = null,
    val confirmedPoint: GeoPoint? = null,
    val lastAccuracy: Float? = null,
    val pendingOffset: Double? = null,
    val pendingAt: Instant? = null,
    /** Highest stop the previous accepted fix was beyond; a second fix must agree. */
    val beyondIndex: Int? = null,
    /** Without real geometry: stops actually approached since the last confirmed pass. */
    val approached: Set<Int> = emptySet(),
    val offRouteCount: Int = 0,
    val status: GpsProgressStatus = GpsProgressStatus.INACTIVE,
    val samples: List<OffsetSample> = emptyList(),
    val exact: Boolean = false,
    /** Along-line (exact) or straight-line (stop chain) distance to the exit target. */
    val targetDistance: Double? = null,
    val targetApproaching: Boolean = false
)

object GpsStopProgress {
    const val MAX_ACCURACY = 65f
    const val FRESH_SECONDS = 45L
    /** A stop counts as passed once two accepted fixes lie this far beyond it. */
    const val PASS_MARGIN_EXACT = 25.0
    const val PASS_MARGIN_CHAIN = 40.0
    const val MAX_SPEED = 30.0
    const val BACKWARD_NOISE = 40.0
    private const val OFF_ROUTE_FIXES = 3

    fun confident(state: GpsProgressState?, now: Instant): Boolean = state != null && state.active &&
        state.status == GpsProgressStatus.TRACKING &&
        state.confirmedAt?.let { Duration.between(it, now).seconds in -5..FRESH_SECONDS } == true

    /**
     * Processes one position. [fromVehicle] marks a NEW position of a reliably assigned bus; it
     * may move the progress but never changes the on-foot evaluation of the phone.
     */
    fun update(previous: GpsProgressState?, ride: Ride, reference: RouteReference?, fix: PhoneFix,
               fromVehicle: Boolean = false): GpsProgressState {
        val key = ride.departure.key
        var state = previous?.takeIf { it.journeyKey == key } ?: GpsProgressState(journeyKey = key)
        if (ride.demo || !ride.boarded || reference == null) {
            return state.copy(active = false, status = GpsProgressStatus.INACTIVE, lastFixAt = fix.at.takeUnless { fromVehicle } ?: state.lastFixAt)
        }
        if (!state.active) {
            val start = maxOf(ride.boardingIndex - 1, ride.gpsPassedIndex)
            state = state.copy(active = true, passedIndex = start, exact = reference.exact,
                offset = reference.offsetOf(maxOf(start, ride.boardingIndex)) ?: reference.offsetOf(ride.boardingIndex) ?: 0.0,
                approached = setOf(ride.boardingIndex), status = GpsProgressStatus.TRACKING)
        }
        if (!fromVehicle) state = state.copy(lastFixAt = fix.at, lastAccuracy = fix.accuracy)
        if (fix.accuracy > MAX_ACCURACY || !Geo.valid(fix.point)) return state
        state.confirmedAt?.let { if (!fix.at.isAfter(it)) return state }

        val offset = state.offset ?: 0.0
        val elapsed = state.confirmedAt?.let { Duration.between(it, fix.at).seconds.coerceIn(1, 3_600) } ?: 30L
        val window = 150.0 + MAX_SPEED * elapsed
        val projection = reference.line.project(fix.point, offset - 60.0, offset + window)
        if (projection == null || projection.distance > reference.lateralTolerance(fix.accuracy.toDouble())) {
            val count = state.offRouteCount + 1
            return state.copy(offRouteCount = count,
                status = if (count >= OFF_ROUTE_FIXES) GpsProgressStatus.OFF_ROUTE else state.status, beyondIndex = null)
        }
        state = state.copy(offRouteCount = 0, status = if (state.status == GpsProgressStatus.OFF_ROUTE) GpsProgressStatus.TRACKING else state.status)

        val measured = projection.offset
        if (measured < offset - BACKWARD_NOISE) {
            // GPS noise or a jump backwards: keep the progress, do not reset the display.
            return state.copy(confirmedAt = fix.at, confirmedPoint = fix.point, pendingOffset = null, pendingAt = null)
        }
        // Stops between the last accepted position and this measurement.
        val margin = if (reference.exact) PASS_MARGIN_EXACT else PASS_MARGIN_CHAIN
        val crossing = ride.stops.indices.filter { index ->
            reference.offsetOf(index)?.let { it + margin > offset && it + margin <= measured } == true
        }
        val speed = (measured - offset) / elapsed
        val jump = speed > MAX_SPEED || crossing.size >= 2
        if (jump) {
            val pending = state.pendingOffset
            val pendingAt = state.pendingAt
            val confirmsPending = pending != null && pendingAt != null && measured >= pending - 60.0 &&
                measured - pending <= 120.0 + MAX_SPEED * Duration.between(pendingAt, fix.at).seconds.coerceAtLeast(1)
            if (!confirmsPending) return state.copy(pendingOffset = measured, pendingAt = fix.at)
        }
        val newOffset = maxOf(offset, measured)
        val samples = (state.samples + OffsetSample(newOffset, fix.at)).takeLast(30)
            .filter { Duration.between(it.at, fix.at).seconds <= 180 }
        state = state.copy(offset = newOffset, confirmedAt = fix.at, confirmedPoint = fix.point,
            pendingOffset = null, pendingAt = null, samples = if (fromVehicle) state.samples else samples)

        if (!fromVehicle) state = state.copy(status = movementStatus(state))
        // Remember stops approached closely (needed without real geometry).
        val radius = maxOf(60.0, fix.accuracy + 30.0)
        val near = ((state.passedIndex + 1)..minOf(ride.stops.lastIndex, state.passedIndex + 4)).filter { index ->
            ride.stops[index].point?.let { it.distanceTo(fix.point) <= radius } == true
        }
        state = state.copy(approached = state.approached + near)

        if (state.status != GpsProgressStatus.ON_FOOT) {
            val beyond = stopsBeyond(ride, reference, state.passedIndex, newOffset, state.approached).maxOrNull()
            val agreed = if (beyond != null && state.beyondIndex != null) minOf(beyond, state.beyondIndex) else null
            state = if (agreed != null && agreed > state.passedIndex) {
                state.copy(passedIndex = agreed, beyondIndex = beyond,
                    approached = state.approached.filter { it > agreed }.toSet())
            } else state.copy(beyondIndex = beyond)
            state = state.copy(dwellIndexes = state.dwellIndexes + dwellAt(ride, reference, state, fix))
        } else state = state.copy(beyondIndex = null)

        val target = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val distance = if (reference.exact) reference.offsetOf(target)?.let { it - newOffset }
            else ride.stops[target].point?.distanceTo(fix.point)
        val approaching = distance != null && state.targetDistance != null && distance < state.targetDistance - 10.0
        return state.copy(targetDistance = distance, targetApproaching = approaching)
    }

    /** Stops after [passed] that a measurement at [offset] lies clearly beyond, in order. */
    private fun stopsBeyond(ride: Ride, reference: RouteReference, passed: Int, offset: Double, approached: Set<Int>): List<Int> {
        val margin = if (reference.exact) PASS_MARGIN_EXACT else PASS_MARGIN_CHAIN
        val beyond = ((passed + 1)..ride.stops.lastIndex).takeWhile { index ->
            val stopOffset = reference.offsetOf(index)
            stopOffset == null || stopOffset + margin <= offset
        }.filter { reference.offsetOf(it) != null }
        if (reference.exact || beyond.isEmpty()) return beyond
        // Stop chain: a stop is only passed if it, or a later stop, was actually approached.
        val lastApproached = approached.filter { it > passed }.maxOrNull() ?: return emptyList()
        return beyond.filter { it <= lastApproached }
    }

    private fun movementStatus(state: GpsProgressState): GpsProgressStatus {
        val samples = state.samples
        if (samples.size < 3) return if (state.status == GpsProgressStatus.OFF_ROUTE) state.status else GpsProgressStatus.TRACKING
        val last = samples.last()
        val window = samples.filter { Duration.between(it.at, last.at).seconds <= 90 }
        val first = window.first()
        val seconds = Duration.between(first.at, last.at).seconds
        val distance = last.offset - first.offset
        // Walking is slow *throughout*; a bus in stop-and-go traffic has the same average but fast parts.
        val fastestPart = window.zipWithNext().mapNotNull { (a, b) ->
            val span = Duration.between(a.at, b.at).seconds
            if (span >= 4) (b.offset - a.offset) / span else null
        }.maxOrNull() ?: 0.0
        if (seconds >= 60 && distance >= 40.0 && distance / seconds in 0.4..2.2 && fastestPart <= 3.0) return GpsProgressStatus.ON_FOOT
        if (state.status == GpsProgressStatus.ON_FOOT) {
            val recent = samples.filter { Duration.between(it.at, last.at).seconds <= 25 }
            val span = Duration.between(recent.first().at, last.at).seconds
            if (span < 10 || (last.offset - recent.first().offset) / span < 3.5) return GpsProgressStatus.ON_FOOT
        }
        return GpsProgressStatus.TRACKING
    }

    private fun dwellAt(ride: Ride, reference: RouteReference, state: GpsProgressState, fix: PhoneFix): Set<Int> {
        val samples = state.samples
        if (samples.size < 2) return emptySet()
        val last = samples.last()
        val still = samples.filter { Duration.between(it.at, last.at).seconds <= 20 }
        val span = Duration.between(still.first().at, last.at).seconds
        if (span < 10 || (last.offset - still.first().offset) / span > 1.5) return emptySet()
        return ride.stops.indices.filter { index ->
            index > ride.boardingIndex - 1 && reference.offsetOf(index)?.let { kotlin.math.abs(it - last.offset) <= 40.0 } == true &&
                (reference.exact || ride.stops[index].point?.let { it.distanceTo(fix.point) <= 40.0 } == true)
        }.toSet()
    }
}

enum class NextStopSource(val label: String) {
    LIVE("Live-Daten"), GPS("Per GPS erkannt"), SCHEDULE("Laut Fahrplan"), UNCERTAIN("Nicht sicher erkannt")
}

data class NextStopInfo(
    val index: Int?,
    val stop: RideStop?,
    val source: NextStopSource,
    /** Only from a fresh live forecast for that stop. Never derived from GPS or timetable. */
    val minutes: Int?,
    val note: String? = null,
    val targetReached: Boolean = false
)

object NextStopResolver {
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Berlin"))

    fun resolve(state: TrackingState, now: Instant, gpsEnabled: Boolean = true): NextStopInfo? {
        val ride = state.ride ?: return null
        val limit = ride.exitTargetIndex() ?: ride.stops.lastIndex
        if (!ride.boarded) return NextStopInfo(ride.boardingIndex, ride.stops.getOrNull(ride.boardingIndex),
            NextStopSource.SCHEDULE, null)
        val liveFresh = !ride.demo && state.running && !state.lastRefreshFailed &&
            now.epochSecond - ride.fetchedAt.epochSecond in -30..120 &&
            (ride.boardingIndex..limit).any { ride.stops[it].estimated != null && !ride.stops[it].cancelled }
        val gps = state.gpsProgress?.takeIf { gpsEnabled && !ride.demo && it.journeyKey == ride.departure.key && it.active }
        fun result(index: Int?, source: NextStopSource, minutes: Int? = null, note: String? = null): NextStopInfo =
            if (index == null || index > limit) NextStopInfo(null, null, source, null, note, targetReached = index != null)
            else NextStopInfo(index, ride.stops[index], source, minutes, note)

        if (liveFresh) {
            val combined = ride.nextIndex(now)
            val liveOnly = ride.copy(gpsPassedIndex = -1).nextIndex(now)
            val source = if (combined > liveOnly) NextStopSource.GPS else NextStopSource.LIVE
            val minutes = ride.stops.getOrNull(combined)?.estimated?.takeIf { source == NextStopSource.LIVE }?.let { minutesUntil(it, now) }
            return result(combined.takeIf { it < ride.stops.size } ?: (limit + 1), source, minutes)
        }
        if (gps != null) {
            if (GpsStopProgress.confident(gps, now)) {
                val next = ((gps.passedIndex + 1).coerceAtLeast(ride.boardingIndex)..ride.stops.lastIndex)
                    .firstOrNull { !ride.stops[it].cancelled } ?: (limit + 1)
                return result(next, NextStopSource.GPS)
            }
            val last = gps.passedIndex.takeIf { it >= ride.boardingIndex }?.let { shortName(ride.stops[it].name) }
            val note = when (gps.status) {
                GpsProgressStatus.OFF_ROUTE -> "Position weicht vom bekannten Fahrtverlauf ab"
                GpsProgressStatus.ON_FOOT -> "Bewegung wie zu Fuß – Fortschritt pausiert"
                else -> gps.confirmedAt?.let { at ->
                    "Letzte bestätigte Position ${clock.format(at)}" + (last?.let { " · nach $it" } ?: "")
                } ?: "Noch keine passende Position"
            }
            return NextStopInfo(null, null, NextStopSource.UNCERTAIN, null, note)
        }
        val index = ride.nextIndex(now)
        return result(index.takeIf { it < ride.stops.size } ?: (limit + 1), NextStopSource.SCHEDULE)
    }
}

/** GPS evidence for the exit warning when no fresh VRR forecast is available. */
object GpsExitSignal {
    const val MAX_ACCURACY = 50f
    const val EXACT_DISTANCE = 400.0
    const val CHAIN_DISTANCE = 300.0

    fun due(ride: Ride, gps: GpsProgressState?, now: Instant): Boolean {
        if (gps == null || gps.journeyKey != ride.departure.key || !GpsStopProgress.confident(gps, now)) return false
        if ((gps.lastAccuracy ?: Float.MAX_VALUE) > MAX_ACCURACY) return false
        val target = ride.exitTargetIndex() ?: return false
        if (gps.passedIndex >= target) return false
        val previous = (ride.boardingIndex until target).lastOrNull { !ride.stops[it].cancelled } ?: return false
        if (gps.passedIndex >= previous) return true
        val distance = gps.targetDistance ?: return false
        val limit = if (gps.exact) EXACT_DISTANCE else CHAIN_DISTANCE
        return gps.targetApproaching && distance in 0.0..limit && gps.passedIndex >= previous - 1
    }
}
