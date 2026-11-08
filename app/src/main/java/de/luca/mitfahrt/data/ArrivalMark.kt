package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Local device observation, never a confirmation by the vehicle operator. */
data class ArrivalMark(val id: String, val stopId: String, val stopName: String, val at: Instant,
    val method: String = "manual") {
    fun json(): JSONObject = JSONObject().apply {
        put("id", id); put("stopId", stopId); put("stopName", stopName); put("at", at.toString()); put("method", method)
    }

    companion object {
        fun create(stop: Stop, now: Instant = Instant.now()) = ArrivalMark(UUID.randomUUID().toString(), stop.id, stop.name, now)
        fun decode(j: JSONObject) = ArrivalMark(j.getString("id"), j.getString("stopId"), j.getString("stopName"), Instant.parse(j.getString("at")),
            j.optString("method", "manual").takeIf { it == "manual" || it == "location" } ?: "manual")
        fun decodeList(value: JSONArray?): List<ArrivalMark> = value?.objects().orEmpty()
            .mapNotNull { runCatching { decode(it) }.getOrNull() }

        fun forRide(marks: List<ArrivalMark>, ride: Ride, createdAt: Instant): List<ArrivalMark> {
            if (ride.demo) return emptyList()
            val stopIds = ride.stops.drop(ride.boardingIndex).map { stopKey(it.id) }.toSet()
            val from = ride.departure.planned.minusSeconds(10_800)
            val until = (ride.terminal.effective(ride.offsetSeconds) ?: ride.departure.planned).plusSeconds(10_800)
            return marks.filter { stopKey(it.stopId) in stopIds && !it.at.isBefore(from) &&
                !it.at.isAfter(until) && !it.at.isAfter(createdAt) }
                .groupBy { stopKey(it.stopId) }.values.map { entries -> entries.maxBy { it.at } }.sortedBy { it.at }
        }
    }
}

fun stopKey(id: String): String = if (id.startsWith("de:")) id.split(':').take(3).joinToString(":") else id
