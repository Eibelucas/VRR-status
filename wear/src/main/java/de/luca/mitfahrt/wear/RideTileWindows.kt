package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant
import kotlin.math.ceil

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
