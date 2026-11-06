package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs

data class ReportedTiming(val planned: Instant, val estimated: Instant,
    val observedAt: Instant?, val event: String, val fromMonitor: Boolean) {
    val delaySeconds: Long get() = estimated.epochSecond - planned.epochSecond
    val delayMinutes: Int get() = (delaySeconds / 60).toInt()
    val shortDelay: String get() = when {
        delaySeconds in 1..59 -> "Unter 1 Min Verspätung"
        delaySeconds > 0 -> "+$delayMinutes Min"
        delaySeconds in -59..-1 -> "Unter 1 Min früher"
        delaySeconds < 0 -> "${abs(delayMinutes)} Min früher"
        else -> "Pünktlich laut Prognose"
    }
}

/** Frozen provider estimates. Never substitute an app-extrapolated delay as provider evidence. */
data class DelayEvidence(
    val id: String, val createdAt: Instant, val observedAt: Instant?,
    val line: String, val destination: String, val stopName: String,
    val event: String, val planned: Instant, val estimated: Instant,
    val lineId: String, val tripCode: String, val serviceDate: String,
    val sourceUrl: String, val notices: List<String>, val cancelled: Boolean,
    val arrivalPlanned: Instant? = null, val arrivalEstimated: Instant? = null,
    val personalArrivals: List<ArrivalMark> = emptyList(),
    val arrivalObservedAt: Instant? = null, val arrivalSourceUrl: String? = null
) {
    val delaySeconds: Long get() = estimated.epochSecond - planned.epochSecond
    val delayMinutes: Int get() = (delaySeconds / 60).toInt()
    fun isOld(): Boolean = observedAt?.let { createdAt.epochSecond - it.epochSecond > 120 } ?: true
    val delayText: String get() {
        val seconds = abs(delaySeconds)
        if (seconds == 0L) return "Pünktlich laut Prognose"
        val parts = listOfNotNull((seconds / 60).takeIf { it > 0 }?.let { "$it Min" },
            (seconds % 60).takeIf { it > 0 }?.let { "$it Sek" }).joinToString(" ")
        return "$parts ${if (delaySeconds > 0) "Verspätung" else "früher"}"
    }

    fun encode(): String = JSONObject().apply {
        put("id", id); put("createdAt", createdAt.toString()); put("observedAt", observedAt?.toString())
        put("line", line); put("destination", destination); put("stopName", stopName); put("event", event)
        put("planned", planned.toString()); put("estimated", estimated.toString()); put("lineId", lineId)
        put("tripCode", tripCode); put("serviceDate", serviceDate); put("sourceUrl", sourceUrl)
        put("notices", JSONArray(notices)); put("cancelled", cancelled)
        put("arrivalPlanned", arrivalPlanned?.toString()); put("arrivalEstimated", arrivalEstimated?.toString())
        put("personalArrivals", JSONArray(personalArrivals.map { it.json() }))
        put("arrivalObservedAt", arrivalObservedAt?.toString()); put("arrivalSourceUrl", arrivalSourceUrl)
    }.toString()

    companion object {
        fun timingFor(ride: Ride, stopIndex: Int): ReportedTiming? {
            if (ride.demo || stopIndex !in ride.boardingIndex..ride.stops.lastIndex) return null
            val stop = ride.stops[stopIndex]
            val departure = stopIndex == ride.boardingIndex || stop.arrival == null ||
                (stop.estimatedArrival == null && stop.departure != null && stop.estimatedDeparture != null)
            val planned = (if (departure) stop.departure else stop.arrival) ?: return null
            val stopEstimate = if (departure) stop.estimatedDeparture else stop.estimatedArrival
            val fromMonitor = stopEstimate == null && stopIndex == ride.boardingIndex && departure
            val estimate = stopEstimate ?: (if (fromMonitor) ride.departure.estimated else null) ?: return null
            return ReportedTiming(planned, estimate, if (fromMonitor) ride.departure.observedAt else ride.fetchedAt,
                if (departure) "Abfahrt" else "Ankunft", fromMonitor)
        }

        fun fromRide(ride: Ride, stopIndex: Int, now: Instant = Instant.now(),
            personalArrivals: List<ArrivalMark> = emptyList()): DelayEvidence {
            require(!ride.demo) { "Aus einer Demofahrt kann kein Beleg erstellt werden." }
            require(stopIndex in ride.boardingIndex..ride.stops.lastIndex) { "Ungültige Haltestelle." }
            val stop = ride.stops[stopIndex]
            val d = ride.departure
            val timing = timingFor(ride, stopIndex)
            require(timing != null) { "Für diesen Halt gibt es keine gemeldete Echtzeitprognose. Wähle einen anderen Halt." }
            val fromMonitor = timing.fromMonitor
            val date = d.planned.atZone(ZoneId.of("Europe/Berlin"))
            val tripParams = mapOf("line" to d.lineId, "stopID" to d.stopId, "tripCode" to d.tripCode,
                    "date" to date.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                    "time" to date.format(DateTimeFormatter.ofPattern("HHmm")), "tStOTType" to "ALL")
            fun url(endpoint: String, params: Map<String, String>): String {
                val query = (mapOf("outputFormat" to "rapidJSON", "useRealtime" to "1") + params)
                    .entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
                return "https://openservice.vrr.de/static03/$endpoint?$query"
            }
            val tripUrl = url("XML_TRIPSTOPTIMES_REQUEST", tripParams)
            val sourceUrl = if (fromMonitor) url("XML_DM_REQUEST", mapOf("type_dm" to "stop", "name_dm" to d.stopId)) else tripUrl
            return DelayEvidence(UUID.randomUUID().toString(), now,
                timing.observedAt, d.line, d.destination, stop.name, timing.event,
                timing.planned, timing.estimated, d.lineId, d.tripCode,
                date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                sourceUrl, d.notices.toList(),
                d.cancelled || stop.cancelled, stop.arrival, stop.estimatedArrival,
                ArrivalMark.forRide(personalArrivals, ride, now),
                if (stop.estimatedArrival != null) ride.fetchedAt else null,
                if (stop.estimatedArrival != null) tripUrl else null)
        }

        fun decode(value: String): DelayEvidence {
            val j = JSONObject(value)
            return DelayEvidence(j.getString("id"), Instant.parse(j.getString("createdAt")),
                j.optString("observedAt").takeIf { it.isNotBlank() && it != "null" }?.let(Instant::parse),
                j.getString("line"), j.getString("destination"), j.getString("stopName"), j.getString("event"),
                Instant.parse(j.getString("planned")), Instant.parse(j.getString("estimated")), j.getString("lineId"),
                j.getString("tripCode"), j.getString("serviceDate"), j.getString("sourceUrl"),
                j.optJSONArray("notices")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                j.optBoolean("cancelled"), j.instant("arrivalPlanned"), j.instant("arrivalEstimated"),
                ArrivalMark.decodeList(j.optJSONArray("personalArrivals")), j.instant("arrivalObservedAt"),
                j.optString("arrivalSourceUrl").takeIf { it.isNotBlank() && it != "null" })
        }
    }
}
