package de.luca.mitfahrt.data

import java.time.Instant
import kotlin.math.cos

/**
 * A synthetic but realistically scaled MG-like line: 2 km east, then 1.2 km north. Stops are
 * 400–500 m apart like on NEW city lines, platforms sit 8 m beside the street centre line,
 * and the bus needs two minutes between stops.
 */
object RouteFixtures {
    val T0: Instant = Instant.parse("2026-11-09T07:00:00Z")
    private const val LAT = 51.19
    private const val LON = 6.44

    fun at(east: Double, north: Double): GeoPoint =
        GeoPoint(LAT + north / 111_195.0, LON + east / (111_195.0 * cos(Math.toRadians(LAT))))

    /** Street centre line with a vertex every 100 m. */
    val street: List<GeoPoint> = (0..20).map { at(it * 100.0, 0.0) } + (1..12).map { at(2000.0, it * 100.0) }

    /** Stop platforms: east part on y=8, north part on x=2008. */
    val stopPositions = listOf(0.0 to 8.0, 500.0 to 8.0, 1000.0 to 8.0, 1500.0 to 8.0,
        2008.0 to 400.0, 2008.0 to 800.0, 2008.0 to 1200.0)

    fun stops(start: Instant = T0, ids: List<String>? = null): List<RideStop> = stopPositions.mapIndexed { index, (e, n) ->
        val time = start.plusSeconds(index * 120L)
        RideStop(ids?.get(index) ?: "de:05116:${200 + index}:1:1", "Halt $index", at(e, n), time, time)
    }

    fun ride(boarded: Boolean = false, boardingIndex: Int = 1, target: Int? = 5, delaySeconds: Long? = null,
             fetchedAt: Instant = T0, stops: List<RideStop> = stops()): Ride {
        val withLive = if (delaySeconds == null) stops else stops.map { stop ->
            stop.copy(estimatedArrival = stop.arrival?.plusSeconds(delaySeconds), estimatedDeparture = stop.departure?.plusSeconds(delaySeconds))
        }
        val boarding = withLive[boardingIndex]
        val ride = Ride(Departure("new:006:H", "006", "Ziel", boarding.id, boarding.name, "trip-1",
            boarding.departure!!, delaySeconds?.let { boarding.departure!!.plusSeconds(it) }, "1", observedAt = fetchedAt),
            withLive, boardingIndex, fetchedAt, boarded = boarded)
        return if (target == null) ride else ride.withExitTarget(target)
    }

    fun shape(ride: Ride = ride(), points: List<GeoPoint> = street): RouteShape =
        RouteShape.verify(ride, points, RouteShapeSource.TRIP)!!

    fun bus(id: String, east: Double, north: Double, at: Instant, delay: Int? = 0, destination: String = "Ziel",
            reliable: Boolean = true, line: String = "006") =
        NewVehicle(id, line, destination, at(east, north), at, delaySeconds = delay, directionReliable = reliable)

    /** Position along the line (0..3200 m) converted to a point, optionally offset sideways. */
    fun along(offset: Double, side: Double = 0.0): GeoPoint =
        if (offset <= 2000.0) at(offset, side) else at(2000.0 + side, offset - 2000.0)

    fun fix(offset: Double, at: Instant, accuracy: Float = 10f, side: Double = 0.0) = PhoneFix(along(offset, side), accuracy, at)
}
