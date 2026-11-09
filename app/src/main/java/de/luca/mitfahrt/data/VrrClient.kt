package de.luca.mitfahrt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import de.luca.mitfahrt.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class VrrClient {
    private val tripCoords = object : LinkedHashMap<String, List<GeoPoint>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<GeoPoint>>?): Boolean = size > 8
    }

    suspend fun search(query: String): List<Stop> {
        val name = query.replace(Regex("^Mönchengladbach[, ]*", RegexOption.IGNORE_CASE), "").trim()
        return EfaParser.stops(request("XML_STOPFINDER_REQUEST", mapOf(
            "locationServerActive" to "1", "type_sf" to "stop", "place_sf" to "Mönchengladbach", "name_sf" to name,
            "anyObjFilter_sf" to "2", "reducedAnyPostcodeObjFilter_sf" to "64"
        )))
    }

    suspend fun resolveStop(stop: Stop): Stop {
        val canonical = canonicalStopId(stop.id)
        val point = stop.point?.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        if (canonical != null && point != null) return stop.copy(id = canonical, point = point, distance = null)
        require(stop.id.isNotBlank()) { "Die Haltestelle hat keine gültige Kennung. Bitte erneut auswählen." }
        return EfaParser.resolveStop(request("XML_STOPFINDER_REQUEST", mapOf(
            "locationServerActive" to "1", "type_sf" to "stop", "name_sf" to stop.id.trim(),
            "anyObjFilter_sf" to "2", "reducedAnyPostcodeObjFilter_sf" to "64"
        )), stop)
    }

    suspend fun nearby(point: GeoPoint): List<Stop> = nearbyWithin(point, 700)

    suspend fun nearbyWithin(point: GeoPoint, radius: Int): List<Stop> = EfaParser.stops(request("XML_COORD_REQUEST", mapOf(
        "coord" to "${point.lon}:${point.lat}:WGS84[dd.ddddd]", "inclFilter" to "1",
        "type_1" to "STOP", "radius_1" to radius.coerceIn(100, 3000).toString(), "max" to "30"
    ))).sortedBy { it.distance ?: Int.MAX_VALUE }

    suspend fun departures(stop: Stop, newOnly: Boolean = true): List<Departure> = EfaParser.departures(request("XML_DM_REQUEST", mapOf(
        "type_dm" to "stop", "name_dm" to stop.id, "mode" to "direct", "limit" to "80",
        "timeOffset" to "-5", "useRealtime" to "1"
    )), newOnly = newOnly)

    suspend fun ride(departure: Departure): Ride {
        val root = request("XML_TRIPSTOPTIMES_REQUEST", tripParameters(departure))
        val ride = EfaParser.ride(root, departure)
        // VRR may include the trip's path as leg.coords; keep it for the map, never stop points.
        EfaShapeParser.coords(root, departure)?.let { synchronized(tripCoords) { tripCoords[departure.key] = it } }
        return ride
    }

    /**
     * Path of exactly this trip, if VRR publishes one. Uses the coordinates of the last trip
     * response, otherwise one stop-sequence request. Returns null when VRR has no path.
     */
    suspend fun tripPath(departure: Departure): List<GeoPoint>? {
        synchronized(tripCoords) { tripCoords[departure.key] }?.let { return it }
        return EfaShapeParser.coords(request("XML_STOPSEQCOORD_REQUEST", tripParameters(departure)), departure)
    }

    /**
     * Journey planning (XML_TRIP_REQUEST2). No operator filter: trains, trams and other buses
     * belong to a journey. An answer without journeys is an empty result, not an invented one.
     */
    suspend fun trips(query: TripQuery, page: TripPage, now: java.time.Instant = java.time.Instant.now()): List<TripJourney> {
        val parameters = TripRequest.parameters(query, page, now)
            ?: throw TripSearchException("Dein Standort ist gerade nicht bekannt. Wähle einen Start aus.")
        val root = requestRaw("XML_TRIP_REQUEST2", parameters)
        return TripParser.journeys(root, java.time.Instant.now())
    }

    /** Stops, addresses, streets, places and POIs as far as VRR resolves them. */
    suspend fun places(text: String): List<TripPlace> {
        if (text.isBlank()) return emptyList()
        return TripParser.places(requestRaw("XML_STOPFINDER_REQUEST", TripRequest.placeSearch(text)))
    }

    private fun tripParameters(departure: Departure): Map<String, String> {
        val date = departure.planned.atZone(ZoneId.of("Europe/Berlin"))
        return mapOf(
            "line" to departure.lineId, "stopID" to departure.stopId, "tripCode" to departure.tripCode,
            "date" to date.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            "time" to date.format(DateTimeFormatter.ofPattern("HHmm")),
            "tStOTType" to "ALL", "useRealtime" to "1"
        )
    }

    private suspend fun request(endpoint: String, parameters: Map<String, String>): JSONObject {
        val root = requestRaw(endpoint, parameters)
        val errors = root.optJSONArray("systemMessages")?.objects().orEmpty().filter { it.str("type") == "error" }
        check(errors.isEmpty()) { "Die VRR-Abfrage konnte nicht verarbeitet werden. Bitte erneut versuchen." }
        return root
    }

    /** EFA reports "nothing found" as an error message; callers that can tell empty from broken use this. */
    private suspend fun requestRaw(endpoint: String, parameters: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val params = mapOf("outputFormat" to "rapidJSON", "language" to "de", "coordOutputFormat" to "WGS84[dd.ddddd]") + parameters
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val conn = URI("https://openservice.vrr.de/static03/$endpoint?$query").toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 18_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Mitfahrt-Android/${BuildConfig.VERSION_NAME}")
            check(conn.responseCode == 200) { "VRR ist gerade nicht erreichbar (${conn.responseCode})." }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } finally { conn.disconnect() }
    }
}
