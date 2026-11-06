package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty()
internal fun JSONObject.instant(key: String): Instant? = str(key).takeIf { it.isNotBlank() }?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

object EfaParser {
    private fun point(obj: JSONObject): GeoPoint? {
        val array = obj.optJSONArray("coord") ?: return null
        val lat = array.optDouble(0, Double.NaN)
        val lon = array.optDouble(1, Double.NaN)
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
    }

    fun stops(root: JSONObject): List<Stop> = root.optJSONArray("locations")?.objects().orEmpty()
        .filter { it.str("type") == "stop" }
        .mapNotNull { obj ->
            val id = obj.str("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val props = obj.optJSONObject("properties")
            Stop(id, obj.str("name"), point(obj), props?.takeIf { it.has("distance") }?.optInt("distance"))
        }.distinctBy { it.id }

    fun departures(root: JSONObject, observedAt: Instant = Instant.now()): List<Departure> = root.optJSONArray("stopEvents")?.objects().orEmpty()
        .mapNotNull { event ->
            val transport = event.optJSONObject("transportation") ?: return@mapNotNull null
            val operator = transport.optJSONObject("operator")
            // Do not suggest trains or a different operator just because it serves the same stop.
            if (operator?.str("code")?.uppercase() != "NEW" &&
                !transport.str("id").startsWith("new:")) return@mapNotNull null
            val planned = event.instant("departureTimePlanned") ?: return@mapNotNull null
            val location = event.optJSONObject("location") ?: return@mapNotNull null
            val tripCode = transport.optJSONObject("properties")?.str("tripCode").orEmpty()
            if (tripCode.isBlank()) return@mapNotNull null
            val infos = event.optJSONArray("infos")?.objects().orEmpty().flatMap { info ->
                info.optJSONArray("infoLinks")?.objects().orEmpty().map { it.str("subtitle").ifBlank { it.str("content") } }
            }.filter { it.isNotBlank() }
            Departure(transport.str("id"), transport.str("number").ifBlank { transport.str("disassembledName") },
                transport.optJSONObject("destination")?.str("name").orEmpty(), location.str("id"),
                location.str("name"), tripCode, planned, event.instant("departureTimeEstimated"),
                location.optJSONObject("properties")?.str("platformName").orEmpty(),
                isCancelled(event), infos, observedAt)
        }.distinctBy { it.key }.sortedBy { it.effective }

    fun ride(root: JSONObject, departure: Departure, now: Instant = Instant.now()): Ride {
        val leg = root.optJSONObject("leg") ?: error("Der VRR liefert keinen Fahrtverlauf. Bitte eine andere Fahrt wählen.")
        val transport = leg.optJSONObject("transportation")
        require(transport?.str("id") == departure.lineId &&
            transport.optJSONObject("properties")?.str("tripCode") == departure.tripCode) {
            "Der gelieferte Fahrtverlauf passt nicht zur gewählten Fahrt."
        }
        val stops = leg.optJSONArray("stopSequence")?.objects().orEmpty().map { obj ->
            val parent = obj.optJSONObject("parent")
            val name = if (parent?.str("type") == "stop") parent.str("name") else obj.str("name")
            RideStop(obj.str("id"), name, point(obj), obj.instant("arrivalTimePlanned"),
                obj.instant("departureTimePlanned"), obj.instant("arrivalTimeEstimated"),
                obj.instant("departureTimeEstimated"), isCancelled(obj))
        }
        require(stops.size >= 2) { "Für diese Fahrt ist kein vollständiger Verlauf verfügbar." }
        // Match the exact platform and departure, especially for routes that visit a stop twice.
        val boarding = stops.indices.filter { stops[it].id == departure.stopId }.minByOrNull {
            kotlin.math.abs((stops[it].departure ?: stops[it].arrival ?: Instant.EPOCH).epochSecond - departure.planned.epochSecond)
        } ?: error("Die Einstiegshaltestelle fehlt im Fahrtverlauf.")
        require(kotlin.math.abs((stops[boarding].departure ?: Instant.EPOCH).epochSecond - departure.planned.epochSecond) <= 120) {
            "Der Fahrtverlauf gehört zu einer anderen Abfahrt."
        }
        return Ride(departure, stops, boarding, now)
    }

    private fun isCancelled(obj: JSONObject): Boolean = obj.optBoolean("isCancelled", false) ||
        obj.optJSONArray("realtimeStatus")?.let { array -> (0 until array.length()).any {
            array.optString(it).uppercase() in setOf("CANCELLED", "CANCELED", "DELETED", "NOT_SERVICED")
        } } == true
}
