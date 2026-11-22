package de.luca.mitfahrt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    suspend fun search(query: String, bias: GeoPoint? = null): List<Stop> {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return emptyList()
        val mgName = cleaned.replace(Regex("^Mönchengladbach[, ]*", RegexOption.IGNORE_CASE), "").trim()
        val anchor = bias?.takeIf(Geo::valid) ?: PlaceGeocoder.DEFAULT_BIAS
        val (localStops, regionalStops) = coroutineScope {
            val mgJob = async {
                runCatching {
                    EfaParser.stops(requestRaw("XML_STOPFINDER_REQUEST", mapOf(
                        "locationServerActive" to "1", "type_sf" to "stop", "place_sf" to "Mönchengladbach", "name_sf" to mgName,
                        "anyObjFilter_sf" to "2", "reducedAnyPostcodeObjFilter_sf" to "64"
                    )))
                }.getOrDefault(emptyList())
            }
            val wideJob = async {
                runCatching {
                    EfaParser.stops(requestRaw("XML_STOPFINDER_REQUEST", mapOf(
                        "locationServerActive" to "1", "type_sf" to "stop", "name_sf" to cleaned,
                        "anyObjFilter_sf" to "2", "anyMaxSizeHitList" to "20"
                    )))
                }.getOrDefault(emptyList())
            }
            mgJob.await() to wideJob.await()
        }
        return (localStops + regionalStops)
            .distinctBy { stopKey(it.id) }
            .sortedBy { stop ->
                val distKm = stop.point?.takeIf(Geo::valid)?.let { anchor.distanceTo(it) / 1000.0 } ?: 50.0
                val mgBonus = if (stop.name.contains("Mönchengl", true) || stop.name.startsWith("MG ", true)) -15.0 else 0.0
                distKm + mgBonus
            }
            .take(20)
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

    suspend fun offlineTimetable(stop: Stop, day: java.time.LocalDate): OfflineBoard {
        val zone = OfflineBoards.zone
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        var cursor = day.atStartOfDay(zone).toInstant()
        val rows = linkedMapOf<String, OfflineDeparture>()
        repeat(16) {
            val local = cursor.atZone(zone)
            val root = request("XML_DM_REQUEST", mapOf(
                "type_dm" to "stop", "name_dm" to stop.id, "mode" to "direct", "limit" to "250",
                "itdDate" to local.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE),
                "itdTime" to local.format(DateTimeFormatter.ofPattern("HHmm")), "useRealtime" to "0"
            ))
            val events = root.optJSONArray("stopEvents")?.objects().orEmpty()
            val last = events.mapNotNull { it.instant("departureTimePlanned") }.maxOrNull()
            EfaParser.departures(root).filter { it.planned.atZone(zone).toLocalDate() == day }.forEach { departure ->
                rows[departure.key] = OfflineDeparture(departure.line, departure.destination, departure.planned, departure.platform)
            }
            if (last == null || !last.isBefore(end)) {
                return OfflineBoard(day, java.time.Instant.now(), rows.values.sortedBy { it.time }, true)
            }
            val next = last.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
            if (next <= cursor)
                return OfflineBoard(day, java.time.Instant.now(), rows.values.sortedBy { it.time }, false)
            cursor = next
        }
        return OfflineBoard(day, java.time.Instant.now(), rows.values.sortedBy { it.time }, false)
    }

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
        val coords = EfaShapeParser.coords(request("XML_STOPSEQCOORD_REQUEST", tripParameters(departure)), departure)
        if (coords != null) synchronized(tripCoords) { tripCoords[departure.key] = coords }
        return coords
    }

    /** Platforms of a station with VRR's public platform coordinates, from its departure board. */
    suspend fun platforms(stopId: String): StationPlatforms = StationPlatformParser.parse(request("XML_DM_REQUEST", mapOf(
        "type_dm" to "stop", "name_dm" to stopKey(stopId), "mode" to "direct", "limit" to "120", "useRealtime" to "1"
    )), stopId, java.time.Instant.now())

    /** NEW lines with this number, one entry per direction. */
    suspend fun linesByNumber(number: String): List<NetworkLine> {
        val clean = number.trim().take(8)
        require(clean.isNotEmpty())
        // EFA searches Germany-wide; only NEW lines of Mönchengladbach are kept.
        val padded = clean.toIntOrNull()?.let { "%03d".format(it) } ?: clean
        return NetworkParser.lines(requestRaw("XML_SERVINGLINES_REQUEST", mapOf(
            "mode" to "line", "lineName" to padded, "lineReqType" to "1", "mergeDir" to "0", "withoutTrains" to "1"
        ))).filter { it.number.equals(padded, true) || it.number.equals(clean, true) }
    }

    /** NEW lines serving a stop, one entry per direction. */
    suspend fun servingLines(stopId: String): List<NetworkLine> = NetworkParser.lines(requestRaw("XML_SERVINGLINES_REQUEST", mapOf(
        "mode" to "odv", "type_sl" to "stopID", "name_sl" to stopKey(stopId), "lineReqType" to "1", "mergeDir" to "0", "withoutTrains" to "1"
    )))

    /** Stop sequence of one line direction. Platforms are reduced to their stops. */
    suspend fun lineStops(lineId: String): List<LineStop> = NetworkParser.stops(request("XML_LINESTOP_REQUEST", mapOf(
        "line" to lineId, "allStopInfo" to "1", "lineReqType" to "1"
    )))

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

    /**
     * Google-Maps-style place search: queries VRR StopFinder (stops, stations, addresses) and
     * OpenStreetMap Photon (businesses, shops, restaurants, exact house numbers) in parallel.
     */
    suspend fun places(text: String, bias: GeoPoint? = null): List<TripPlace> {
        if (text.isBlank()) return emptyList()
        return coroutineScope {
            val vrrJob = async {
                runCatching { TripParser.places(requestRaw("XML_STOPFINDER_REQUEST", TripRequest.placeSearch(text))) }
            }
            val geoJob = async {
                runCatching { PlaceGeocoder.search(text, bias) }.getOrDefault(emptyList())
            }
            val vrrResult = vrrJob.await()
            val geocoded = geoJob.await()
            if (vrrResult.isFailure && geocoded.isEmpty()) throw vrrResult.exceptionOrNull()!!
            PlaceGeocoder.merge(text, vrrResult.getOrDefault(emptyList()), geocoded, bias)
        }
    }

    suspend fun plannerStops(text: String): List<Stop> =
        TripParser.places(requestRaw("XML_STOPFINDER_REQUEST", TripRequest.placeSearch(text)))
            .filter { it.kind == PlaceKind.STOP && it.id.isNotBlank() }
            .map { Stop(it.id, it.name, it.point) }
            .distinctBy { stopKey(it.id) }

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
