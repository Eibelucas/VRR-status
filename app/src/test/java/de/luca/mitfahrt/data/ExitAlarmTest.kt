package de.luca.mitfahrt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ExitAlarmTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride(): Ride {
        val base = DemoRide.create(now)
        val stops = (0..4).map { index ->
            val at = now.plusSeconds((index - 2) * 120L)
            RideStop("de:05116:${100 + index}:1:1", "Stop $index", null, at, at)
        }
        return base.copy(demo = false, boarded = true, stops = stops, boardingIndex = 0).withExitTarget(3)
    }
    private fun state(ride: Ride = ride()) = TrackingState(ride = ride, running = true)

    @Test fun `alarm activates at the previous served stop and not while still approaching it`() {
        assertTrue(ExitAlarm.due(state(), now))
        assertFalse(ExitAlarm.due(state(), now.minusSeconds(60)))
        assertTrue(ExitAlarm.due(state(), now.minusSeconds(15)))
        assertFalse(ExitAlarm.due(state(), now.plusSeconds(120)))
    }

    @Test fun `GPS passing the previous stop may precede its forecast`() {
        val ride = ride().copy(gpsPassedIndex = 2, fetchedAt = now.minusSeconds(60))
        assertTrue(ExitAlarm.due(state(ride), now.minusSeconds(60)))
        assertFalse(ExitAlarm.due(state(ride.copy(gpsPassedIndex = 3)), now))
    }

    @Test fun `cancelled intermediate stops are skipped when finding the stop before destination`() {
        val ride = ride().let { r -> r.copy(stops = r.stops.mapIndexed { i, s -> if (i == 2) s.copy(cancelled = true) else s }) }
        assertTrue(ExitAlarm.due(state(ride.copy(fetchedAt = now.minusSeconds(120))), now.minusSeconds(120)))
    }

    @Test fun `fresh changed predictions postpone a warning that has not fired`() {
        val ride = ride().let { r -> r.copy(stops = r.stops.mapIndexed { i, s ->
            if (i >= 2) s.copy(estimatedArrival = s.arrival!!.plusSeconds(180)) else s
        }) }
        assertFalse(ExitAlarm.due(state(ride), now))
        assertTrue(ExitAlarm.due(state(ride.copy(fetchedAt = now.plusSeconds(180))), now.plusSeconds(180)))
    }

    @Test fun `waiting demo cancelled paused and stale rides do not warn`() {
        val r = ride()
        listOf(state(r.copy(boarded = false)), state(r.copy(demo = true)),
            state(r.copy(departure = r.departure.copy(cancelled = true))),
            state(r.copy(fetchedAt = now.minusSeconds(121))), state(r.copy(fetchedAt = now.plusSeconds(60))),
            state(r).copy(running = false), state(r).copy(lastRefreshFailed = true),
            state(r.copy(stops = r.stops.mapIndexed { i, s -> if (i == 3) s.copy(cancelled = true) else s }))
        ).forEach { assertFalse(ExitAlarm.due(it, now)) }
    }

    @Test fun `old monitor estimates cannot be revived by a fresh trip fetch`() {
        val r = ride().copy(gpsPassedIndex = 2).let { it.copy(departure = it.departure.copy(
            estimated = it.departure.planned.plusSeconds(60), observedAt = now.minusSeconds(180))) }
        assertFalse(ExitAlarm.due(state(r), now))
        assertTrue(ExitAlarm.due(state(r.copy(departure = r.departure.copy(observedAt = now))), now))
    }

    @Test fun `an exit target must be later than boarding and a cancelled stop cannot be selected`() {
        assertTrue(runCatching { ride().withExitTarget(0) }.isFailure)
        val r = ride().let { it.copy(stops = it.stops.mapIndexed { i, s -> if (i == 3) s.copy(cancelled = true) else s }) }
        assertTrue(runCatching { r.withExitTarget(3) }.isFailure)
        assertFalse(ExitAlarm.due(state(ride().withExitTarget(null)), now))
        assertNull(ride().withExitTarget(null).exitTargetStop())
    }

    @Test fun `loop routes retain the selected occurrence instead of choosing the first same-named stop`() {
        val r = ride().let { it.copy(stops = it.stops.mapIndexed { i, s ->
            if (i == 4) s.copy(id = it.stops[2].id) else s
        }) }.withExitTarget(4)
        assertEquals(4, r.exitTargetIndex())
        assertFalse(ExitAlarm.due(state(r), now))
        assertTrue(ExitAlarm.due(state(r.copy(fetchedAt = now.plusSeconds(120))), now.plusSeconds(120)))
        assertNull(r.copy(targetStopIndex = null).exitTargetIndex())
    }

    @Test fun `refresh remaps a target around diversions and platform changes without changing its alert identity`() {
        val old = ride()
        val diversion = old.stops[1].copy(id = "de:05116:999", name = "Diversion")
        val fresh = old.copy(stops = old.stops.take(2) + diversion + old.stops.drop(2).map {
            if (it.id == old.targetStopId) it.copy(id = "de:05116:103:2:4") else it
        }).remapExitTargetFrom(old)
        assertEquals(4, fresh.exitTargetIndex())
        assertEquals("de:05116:103:2:4", fresh.targetStopId)
        assertEquals(ExitAlarm.id(old), ExitAlarm.id(fresh))
    }

    @Test fun `missing or different-lap destinations are cleared on refresh`() {
        val old = ride()
        assertNull(old.copy(stops = old.stops.filterIndexed { i, _ -> i != 3 }).remapExitTargetFrom(old).exitTargetIndex())
        val otherLap = old.copy(stops = old.stops.mapIndexed { i, s ->
            if (i == 3) s.copy(arrival = s.arrival!!.plusSeconds(3600), departure = s.departure!!.plusSeconds(3600)) else s
        }).remapExitTargetFrom(old)
        assertNull(otherLap.targetStopId)
    }

    @Test fun `selected target survives save restore while old rides decode without an alarm`() {
        val r = ride()
        assertEquals(r, RideCodec.decode(RideCodec.encode(r)))
        val old = JSONObject(RideCodec.encode(r)).apply { remove("targetStopId"); remove("targetStopIndex") }
        assertNull(RideCodec.decode(old.toString()).exitTargetIndex())
    }
}
