package de.luca.mitfahrt.data

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pure, testable transit helpers for:
 * - Leave-time countdown before boarding (Rang 1)
 * - Missed / passed bus detection (Rang 2)
 * - Hub platform prominence & platform-change detection (Rang 3)
 * - VRR vs. NEW live forecast divergence (Rang 7)
 * - Fail-closed 3-2-1 exit countdown haptics (Bonus 1)
 * - Post-exit transfer handoff state (Rang 5)
 */

/** Calculated leave-time guidance before boarding. */
data class LeaveGuidance(
    val leaveAt: Instant,
    val secondsUntilLeave: Long,
    val state: ReachabilityState,
    val walkMinutes: Int,
    val bufferMinutes: Int,
    val approximate: Boolean
) {
    /** Compact chip/title label, e.g. "Los in 4 Min", "Jetzt los", "Knapp". */
    fun chipLabel(): String = when (state) {
        ReachabilityState.CANCELLED -> "Fällt aus"
        ReachabilityState.DEPARTED -> "Abfahrt vorbei"
        ReachabilityState.TOO_LATE -> "Zu spät"
        ReachabilityState.TIGHT -> "Knapp"
        ReachabilityState.POSSIBLE -> {
            val mins = ceil(secondsUntilLeave / 60.0).toInt().coerceAtLeast(0)
            if (mins <= 0) "Jetzt los"
            else if (approximate) "ca. los in $mins Min"
            else "Los in $mins Min"
        }
        else -> ""
    }

    /** Short compact chip for tight surfaces (Now Bar pill / Watch complication). */
    fun shortChip(): String = when (state) {
        ReachabilityState.TOO_LATE -> "Zu spät"
        ReachabilityState.TIGHT -> "Knapp"
        ReachabilityState.POSSIBLE -> {
            val mins = ceil(secondsUntilLeave / 60.0).toInt().coerceAtLeast(0)
            if (mins <= 0) "Jetzt los" else "Los $mins Min"
        }
        else -> ""
    }
}

object LeaveCountdown {
    /**
     * Computes when the user needs to leave for [boardingAt].
     * If [liveLocation] and [stopPoint] are present and within 3 km, uses live air-distance estimate
     * (explicitly marked [LeaveGuidance.approximate] = true); otherwise uses planned walking + buffer minutes.
     */
    fun compute(
        boardingAt: Instant?,
        cancelled: Boolean,
        now: Instant,
        plannedWalkMinutes: Int,
        bufferMinutes: Int,
        liveLocation: GeoPoint? = null,
        stopPoint: GeoPoint? = null,
        walkSpeedCmPerSec: Int = 120
    ): LeaveGuidance? {
        if (boardingAt == null) return null
        val remaining = boardingAt.epochSecond - now.epochSecond
        if (cancelled) return LeaveGuidance(boardingAt, 0, ReachabilityState.CANCELLED, plannedWalkMinutes, bufferMinutes, false)
        if (remaining <= 0) return LeaveGuidance(boardingAt, remaining, ReachabilityState.DEPARTED, plannedWalkMinutes, bufferMinutes, false)

        val liveDistance = if (liveLocation != null && stopPoint != null && Geo.valid(liveLocation) && Geo.valid(stopPoint)) {
            liveLocation.distanceTo(stopPoint).takeIf { it.isFinite() && it in 0.0..3000.0 }?.toInt()
        } else null

        val walkSec: Long
        val walkMin: Int
        val approximate: Boolean
        if (liveDistance != null) {
            val est = WalkingEstimate(liveDistance, ceil(liveDistance.toDouble() * 135 / walkSpeedCmPerSec.coerceIn(50, 220)).toLong())
            walkSec = est.seconds
            walkMin = est.minutes
            approximate = true
        } else {
            walkMin = plannedWalkMinutes.coerceIn(0, 60)
            walkSec = walkMin * 60L
            approximate = false
        }
        val bufMin = bufferMinutes.coerceIn(0, 30)
        val bufSec = bufMin * 60L
        val required = walkSec + bufSec
        val secondsUntilLeave = remaining - required
        val state = when {
            remaining >= required -> ReachabilityState.POSSIBLE
            remaining >= walkSec -> ReachabilityState.TIGHT
            else -> ReachabilityState.TOO_LATE
        }
        return LeaveGuidance(
            leaveAt = boardingAt.minusSeconds(required),
            secondsUntilLeave = secondsUntilLeave,
            state = state,
            walkMinutes = walkMin,
            bufferMinutes = bufMin,
            approximate = approximate
        )
    }

    /** Matches an active or enabled [RoutePlan] to [ride] to retrieve its configured walking & buffer minutes. */
    fun matchingRoute(ride: Ride, plans: List<RoutePlan>): RoutePlan? {
        val origin = stopKey(ride.departure.stopId)
        val target = ride.exitTargetStop()?.id?.let(::stopKey)
        return plans.firstOrNull { plan ->
            plan.enabled && stopKey(plan.origin.id) == origin &&
                (target == null || stopKey(plan.effectiveDestination().id) == target) &&
                ride.stops.drop(ride.boardingIndex + 1).any { stopKey(it.id) == stopKey(plan.effectiveDestination().id) } &&
                (plan.match == RouteMatch.STOP || ride.departure.line.equals(plan.line, true) ||
                    plan.allowedLines.any { it.equals(ride.departure.line, true) })
        }
    }
}

/** Detects when a waited-for bus appears to have passed or departed while the user is still waiting. */
object MissedBusDetector {
    /** Vehicle distance past boarding stop on verified geometry to suggest the bus passed. */
    const val PASSED_METRES = 150.0
    /** Grace window after effective boarding time before offering missed/passed actions. */
    const val DEPARTURE_GRACE_SECONDS = 60L

    fun shouldPrompt(state: TrackingState, now: Instant): Boolean {
        val ride = state.ride ?: return false
        if (!state.running || ride.demo || ride.boarded || ride.departure.cancelled) return false

        // 1. Live matched NEW vehicle already clearly passed the boarding stop on the verified route.
        val shape = state.routeShape?.takeIf { it.journeyKey == ride.departure.key }
        val live = state.liveBus
        val trustedBus = live.vehicle?.takeIf {
            live.journeyKey == ride.departure.key && it.isFresh(now) &&
                (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle)
        }
        if (shape != null && trustedBus != null && live.routeOffset != null) {
            val boardingOffset = shape.offsetOf(ride.boardingIndex)
            if (boardingOffset != null && live.routeOffset - boardingOffset >= PASSED_METRES) {
                return true
            }
        }

        // 2. Effective boarding time passed by more than 60s and user never boarded.
        val boardingAt = ride.liveBoardingTime(now)?.at ?: ride.boardingTime().at
        if (boardingAt != null && now.epochSecond - boardingAt.epochSecond >= DEPARTURE_GRACE_SECONDS) {
            return true
        }
        return false
    }
}

/** Hub platform formatting and platform-change tracking. */
data class PlatformChange(val previous: String, val current: String)

object PlatformHubHelper {
    /** True for known multi-platform hubs (MG Hbf, Rheydt Hbf) or stops whose name indicates a major hub. */
    fun isMultiPlatformHub(stopId: String, stopName: String): Boolean =
        PlatformStations.isKnown(stopId) ||
            stopName.contains("Hbf", ignoreCase = true) ||
            stopName.contains("Hauptbahnhof", ignoreCase = true) ||
            stopName.contains("ZOB", ignoreCase = true) ||
            stopName.contains("Bismarckplatz", ignoreCase = true)

    fun formatPlatform(platform: String): String? {
        val clean = platform.trim()
        if (clean.isBlank()) return null
        return if (clean.startsWith("Steig", true) || clean.startsWith("Gleis", true) || clean.startsWith("Bst", true)) {
            clean
        } else {
            "Steig $clean"
        }
    }

    fun detectChange(previous: String?, current: String?): PlatformChange? {
        val old = previous?.trim().orEmpty()
        val now = current?.trim().orEmpty()
        if (old.isBlank() || now.isBlank() || old.equals(now, ignoreCase = true)) return null
        return PlatformChange(old, now)
    }
}

/** Divergence between official VRR forecast and confirmed NEW vehicle live telemetry. */
data class ForecastDivergence(
    val journeyKey: String,
    val vrrDelayMinutes: Int,
    val vehicleDelayMinutes: Int,
    val vehicleId: String,
    val observedAt: Instant
) {
    val label: String
        get() {
            val vrr = if (vrrDelayMinutes == 0) "pünktlich" else if (vrrDelayMinutes > 0) "+$vrrDelayMinutes Min" else "$vrrDelayMinutes Min"
            val bus = if (vehicleDelayMinutes == 0) "pünktlich" else if (vehicleDelayMinutes > 0) "+$vehicleDelayMinutes Min" else "$vehicleDelayMinutes Min"
            return "VRR: $vrr · Bus meldet $bus (NEW-Fahrzeugdaten)"
        }
}

object ForecastReconciler {
    /** Minimum discrepancy (3 minutes) before surfacing a divergence hint. */
    const val MIN_DIVERGENCE_SECONDS = 180L
    /** Sanity ceiling for NEW PLAN_ABWEICHNG to ignore corrupt telemetry. */
    private const val MAX_PLAUSIBLE_DELAY_SECONDS = 5400L

    fun evaluate(ride: Ride, live: LiveBusState, now: Instant): ForecastDivergence? {
        if (ride.demo || ride.departure.cancelled || live.journeyKey != ride.departure.key) return null
        val vehicle = live.vehicle?.takeIf {
            it.isFresh(now) && (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle)
        } ?: return null
        val rawDelay = vehicle.delaySeconds ?: return null
        if (abs(rawDelay) > MAX_PLAUSIBLE_DELAY_SECONDS) return null

        val vrrOffsetSec = ride.offsetSeconds
        val diff = abs(rawDelay - vrrOffsetSec)
        if (diff < MIN_DIVERGENCE_SECONDS) return null

        val vrrMin = (vrrOffsetSec / 60).toInt()
        val busMin = (rawDelay / 60).toInt()
        if (abs(busMin - vrrMin) < 3) return null

        return ForecastDivergence(
            journeyKey = ride.departure.key,
            vrrDelayMinutes = vrrMin,
            vehicleDelayMinutes = busMin,
            vehicleId = vehicle.id,
            observedAt = vehicle.observedAt
        )
    }
}

/**
 * Fail-closed 3-2-1 tactile stop countdown before exit (Bonus 1).
 * Uses the same confidence contract as [ExitAlarm]: never fires on stale or timetable-only guesses.
 */
data class ExitCountdownSignal(val id: String, val stopsRemaining: Int, val targetName: String)

object ExitCountdownHaptics {
    fun due(state: TrackingState, now: Instant): ExitCountdownSignal? {
        val ride = state.ride ?: return null
        if (!state.running || ride.demo || !ride.boarded || ride.departure.cancelled) return null
        val targetIndex = ride.exitTargetIndex() ?: return null
        val target = ride.stops[targetIndex]
        if (target.cancelled || ride.gpsPassedIndex >= targetIndex) return null

        val servedIndices = (ride.boardingIndex..targetIndex).filter { !ride.stops[it].cancelled }
        val targetPos = servedIndices.indexOf(targetIndex)
        if (targetPos < 2) return null

        // Determine the furthest confidently passed served stop (via GPS or fresh stop-level forecast).
        val freshGps = state.locationAt?.let { now.epochSecond - it.epochSecond in -30..90 } == true &&
            ride.gpsPassedIndex >= ride.boardingIndex
        val freshVrr = !state.lastRefreshFailed && now.epochSecond - ride.fetchedAt.epochSecond in -30..120

        var passedPos = -1
        if (freshGps) {
            passedPos = servedIndices.indexOfLast { it <= ride.gpsPassedIndex }
        }
        if (passedPos < 0 && freshVrr) {
            passedPos = servedIndices.indexOfLast { idx ->
                val est = ride.stops[idx].estimated
                est != null && est.epochSecond <= now.epochSecond + 15
            }
        }
        if (passedPos < 0) return null

        val remaining = targetPos - passedPos
        if (remaining != 2 && remaining != 3) return null

        val stopId = stopKey(target.id)
        val occurrence = (0..targetIndex).count { stopKey(ride.stops[it].id) == stopId }
        return ExitCountdownSignal(
            id = "exit_countdown/${ride.departure.key}/$stopId/$occurrence/$remaining",
            stopsRemaining = remaining,
            targetName = target.name
        )
    }
}

/** Active post-exit handoff window offering 1-tap start of the next leg on Now Bar and Watch. */
data class TransferHandoff(
    val nextLine: String,
    val nextDestination: String,
    val nextStopName: String,
    val nextPlatform: String,
    val nextDepartureAt: Instant?,
    val expiresAt: Instant
) {
    val label: String get() = "Weiter mit $nextLine"
}
