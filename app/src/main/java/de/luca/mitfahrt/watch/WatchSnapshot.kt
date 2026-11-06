package de.luca.mitfahrt.watch

import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.reports.DelayEvidence
import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant

fun TrackingState.watchSnapshot(now: Instant = Instant.now()): WatchRide {
    val ride = ride ?: return WatchRide(now)
    val boarding = ride.boardingTime()
    val live = if (!ride.boarded) boarding.live else ride.terminal.estimated != null
    return WatchRide(sentAt = now, active = true, running = running, fetchedAt = ride.fetchedAt,
        line = ride.departure.line, destination = shortName(ride.terminal.name),
        nextStop = ride.nextStop(now)?.name?.let(::shortName) ?: "Endhaltestelle",
        boarded = ride.boarded, boardingAt = boarding.at, boardingIsArrival = boarding.isArrival,
        terminalAt = ride.terminal.effective(ride.offsetSeconds),
        source = when {
            ride.demo -> "Demo"
            live -> "VRR live"
            ride.departure.estimated != null && ride.boarded -> "Geschätzt"
            else -> "Fahrplan"
        },
        delay = DelayEvidence.timingFor(ride, if (ride.boarded) ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex) else ride.boardingIndex)?.shortDelay.orEmpty(),
        demo = ride.demo, cancelled = ride.departure.cancelled, refreshFailed = lastRefreshFailed)
}
