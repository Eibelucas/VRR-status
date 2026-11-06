package de.luca.mitfahrt.data

import java.time.Instant

fun Ride.displayTarget(): RideStop = exitTargetStop() ?: terminal
fun Ride.minutesToTarget(now: Instant): Int? = displayTarget().effective(offsetSeconds)?.let { minutesUntil(it, now) }
fun Ride.progressToTarget(now: Instant): Float {
    val begin = stops.getOrNull(boardingIndex)?.effective(offsetSeconds) ?: return 0f
    val end = displayTarget().effective(offsetSeconds) ?: return 0f
    return ((now.epochSecond - begin.epochSecond).toFloat() / (end.epochSecond - begin.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)
}

fun Ride.targetOccurrence(): Int {
    val index = exitTargetIndex() ?: return 0
    val id = stopKey(stops[index].id)
    return (boardingIndex + 1..index).count { stopKey(stops[it].id) == id } - 1
}

fun Ride.nextStopToTarget(now: Instant): RideStop? {
    val limit = exitTargetIndex() ?: stops.lastIndex
    return nextStop(now)?.takeIf { nextIndex(now) <= limit }
}
