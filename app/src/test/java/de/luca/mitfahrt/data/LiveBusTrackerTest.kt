package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

class LiveBusTrackerTest {
    private val start = Instant.parse("2026-11-08T07:00:00Z")
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun ride(line: String = "006", trip: String = "chosen"): Ride {
        val stops = (0..3).map { index ->
            val at = start.plusSeconds((index - 1) * 120L)
            RideStop("de:05116:${100 + index}:1:1", if (index == 3) "Ziel" else "Halt $index",
                GeoPoint(51.19 + index * .002, 6.44), at, at)
        }
        return Ride(Departure("new:$line:H", line, "Ziel", stops[1].id, stops[1].name,
            trip, stops[1].departure!!, start.plusSeconds(30), "1", observedAt = start),
            stops, 1, start, boarded = false)
    }
    private fun bus(id: String = "2114", at: Instant = start, line: String = "006", rich: Boolean = false): NewVehicle {
        val r = ride(line)
        val first = r.stops.first().departure!!.atZone(berlin)
        val path = if (rich) r.stops.map { stop ->
            val planned = stop.departure!!.atZone(berlin)
            NewVehicleStop(stop.point!!, planned.toLocalTime().toSecondOfDay(), stop.name, planned.dayOfMonth)
        } else emptyList()
        return NewVehicle(id, line, "Ziel", GeoPoint(51.19, 6.44), at,
            delaySeconds = 0, tripId = "02", scheduleId = if (rich) "3372" else null,
            journeyStartSeconds = if (rich) first.toLocalTime().toSecondOfDay() else null,
            routeStops = path, routeObservedAt = if (rich) at else null)
    }
    private fun state(ride: Ride = ride(), vehicle: NewVehicle = bus(), selected: Boolean = true) =
        LiveBusState(journeyKey = ride.departure.key, candidates = listOf(vehicle), vehicle = vehicle,
            selectedVehicleId = if (selected) vehicle.id else null, confirmed = !selected,
            fetchedAt = start, revision = 7)

    @Test fun `same line and headsign produce candidates without pretending to identify a departure`() = runBlocking {
        val r = ride()
        val item = bus()
        var individualCalls = 0
        val tracker = LiveBusTracker({ listOf(item) }, { individualCalls++; item }, { start })
        val result = tracker.refresh(r, LiveBusState())
        assertEquals(r.departure.key, result.journeyKey)
        assertEquals(listOf(item), result.candidates)
        assertNull(result.vehicle)
        assertFalse(result.confirmed)
        assertNull(result.selectedVehicleId)
        assertEquals(0, individualCalls)
    }

    @Test fun `manual selection uses the latest state candidate and never itself proves a trip`() {
        val r = ride()
        val staleSheet = bus()
        val latest = staleSheet.copy(point = GeoPoint(51.192, 6.441), observedAt = start.plusSeconds(10))
        val state = state(r, latest).copy(vehicle = null, selectedVehicleId = null, confirmed = false)
        val selected = LiveBusTracker.select(r, state, staleSheet, start.plusSeconds(10))!!
        assertEquals(latest, selected.vehicle)
        assertEquals(latest.id, selected.selectedVehicleId)
        assertFalse(selected.confirmed)
        assertEquals(7L, selected.revision)
    }

    @Test fun `selection rejects stale wrong line unknown identity different journey and boarded rides`() {
        val r = ride()
        val item = bus()
        val current = state(r, item)
        assertNull(LiveBusTracker.select(r, current, item.copy(id = "unlisted"), start))
        assertNull(LiveBusTracker.select(r, state(r, item.copy(line = "017")), item.copy(line = "017"), start))
        assertNull(LiveBusTracker.select(r, current, item, start.plusSeconds(91)))
        assertNull(LiveBusTracker.select(r, current.copy(journeyKey = "other"), item, start))
        assertNull(LiveBusTracker.select(r.copy(boarded = true), current, item, start))
        assertNull(LiveBusTracker.select(r.copy(demo = true), current, item, start))
    }

    @Test fun `selected telemetry updates via individual node without changing the VRR forecast`() = runBlocking {
        val r = ride()
        val original = bus()
        val later = start.plusSeconds(15)
        val current = original.copy(observedAt = later, point = GeoPoint(51.194, 6.443), delaySeconds = 600)
        var fleetCalls = 0
        val tracker = LiveBusTracker({ fleetCalls++; listOf(current) }, { current }, { later })
        val result = tracker.refresh(r, state(r, original))
        assertEquals(current, result.vehicle)
        assertEquals(original.id, result.selectedVehicleId)
        assertFalse(result.confirmed)
        assertEquals(7L, result.revision)
        assertEquals(0, fleetCalls)
        assertEquals(start.plusSeconds(30), r.departure.estimated)
        assertEquals(start, r.fetchedAt)
    }

    @Test fun `selected vehicle changing line or direction is no longer displayed`() = runBlocking {
        val r = ride()
        for (changed in listOf(bus(line = "017"), bus().copy(destination = "Anderer Endhalt"))) {
            val tracker = LiveBusTracker({ emptyList() }, { changed }, { start })
            val result = tracker.refresh(r, state(r))
            assertNull(result.vehicle)
            assertNull(result.selectedVehicleId)
            assertFalse(result.confirmed)
            assertNotNull(result.error)
        }
    }

    @Test fun `missing or stale selected observation cannot remain a current marker`() = runBlocking {
        val r = ride()
        for (observed in listOf(null, bus(at = start.minusSeconds(91)))) {
            val tracker = LiveBusTracker({ emptyList() }, { observed }, { start })
            val result = tracker.refresh(r, state(r))
            assertNull(result.vehicle)
            assertEquals("2114", result.selectedVehicleId)
            assertTrue(result.candidates.isEmpty())
            assertFalse(result.confirmed)
            assertNotNull(result.error)
        }
    }

    @Test fun `an old sample may survive only as stale source data with a visible error`() = runBlocking {
        val old = bus(at = start.minusSeconds(91))
        val tracker = LiveBusTracker({ emptyList() }, { null }, { start })
        val result = tracker.refresh(ride(), state(vehicle = old))
        assertEquals(old, result.vehicle)
        assertFalse(result.vehicle!!.isFresh(start))
        assertEquals(old.observedAt, result.vehicle!!.observedAt)
        assertNotNull(result.error)
    }

    @Test fun `fleet cache expires at thirty seconds and never refreshes an observation timestamp`() = runBlocking {
        var now = start
        var calls = 0
        val item = bus()
        val tracker = LiveBusTracker({ calls++; listOf(item) }, { item }, { now })
        val first = tracker.refresh(ride(), LiveBusState())
        now = start.plusSeconds(29)
        val second = tracker.refresh(ride(), first)
        assertEquals(1, calls)
        assertEquals(start, second.candidates.single().observedAt)
        now = start.plusSeconds(30)
        tracker.refresh(ride(), second)
        assertEquals(2, calls)
    }

    @Test fun `source older than ninety seconds is hidden even while its fleet cache is still valid`() = runBlocking {
        var now = start
        var calls = 0
        val item = bus(at = start.minusSeconds(80))
        val tracker = LiveBusTracker({ calls++; listOf(item) }, { item }, { now })
        val first = tracker.refresh(ride(), LiveBusState())
        assertEquals(1, first.candidates.size)
        now = start.plusSeconds(20)
        val next = tracker.refresh(ride(), first)
        assertTrue(next.candidates.isEmpty())
        assertNull(next.vehicle)
        assertEquals(1, calls)
    }

    @Test fun `confirmed position updates individually and fleet ambiguity removes confirmation`() = runBlocking {
        var now = start
        var fleetCalls = 0
        var individualCalls = 0
        val tracker = LiveBusTracker({
            fleetCalls++
            val current = bus(at = now, rich = true)
            if (fleetCalls == 1) listOf(current) else listOf(current, current.copy(id = "2120"))
        }, { individualCalls++; bus(at = now, rich = true) }, { now })
        val first = tracker.refresh(ride(), LiveBusState())
        assertTrue(first.confirmed)
        assertEquals("2114", first.vehicle!!.id)
        now = start.plusSeconds(15)
        val second = tracker.refresh(ride(), first)
        assertTrue(second.confirmed)
        assertEquals(now, second.vehicle!!.observedAt)
        assertEquals(1, individualCalls)
        now = start.plusSeconds(30)
        val third = tracker.refresh(ride(), second)
        assertEquals(2, fleetCalls)
        assertFalse(third.confirmed)
        assertNull(third.vehicle)
        assertEquals(2, third.candidates.size)
    }

    @Test fun `network failure does not revive a cached match on the following refresh`() = runBlocking {
        var fleetCalls = 0
        var fail = false
        val tracker = LiveBusTracker({ fleetCalls++; listOf(bus(rich = true)) }, {
            if (fail) throw IOException("offline") else bus(rich = true)
        }, { start })
        val first = tracker.refresh(ride(), LiveBusState())
        assertTrue(first.confirmed)
        fail = true
        val failed = tracker.refresh(ride(), first)
        assertNull(failed.vehicle)
        assertFalse(failed.confirmed)
        assertNotNull(failed.error)
        assertEquals(first.fetchedAt, failed.fetchedAt)
        assertTrue(failed.candidates.isEmpty())
        fail = false
        tracker.refresh(ride(), failed)
        assertEquals(2, fleetCalls)
    }

    @Test fun `individual response for a different vehicle is not accepted`() = runBlocking {
        val tracker = LiveBusTracker({ emptyList() }, { bus(id = "other") }, { start })
        val result = tracker.refresh(ride(), state())
        assertNull(result.vehicle)
        assertNotNull(result.error)
        assertEquals("2114", result.selectedVehicleId)
    }

    @Test fun `new journey never inherits a selected vehicle and forces a fresh fleet snapshot`() = runBlocking {
        var calls = 0
        val tracker = LiveBusTracker({ calls++; listOf(bus()) }, { bus() }, { start })
        val first = tracker.refresh(ride(), LiveBusState())
        val selected = LiveBusTracker.select(ride(), first, bus(), start)!!
        val next = ride(line = "017", trip = "next")
        val result = tracker.refresh(next, selected)
        assertEquals(next.departure.key, result.journeyKey)
        assertNull(result.selectedVehicleId)
        assertNull(result.vehicle)
        assertTrue(result.candidates.isEmpty())
        assertEquals(2, calls)
    }

    @Test fun `demo boarded and cancelled rides do not fetch vehicle positions`() = runBlocking {
        val tracker = LiveBusTracker({ error("must not fetch") }, { error("must not fetch") }, { start })
        val r = ride()
        for (disabled in listOf(r.copy(demo = true), r.copy(boarded = true), r.copy(departure = r.departure.copy(cancelled = true)))) {
            val result = tracker.refresh(disabled, state(r))
            assertNull(result.vehicle)
            assertTrue(result.candidates.isEmpty())
            assertNull(result.selectedVehicleId)
            assertEquals(7L, result.revision)
        }
    }

    @Test fun `cancellation remains cancellation instead of being converted into an offline state`() = runBlocking {
        val tracker = LiveBusTracker({ throw CancellationException("screen closed") }, { null }, { start })
        val failure = runCatching { tracker.refresh(ride(), LiveBusState()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
