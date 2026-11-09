package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * General journey planning with VRR EFA (XML_TRIP_REQUEST2 / XML_STOPFINDER_REQUEST).
 * Unlike the NEW departure boards this never filters by operator: trains, trams and other
 * operators' buses are part of a planned journey. See docs/JOURNEY_PLANNER.md.
 */

enum class PlaceKind { MY_LOCATION, STOP, ADDRESS, STREET, POI, LOCALITY, OTHER }

data class TripPlace(
    val kind: PlaceKind,
    /** EFA location id (stop id, street/poi id …); empty for the own position. */
    val id: String,
    val name: String,
    val locality: String = "",
    val point: GeoPoint? = null
) {
    val isMyLocation: Boolean get() = kind == PlaceKind.MY_LOCATION
    /** Stable identity for suggestions and history; never contains the own coordinates. */
    val key: String get() = if (isMyLocation) "me" else "${kind.name}:${id.ifBlank { name.lowercase(Locale.ROOT) }}"

    companion object {
        val MY_LOCATION = TripPlace(PlaceKind.MY_LOCATION, "", "Mein Standort")
        fun stop(stop: Stop) = TripPlace(PlaceKind.STOP, stop.id, stop.name, point = stop.point)
    }
}

enum class TripTimeMode { NOW, DEPART, ARRIVE }

enum class WalkSpeed(val efa: String, val label: String) {
    SLOW("slow", "Langsam"), NORMAL("normal", "Normal"), FAST("fast", "Schnell")
}

enum class RoutePreference(val efa: String, val label: String) {
    FASTEST("leasttime", "Schnellste"), FEWEST_CHANGES("leastinterchange", "Wenig Umstiege"),
    LEAST_WALKING("leastwalking", "Wenig Fußweg")
}

/** Groups of EFA means-of-transport ids (`exclMOT_<id>`). Unknown ids are never excluded. */
enum class ProductGroup(val label: String, val mots: List<Int>) {
    TRAIN("Bahn", listOf(0, 1, 13, 14, 15, 16)),
    LIGHT_RAIL("U-/Stadtbahn & Tram", listOf(2, 3, 4)),
    BUS("Bus", listOf(5, 6, 7, 19)),
    ON_DEMAND("Rufbus & AST", listOf(10)),
    OTHER("Sonstige", listOf(8, 9, 11));

    companion object {
        fun of(productClass: Int?): ProductGroup? = productClass?.let { id -> entries.firstOrNull { id in it.mots } }
    }
}

data class TripOptions(
    val excluded: Set<ProductGroup> = emptySet(),
    /** 0 = only direct connections, null = server default. */
    val maxChanges: Int? = null,
    val walkSpeed: WalkSpeed = WalkSpeed.NORMAL,
    /** Maximum walking time to/from stops in minutes; null = server default (10). */
    val maxWalkMinutes: Int? = null,
    val preference: RoutePreference = RoutePreference.FASTEST,
    val noSolidStairs: Boolean = false,
    val noEscalators: Boolean = false,
    val lowFloor: Boolean = false,
    val wheelchair: Boolean = false
) {
    val accessibility: Boolean get() = noSolidStairs || noEscalators || lowFloor || wheelchair
    val changedFromDefault: Boolean get() = this != TripOptions()

    companion object {
        val MAX_CHANGES = listOf(0, 1, 2, 3)
        val MAX_WALK = listOf(5, 10, 15, 20, 30)
    }
}

data class TripQuery(
    val origin: TripPlace,
    val destination: TripPlace,
    val via: TripPlace? = null,
    val mode: TripTimeMode = TripTimeMode.NOW,
    val at: Instant? = null,
    val options: TripOptions = TripOptions(),
    /** Resolved own position at search time when origin or destination is "Mein Standort". */
    val ownPosition: GeoPoint? = null
) {
    val needsOwnPosition: Boolean get() = origin.isMyLocation || destination.isMyLocation || via?.isMyLocation == true
    fun swapped(): TripQuery = copy(origin = destination, destination = origin)

    /** Identity for caching; the own position is rounded to ~100 m and not persisted anywhere. */
    fun key(): String = listOf(origin.key, destination.key, via?.key.orEmpty(), mode.name, at?.epochSecond?.div(60) ?: "",
        options.toString(), ownPosition?.let { "%.3f,%.3f".format(Locale.ROOT, it.lat, it.lon) }.orEmpty()).joinToString("|")
}

/** One request page: the base search or an earlier/later window. */
data class TripPage(val mode: TripTimeMode, val at: Instant?, val count: Int = 5)

data class TripStopTime(
    val id: String,
    val name: String,
    val point: GeoPoint? = null,
    val platform: String = "",
    val plannedArrival: Instant? = null,
    val estimatedArrival: Instant? = null,
    val plannedDeparture: Instant? = null,
    val estimatedDeparture: Instant? = null,
    val cancelled: Boolean = false
) {
    val departure: Instant? get() = estimatedDeparture ?: plannedDeparture
    val arrival: Instant? get() = estimatedArrival ?: plannedArrival
}

enum class LegKind { TRANSIT, WALK, OTHER }

data class TripLeg(
    val kind: LegKind,
    val origin: TripStopTime,
    val destination: TripStopTime,
    val lineId: String = "",
    val line: String = "",
    val lineName: String = "",
    val productClass: Int? = null,
    val productName: String = "",
    val direction: String = "",
    val operatorCode: String = "",
    val tripCode: String = "",
    val intermediate: List<TripStopTime> = emptyList(),
    /** Real path from VRR (`leg.coords`); empty if not delivered. Never stop coordinates. */
    val path: List<GeoPoint> = emptyList(),
    val durationSeconds: Long = 0,
    val distanceMetres: Int? = null,
    val notices: List<String> = emptyList(),
    /** Step-free relevant elements of a footpath, as reported. */
    val footpath: List<String> = emptyList(),
    val realtime: Boolean = false,
    val cancelled: Boolean = false
) {
    val isNew: Boolean get() = lineId.startsWith("new:") || operatorCode.equals("NEW", true)
    val departure: Instant? get() = origin.departure
    val arrival: Instant? get() = destination.arrival
    val delaySeconds: Long? get() {
        val planned = origin.plannedDeparture ?: return null
        val estimated = origin.estimatedDeparture ?: return null
        return estimated.epochSecond - planned.epochSecond
    }
    /** Departure that the existing single-ride tracking can follow; only for VRR-identified trips. */
    fun asDeparture(fetchedAt: Instant): Departure? {
        if (kind != LegKind.TRANSIT || lineId.isBlank() || tripCode.isBlank() || origin.id.isBlank()) return null
        val planned = origin.plannedDeparture ?: return null
        return Departure(lineId, line.ifBlank { lineName }, direction, origin.id, origin.name, tripCode, planned,
            origin.estimatedDeparture, origin.platform, cancelled, notices.take(3), fetchedAt.takeIf { origin.estimatedDeparture != null },
            operator = if (isNew) "" else operatorCode.ifBlank { "?" })
    }
}

enum class TransferRisk { OK, TIGHT, MISSED }

data class TransferAssessment(val fromLeg: Int, val toLeg: Int, val slackSeconds: Long, val risk: TransferRisk)

data class TripJourney(val legs: List<TripLeg>, val interchanges: Int, val fetchedAt: Instant) {
    val transit: List<TripLeg> get() = legs.filter { it.kind == LegKind.TRANSIT }
    val plannedDeparture: Instant? get() = legs.firstNotNullOfOrNull { it.origin.plannedDeparture }
    val plannedArrival: Instant? get() = legs.asReversed().firstNotNullOfOrNull { it.destination.plannedArrival }
    /**
     * Expected start. Footpaths often carry planned times only; then the live departure of the
     * first vehicle minus the reported walking time is used.
     */
    val departure: Instant? get() {
        val first = legs.first().origin
        if (first.estimatedDeparture != null) return first.estimatedDeparture
        val index = legs.indexOfFirst { it.kind == LegKind.TRANSIT }
        val live = legs.getOrNull(index)?.origin?.estimatedDeparture ?: return legs.firstNotNullOfOrNull { it.origin.departure }
        return live.minusSeconds(legs.subList(0, index).sumOf { it.durationSeconds })
    }
    /** Expected arrival: live arrival of the last vehicle plus the reported walking time after it. */
    val arrival: Instant? get() {
        val last = legs.last().destination
        if (last.estimatedArrival != null) return last.estimatedArrival
        val index = legs.indexOfLast { it.kind == LegKind.TRANSIT }
        val live = legs.getOrNull(index)?.destination?.estimatedArrival ?: return legs.asReversed().firstNotNullOfOrNull { it.destination.arrival }
        return live.plusSeconds(legs.subList(index + 1, legs.size).sumOf { it.durationSeconds })
    }
    val durationSeconds: Long? get() {
        val start = departure ?: return null
        val end = arrival ?: return null
        return Duration.between(start, end).seconds.takeIf { it >= 0 }
    }
    val walkingSeconds: Long get() = legs.filter { it.kind == LegKind.WALK }.sumOf { it.durationSeconds }
    val cancelled: Boolean get() = transit.any { it.cancelled || it.origin.cancelled || it.destination.cancelled }
    val realtime: Boolean get() = transit.any { it.realtime }
    /** Identity across refreshes: the same trips at the same planned times. */
    val key: String get() = legs.joinToString(">") { leg ->
        if (leg.kind == LegKind.TRANSIT) "${leg.lineId}/${leg.tripCode}/${stopKey(leg.origin.id)}/${leg.origin.plannedDeparture?.epochSecond}"
        else "walk/${stopKey(leg.origin.id)}/${stopKey(leg.destination.id)}"
    }
}

object TripTransfers {
    const val TIGHT_SECONDS = 120L

    /**
     * Only judged when both the arriving and the departing trip carry a live forecast.
     * Planned-only times give no reliable basis, so no statement is made for them.
     */
    fun assess(journey: TripJourney, now: Instant? = null): List<TransferAssessment> {
        if (now != null && now.epochSecond - journey.fetchedAt.epochSecond !in -30..120) return emptyList()
        val transit = journey.legs.withIndex().filter { it.value.kind == LegKind.TRANSIT }
        return transit.zipWithNext().mapNotNull { (incoming, outgoing) ->
            val arrival = incoming.value.destination.estimatedArrival ?: return@mapNotNull null
            val departure = outgoing.value.origin.estimatedDeparture ?: return@mapNotNull null
            if (outgoing.value.cancelled || outgoing.value.origin.cancelled) {
                return@mapNotNull TransferAssessment(incoming.index, outgoing.index, Long.MIN_VALUE, TransferRisk.MISSED)
            }
            val walk = journey.legs.subList(incoming.index + 1, outgoing.index).filter { it.kind == LegKind.WALK }.sumOf { it.durationSeconds }
            val slack = Duration.between(arrival, departure).seconds - walk
            TransferAssessment(incoming.index, outgoing.index, slack, when {
                slack < 0 -> TransferRisk.MISSED
                slack < TIGHT_SECONDS -> TransferRisk.TIGHT
                else -> TransferRisk.OK
            })
        }
    }
}

/** Builds EFA request parameters. Only documented parameters are sent. */
object TripRequest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val date = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val time = DateTimeFormatter.ofPattern("HHmm")

    fun place(prefix: String, place: TripPlace, ownPosition: GeoPoint?): Map<String, String>? = when {
        place.isMyLocation -> ownPosition?.takeIf(Geo::valid)?.let {
            mapOf("type_$prefix" to "coord", "name_$prefix" to "%.6f:%.6f:WGS84[dd.ddddd]".format(Locale.ROOT, it.lon, it.lat))
        }
        place.id.isNotBlank() -> mapOf("type_$prefix" to "any", "name_$prefix" to place.id)
        place.point != null && Geo.valid(place.point) -> mapOf("type_$prefix" to "coord",
            "name_$prefix" to "%.6f:%.6f:WGS84[dd.ddddd]:%s".format(Locale.ROOT, place.point.lon, place.point.lat, place.name))
        else -> null
    }

    /** Returns null when a place cannot be expressed (e.g. own position unknown). */
    fun parameters(query: TripQuery, page: TripPage, now: Instant): Map<String, String>? {
        val origin = place("origin", query.origin, query.ownPosition) ?: return null
        val destination = place("destination", query.destination, query.ownPosition) ?: return null
        val via = query.via?.let { place("via", it, query.ownPosition) ?: return null }.orEmpty()
        val at = (if (page.mode == TripTimeMode.NOW) null else page.at) ?: now
        val local = at.atZone(zone)
        val options = query.options
        return buildMap {
            putAll(origin); putAll(destination); putAll(via)
            put("locationServerActive", "1")
            put("useRealtime", "1")
            put("calcOneDirection", "1")
            put("calcNumberOfTrips", page.count.coerceIn(1, 8).toString())
            put("itdTripDateTimeDepArr", if (page.mode == TripTimeMode.ARRIVE) "arr" else "dep")
            put("itdDate", local.format(date))
            put("itdTime", local.format(time))
            put("routeType", options.preference.efa)
            put("changeSpeed", options.walkSpeed.efa)
            options.maxChanges?.let { put("maxChanges", it.coerceIn(0, 9).toString()) }
            options.maxWalkMinutes?.let { put("trITMOT", "100"); put("trITMOTvalue100", it.coerceIn(1, 60).toString()) }
            if (options.excluded.isNotEmpty()) {
                put("ptOptionsActive", "1")
                put("excludedMeans", "checkbox")
                options.excluded.flatMap { it.mots }.distinct().sorted().forEach { put("exclMOT_$it", "1") }
            }
            // EFA activates these with any value, even "false" – so they are only sent when wanted.
            if (options.accessibility) {
                put("imparedOptionsActive", "1")
                if (options.noSolidStairs) put("noSolidStairs", "1")
                if (options.noEscalators) put("noEscalators", "1")
                if (options.lowFloor) put("lowPlatformVhcl", "1")
                if (options.wheelchair) put("wheelchair", "1")
            }
        }
    }

    fun placeSearch(text: String): Map<String, String> = mapOf(
        "locationServerActive" to "1", "type_sf" to "any", "name_sf" to text.trim().take(80), "anyMaxSizeHitList" to "20"
    )
}

object TripParser {
    fun places(root: JSONObject): List<TripPlace> = root.optJSONArray("locations")?.objects().orEmpty()
        .sortedWith(compareByDescending<JSONObject> { it.optBoolean("isBest", false) }.thenByDescending { it.optInt("matchQuality", 0) })
        .mapNotNull { obj ->
            val kind = when (obj.str("type").lowercase(Locale.ROOT)) {
                "stop" -> PlaceKind.STOP
                "address", "singlehouse" -> PlaceKind.ADDRESS
                "street" -> PlaceKind.STREET
                "poi" -> PlaceKind.POI
                "locality", "suburb" -> PlaceKind.LOCALITY
                else -> PlaceKind.OTHER
            }
            val id = obj.str("id")
            val name = obj.str("disassembledName").ifBlank { obj.str("name") }.ifBlank { return@mapNotNull null }
            if (id.isBlank() && kind != PlaceKind.LOCALITY) return@mapNotNull null
            val parent = obj.optJSONObject("parent")?.takeIf { it.str("type") in setOf("locality", "suburb", "") }?.str("name").orEmpty()
            TripPlace(kind, id, name, parent.takeUnless { it.equals(name, true) }.orEmpty(), point(obj))
        }.distinctBy { it.key }

    /** Parses journeys; walking-only answers are kept, an empty list means no connection found. */
    fun journeys(root: JSONObject, fetchedAt: Instant): List<TripJourney> = root.optJSONArray("journeys")?.objects().orEmpty()
        .mapNotNull { journey ->
            val legs = journey.optJSONArray("legs")?.objects().orEmpty().mapNotNull { leg(it) }
            if (legs.isEmpty()) return@mapNotNull null
            TripJourney(legs, journey.optInt("interchanges", maxOf(0, legs.count { it.kind == LegKind.TRANSIT } - 1)), fetchedAt)
        }.distinctBy { it.key }

    /** EFA system messages of type error, e.g. "no connection found". */
    fun errors(root: JSONObject): List<String> = root.optJSONArray("systemMessages")?.objects().orEmpty()
        .filter { it.str("type").equals("error", true) }.map { "${it.str("module")}:${it.optInt("code")}" }

    private fun leg(obj: JSONObject): TripLeg? {
        val origin = stopTime(obj.optJSONObject("origin") ?: return null)
        val destination = stopTime(obj.optJSONObject("destination") ?: return null)
        val transport = obj.optJSONObject("transportation")
        val product = transport?.optJSONObject("product")
        val productClass = product?.takeIf { it.has("class") }?.optInt("class")
        val productName = product?.str("name").orEmpty()
        // EFA means 99/100 = footpath; 97 "stay seated", 98 secured connection, 105 taxi, 107 bike are no transit legs.
        val walk = productClass in WALK_CLASSES || productName.lowercase(Locale.ROOT).let { "fußweg" in it || "fussweg" in it || "footpath" in it }
        val kind = when {
            walk -> LegKind.WALK
            transport == null || productClass != null && productClass >= 97 -> LegKind.OTHER
            transport.str("id").isBlank() && transport.str("number").isBlank() -> LegKind.OTHER
            else -> LegKind.TRANSIT
        }
        val sequence = obj.optJSONArray("stopSequence")?.objects().orEmpty().map(::stopTime)
        val notices = (obj.optJSONArray("infos")?.objects().orEmpty().flatMap { info ->
            listOf(info.str("subtitle"), info.str("title")).filter(String::isNotBlank).take(1) +
                info.optJSONArray("infoLinks")?.objects().orEmpty().map { it.str("subtitle").ifBlank { it.str("urlText") } }
        } + obj.optJSONArray("hints")?.objects().orEmpty().map { it.str("content") } +
            listOfNotNull(obj.optJSONObject("interchange")?.str("desc")?.takeIf { it.isNotBlank() }))
            .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }.distinct().take(6)
        val footpath = obj.optJSONArray("footPathInfo")?.objects().orEmpty().flatMap { info ->
            info.optJSONArray("footPathElem")?.objects().orEmpty().mapNotNull { element ->
                when (element.str("type").uppercase(Locale.ROOT)) {
                    "STAIRS" -> "Treppe"
                    "ESCALATOR" -> "Rolltreppe"
                    "ELEVATOR" -> "Aufzug"
                    "RAMP" -> "Rampe"
                    else -> null
                }
            }
        }.distinct()
        val status = obj.optJSONArray("realtimeStatus")?.let { array -> (0 until array.length()).map { array.optString(it).uppercase(Locale.ROOT) } }.orEmpty()
        val cancelled = obj.optBoolean("isCancelled", false) || status.any { it in CANCELLED }
        val estimates = listOf(origin, destination).any { it.estimatedArrival != null || it.estimatedDeparture != null }
        return TripLeg(kind, origin, destination,
            lineId = transport?.str("id").orEmpty(),
            line = transport?.let { it.str("number").ifBlank { it.str("disassembledName") } }.orEmpty(),
            lineName = transport?.str("name").orEmpty(),
            productClass = productClass, productName = productName,
            direction = transport?.optJSONObject("destination")?.str("name").orEmpty(),
            operatorCode = transport?.optJSONObject("operator")?.str("code").orEmpty(),
            tripCode = transport?.optJSONObject("properties")?.str("tripCode").orEmpty(),
            intermediate = if (sequence.size > 2) sequence.subList(1, sequence.size - 1) else emptyList(),
            path = coords(obj),
            durationSeconds = obj.optLong("duration", 0L).coerceAtLeast(0L),
            distanceMetres = obj.takeIf { it.has("distance") }?.optInt("distance"),
            notices = notices, footpath = footpath,
            realtime = kind == LegKind.TRANSIT && (obj.optBoolean("isRealtimeControlled", false) || "MONITORED" in status || estimates),
            cancelled = cancelled)
    }

    private val CANCELLED = setOf("CANCELLED", "CANCELED", "TRIP_CANCELLED", "DELETED", "NOT_SERVICED")
    private val WALK_CLASSES = setOf(99, 100)

    private fun stopTime(obj: JSONObject): TripStopTime {
        val parent = obj.optJSONObject("parent")
        val name = if (parent?.str("type") == "stop") parent.str("name").ifBlank { obj.str("name") } else obj.str("name")
        val props = obj.optJSONObject("properties")
        val platform = props?.str("platformName").orEmpty().ifBlank { props?.str("platform").orEmpty() }
            .ifBlank { obj.str("disassembledName").takeIf { parent?.str("type") == "stop" }.orEmpty() }
        val status = obj.optJSONArray("realtimeStatus")?.let { array -> (0 until array.length()).map { array.optString(it).uppercase(Locale.ROOT) } }.orEmpty()
        return TripStopTime(obj.str("id"), name, point(obj), platform,
            obj.instant("arrivalTimePlanned"), obj.instant("arrivalTimeEstimated"),
            obj.instant("departureTimePlanned"), obj.instant("departureTimeEstimated"),
            obj.optBoolean("isCancelled", false) || status.any { it in CANCELLED })
    }

    private fun point(obj: JSONObject): GeoPoint? {
        val array = obj.optJSONArray("coord") ?: return null
        return GeoPoint(array.optDouble(0, Double.NaN), array.optDouble(1, Double.NaN)).takeIf(Geo::valid)
    }

    private fun coords(obj: JSONObject): List<GeoPoint> {
        var array: JSONArray = obj.optJSONArray("coords") ?: return emptyList()
        if (array.length() == 1 && array.optJSONArray(0)?.optJSONArray(0) != null) array = array.getJSONArray(0)
        if (array.length() < 2 || array.length() > RouteShape.MAX_POINTS) return emptyList()
        val points = (0 until array.length()).map { index ->
            val pair = array.optJSONArray(index) ?: return emptyList()
            GeoPoint(pair.optDouble(0, Double.NaN), pair.optDouble(1, Double.NaN))
        }
        return if (points.all(Geo::valid)) points else emptyList()
    }
}

/** Search options are stored as ordinary settings so the options sheet and the settings screen agree. */
object TripSettings {
    val MODES = listOf("train", "light_rail", "bus", "on_demand", "other")
    private val GROUPS = mapOf("train" to ProductGroup.TRAIN, "light_rail" to ProductGroup.LIGHT_RAIL, "bus" to ProductGroup.BUS,
        "on_demand" to ProductGroup.ON_DEMAND, "other" to ProductGroup.OTHER)
    fun key(group: ProductGroup): String = GROUPS.entries.first { it.value == group }.key

    fun options(settings: AppSettings): TripOptions {
        val allowed = settings.csv("trip_modes").mapNotNull(GROUPS::get).toSet().ifEmpty { ProductGroup.entries.toSet() }
        return TripOptions(
            excluded = ProductGroup.entries.toSet() - allowed,
            maxChanges = settings.string("trip_max_changes").toIntOrNull()?.coerceIn(0, 9),
            walkSpeed = WalkSpeed.entries.firstOrNull { it.efa == settings.string("trip_walk_speed") } ?: WalkSpeed.NORMAL,
            maxWalkMinutes = settings.string("trip_max_walk").toIntOrNull()?.coerceIn(1, 60),
            preference = RoutePreference.entries.firstOrNull { it.efa == settings.string("trip_route_type") } ?: RoutePreference.FASTEST,
            noSolidStairs = settings.bool("trip_no_stairs"), noEscalators = settings.bool("trip_no_escalators"),
            lowFloor = settings.bool("trip_low_floor"), wheelchair = settings.bool("trip_wheelchair")
        )
    }

    /** Setting values for [options]; used by the options sheet. */
    fun values(options: TripOptions): Map<String, String> = mapOf(
        "trip_modes" to ProductGroup.entries.filter { it !in options.excluded }.ifEmpty { ProductGroup.entries }.joinToString(",") { key(it) },
        "trip_max_changes" to (options.maxChanges?.toString() ?: "any"),
        "trip_walk_speed" to options.walkSpeed.efa,
        "trip_max_walk" to (options.maxWalkMinutes?.toString() ?: "default"),
        "trip_route_type" to options.preference.efa,
        "trip_no_stairs" to options.noSolidStairs.toString(), "trip_no_escalators" to options.noEscalators.toString(),
        "trip_low_floor" to options.lowFloor.toString(), "trip_wheelchair" to options.wheelchair.toString()
    )
}
