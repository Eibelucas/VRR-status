package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BoardingCountdownTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride() = DemoRide.create(now).copy(demo = false, boarded = false,
        departure = DemoRide.create(now).departure.copy(estimated = now.plusSeconds(205)), trackingStartedAt = now)

    @Test fun `countdown is minutes and seconds and never negative`() {
        assertEquals("3:25", countdownUntil(now.plusSeconds(205), now))
        assertEquals("0:01", countdownUntil(now.plusSeconds(1), now))
        assertEquals("0:00", countdownUntil(now.minusSeconds(1), now))
    }
    @Test fun `departure forecast is identified as departure rather than vehicle arrival`() {
        val time = ride().boardingTime()
        assertEquals(now.plusSeconds(205), time.at)
        assertTrue(time.live); assertFalse(time.isArrival)
    }
    @Test fun `explicit arrival forecast takes priority over departure forecast`() {
        val r = ride()
        val stops = r.stops.toMutableList()
        stops[0] = stops[0].copy(estimatedArrival = now.plusSeconds(180))
        val time = r.copy(stops = stops).boardingTime()
        assertTrue(time.isArrival); assertTrue(time.live)
        assertEquals(now.plusSeconds(180), time.at)
    }
    @Test fun `missing live times use the timetable and remain labelled as such`() {
        val r = ride().let { it.copy(departure = it.departure.copy(estimated = null)) }
        assertFalse(r.boardingTime().live)
    }
    @Test fun `waiting state and tracking start survive app restart`() {
        val r = ride()
        assertEquals(r, RideCodec.decode(RideCodec.encode(r)))
        assertFalse(RideCodec.decode(RideCodec.encode(r)).boarded)
    }
    @Test fun `old saved rides stay boarded when no waiting flag exists`() {
        val data = org.json.JSONObject(RideCodec.encode(ride()))
        data.remove("boarded"); data.remove("trackingStartedAt")
        assertTrue(RideCodec.decode(data.toString()).boarded)
    }
}
