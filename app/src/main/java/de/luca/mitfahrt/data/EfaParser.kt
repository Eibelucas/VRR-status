package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty()
internal fun JSONObject.instant(key: String): Instant? = str(key).takeIf { it.isNotBlank() }?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}
internal fun canonicalStopId(value: String): String? = stopKey(value.trim()).takeIf { id ->
    id.startsWith("de:") && id.split(':').let { parts -> parts.size == 3 && parts.all { it.isNotBlank() && it.none(Char::isWhitespace) } }
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

    /** Resolve only identities that the backend explicitly connects; names and ID digits are not aliases. */
    fun resolveStop(root: JSONObject, original: Stop): Stop {
        val requested = original.id.trim()
        require(requested.isNotBlank()) { "Die Haltestelle hat keine gültige Kennung. Bitte erneut auswählen." }
        val requestedCanonical = canonicalStopId(requested)
        fun sameId(value: String): Boolean = value.trim() == requested ||
            (requestedCanonical != null && canonicalStopId(value) == requestedCanonical)
        fun explicitMatch(obj: JSONObject): Boolean = sameId(obj.str("id")) ||
            obj.optJSONObject("properties")?.str("stopId")?.let(::sameId) == true
        fun isStop(obj: JSONObject): Boolean = obj.str("type").let { it.isEmpty() || it == "stop" }

        val locations = root.optJSONArray("locations")?.objects().orEmpty().filter { it.str("type") == "stop" }
        val nodes = mutableListOf<JSONObject>()
        val matches = linkedSetOf<String>()
        locations.forEach { location ->
            nodes.add(location)
            val canonical = canonicalStopId(location.str("id"))
            if (canonical != null && explicitMatch(location)) matches.add(canonical)
            location.optJSONArray("assignedStops")?.objects().orEmpty().filter(::isStop).forEach { assigned ->
                nodes.add(assigned)
                if (explicitMatch(assigned)) {
                    val assignedCanonical = canonicalStopId(assigned.str("id"))
                    if (assignedCanonical != null) matches.add(assignedCanonical)
                    else if (canonical != null && assigned.optJSONObject("properties")?.str("stopId")?.let(::sameId) == true)
                        matches.add(canonical)
                }
            }
        }
        require(matches.isNotEmpty()) {
            "Die Haltestelle konnte vom VRR nicht zugeordnet werden. Bitte erneut suchen und auswählen."
        }
        require(matches.size == 1) {
            "Der VRR liefert mehrere passende Haltestellen. Bitte die Haltestelle erneut auswählen."
        }
        val id = matches.single()
        val matchingNodes = nodes.filter { canonicalStopId(it.str("id")) == id }
        val name = matchingNodes.firstNotNullOfOrNull { it.str("name").takeIf(String::isNotBlank) }
            ?: original.name
        val resolvedPoint = matchingNodes.firstNotNullOfOrNull(::point) ?: original.point?.takeIf {
            it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0
        }
        return Stop(id, name, resolvedPoint)
    }

    /**
     * Departure board. [newOnly] keeps the NEW focus of stop boards and suggestions; tracking a
     * leg of a planned journey (train, other operator) reads the same board without that filter.
     */
    fun departures(root: JSONObject, observedAt: Instant = Instant.now(), newOnly: Boolean = true): List<Departure> = root.optJSONArray("stopEvents")?.objects().orEmpty()
        .mapNotNull { event ->
            val transport = event.optJSONObject("transportation") ?: return@mapNotNull null
            val operator = transport.optJSONObject("operator")
            val isNew = operator?.str("code")?.uppercase() == "NEW" || transport.str("id").startsWith("new:")
            // Do not suggest trains or a different operator just because it serves the same stop.
            if (newOnly && !isNew) return@mapNotNull null
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
                isCancelled(event), infos, observedAt, if (isNew) "" else operator?.str("code").orEmpty().ifBlank { "?" })
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
