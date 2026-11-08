package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BoardingCountdownTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride() = DemoRide.create(now).copy(demo = false, boarded = false,
        departure = DemoRide.create(now).departure.copy(estimated = now.plusSeconds(205), observedAt = now), trackingStartedAt = now)

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
    @Test fun `a fresh trip cannot make an old monitor forecast live again`() {
        val r = ride().let { it.copy(departure = it.departure.copy(observedAt = now.minusSeconds(121))) }
        assertTrue(r.isStale(now))
        assertFalse(r.boardingTime().isFresh(now))
        val fresh = r.copy(departure = r.departure.copy(observedAt = now))
        assertFalse(fresh.isStale(now))
        assertTrue(fresh.boardingTime().isFresh(now))
    }
    @Test fun `independent fresh trip arrival does not inherit an old monitor timestamp`() {
        val r = ride().let { base -> base.copy(
            departure = base.departure.copy(observedAt = now.minusSeconds(300)),
            stops = base.stops.mapIndexed { i, stop -> if (i == base.boardingIndex)
                stop.copy(estimatedArrival = now.plusSeconds(60)) else stop }) }
        assertFalse(r.isStale(now))
        assertTrue(r.boardingTime().isFresh(now))
        assertTrue(r.copy(fetchedAt = now.minusSeconds(121)).isStale(now))
    }
    @Test fun `future monitor timestamps beyond clock tolerance are not considered fresh`() {
        val r = ride().let { it.copy(departure = it.departure.copy(observedAt = now.plusSeconds(31))) }
        assertTrue(r.isStale(now))
        assertFalse(r.boardingTime().isFresh(now))
    }

    @Test fun `only an actual fresh forecast supplies a live boarding time`() {
        val r = ride()
        assertEquals(now.plusSeconds(205), r.liveBoardingTime(now)?.at)
        val timetable = r.copy(departure = r.departure.copy(estimated = null))
        assertNull(timetable.liveBoardingTime(now))
        assertNull(r.copy(fetchedAt = now.minusSeconds(121)).liveBoardingTime(now))
        assertNull(r.copy(fetchedAt = now.plusSeconds(31)).liveBoardingTime(now))
        assertNull(r.copy(departure = r.departure.copy(observedAt = now.minusSeconds(121))).liveBoardingTime(now))
        assertNull(r.copy(departure = r.departure.copy(observedAt = null)).liveBoardingTime(now))
        assertNull(r.copy(departure = r.departure.copy(observedAt = now.plusSeconds(31))).liveBoardingTime(now))
    }

    @Test fun `fresh direct boarding forecast stays independent of an old monitor`() {
        val r = ride().let { base -> base.copy(departure = base.departure.copy(observedAt = now.minusSeconds(300)),
            stops = base.stops.mapIndexed { i, stop -> if (i == base.boardingIndex)
                stop.copy(estimatedArrival = now.plusSeconds(75)) else stop }) }
        assertEquals(now.plusSeconds(75), r.liveBoardingTime(now)?.at)
        assertTrue(r.liveBoardingTime(now)!!.isArrival)
    }

    @Test fun `cancelled demo and invalid boarding stops never supply a real boarding forecast`() {
        val r = ride()
        assertNull(r.copy(demo = true).liveBoardingTime(now))
        assertNull(r.copy(departure = r.departure.copy(cancelled = true)).liveBoardingTime(now))
        assertNull(r.copy(stops = r.stops.mapIndexed { i, stop -> stop.copy(cancelled = i == r.boardingIndex) }).liveBoardingTime(now))
        assertNull(r.copy(boardingIndex = r.stops.size).liveBoardingTime(now))
    }

    @Test fun `departure lists only expose countdown estimates with a current source`() {
        val base = ride().departure.copy(estimated = now.plusSeconds(60), observedAt = now)
        assertEquals(base.estimated, base.liveEstimate(now))
        assertNull(base.copy(estimated = null).liveEstimate(now))
        assertNull(base.copy(observedAt = null).liveEstimate(now))
        assertNull(base.copy(observedAt = now.minusSeconds(121)).liveEstimate(now))
        assertNull(base.copy(observedAt = now.plusSeconds(31)).liveEstimate(now))
        assertNull(base.copy(cancelled = true).liveEstimate(now))
    }

}
