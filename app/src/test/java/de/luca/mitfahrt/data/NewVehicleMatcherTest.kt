package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NewVehicleMatcherTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-11-07T08:30:00Z")

    private fun ride(now: Instant = this.now, line: String = "006", destination: String = "Mönchengladbach, Hauptbahnhof"): Ride {
        val stops = (0..3).map { index ->
            val at = now.minusSeconds(300).plusSeconds(index * 300L)
            RideStop("de:05116:${1000 + index}:1:1", if (index == 3) "Hbf / Europaplatz" else "Halt $index",
                GeoPoint(51.19 + index * .0012, 6.44 + index * .002), at, at)
        }
        val departure = Departure("new:$line:H", line, destination, stops[1].id, stops[1].name,
            "efa-actual-trip", stops[1].departure!!, null, "1", observedAt = now)
        return Ride(departure, stops, 1, now, boarded = false)
    }

    private fun vehicle(ride: Ride = ride(), id: String = "2100", now: Instant = this.now): NewVehicle {
        val first = ride.stops.first().departure!!.atZone(berlin)
        return NewVehicle(id, ride.departure.line, "MG Hbf / Europaplatz", ride.stops.first().point!!, now,
            tripId = "01", scheduleId = "9999", journeyStartSeconds = first.toLocalTime().toSecondOfDay(),
            routeStops = ride.stops.map { stop ->
                val at = stop.departure!!.atZone(berlin)
                NewVehicleStop(stop.point!!.copy(lat = stop.point.lat + .0001), at.toLocalTime().toSecondOfDay(),
                    stop.name, at.dayOfMonth)
            }, routeObservedAt = now.minusSeconds(15))
    }

    @Test fun `matching a line and headsign gives a candidate but never proves the departure`() {
        val r = ride()
        val candidate = vehicle(r).copy(routeStops = emptyList(), journeyStartSeconds = null)
        assertEquals(listOf(candidate), NewVehicleMatcher.candidates(r, listOf(candidate), now))
        assertTrue(NewVehicleMatcher.sameDirection(r, candidate))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(candidate), now))
    }

    @Test fun `a unique fresh path matches exact start day times and boarding in sequence`() {
        val r = ride()
        val bus = vehicle(r)
        assertEquals(bus, NewVehicleMatcher.confirmed(r, listOf(bus), now))
        assertEquals(bus, NewVehicleMatcher.confirmed(r, listOf(bus, bus), now))
    }

    @Test fun `different vehicles with the same schedule remain ambiguous`() {
        val r = ride()
        val bus = vehicle(r)
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus, bus.copy(id = "2200")), now))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus, bus.copy(point = GeoPoint(51.2, 6.45))), now))
    }

    @Test fun `unknown direction is selectable but ranked after a confirmed headsign`() {
        val r = ride()
        val known = vehicle(r)
        val unknown = known.copy(id = "1", destination = "", point = r.stops[1].point!!)
        assertEquals(listOf(known, unknown), NewVehicleMatcher.candidates(r, listOf(unknown, known), now))
        assertFalse(NewVehicleMatcher.sameDirection(r, unknown))
        assertTrue(NewVehicleMatcher.matchesCandidate(r, unknown, now))
    }

    @Test fun `leading zeros match but night and express line prefixes remain distinct`() {
        val r = ride()
        val bus = vehicle(r).copy(line = "6")
        assertTrue(NewVehicleMatcher.matchesCandidate(r, bus, now))
        assertFalse(NewVehicleMatcher.matchesCandidate(r, bus.copy(line = "NE6"), now))
        assertFalse(NewVehicleMatcher.matchesCandidate(r, bus.copy(line = "SB6"), now))
        assertTrue(NewVehicleMatcher.candidates(r, listOf(bus.copy(line = "015")), now).isEmpty())
    }

    @Test fun `opaque LED and advertising text remains manually selectable but never proves direction or departure`() {
        val r = ride()
        listOf("5MÖNCHENGLADBACH HAUPTBAHNHOF    MPL-HARDTERBR.  MÖNCHENGLADB.HBF",
            "NEW.DE/BUSFAHREN", "Rheydt Hauptbahnhof", "MG Hbf / Europaplatz").forEach { packed ->
            val bus = vehicle(r).copy(destination = packed, directionReliable = false)
            assertTrue(NewVehicleMatcher.matchesCandidate(r, bus, now))
            assertEquals(listOf(bus), NewVehicleMatcher.candidates(r, listOf(bus), now))
            assertFalse(NewVehicleMatcher.sameDirection(r, bus))
            // A questionable display string must not become a direction assertion through sanitizing.
            assertNull(NewVehicleMatcher.confirmed(r, listOf(bus), now))
        }
        val opposite = vehicle(r).copy(destination = "Rheydt Hauptbahnhof", directionReliable = true)
        assertFalse(NewVehicleMatcher.matchesCandidate(r, opposite, now))
    }

    @Test fun `the selected intermediate destination is not confused with the bus headsign`() {
        val r = ride().withExitTarget(2)
        val bus = vehicle(r)
        assertTrue(NewVehicleMatcher.sameDirection(r, bus))
        assertEquals(bus, NewVehicleMatcher.confirmed(r, listOf(bus), now))
        assertFalse(NewVehicleMatcher.sameDirection(r, bus.copy(destination = r.displayTarget().name)))
    }

    @Test fun `MG and Rheydt main stations are never folded into the same direction`() {
        val r = ride()
        val bus = vehicle(r).copy(destination = "Rheydt Hauptbahnhof")
        assertFalse(NewVehicleMatcher.sameDirection(r, bus))
        assertFalse(NewVehicleMatcher.matchesCandidate(r, bus, now))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus), now))
    }

    @Test fun `raw course and provider schedule IDs are not treated as EFA trip identifiers`() {
        val r = ride()
        val bus = vehicle(r).copy(tripId = r.departure.tripCode, scheduleId = r.departure.tripCode,
            routeStops = emptyList(), journeyStartSeconds = null)
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus), now))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(vehicle(r).copy(journeyStartSeconds = null)), now))
    }

    @Test fun `stale future and invalid observations are never selectable current positions`() {
        val r = ride()
        val bus = vehicle(r)
        listOf(bus.copy(observedAt = now.minusSeconds(91)), bus.copy(observedAt = now.plusSeconds(16)),
            bus.copy(point = GeoPoint(0.0, 0.0)), bus.copy(point = GeoPoint(Double.NaN, 6.4)),
            bus.copy(point = GeoPoint(95.0, 6.4))).forEach {
            assertFalse(NewVehicleMatcher.matchesCandidate(r, it, now))
            assertNull(NewVehicleMatcher.confirmed(r, listOf(it), now))
        }
    }

    @Test fun `fresh position cannot revive a stale or missing schedule association`() {
        val r = ride()
        val bus = vehicle(r)
        listOf(bus.copy(routeObservedAt = null), bus.copy(routeObservedAt = now.minusSeconds(91)),
            bus.copy(routeObservedAt = now.plusSeconds(16))).forEach {
            assertTrue(NewVehicleMatcher.matchesCandidate(r, it, now))
            assertNull(NewVehicleMatcher.confirmed(r, listOf(it), now))
        }
        assertNull(NewVehicleMatcher.confirmed(r.copy(fetchedAt = now.minusSeconds(121)), listOf(bus), now))
    }

    @Test fun `different start time or published day prevents confirmation despite matching line`() {
        val r = ride()
        val bus = vehicle(r)
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus.copy(journeyStartSeconds = bus.journeyStartSeconds!! + 60)), now))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus.copy(routeStops = bus.routeStops.map {
            it.copy(plannedDayOfMonth = 6)
        })), now))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus.copy(routeStops = bus.routeStops.map {
            it.copy(plannedDayOfMonth = null)
        })), now))
    }

    @Test fun `at least three distinct stations and the chosen boarding time are required`() {
        val r = ride()
        val bus = vehicle(r)
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus.copy(routeStops = bus.routeStops.take(2))), now))
        val noBoarding = bus.routeStops.mapIndexed { index, stop -> if (index == r.boardingIndex)
            stop.copy(plannedSeconds = stop.plannedSeconds!! + 60) else stop }
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus.copy(routeStops = noBoarding)), now))
        val duplicateIds = r.copy(stops = r.stops.map { it.copy(id = r.stops.first().id) },
            departure = r.departure.copy(stopId = r.stops.first().id))
        assertNull(NewVehicleMatcher.confirmed(duplicateIds, listOf(bus), now))
    }

    @Test fun `ordered subsequences allow an extra EFA diversion stop but not reversed source order`() {
        val r = ride()
        val bus = vehicle(r)
        val extra = RideStop("de:05116:999", "Umleitung", GeoPoint(51.6, 6.8),
            now.plusSeconds(100), now.plusSeconds(100))
        val diverted = r.copy(stops = r.stops.take(2) + extra + r.stops.drop(2))
        assertEquals(bus, NewVehicleMatcher.confirmed(diverted, listOf(bus), now))
        val reversed = bus.copy(routeStops = bus.routeStops.take(1) + bus.routeStops.drop(1).reversed())
        assertNull(NewVehicleMatcher.confirmed(r, listOf(reversed), now))
    }

    @Test fun `cancelled or demo journeys and cancelled boarding stops have no vehicle matches`() {
        val r = ride()
        val bus = vehicle(r)
        val cancelledBoarding = r.copy(stops = r.stops.mapIndexed { index, stop ->
            if (index == r.boardingIndex) stop.copy(cancelled = true) else stop })
        listOf(r.copy(demo = true), r.copy(departure = r.departure.copy(cancelled = true)),
            cancelledBoarding, r.copy(boardingIndex = 99)).forEach {
            assertTrue(NewVehicleMatcher.candidates(it, listOf(bus), now).isEmpty())
            assertNull(NewVehicleMatcher.confirmed(it, listOf(bus), now))
        }
    }

    @Test fun `published day and complete EFA dates support midnight without guessing a service day`() {
        val night = Instant.parse("2026-11-07T23:00:00Z") // Sunday 00:00 Berlin, start Saturday 23:55.
        val r = ride(night)
        val bus = vehicle(r, now = night)
        assertEquals(7, bus.routeStops.first().plannedDayOfMonth)
        assertEquals(8, bus.routeStops[1].plannedDayOfMonth)
        assertEquals(bus, NewVehicleMatcher.confirmed(r, listOf(bus), night))
        val old = ride(night.minusSeconds(31 * 86400L))
        assertNull(NewVehicleMatcher.confirmed(old.copy(fetchedAt = night), listOf(bus), night))
    }

    @Test fun `ambiguous repeated Berlin wall times cannot confirm a scheduled trip`() {
        val repeatedHour = Instant.parse("2026-10-25T00:30:00Z")
        val r = ride(repeatedHour)
        val bus = vehicle(r, now = repeatedHour)
        assertTrue(NewVehicleMatcher.matchesCandidate(r, bus, repeatedHour))
        assertNull(NewVehicleMatcher.confirmed(r, listOf(bus), repeatedHour))
    }
}
