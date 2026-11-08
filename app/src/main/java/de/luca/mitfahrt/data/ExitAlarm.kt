package de.luca.mitfahrt.data

import java.time.Instant
import kotlin.math.abs

/** An index is retained because circular routes can visit the same stop more than once. */
fun Ride.exitTargetIndex(): Int? {
    val id = targetStopId ?: return null
    val candidates = stops.indices.filter { it > boardingIndex && stopKey(stops[it].id) == stopKey(id) }
    return targetStopIndex?.takeIf { it in candidates } ?: candidates.singleOrNull()
}

fun Ride.exitTargetStop(): RideStop? = exitTargetIndex()?.let(stops::get)

fun Ride.withExitTarget(index: Int?): Ride {
    if (index == null) return copy(targetStopId = null, targetStopIndex = null)
    require(index > boardingIndex && index in stops.indices && !stops[index].cancelled) { "Bitte einen späteren bedienten Halt wählen." }
    return copy(targetStopId = stops[index].id, targetStopIndex = index)
}

/** Refreshes may add diversion stops or change platforms; keep the selected occurrence. */
fun Ride.remapExitTargetFrom(previous: Ride): Ride {
    val old = previous.exitTargetStop() ?: return copy(targetStopId = null, targetStopIndex = null)
    val candidates = stops.indices.filter { it > boardingIndex && stopKey(stops[it].id) == stopKey(old.id) }
    val selected = if (old.planned != null) {
        val timed = candidates.mapNotNull { index -> stops[index].planned?.let { index to abs(it.epochSecond - old.planned!!.epochSecond) } }
        val closest = timed.minOfOrNull { it.second }
        // Ambiguous occurrences fail closed instead of silently selecting the wrong lap.
        timed.filter { it.second == closest && it.second <= 120 }.singleOrNull()?.first
    } else candidates.singleOrNull()
    return if (selected == null) copy(targetStopId = null, targetStopIndex = null)
    else copy(targetStopId = stops[selected].id, targetStopIndex = selected)
}

object ExitAlarm {
    fun due(state: TrackingState, now: Instant): Boolean {
        val ride = state.ride ?: return false
        if (!state.running || state.lastRefreshFailed || ride.demo || !ride.boarded || ride.departure.cancelled ||
            now.epochSecond - ride.fetchedAt.epochSecond !in -30..120) return false
        val targetIndex = ride.exitTargetIndex() ?: return false
        val target = ride.stops[targetIndex]
        if (target.cancelled || ride.gpsPassedIndex >= targetIndex) return false
        // A current explicit target forecast may establish that this warning is already late.
        // A timetable cannot override an actually passed stop when the bus is delayed.
        if (target.estimated?.isAfter(now) == false) return false
        val previous = (ride.boardingIndex until targetIndex).lastOrNull { !ride.stops[it].cancelled } ?: return false
        if (ride.gpsPassedIndex >= previous &&
            state.locationAt?.let { now.epochSecond - it.epochSecond in -30..90 } == true) return true
        val previousAt = ride.stops[previous].estimated ?: return false
        // A direct current forecast at the previous served stop is evidence. A fetched
        // timetable or an offset copied from the boarding delay is not.
        return previousAt.epochSecond - now.epochSecond in -120..15
    }

    fun id(ride: Ride): String? = ride.exitTargetIndex()?.let { index ->
        val stop = stopKey(ride.stops[index].id)
        val occurrence = (0..index).count { stopKey(ride.stops[it].id) == stop }
        "exit/${ride.departure.key}/$stop/$occurrence"
    }
}
