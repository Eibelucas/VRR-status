package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class WatchDataState { LIVE, OFFLINE, EXPIRED }

/** How usable the last phone snapshot still is when the phone does not answer. */
object WatchFreshness {
    /** A stored snapshot is shown for this long, clearly as an old state, then hidden. */
    const val USABLE_MINUTES = 30L

    fun state(ride: WatchRide, now: Instant): WatchDataState {
        if (!ride.stale(now)) return WatchDataState.LIVE
        val age = Duration.between(ride.sentAt, now).toMinutes()
        val over = (if (ride.boarded) ride.terminalAt else ride.boardingAt)?.let { now.isAfter(it.plusSeconds(15 * 60)) } == true
        return if (age in 0..USABLE_MINUTES && !over) WatchDataState.OFFLINE else WatchDataState.EXPIRED
    }

    fun label(ride: WatchRide, now: Instant): String = when (state(ride, now)) {
        WatchDataState.LIVE -> ""
        WatchDataState.OFFLINE -> "Offline · Stand ${clock(ride.fetchedAt ?: ride.sentAt)}"
        WatchDataState.EXPIRED -> "Letzter Stand zu alt"
    }

    fun clock(at: Instant): String = at.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("HH:mm"))
}

/** The tracked departure's identity, sent by the phone in the companion settings. */
data class DepartureIdentity(val stopId: String, val lineId: String, val tripCode: String, val planned: Instant) {
    companion object {
        fun from(settings: Map<String, String>): DepartureIdentity? {
            val stop = settings["ride_stop_id"].orEmpty(); val line = settings["ride_line_id"].orEmpty()
            val trip = settings["ride_trip_code"].orEmpty()
            val planned = settings["ride_planned"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            if (stop.isBlank() || line.isBlank() || trip.isBlank() || stop.length > 64 || line.length > 64 || trip.length > 32) return null
            return DepartureIdentity(stop, line, trip, planned)
        }
    }
}

data class DirectDeparture(val at: Instant, val live: Boolean, val cancelled: Boolean, val fetchedAt: Instant)

/**
 * One explicit VRR request from the watch's own connection for exactly the tracked departure.
 * Only an event with the same line, trip and planned time is used; anything else is no result.
 */
object DirectVrr {
    fun parse(root: JSONObject, identity: DepartureIdentity, fetchedAt: Instant): DirectDeparture? {
        val events = root.optJSONArray("stopEvents") ?: return null
        val matches = (0 until events.length()).mapNotNull { events.optJSONObject(it) }.filter { event ->
            val transport = event.optJSONObject("transportation") ?: return@filter false
            transport.optString("id") == identity.lineId &&
                transport.optJSONObject("properties")?.optString("tripCode") == identity.tripCode &&
                runCatching { Instant.parse(event.optString("departureTimePlanned")) }.getOrNull() == identity.planned
        }
        val event = matches.singleOrNull() ?: return null
        val estimated = event.optString("departureTimeEstimated").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val status = event.optString("realtimeStatus") + event.optJSONArray("realtimeStatus")?.toString().orEmpty()
        val cancelled = event.optBoolean("isCancelled") || status.contains("CANCELLED") || status.contains("DELETED")
        return DirectDeparture(estimated ?: identity.planned, estimated != null, cancelled, fetchedAt)
    }

    suspend fun fetch(identity: DepartureIdentity): DirectDeparture? = withContext(Dispatchers.IO) {
        val params = mapOf("outputFormat" to "rapidJSON", "language" to "de", "type_dm" to "stop", "name_dm" to identity.stopId,
            "mode" to "direct", "limit" to "40", "useRealtime" to "1",
            "itdDate" to identity.planned.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            "itdTime" to identity.planned.minusSeconds(120).atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("HHmm")))
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URI("https://openservice.vrr.de/static03/XML_DM_REQUEST?$query").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder(); val buffer = CharArray(8192)
                while (true) { val n = reader.read(buffer); if (n < 0) break; text.append(buffer, 0, n); require(text.length <= 1_500_000) }
                text.toString()
            }
            parse(JSONObject(body), identity, Instant.now())
        } finally { connection.disconnect() }
    }
}

/** A route change started on the watch is shown as done only when the phone reports the new journey. */
object WatchStartFlow {
    enum class Result { WAITING, DONE, FAILED }

    fun resolve(previousJourney: String, currentJourney: String, feedbackArrived: Boolean): Result = when {
        currentJourney.isNotEmpty() && currentJourney != previousJourney -> Result.DONE
        feedbackArrived -> Result.FAILED
        else -> Result.WAITING
    }
}

/** What a complication shows: the tracked ride first, otherwise the departure chosen for the tile. */
data class ComplicationFace(val title: String, val value: String, val description: String,
    val countdownTo: Instant?, val validUntil: Instant?, val longValue: String? = null) {
    companion object {
        fun of(ride: WatchRide?, tile: de.luca.mitfahrt.shared.WatchTile?, now: Instant): ComplicationFace {
            val current = ride?.takeIf { it.active }
            if (current != null) {
                val state = WatchFreshness.state(current, now)
                val target = if (current.boarded) current.terminalAt else current.boardingAt
                val title = current.line.take(7).ifBlank { "Bus" }
                return when {
                    current.cancelled -> ComplicationFace(title, "Ausfall", "Linie ${current.line} fällt aus", null, null)
                    state == WatchDataState.EXPIRED || target == null ->
                        ComplicationFace(title, "–", "Mitfahrt · keine aktuelle Zeit", null, null)
                    state == WatchDataState.OFFLINE -> ComplicationFace("Stand", WatchFreshness.clock(current.fetchedAt ?: current.sentAt),
                        "Offline · letzter Stand ${WatchFreshness.clock(current.fetchedAt ?: current.sentAt)}", null,
                        current.sentAt.plusSeconds(WatchFreshness.USABLE_MINUTES * 60))
                    // Live data: count down until two minutes after the source time, then fall back.
                    else -> ComplicationFace(title, WatchFreshness.clock(target),
                        "Linie ${current.line} · ${if (current.boarded) "Ankunft" else "Abfahrt"} ${WatchFreshness.clock(target)}",
                        target, listOfNotNull(current.sentAt, current.fetchedAt).minOrNull()?.plusSeconds(120),
                        "${current.line} · ${WatchFreshness.clock(target)}")
                }
            }
            val chosen = tile?.takeIf { now.epochSecond - it.fetchedAt.epochSecond in -60..120 }
            val at = chosen?.at?.takeIf { it.isAfter(now) }
            if (chosen != null && at != null) return ComplicationFace(chosen.line.take(7).ifBlank { "Bus" }, WatchFreshness.clock(at),
                "${chosen.title} · ${chosen.line} ${if (chosen.live) "live" else "laut Fahrplan"} ${WatchFreshness.clock(at)}",
                at.takeIf { chosen.live }, chosen.fetchedAt.plusSeconds(120), "${chosen.line} · ${WatchFreshness.clock(at)}")
            return ComplicationFace("Mitfahrt", "–", "Mitfahrt · keine Fahrt", null, null)
        }
    }
}
