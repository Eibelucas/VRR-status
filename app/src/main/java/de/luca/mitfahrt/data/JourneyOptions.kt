package de.luca.mitfahrt.data

import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/** An estimate from the stop's air distance, not a routed pedestrian itinerary. */
data class WalkingEstimate(val distanceMeters: Int, val seconds: Long, val boardingBufferSeconds: Long = 60) {
    val minutes: Int get() = ((seconds + 59) / 60).toInt()
    val requiredSeconds: Long get() = seconds + boardingBufferSeconds
    val label: String get() = "ca. $minutes Min zu Fuß"
}

enum class ReachabilityState { POSSIBLE, TIGHT, TOO_LATE, UNKNOWN, STALE, CANCELLED, DEPARTED }

data class Reachability(val state: ReachabilityState, val walking: WalkingEstimate?, val secondsUntilDeparture: Long?) {
    /** True only when walking plus a boarding buffer fits inside a fresh forecast. */
    val isPossible: Boolean get() = state == ReachabilityState.POSSIBLE
    val label: String get() = when (state) {
        ReachabilityState.POSSIBLE -> "Voraussichtlich erreichbar"
        ReachabilityState.TIGHT -> "Wird knapp"
        ReachabilityState.TOO_LATE -> "Zu spät zu Fuß"
        ReachabilityState.UNKNOWN -> "Gehzeit nicht verfügbar"
        ReachabilityState.STALE -> "Zeitdaten veraltet"
        ReachabilityState.CANCELLED -> "Fällt aus"
        ReachabilityState.DEPARTED -> "Abfahrt vorbei"
    }
}

data class JourneyAlternative(val stop: Stop, val departure: Departure, val reachability: Reachability,
    val isSameStop: Boolean)

object JourneyOptions {
    /** 35% detour allowance, 1.2 m/s walking speed and a separate one-minute buffer. */
    fun walking(stop: Stop): WalkingEstimate? = stop.distance?.takeIf { it >= 0 }?.let {
        WalkingEstimate(it, ceil(it.toDouble() * 1.35 / 1.2).toLong())
    }

    fun reachability(stop: Stop, departure: Departure, now: Instant): Reachability {
        val walk = walking(stop)
        val remaining = departure.effective.epochSecond - now.epochSecond
        val state = when {
            departure.cancelled -> ReachabilityState.CANCELLED
            !fresh(departure, now) -> ReachabilityState.STALE
            remaining <= 0 -> ReachabilityState.DEPARTED
            walk == null -> ReachabilityState.UNKNOWN
            remaining >= walk.requiredSeconds -> ReachabilityState.POSSIBLE
            remaining >= walk.seconds -> ReachabilityState.TIGHT
            else -> ReachabilityState.TOO_LATE
        }
        return Reachability(state, walk, remaining)
    }

    /**
     * Direct alternatives with the same advertised destination. This does not assert an
     * earlier arrival or that an alternative serves every intermediate stop of the old bus.
     * Pass only nearby stops from a current location lookup. Without a distance, only the
     * original station is retained, explicitly labelled UNKNOWN instead of reachable.
     */
    fun alternatives(selected: Departure, nearbyStops: List<Stop>, departures: List<Departure>, now: Instant): List<JourneyAlternative> {
        val destination = destinationKey(selected.destination)
        if (destination.isBlank()) return emptyList()
        val nearby = nearbyStops.groupBy { stopKey(it.id) }.mapValues { (_, variants) ->
            variants.minBy { it.distance?.takeIf { distance -> distance >= 0 } ?: Int.MAX_VALUE }
        }
        val originalId = stopKey(selected.stopId)
        val ranked = departures.asSequence()
            .filter { !sameJourney(it, selected) && destinationKey(it.destination) == destination }
            .mapNotNull { departure ->
                val id = stopKey(departure.stopId)
                val sameStop = id == originalId
                val stop = nearby[id] ?: if (sameStop) Stop(originalId, selected.stopName) else return@mapNotNull null
                val reachability = reachability(stop, departure, now)
                if (reachability.isPossible || sameStop && reachability.state == ReachabilityState.UNKNOWN)
                    JourneyAlternative(stop, departure, reachability, sameStop)
                else null
            }
            .sortedWith(compareBy<JourneyAlternative> { !it.reachability.isPossible }
                .thenBy { it.departure.effective }
                .thenBy { it.stop.distance ?: Int.MAX_VALUE })
            .toList()
        val result = mutableListOf<JourneyAlternative>()
        for (choice in ranked) {
            if (result.none { sameJourney(it.departure, choice.departure) }) result += choice
            if (result.size == 4) break
        }
        return result
    }

    fun destinationKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim()
        .replace(Regex("^(mönchengladbach|mönchengl\\.?)[,\\s]+|^mg\\s+"), "")
        .replace(Regex("[\\s\\p{Z}]+"), " ")
        .replace(Regex("\\s*/\\s*"), "/").trim()

    private fun fresh(departure: Departure, now: Instant): Boolean =
        departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true

    private fun sameJourney(a: Departure, b: Departure): Boolean = a.key == b.key ||
        a.tripCode.isNotBlank() && a.tripCode == b.tripCode && a.lineId == b.lineId &&
            abs(a.planned.epochSecond - b.planned.epochSecond) <= 6 * 3600
}
