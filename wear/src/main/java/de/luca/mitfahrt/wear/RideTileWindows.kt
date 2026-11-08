package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant
import kotlin.math.ceil
import java.util.Locale

internal data class RideTileWindow(val startMillis: Long, val endMillis: Long)

/** Minute boundaries and freshness expiry are known without waking the phone or a timer. */
internal fun rideTileWindows(ride: WatchRide?, now: Instant): List<RideTileWindow> {
    if (ride?.active != true || ride.stale(now)) return emptyList()
    val expiry = minOf(ride.sentAt.epochSecond + 121, requireNotNull(ride.fetchedAt).epochSecond + 121) * 1000
    val target = (if (ride.boarded) ride.terminalAt else ride.boardingAt)?.toEpochMilli()
    val windows = mutableListOf<RideTileWindow>()
    var cursor = now.toEpochMilli()
    // Freshness is bounded to at most three minutes, including tolerated future clock skew.
    repeat(8) {
        if (cursor >= expiry) return windows
        val minutes = target?.let { ceil((it - cursor).coerceAtLeast(0) / 60_000.0).toLong() } ?: 0L
        val boundary = if (minutes > 0) target!! - (minutes - 1) * 60_000 else expiry
        val end = minOf(expiry, maxOf(cursor + 1, boundary))
        windows += RideTileWindow(cursor, end)
        cursor = end
    }
    return windows
}

internal data class RideTimeDisplay(val value: String, val detail: String)

/** Missing or expired live times never turn into a synthetic zero-minute arrival. */
internal fun rideTimeDisplay(ride: WatchRide, now: Instant, minutesOnly: Boolean = false,
    expired: Boolean = false): RideTimeDisplay {
    if (ride.cancelled) return RideTimeDisplay("Fällt aus", "Andere Fahrt wählen")
    if (expired || ride.stale(now)) return RideTimeDisplay("–", if (!ride.running) "Fahrt pausiert" else "Daten veraltet")
    val target = if (ride.boarded) ride.terminalAt else ride.boardingAt
    if (target == null) return RideTimeDisplay("–", if (ride.boarded) "Ankunft unbekannt" else "Keine Live-Zeit")
    val seconds = (target.epochSecond - now.epochSecond).coerceAtLeast(0)
    val value = when {
        !ride.boarded && seconds == 0L -> "Erwartet"
        ride.boarded || minutesOnly -> "${ceil(seconds / 60.0).toInt()} Min"
        else -> "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }
    val detail = when {
        ride.boarded -> "bis zum Ziel"
        ride.boardingIsArrival -> "bis dein Bus kommt"
        else -> "bis zur Abfahrt"
    }
    return RideTimeDisplay(value, detail)
}
