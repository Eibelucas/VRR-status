package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Locale

enum class RouteShapeSource {
    /** Geometry published by VRR for exactly this trip. */
    TRIP,
    /** Geometry of an earlier VRR trip with the same line, direction and stop sequence, re-verified. */
    VARIANT
}

/**
 * Real line geometry that has been verified against the ordered stops of a ride.
 * There is deliberately no constructor from stop coordinates: those are not a road path.
 */
class RouteShape private constructor(
    val journeyKey: String,
    val source: RouteShapeSource,
    val points: List<GeoPoint>,
    /** Offset along [line] for each ride stop index; null where the stop could not be placed. */
    val stopOffsets: List<Double?>,
    val variantKey: String
) {
    val line: RouteLine = RouteLine(points)

    fun offsetOf(index: Int): Double? = stopOffsets.getOrNull(index)

    companion object {
        const val MAX_POINTS = 5_000
        const val MAX_GAP_METRES = 2_500.0
        /** A platform coordinate is usually within a few metres of the drawn street line. */
        const val STOP_TOLERANCE_METRES = 60.0
        const val MIN_MATCHED_SHARE = 0.8

        /** Returns null when the geometry does not demonstrably belong to this ride's stop order. */
        fun verify(ride: Ride, points: List<GeoPoint>, source: RouteShapeSource): RouteShape? {
            if (ride.demo || points.size !in 2..MAX_POINTS || points.any { !Geo.valid(it) }) return null
            val line = RouteLine(points)
            if (!line.usable || line.length < 200.0 || line.length > 300_000.0) return null
            if ((1 until line.points.size).any { line.cumulative[it] - line.cumulative[it - 1] > MAX_GAP_METRES }) return null
            val offsets = MutableList<Double?>(ride.stops.size) { null }
            var cursor = 0.0
            for ((index, stop) in ride.stops.withIndex()) {
                val point = stop.point?.takeIf(Geo::valid) ?: continue
                val pass = line.firstPass(point, cursor, STOP_TOLERANCE_METRES) ?: continue
                offsets[index] = pass.offset
                cursor = pass.offset
            }
            val target = ride.exitTargetIndex() ?: ride.stops.lastIndex
            if (offsets.getOrNull(ride.boardingIndex) == null || offsets.getOrNull(target) == null) return null
            val relevant = (ride.boardingIndex..target).filter { !ride.stops[it].cancelled && Geo.valid(ride.stops[it].point) }
            if (relevant.size < 2) return null
            val matched = relevant.count { offsets[it] != null }
            if (matched < relevant.size * MIN_MATCHED_SHARE) return null
            return RouteShape(ride.departure.key, source, line.points, offsets, variantKey(ride))
        }

        /** Same line, same headsign, same ordered stations. Platform-level differences are re-verified. */
        fun variantKey(ride: Ride): String {
            val text = listOf(ride.departure.lineId, normalize(ride.departure.destination),
                ride.stops.joinToString(">") { stopKey(it.id) }).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
        }

        private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}

/** Reads the optional `leg.coords` path of an EFA trip response. Stop coordinates are never used. */
object EfaShapeParser {
    fun coords(root: JSONObject, departure: Departure): List<GeoPoint>? {
        val leg = root.optJSONObject("leg") ?: return null
        val transport = leg.optJSONObject("transportation") ?: return null
        if (transport.str("id") != departure.lineId ||
            transport.optJSONObject("properties")?.str("tripCode") != departure.tripCode) return null
        var array = leg.optJSONArray("coords") ?: return null
        // Some EFA versions wrap the path once more.
        if (array.length() == 1 && array.optJSONArray(0)?.optJSONArray(0) != null) array = array.getJSONArray(0)
        if (array.length() < 2 || array.length() > RouteShape.MAX_POINTS) return null
        val points = ArrayList<GeoPoint>(array.length())
        for (index in 0 until array.length()) {
            val pair = array.optJSONArray(index) ?: return null
            val lat = pair.optDouble(0, Double.NaN)
            val lon = pair.optDouble(1, Double.NaN)
            val point = GeoPoint(lat, lon)
            if (!Geo.valid(point)) return null
            points += point
        }
        return points
    }
}

/** Small, bounded cache of verified public line geometry. Contains no personal data. */
class RouteShapeCache(
    private val file: File? = null,
    private val clock: () -> Instant = Instant::now,
    private val maxEntries: Int = 40,
    private val maxAge: Duration = Duration.ofDays(60)
) {
    private data class Entry(val points: List<GeoPoint>, val savedAt: Instant)
    private val entries = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }
    private var loaded = false

    @Synchronized fun get(variantKey: String): List<GeoPoint>? {
        load()
        val entry = entries[variantKey] ?: return null
        if (Duration.between(entry.savedAt, clock()) > maxAge) { entries.remove(variantKey); return null }
        return entry.points
    }

    @Synchronized fun put(variantKey: String, points: List<GeoPoint>) {
        load()
        entries[variantKey] = Entry(points.map { GeoPoint(round(it.lat), round(it.lon)) }, clock())
        persist()
    }

    private fun round(value: Double) = Math.round(value * 1e6) / 1e6

    private fun load() {
        if (loaded) return
        loaded = true
        val source = file?.takeIf { it.isFile && it.length() in 1..3_000_000 } ?: return
        runCatching {
            val root = JSONObject(source.readText(Charsets.UTF_8))
            root.keys().asSequence().take(maxEntries).forEach { key ->
                val item = root.optJSONObject(key) ?: return@forEach
                val savedAt = runCatching { Instant.parse(item.getString("savedAt")) }.getOrNull() ?: return@forEach
                val array = item.optJSONArray("points") ?: return@forEach
                val points = (0 until minOf(array.length(), RouteShape.MAX_POINTS)).mapNotNull { index ->
                    array.optJSONArray(index)?.let { GeoPoint(it.optDouble(0, Double.NaN), it.optDouble(1, Double.NaN)) }
                        ?.takeIf(Geo::valid)
                }
                if (points.size >= 2 && key.matches(Regex("[0-9a-f]{32}"))) entries[key] = Entry(points, savedAt)
            }
        }
    }

    private fun persist() {
        val target = file ?: return
        runCatching {
            val root = JSONObject()
            entries.forEach { (key, entry) ->
                root.put(key, JSONObject().apply {
                    put("savedAt", entry.savedAt.toString())
                    put("points", JSONArray(entry.points.map { JSONArray(listOf(it.lat, it.lon)) }))
                })
            }
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        }
    }
}

/**
 * Loads geometry in the documented order: this trip from VRR, then a verified earlier trip with
 * the same variant, otherwise nothing. At most one request per trip and retry window; calls are
 * serialised so no parallel requests reach VRR.
 */
class RouteShapeRepository(
    private val fetchTrip: suspend (Departure) -> List<GeoPoint>?,
    private val cache: RouteShapeCache,
    private val clock: () -> Instant = Instant::now
) {
    private val mutex = Mutex()
    private val attempts = object : LinkedHashMap<String, Instant>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Instant>?): Boolean = size > 64
    }
    private var last: RouteShape? = null

    suspend fun load(ride: Ride): RouteShape? = mutex.withLock {
        if (ride.demo) return@withLock null
        last?.takeIf { it.journeyKey == ride.departure.key }?.let { cached ->
            // The ride may have been refreshed (diversion stops); re-verify against its current order.
            RouteShape.verify(ride, cached.points, cached.source)?.let { return@withLock it }
            last = null
        }
        val key = ride.departure.key
        val retryAfter = attempts[key]?.plus(RETRY)
        if (retryAfter == null || clock() >= retryAfter) {
            attempts[key] = clock()
            val points = try { fetchTrip(ride.departure) } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { null }
            points?.let { RouteShape.verify(ride, it, RouteShapeSource.TRIP) }?.let { shape ->
                cache.put(shape.variantKey, shape.points)
                last = shape
                return@withLock shape
            }
        }
        val variant = cache.get(RouteShape.variantKey(ride))?.let { RouteShape.verify(ride, it, RouteShapeSource.VARIANT) }
        if (variant != null) last = variant
        variant
    }

    companion object { val RETRY: Duration = Duration.ofMinutes(20) }
}

/**
 * Line used internally to measure progress. With a verified shape it is the real path; otherwise
 * it is the chain of stop coordinates, which is only ever used with wide tolerances for ordering
 * and never drawn or presented as a road route.
 */
class RouteReference private constructor(val line: RouteLine, val stopOffsets: List<Double?>, val exact: Boolean) {
    /** How far a measurement may be from the line and still count as on it. */
    fun lateralTolerance(accuracy: Double = 0.0): Double =
        if (exact) (25.0 + accuracy * 1.2).coerceIn(40.0, 90.0) else (180.0 + accuracy).coerceAtMost(260.0)

    fun offsetOf(index: Int): Double? = stopOffsets.getOrNull(index)

    /** Timetable time at an offset, interpolated between the surrounding stops. */
    fun plannedTimeAt(ride: Ride, offset: Double): Instant? {
        val timed = ride.stops.indices.mapNotNull { index ->
            val at = ride.stops[index].let { it.departure ?: it.arrival } ?: return@mapNotNull null
            stopOffsets.getOrNull(index)?.let { it to at }
        }
        if (timed.size < 2) return null
        val after = timed.indexOfFirst { it.first >= offset }
        if (after <= 0) return if (after == 0 && timed[0].first - offset <= 50.0) timed[0].second else null
        val (startOffset, startTime) = timed[after - 1]
        val (endOffset, endTime) = timed[after]
        val span = endOffset - startOffset
        if (span <= 0.0) return startTime
        val fraction = ((offset - startOffset) / span).coerceIn(0.0, 1.0)
        return startTime.plusMillis(((endTime.toEpochMilli() - startTime.toEpochMilli()) * fraction).toLong())
    }

    companion object {
        fun of(ride: Ride, shape: RouteShape?): RouteReference? {
            if (shape != null && shape.journeyKey == ride.departure.key && shape.line.usable)
                return RouteReference(shape.line, shape.stopOffsets, true)
            val indexed = ride.stops.withIndex().filter { Geo.valid(it.value.point) }
            if (indexed.size < 2) return null
            val line = RouteLine(indexed.map { it.value.point!! })
            if (!line.usable) return null
            // Consecutive identical coordinates collapse in RouteLine; map each stop to its vertex offset.
            val offsets = MutableList<Double?>(ride.stops.size) { null }
            var vertex = 0
            var previous: GeoPoint? = null
            for ((index, stop) in indexed) {
                if (previous != null && stop.point != previous) vertex++
                offsets[index] = line.cumulative.getOrNull(vertex)
                previous = stop.point
            }
            return RouteReference(line, offsets, false)
        }
    }
}

/** Where a vehicle may be drawn on the line. Never pulls a deviating vehicle onto the old path. */
object RouteSnapping {
    const val MAX_SNAP_METRES = 35.0
    const val DEVIATION_METRES = 80.0
    const val MAX_HEADING_DIFFERENCE = 60.0

    data class Result(val projected: Projection?, val deviating: Boolean)

    /**
     * [history] are earlier observations of the same vehicle, oldest first. The projection window
     * follows the last known position so a vehicle on a loop is not snapped to the other lap.
     */
    fun vehicle(shape: RouteShape?, point: GeoPoint, history: List<VehicleObservation>, around: Double? = null): Result {
        val line = shape?.line?.takeIf { it.usable } ?: return Result(null, false)
        val window = around?.let { (it - 500.0) to (it + 2_000.0) } ?: (0.0 to line.length)
        val projection = line.project(point, window.first, window.second) ?: return Result(null, false)
        val earlier = history.lastOrNull { it.point.distanceTo(point) >= 15.0 }
        val heading = earlier?.let { Geo.bearing(it.point, point) }
        val headingOk = heading == null || Geo.angle(heading, projection.bearing) <= MAX_HEADING_DIFFERENCE
        val recentDeviation = history.takeLast(2).count { old ->
            (line.project(old.point, window.first, window.second)?.distance ?: Double.MAX_VALUE) > DEVIATION_METRES
        }
        val deviating = projection.distance > DEVIATION_METRES && recentDeviation >= 1
        val snap = projection.takeIf { it.distance <= MAX_SNAP_METRES && headingOk && !deviating }
        return Result(snap, deviating)
    }
}
