package de.luca.mitfahrt.data

import org.json.JSONObject
import java.time.Instant

enum class PlatformKind { BUS, RAIL }

/**
 * One platform as the VRR departure board reports it. [point] is set only when every departure
 * on this platform carried the same public coordinate; otherwise the platform stays text only.
 */
data class PlatformInfo(
    val id: String, val name: String, val kind: PlatformKind, val point: GeoPoint?,
    val lines: List<String>, val directions: List<String>, val nextDeparture: Instant?
)

data class StationPlatforms(val stopId: String, val stopName: String, val platforms: List<PlatformInfo>, val fetchedAt: Instant) {
    val withPosition: Int get() = platforms.count { it.point != null }
}

/** Stations whose platforms are worth an own overview (many platforms on one site). */
object PlatformStations {
    val known = linkedMapOf("de:05116:23007" to "Mönchengladbach Hbf", "de:05116:23023" to "Rheydt Hbf")
    fun isKnown(stopId: String): Boolean = stopKey(stopId) in known
}

object StationPlatformParser {
    /** Coordinates of one platform that differ by more than this are not trusted. */
    private const val SAME_PLACE = 15.0
    /** A platform further than this from the others' centre is not placed on the map. */
    private const val STATION_RADIUS = 600.0
    private val railClasses = setOf(0, 1, 2, 3, 4, 13, 14, 15, 16)

    fun parse(root: JSONObject, stopId: String, now: Instant): StationPlatforms {
        val station = stopKey(stopId)
        data class Seen(val id: String, val name: String, val point: GeoPoint?, val rail: Boolean, val line: String, val direction: String, val at: Instant?)
        val seen = root.optJSONArray("stopEvents")?.objects().orEmpty().mapNotNull { event ->
            val location = event.optJSONObject("location") ?: return@mapNotNull null
            val id = location.str("id")
            if (stopKey(id) != station || id.split(':').size < 5) return@mapNotNull null
            val props = location.optJSONObject("properties")
            // "platform" is the bare number; "platformName" may carry a prefix like "Bst.".
            val name = props?.str("platform")?.ifBlank { props.str("platformName") }.orEmpty().ifBlank { location.str("disassembledName") }
            if (name.isBlank()) return@mapNotNull null
            val transport = event.optJSONObject("transportation")
            val productClass = transport?.optJSONObject("product")?.optInt("class", -1) ?: -1
            val rail = location.str("pointType").equals("TRACK", true) || productClass in railClasses
            val array = location.optJSONArray("coord")
            val point = array?.let { GeoPoint(it.optDouble(0, Double.NaN), it.optDouble(1, Double.NaN)) }?.takeIf(Geo::valid)
            Seen(id, name, point, rail, transport?.str("number")?.ifBlank { transport.str("disassembledName") }.orEmpty(),
                transport?.optJSONObject("destination")?.str("name").orEmpty(),
                event.instant("departureTimeEstimated") ?: event.instant("departureTimePlanned"))
        }
        val grouped = seen.groupBy { it.id }
        // Median, so a single wrong coordinate cannot pull the station centre away.
        val centre = grouped.values.mapNotNull { it.first().point }.let { points ->
            if (points.isEmpty()) null else GeoPoint(points.map { it.lat }.sorted()[points.size / 2], points.map { it.lon }.sorted()[points.size / 2])
        }
        val platforms = grouped.map { (id, events) ->
            val points = events.map { it.point }
            val first = points.firstOrNull()
            val consistent = first != null && points.all { it != null && it.distanceTo(first) <= SAME_PLACE }
            val placed = first?.takeIf { consistent && (centre == null || it.distanceTo(centre) <= STATION_RADIUS) }
            PlatformInfo(id, events.first().name, if (events.count { it.rail } * 2 > events.size) PlatformKind.RAIL else PlatformKind.BUS,
                placed, events.map { it.line }.filter { it.isNotBlank() }.distinct().sortedWith(lineOrder),
                events.map { shortName(it.direction) }.filter { it.isNotBlank() }.distinct().take(4),
                events.mapNotNull { it.at }.filter { !it.isBefore(now.minusSeconds(60)) }.minOrNull())
        }.sortedWith(compareBy<PlatformInfo> { it.kind }.thenBy { it.name.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.name })
        val name = root.optJSONArray("stopEvents")?.objects()?.firstNotNullOfOrNull { it.optJSONObject("location")?.optJSONObject("parent")?.str("name") }
            ?: PlatformStations.known[station].orEmpty()
        return StationPlatforms(station, name, platforms, now)
    }

    private val lineOrder = compareBy<String> { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
}

/** A line of the network with one direction, as VRR lists it. */
data class NetworkLine(val id: String, val number: String, val direction: String, val description: String, val newOperated: Boolean) {
    /** EFA line ids end with the direction (H/R) and the timetable period; both directions share the rest. */
    val base: String get() = id.split(':').take(2).joinToString(":")
}

data class LineStop(val id: String, val name: String, val point: GeoPoint?)

object NetworkParser {
    fun lines(root: JSONObject, newOnly: Boolean = true): List<NetworkLine> = root.optJSONArray("lines")?.objects().orEmpty().mapNotNull { line ->
        val id = line.str("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val isNew = id.startsWith("new:") || line.optJSONObject("operator")?.str("code").equals("NEW", true)
        if (newOnly && !isNew) return@mapNotNull null
        NetworkLine(id, line.str("number").ifBlank { line.str("disassembledName") },
            line.optJSONObject("destination")?.str("name").orEmpty(), line.str("description"), isNew)
    }.distinctBy { it.id }
        // Several timetable periods list the same line twice; one entry per line and direction is enough.
        .distinctBy { "${it.number}/${it.direction}/${it.id.split(':').getOrNull(3)}" }
        .sortedWith(compareBy<NetworkLine> { it.number.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.number }.thenBy { it.direction })

    fun stops(root: JSONObject): List<LineStop> = root.optJSONArray("locationSequence")?.objects().orEmpty().mapNotNull { location ->
        val parent = location.optJSONObject("parent")
        val id = canonicalStopId(location.str("id")) ?: parent?.str("id")?.let(::canonicalStopId) ?: return@mapNotNull null
        val array = location.optJSONArray("coord") ?: parent?.optJSONArray("coord")
        val point = array?.let { GeoPoint(it.optDouble(0, Double.NaN), it.optDouble(1, Double.NaN)) }?.takeIf(Geo::valid)
        LineStop(id, parent?.str("name")?.ifBlank { null } ?: location.str("name"), point)
    }.fold(mutableListOf<LineStop>()) { list, stop -> if (list.lastOrNull()?.id != stop.id) list += stop; list }
}

/** Verified course of a line direction, or the reason why only the stop sequence is shown. */
data class NetworkGeometry(val points: List<GeoPoint>, val source: RouteShapeSource?, val note: String?)

/**
 * Finds the real geometry for a line direction: one current trip of exactly this line id is
 * loaded and its VRR path verified against the stops. Nothing is drawn between stops otherwise.
 */
class NetworkPlanLoader(
    private val departures: suspend (Stop) -> List<Departure>,
    private val ride: suspend (Departure) -> Ride,
    private val shape: suspend (Ride) -> RouteShape?
) {
    suspend fun geometry(line: NetworkLine, stops: List<LineStop>, now: Instant): NetworkGeometry {
        val first = stops.firstOrNull() ?: return NetworkGeometry(emptyList(), null, "Keine Haltestellenfolge vom VRR.")
        val departure = runCatching { departures(Stop(first.id, first.name, first.point)) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            return NetworkGeometry(emptyList(), null, "Strecke gerade nicht abrufbar. Gezeigt wird die Haltestellenfolge.")
        }.filter { sameDirection(it.lineId, line.id) && !it.cancelled && it.planned.isAfter(now.minusSeconds(120)) &&
            it.planned.isBefore(now.plusSeconds(4 * 3600L)) }.minByOrNull { it.planned }
            ?: return NetworkGeometry(emptyList(), null, "Gerade keine Fahrt dieser Linie, daher ohne Streckenverlauf.")
        val verified = runCatching { shape(ride(departure)) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            null
        } ?: return NetworkGeometry(emptyList(), null, "Der VRR liefert für diese Fahrt keinen Streckenverlauf.")
        return NetworkGeometry(verified.points, verified.source, null)
    }

    companion object {
        /** Same line and direction letter; the timetable period suffix may differ. */
        fun sameDirection(a: String, b: String): Boolean {
            val x = a.split(':'); val y = b.split(':')
            return x.size >= 4 && y.size >= 4 && x.take(4) == y.take(4)
        }
    }
}
