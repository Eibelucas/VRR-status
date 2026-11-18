package de.luca.mitfahrt.data

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** Local preferences applied to returned connections. Raw results still provide pagination cursors. */
object TripResultPreferences {
    fun lineKey(line: String): String = line.trim().uppercase(Locale.ROOT).replace(" ", "")
        .let { if (it.isNotEmpty() && it.all(Char::isDigit)) it.trimStart('0').ifEmpty { "0" } else it }

    fun lines(text: String): Set<String> = text.split(',').map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 14 && it.all { char -> char.isLetterOrDigit() || char == ' ' || char == '-' } }
        .take(20).toSet()

    fun apply(journeys: List<TripJourney>, options: TripOptions, now: Instant): List<TripJourney> {
        val avoid = options.excludedStopIds.map(::stopKey).toSet()
        val preferred = options.preferredLines.map(::lineKey).toSet()
        return journeys.filter { journey ->
            journey.transit.none { leg -> stopKey(leg.origin.id) in avoid || stopKey(leg.destination.id) in avoid } &&
                hasTransferTime(journey, options.minimumTransferMinutes, now)
        }.let { visible ->
            if (preferred.isEmpty()) visible else visible.sortedByDescending { journey ->
                journey.transit.any { lineKey(it.line.ifBlank { it.lineName }) in preferred }
            }
        }
    }

    private fun hasTransferTime(journey: TripJourney, minutes: Int, now: Instant): Boolean {
        if (minutes <= 0) return true
        val fresh = now.epochSecond - journey.fetchedAt.epochSecond in -30..120
        return journey.legs.withIndex().filter { it.value.kind == LegKind.TRANSIT }.zipWithNext()
            .none { (incoming, outgoing) ->
                val liveArrival = incoming.value.destination.estimatedArrival
                val liveDeparture = outgoing.value.origin.estimatedDeparture
                // Compare like with like. A single prediction cannot verify the transfer.
                val arrival = if (fresh && liveDeparture != null && liveArrival != null) liveArrival else incoming.value.destination.plannedArrival
                val departure = if (fresh && liveDeparture != null && liveArrival != null) liveDeparture else outgoing.value.origin.plannedDeparture
                if (arrival == null || departure == null) return@none false
                val walk = journey.legs.subList(incoming.index + 1, outgoing.index)
                    .filter { it.kind == LegKind.WALK }.sumOf { it.durationSeconds }
                Duration.between(arrival, departure).seconds - walk < minutes * 60L
            }
    }
}

/** The user chooses real VRR stop identities; names are only labels for the editor. */
object ExcludedTripStops {
    fun decode(text: String): Map<String, String> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().filter { it.isNotBlank() && it.length <= 100 }
            .take(20).associateWith { json.optString(it).take(85) }
    }

    fun encode(stops: Map<String, String>): String = if (stops.isEmpty()) "" else JSONObject(stops).toString()
}
