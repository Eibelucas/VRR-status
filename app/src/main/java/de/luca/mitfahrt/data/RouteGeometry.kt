package de.luca.mitfahrt.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** City-scale planar helpers. Errors stay well below GPS noise inside a transport network. */
object Geo {
    private const val EARTH = 6_371_000.0

    fun valid(point: GeoPoint?): Boolean = point != null && point.lat.isFinite() && point.lon.isFinite() &&
        point.lat in -85.0..85.0 && point.lon in -180.0..180.0 && (point.lat != 0.0 || point.lon != 0.0)

    /** Initial bearing in degrees, 0 = north, clockwise. */
    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val x = (to.lon - from.lon) * cos(Math.toRadians((from.lat + to.lat) / 2))
        val y = to.lat - from.lat
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /** Smallest angle between two bearings, 0..180. */
    fun angle(a: Double, b: Double): Double {
        val diff = abs(((a - b) % 360.0 + 360.0) % 360.0)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    internal fun metresPerDegreeLat(): Double = EARTH * PI / 180.0
}

data class Projection(
    /** Distance along the line from its start, in metres. */
    val offset: Double,
    /** Perpendicular distance from the measured point to the line, in metres. */
    val distance: Double,
    val point: GeoPoint,
    /** Direction of travel of the line at [offset]. */
    val bearing: Double
)

/** An ordered list of points with cumulative distances. Never invents points between them. */
class RouteLine(points: List<GeoPoint>) {
    val points: List<GeoPoint> = points.filterIndexed { index, point -> index == 0 || point != points[index - 1] }
    private val referenceLat = this.points.map { it.lat }.average().takeIf { it.isFinite() } ?: 0.0
    private val metresLat = Geo.metresPerDegreeLat()
    private val metresLon = metresLat * cos(Math.toRadians(referenceLat))
    private val xs = DoubleArray(this.points.size) { this.points[it].lon * metresLon }
    private val ys = DoubleArray(this.points.size) { this.points[it].lat * metresLat }
    val cumulative: DoubleArray = DoubleArray(this.points.size).also { sums ->
        for (index in 1 until this.points.size) {
            sums[index] = sums[index - 1] + hypot(xs[index] - xs[index - 1], ys[index] - ys[index - 1])
        }
    }
    val length: Double get() = cumulative.lastOrNull() ?: 0.0
    val usable: Boolean get() = points.size >= 2 && length > 0.0

    /**
     * Nearest point of the line to [point], restricted to offsets [from]..[to]. Restricting the
     * window is how loops and repeated street sections are kept in their order.
     */
    fun project(point: GeoPoint, from: Double = 0.0, to: Double = length): Projection? {
        if (!usable || !Geo.valid(point)) return null
        val start = from.coerceIn(0.0, length)
        val end = to.coerceIn(start, length)
        val px = point.lon * metresLon
        val py = point.lat * metresLat
        var best: Projection? = null
        for (index in 0 until points.size - 1) {
            val segmentStart = cumulative[index]
            val segmentEnd = cumulative[index + 1]
            if (segmentEnd < start || segmentStart > end) continue
            val segmentLength = segmentEnd - segmentStart
            if (segmentLength <= 0.0) continue
            val dx = xs[index + 1] - xs[index]
            val dy = ys[index + 1] - ys[index]
            val minimum = ((start - segmentStart) / segmentLength).coerceIn(0.0, 1.0)
            val maximum = ((end - segmentStart) / segmentLength).coerceIn(0.0, 1.0)
            val t = (((px - xs[index]) * dx + (py - ys[index]) * dy) / (segmentLength * segmentLength)).coerceIn(minimum, maximum)
            val x = xs[index] + t * dx
            val y = ys[index] + t * dy
            val distance = hypot(px - x, py - y)
            if (best == null || distance < best.distance - 1e-9) {
                best = Projection(segmentStart + t * segmentLength, distance,
                    GeoPoint(y / metresLat, x / metresLon), Geo.bearing(points[index], points[index + 1]))
            }
        }
        return best
    }

    /**
     * The first offset after [from] where the line passes within [tolerance] of [point]; within
     * that pass the closest offset is returned. Used for ordered stop matching on loops.
     */
    fun firstPass(point: GeoPoint, from: Double, tolerance: Double): Projection? {
        if (!usable || !Geo.valid(point)) return null
        var bestInRun: Projection? = null
        for (index in 0 until points.size - 1) {
            if (cumulative[index + 1] < from) continue
            val projection = project(point, maxOf(from, cumulative[index]), cumulative[index + 1]) ?: continue
            if (projection.distance <= tolerance) {
                if (bestInRun == null || projection.distance < bestInRun.distance) bestInRun = projection
            } else if (bestInRun != null) return bestInRun
        }
        return bestInRun
    }

    fun pointAt(offset: Double): GeoPoint {
        val target = offset.coerceIn(0.0, length)
        val index = (1 until points.size).firstOrNull { cumulative[it] >= target } ?: return points.last()
        val segmentLength = cumulative[index] - cumulative[index - 1]
        val t = if (segmentLength <= 0.0) 0.0 else (target - cumulative[index - 1]) / segmentLength
        return GeoPoint(points[index - 1].lat + t * (points[index].lat - points[index - 1].lat),
            points[index - 1].lon + t * (points[index].lon - points[index - 1].lon))
    }

    /** The original vertices between two offsets, with exact cut points at both ends. */
    fun slice(from: Double, to: Double): List<GeoPoint> {
        if (!usable) return emptyList()
        val start = from.coerceIn(0.0, length)
        val end = to.coerceIn(0.0, length)
        if (end <= start) return emptyList()
        val inner = points.indices.filter { cumulative[it] > start && cumulative[it] < end }.map { points[it] }
        return listOf(pointAt(start)) + inner + pointAt(end)
    }
}
