package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlertPreferencesTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun waiting(seconds: Long): TrackingState {
        val base = DemoRide.create(now)
        val boarding = base.stops[0].copy(estimatedArrival = now.plusSeconds(seconds))
        return TrackingState(ride = base.copy(demo = false, boarded = false,
            stops = listOf(boarding) + base.stops.drop(1)), running = true)
    }
    @Test fun `default minute remains unchanged but earlier windows can be chosen`() {
        assertTrue(ArrivalAlertPolicy.due(waiting(60), AlertPreferences(), now))
        assertFalse(ArrivalAlertPolicy.due(waiting(61), AlertPreferences(), now))
        assertTrue(ArrivalAlertPolicy.due(waiting(120), AlertPreferences(leadSeconds = 120), now))
        assertFalse(ArrivalAlertPolicy.due(waiting(121), AlertPreferences(leadSeconds = 120), now))
        assertTrue(ArrivalAlertPolicy.due(waiting(180), AlertPreferences(leadSeconds = 180), now))
        assertFalse(ArrivalAlertPolicy.due(waiting(181), AlertPreferences(leadSeconds = 180), now))
    }
    @Test fun `muting or boarding suppresses both-device trigger without consuming alert state`() {
        val state = waiting(30)
        assertFalse(ArrivalAlertPolicy.due(state, AlertPreferences(arrivalEnabled = false), now))
        assertTrue(ArrivalAlertPolicy.due(state, AlertPreferences(arrivalEnabled = true), now))
        assertFalse(ArrivalAlertPolicy.due(state.copy(ride = state.ride!!.copy(boarded = true)), AlertPreferences(), now))
    }
    @Test fun `cancelled boarding stops stale data demos and past arrivals never alert`() {
        val state = waiting(30)
        val ride = state.ride!!
        val invalid = listOf(state.copy(ride = ride.copy(demo = true)), state.copy(running = false),
            state.copy(lastRefreshFailed = true), state.copy(ride = ride.copy(fetchedAt = now.minusSeconds(121))),
            state.copy(ride = ride.copy(fetchedAt = now.plusSeconds(31))),
            state.copy(ride = ride.copy(stops = ride.stops.mapIndexed { i, stop -> stop.copy(cancelled = i == 0) })),
            waiting(0), waiting(-10))
        invalid.forEach { assertFalse(ArrivalAlertPolicy.due(it, AlertPreferences(leadSeconds = 180), now)) }
    }
    @Test fun `arrival and exit controls are independent`() {
        val base = DemoRide.create(now)
        val r = base.copy(demo = false, boarded = true, stops = base.stops.mapIndexed { i, stop ->
            if (i == 0) stop.copy(estimatedArrival = now) else stop }).withExitTarget(1)
        val state = TrackingState(r, running = true)
        assertTrue(ArrivalAlertPolicy.exitDue(state, AlertPreferences(arrivalEnabled = false), now))
        assertFalse(ArrivalAlertPolicy.exitDue(state, AlertPreferences(exitEnabled = false), now))
    }
    @Test fun `unsupported lead intervals cannot silently alter alert timing`() {
        assertTrue(runCatching { AlertPreferences(leadSeconds = 0) }.isFailure)
        assertTrue(runCatching { AlertPreferences(leadSeconds = 3600) }.isFailure)
    }
    @Test fun `a freshly fetched timetable cannot trigger an arrival warning`() {
        val base = DemoRide.create(now)
        val r = base.copy(demo = false, boarded = false, stops = base.stops.mapIndexed { i, stop ->
            if (i == 0) stop.copy(arrival = now.plusSeconds(30), departure = now.plusSeconds(30)) else stop },
            departure = base.departure.copy(planned = now.plusSeconds(30)))
        assertFalse(ArrivalAlertPolicy.due(TrackingState(r, running = true), AlertPreferences(), now))
    }

    @Test fun `fresh monitor forecast can warn but missing or old monitor timestamps cannot`() {
        val base = DemoRide.create(now)
        val r = base.copy(demo = false, boarded = false, departure = base.departure.copy(
            estimated = now.plusSeconds(30), observedAt = now))
        assertTrue(ArrivalAlertPolicy.due(TrackingState(r, running = true), AlertPreferences(), now))
        listOf(null, now.minusSeconds(121), now.plusSeconds(31)).forEach { source ->
            assertFalse(ArrivalAlertPolicy.due(TrackingState(r.copy(departure = r.departure.copy(observedAt = source)),
                running = true), AlertPreferences(), now))
        }
    }

}
