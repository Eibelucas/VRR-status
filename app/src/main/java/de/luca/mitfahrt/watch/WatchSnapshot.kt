package de.luca.mitfahrt.watch

import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.reports.DelayEvidence
import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant

fun TrackingState.watchSnapshot(now: Instant = Instant.now()): WatchRide {
    val ride = ride ?: return WatchRide(now)
    val boarding = ride.boardingTime()
    val liveBoarding = if (running && !lastRefreshFailed) ride.liveBoardingTime(now) else null
    val live = if (!ride.boarded) liveBoarding != null else ride.displayTarget().estimated != null
    val boardingStop = ride.stops.getOrNull(ride.boardingIndex)
    val monitorOnly = !ride.boarded && boardingStop?.estimatedArrival == null && boardingStop?.estimatedDeparture == null && ride.departure.estimated != null
    val sourceAt = if (monitorOnly) minOf(ride.fetchedAt, ride.departure.observedAt ?: Instant.EPOCH) else ride.fetchedAt
    return WatchRide(sentAt = now, active = true, running = running, fetchedAt = sourceAt,
        line = ride.departure.line, destination = shortName(ride.displayTarget().name),
        nextStop = ride.nextStopToTarget(now)?.name?.let(::shortName) ?: "Ziel laut Fahrtverlauf",
        boarded = ride.boarded,
        boardingAt = if (ride.boarded || ride.demo) boarding.at else liveBoarding?.at,
        boardingIsArrival = liveBoarding?.isArrival ?: boarding.isArrival,
        terminalAt = ride.displayTarget().effective(ride.offsetSeconds),
        source = when {
            ride.demo -> "Demo"
            !ride.boarded && liveBoarding == null -> "Keine Live-Zeit"
            live -> "VRR live"
            ride.departure.estimated != null && ride.boarded -> "Geschätzt"
            else -> "Fahrplan"
        },
        delay = DelayEvidence.timingFor(ride, if (ride.boarded) ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex) else ride.boardingIndex)?.shortDelay.orEmpty(),
        demo = ride.demo, cancelled = ride.departure.cancelled, refreshFailed = lastRefreshFailed)
}
