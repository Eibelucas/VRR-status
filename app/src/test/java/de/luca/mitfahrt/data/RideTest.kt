package de.luca.mitfahrt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RideTest {
    private val now = Instant.parse("2026-11-06T07:00:00Z")
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/$name.json")!!.readText())
    private fun departure() = EfaParser.departures(fixture("departures")).first { !it.cancelled }

    @Test fun `recognize only the configured hotspot and normal formatting variants`() {
        listOf("NEW-Hotspot", "\"NEW-Hotspot\"", "new-hotspot", "NEW_Hotspot", "NEW Hotspot").forEach { assertTrue(it, isNewHotspot(it)) }
        listOf("NEW", "Hotel NEW-Hotspot", "NEW-Hotspot-Free", "<unknown ssid>", "").forEach { assertFalse(it, isNewHotspot(it)) }
    }

    @Test fun `nearby stops use latitude longitude and preserve distance`() {
        val stops = EfaParser.stops(fixture("nearby"))
        assertEquals("de:05116:23007", stops.first().id)
        assertEquals(221, stops.first().distance)
        assertEquals(51.197158, stops.first().point!!.lat, .000001)
        assertTrue(stops.first().point!!.distanceTo(GeoPoint(51.196, 6.446)) < 300)
    }

    @Test fun `departure monitor excludes trains and keeps cancellations visible`() {
        val departures = EfaParser.departures(fixture("departures"))
        assertEquals(2, departures.size)
        assertTrue(departures.all { it.line == "006" })
        assertEquals(1, departures.count { it.cancelled })
        assertEquals(12, departure().delayMinutes)
    }

    @Test fun `real VRR trip response preserves exact journey and terminal`() {
        val ride = EfaParser.ride(fixture("ride"), departure(), now)
        assertEquals("de:05116:23007:1:6", ride.stops[ride.boardingIndex].id)
        assertTrue(ride.stops.size > 20)
        assertTrue(ride.terminal.name.contains("Wanlo Markt"))
        assertNull(ride.terminal.estimated)
        assertEquals(744, ride.offsetSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuse a different trip even on the same line`() {
        EfaParser.ride(fixture("ride"), departure().copy(tripCode = "1234"), now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refuse same trip code from a different service day`() {
        EfaParser.ride(fixture("ride"), departure().copy(planned = departure().planned.plusSeconds(86400)), now)
    }

    @Test fun `end of service yields no next stop and zero remaining time`() {
        val ride = DemoRide.create(now)
        val after = now.plusSeconds(1000)
        assertNull(ride.nextStop(after))
        assertEquals(0, ride.remainingMinutes(after))
        assertTrue(ride.complete(after))
        assertEquals(1f, ride.progress(after))
    }

    @Test fun `round countdown upward and never show negative minutes`() {
        assertEquals(1, minutesUntil(now.plusSeconds(1), now))
        assertEquals(2, minutesUntil(now.plusSeconds(61), now))
        assertEquals(0, minutesUntil(now.minusSeconds(10), now))
    }

    @Test fun `live estimates override propagated departure delay`() {
        val stop = RideStop("id", "Halt", null, now, now, now.plusSeconds(60))
        assertEquals(now.plusSeconds(60), stop.effective(600))
    }

    @Test fun `cancelled intermediate stops are skipped`() {
        val ride = DemoRide.create(now)
        val stops = ride.stops.mapIndexed { index, stop -> if (index == 1) stop.copy(cancelled = true) else stop }
        assertEquals(2, ride.copy(stops = stops).nextIndex(now))
    }

    @Test fun `GPS passed stops are not shown again`() {
        val ride = DemoRide.create(now).copy(gpsPassedIndex = 3)
        assertEquals(4, ride.nextIndex(now))
    }

    @Test fun `offline data becomes stale instead of being labelled live`() {
        val ride = DemoRide.create(now)
        assertFalse(ride.isStale(now.plusSeconds(120)))
        assertTrue(ride.isStale(now.plusSeconds(121)))
    }

    @Test fun `saved ride round trip preserves all fields`() {
        val ride = EfaParser.ride(fixture("ride"), departure(), now).copy(gpsPassedIndex = 4)
        assertEquals(ride, RideCodec.decode(RideCodec.encode(ride)))
    }

    @Test fun `demo contains no live estimates or location`() {
        val ride = DemoRide.create(now)
        assertTrue(ride.demo)
        assertNull(ride.departure.estimated)
        assertTrue(ride.stops.all { it.estimated == null && it.point == null })
        assertEquals(12, ride.remainingMinutes(now))
    }

    @Test fun `German times use the local timezone including summer time`() {
        assertEquals("08:00", clockTime(now))
        assertEquals("09:00", clockTime(Instant.parse("2026-07-06T07:00:00Z")))
    }
}
