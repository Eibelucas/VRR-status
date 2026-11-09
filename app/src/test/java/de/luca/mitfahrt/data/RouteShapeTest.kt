package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import de.luca.mitfahrt.data.RouteFixtures.at
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class RouteShapeTest {
    private val ride = RouteFixtures.ride()

    @Test fun `real path is accepted and every stop gets an ordered offset`() {
        val shape = RouteFixtures.shape(ride)
        assertEquals(RouteShapeSource.TRIP, shape.source)
        val offsets = shape.stopOffsets.map { it!! }
        assertEquals(offsets.sorted(), offsets)
        assertEquals(500.0, offsets[1], 2.0)
        assertEquals(2400.0, offsets[4], 2.0)
    }

    @Test fun `stop coordinates alone are never accepted as a path when they do not form the street`() {
        // A diagonal shortcut between first and last stop misses the intermediate stops.
        assertNull(RouteShape.verify(ride, listOf(at(0.0, 0.0), at(2000.0, 1200.0)), RouteShapeSource.TRIP))
    }

    @Test fun `wrong variant missing the boarding street is rejected`() {
        val variant = (0..20).map { at(it * 100.0, 300.0) } + (1..12).map { at(2000.0, 300.0 + it * 100.0) }
        assertNull(RouteShape.verify(ride, variant, RouteShapeSource.VARIANT))
    }

    @Test fun `implausible data is rejected`() {
        assertNull(RouteShape.verify(ride, listOf(at(0.0, 0.0)), RouteShapeSource.TRIP))
        assertNull(RouteShape.verify(ride, RouteFixtures.street + GeoPoint(Double.NaN, 6.0), RouteShapeSource.TRIP))
        val gap = listOf(at(0.0, 0.0), at(500.0, 0.0), at(4000.0, 0.0))
        assertNull(RouteShape.verify(ride, gap, RouteShapeSource.TRIP))
        assertNull(RouteShape.verify(ride.copy(demo = true), RouteFixtures.street, RouteShapeSource.TRIP))
    }

    @Test fun `loop keeps the order of a stop served twice`() {
        // Out and back on the same street: stops 0..3 eastbound, then 4..6 westbound on the other side.
        val loop = (0..15).map { at(it * 100.0, 0.0) } + (14 downTo 0).map { at(it * 100.0, -20.0) }
        val positions = listOf(0.0 to 8.0, 500.0 to 8.0, 1000.0 to 8.0, 1500.0 to 8.0, 1000.0 to -28.0, 500.0 to -28.0, 0.0 to -28.0)
        val stops = positions.mapIndexed { index, (e, n) ->
            val time = T0.plusSeconds(index * 120L)
            RideStop("de:05116:${300 + index}:1:1", "Halt $index", at(e, n), time, time)
        }
        val r = RouteFixtures.ride(stops = stops)
        val shape = RouteShape.verify(r, loop, RouteShapeSource.TRIP)!!
        assertTrue(shape.offsetOf(5)!! > shape.offsetOf(3)!!)
        assertEquals(500.0, shape.offsetOf(1)!!, 3.0)
        assertTrue(shape.offsetOf(5)!! > 2000.0)
    }

    @Test fun `EFA path is read only for the same trip and in both nestings`() {
        val coords = JSONArray(RouteFixtures.street.map { JSONArray(listOf(it.lat, it.lon)) })
        fun root(array: JSONArray, trip: String = "trip-1") = JSONObject().put("leg", JSONObject()
            .put("transportation", JSONObject().put("id", "new:006:H").put("properties", JSONObject().put("tripCode", trip)))
            .put("coords", array))
        assertEquals(RouteFixtures.street.size, EfaShapeParser.coords(root(coords), ride.departure)!!.size)
        assertEquals(RouteFixtures.street.size, EfaShapeParser.coords(root(JSONArray().put(coords)), ride.departure)!!.size)
        assertNull(EfaShapeParser.coords(root(coords, "other"), ride.departure))
        assertNull(EfaShapeParser.coords(JSONObject().put("leg", JSONObject()), ride.departure))
        assertNull(EfaShapeParser.coords(root(JSONArray().put(JSONArray(listOf(0.0, 0.0))).put(coords.get(1))), ride.departure))
    }

    @Test fun `the recorded VRR trip response has no path and yields no geometry`() {
        val text = javaClass.classLoader!!.getResource("ride.json")!!.readText()
        val root = JSONObject(text)
        val leg = root.getJSONObject("leg")
        val transport = leg.getJSONObject("transportation")
        val departure = ride.departure.copy(lineId = transport.getString("id"),
            tripCode = transport.getJSONObject("properties").getString("tripCode"))
        assertFalse(leg.has("coords"))
        assertNull(EfaShapeParser.coords(root, departure))
    }

    @Test fun `repository uses trip path first then a verified variant then nothing`() = runBlocking {
        var now = T0
        var calls = 0
        var path: List<GeoPoint>? = RouteFixtures.street
        val cache = RouteShapeCache(clock = { now })
        val repository = RouteShapeRepository({ calls++; path }, cache, { now })
        assertEquals(RouteShapeSource.TRIP, repository.load(ride)!!.source)

        val nextTrip = RouteFixtures.ride().let { it.copy(departure = it.departure.copy(tripCode = "trip-2")) }
        path = null
        val variant = repository.load(nextTrip)!!
        assertEquals(RouteShapeSource.VARIANT, variant.source)
        assertEquals(2, calls)
        // No new request inside the retry window.
        repository.load(nextTrip.copy(fetchedAt = T0.plusSeconds(60)))
        assertEquals(2, calls)

        val otherVariant = RouteFixtures.ride(stops = RouteFixtures.stops().dropLast(1)).let {
            it.copy(departure = it.departure.copy(tripCode = "trip-3"))
        }
        assertNull(repository.load(otherVariant))
    }

    @Test fun `repository failures and wrong paths do not produce geometry`() = runBlocking {
        val repository = RouteShapeRepository({ throw java.io.IOException("offline") }, RouteShapeCache())
        assertNull(repository.load(ride))
        val wrong = RouteShapeRepository({ listOf(at(0.0, 0.0), at(2000.0, 1200.0)) }, RouteShapeCache())
        assertNull(wrong.load(ride))
    }

    @Test fun `repository never sends parallel requests`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val repository = RouteShapeRepository({
            active++; maximum = maxOf(maximum, active); gate.await(); active--; RouteFixtures.street
        }, RouteShapeCache())
        val first = async { repository.load(ride) }
        val second = async { repository.load(ride) }
        gate.complete(Unit)
        assertNotNull(first.await()); assertNotNull(second.await())
        assertEquals(1, maximum)
    }

    @Test fun `cache persists bounded public geometry and expires old entries`() {
        val dir = Files.createTempDirectory("shapes").toFile()
        val file = java.io.File(dir, "route_shapes.json")
        var now = T0
        RouteShapeCache(file, { now }).put(RouteShape.variantKey(ride), RouteFixtures.street)
        assertEquals(RouteFixtures.street.size, RouteShapeCache(file, { now }).get(RouteShape.variantKey(ride))!!.size)
        now = T0.plusSeconds(61L * 86_400)
        assertNull(RouteShapeCache(file, { now }).get(RouteShape.variantKey(ride)))
        file.writeText("kaputt")
        assertNull(RouteShapeCache(file, { T0 }).get(RouteShape.variantKey(ride)))
        dir.deleteRecursively()
    }

    @Test fun `vehicle is snapped only when close and heading the line direction`() {
        val shape = RouteFixtures.shape(ride)
        val history = listOf(VehicleObservation(RouteFixtures.along(700.0), T0))
        val snapped = RouteSnapping.vehicle(shape, RouteFixtures.along(800.0, 20.0), history)
        assertEquals(800.0, snapped.projected!!.offset, 2.0)
        // Driving against the line direction on the same street is not snapped.
        val reverse = RouteSnapping.vehicle(shape, RouteFixtures.along(700.0, 10.0), listOf(VehicleObservation(RouteFixtures.along(800.0), T0)))
        assertNull(reverse.projected)
        // Too far away: shown at its real position.
        assertNull(RouteSnapping.vehicle(shape, RouteFixtures.along(800.0, 60.0), history).projected)
    }

    @Test fun `diversion is reported and the vehicle is not pulled back onto the old line`() {
        val shape = RouteFixtures.shape(ride)
        val history = listOf(VehicleObservation(RouteFixtures.along(1000.0, 150.0), T0),
            VehicleObservation(RouteFixtures.along(1100.0, 160.0), T0.plusSeconds(20)))
        val result = RouteSnapping.vehicle(shape, RouteFixtures.along(1200.0, 170.0), history)
        assertTrue(result.deviating)
        assertNull(result.projected)
        assertFalse(RouteSnapping.vehicle(null, RouteFixtures.along(1200.0), history).deviating)
    }

    @Test fun `stop chain reference is internal and much more tolerant than real geometry`() {
        val chain = RouteReference.of(ride, null)!!
        assertFalse(chain.exact)
        assertTrue(chain.lateralTolerance(10.0) > 150.0)
        val exact = RouteReference.of(ride, RouteFixtures.shape(ride))!!
        assertTrue(exact.exact)
        assertTrue(exact.lateralTolerance(10.0) <= 90.0)
        val planned = exact.plannedTimeAt(ride, 750.0)!!
        assertEquals(T0.plusSeconds(180).epochSecond.toDouble(), planned.epochSecond.toDouble(), 1.0)
    }

    @Test fun `variant key separates direction and stop order`() {
        val other = ride.copy(departure = ride.departure.copy(destination = "Hbf"))
        assertNotEquals(RouteShape.variantKey(ride), RouteShape.variantKey(other))
        val sameStations = ride.copy(departure = ride.departure.copy(tripCode = "x", planned = Instant.EPOCH))
        assertEquals(RouteShape.variantKey(ride), RouteShape.variantKey(sameStations))
    }
}
