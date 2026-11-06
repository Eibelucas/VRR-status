package de.luca.mitfahrt.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double) {
    fun distanceTo(other: GeoPoint): Double {
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat)) *
            cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(a), sqrt((1 - a).coerceAtLeast(0.0)))
    }
}

data class Stop(val id: String, val name: String, val point: GeoPoint? = null, val distance: Int? = null)

data class Departure(
    val lineId: String, val line: String, val destination: String,
    val stopId: String, val stopName: String, val tripCode: String,
    val planned: Instant, val estimated: Instant?, val platform: String,
    val cancelled: Boolean = false, val notices: List<String> = emptyList(),
    val observedAt: Instant? = null
) {
    val key: String get() = "$lineId/$tripCode/$planned"
    val effective: Instant get() = estimated ?: planned
    val delayMinutes: Int get() = ((effective.epochSecond - planned.epochSecond) / 60).toInt()
}

data class RideStop(
    val id: String, val name: String, val point: GeoPoint?,
    val arrival: Instant?, val departure: Instant?,
    val estimatedArrival: Instant? = null, val estimatedDeparture: Instant? = null,
    val cancelled: Boolean = false
) {
    val planned: Instant? get() = arrival ?: departure
    val estimated: Instant? get() = estimatedArrival ?: estimatedDeparture
    fun effective(offsetSeconds: Long = 0): Instant? = estimated ?: planned?.plusSeconds(offsetSeconds)
}

data class Ride(
    val departure: Departure, val stops: List<RideStop>, val boardingIndex: Int,
    val fetchedAt: Instant, val demo: Boolean = false, val gpsPassedIndex: Int = -1,
    val boarded: Boolean = true, val trackingStartedAt: Instant? = null,
    val targetStopId: String? = null, val targetStopIndex: Int? = null
) {
    val offsetSeconds: Long get() = departure.estimated?.let { it.epochSecond - departure.planned.epochSecond } ?: 0
    // A departure delay is only an extrapolation for later stops, never a live ETA.
    fun nextIndex(now: Instant): Int = stops.indices.firstOrNull {
        it >= boardingIndex && it > gpsPassedIndex && !stops[it].cancelled &&
            (stops[it].effective(offsetSeconds)?.isBefore(now.minusSeconds(15)) != true)
    } ?: stops.size
    fun nextStop(now: Instant): RideStop? = stops.getOrNull(nextIndex(now))
    val terminal: RideStop get() = stops.last()
    fun remainingMinutes(now: Instant): Int? = terminal.effective(offsetSeconds)?.let { minutesUntil(it, now) }
    fun isStale(now: Instant): Boolean {
        if (now.epochSecond - fetchedAt.epochSecond !in -30..120) return true
        if (!boarded) return boardingTime().let { it.live && !it.isFresh(now) }
        if (displayTarget().estimated == null && offsetSeconds != 0L)
            return departure.observedAt?.let { now.epochSecond - it.epochSecond !in -30..120 } != false
        return false
    }
    fun complete(now: Instant): Boolean = terminal.effective(offsetSeconds)?.let { now.isAfter(it.plusSeconds(120)) } ?: false
    fun progress(now: Instant): Float {
        val begin = stops.getOrNull(boardingIndex)?.effective(offsetSeconds) ?: return 0f
        val end = terminal.effective(offsetSeconds) ?: return 0f
        return ((now.epochSecond - begin.epochSecond).toFloat() /
            (end.epochSecond - begin.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
}

data class BoardingTime(val at: Instant, val isArrival: Boolean, val live: Boolean, val observedAt: Instant? = null) {
    fun isFresh(now: Instant): Boolean = !live || observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
}

fun Ride.boardingTime(): BoardingTime {
    val stop = stops.getOrNull(boardingIndex)
    return when {
        stop?.estimatedArrival != null -> BoardingTime(stop.estimatedArrival, true, true, fetchedAt)
        stop?.estimatedDeparture != null -> BoardingTime(stop.estimatedDeparture, false, true, fetchedAt)
        departure.estimated != null -> BoardingTime(departure.estimated, false, true, departure.observedAt)
        stop?.arrival != null -> BoardingTime(stop.arrival, true, false)
        else -> BoardingTime(stop?.departure ?: departure.planned, false, false)
    }
}

fun countdownUntil(at: Instant, now: Instant): String {
    val seconds = (at.epochSecond - now.epochSecond).coerceAtLeast(0)
    return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}

enum class WifiSignal { NONE, NEARBY, CONNECTED, UNAVAILABLE }

fun isNewHotspot(ssid: String): Boolean = ssid.trim().trim('"').matches(Regex("NEW[- _]Hotspot", RegexOption.IGNORE_CASE))
fun minutesUntil(instant: Instant, now: Instant = Instant.now()): Int =
    ceil((instant.epochSecond - now.epochSecond) / 60.0).toInt().coerceAtLeast(0)
fun clockTime(instant: Instant?): String = instant?.atZone(ZoneId.of("Europe/Berlin"))
    ?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "–"
fun shortName(name: String): String = name.replace(Regex("^(Mönchengladbach[, ]+|Mönchengl\\.?[, ]+|MG )"), "")

object DemoRide {
    fun create(now: Instant = Instant.now()): Ride {
        val names = listOf("Hbf /Europaplatz", "Vitusbad", "Prinzenstraße", "Jahnhalle", "Anton-Kreitz-Straße", "Volksgartenstraße", "Hardterbroich Markt")
        val offsets = listOf(-2, 2, 3, 5, 7, 9, 12)
        val stops = names.mapIndexed { index, name ->
            val time = now.plusSeconds(offsets[index] * 60L)
            RideStop("demo:$index", name, null, time, time)
        }
        return Ride(Departure("demo", "006", "Hardterbroich Markt", "demo:0", names[0], "demo",
            stops.first().planned!!, null, "6"), stops, 0, now, demo = true)
    }
}
