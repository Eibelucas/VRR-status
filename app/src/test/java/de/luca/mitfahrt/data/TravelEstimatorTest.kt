package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test

/** Synthetic street: stops every 500 m and 120 s; boarding at stop 1 (500 m), exit at stop 5 (2800 m, T0 + 600 s). */
class TravelEstimatorTest {
    private val waiting = RouteFixtures.ride(boarded = false)
    private val boarded = waiting.board("manual", T0.plusSeconds(120))
    private fun state(ride: Ride, live: LiveBusState = LiveBusState(), gps: GpsProgressState? = null) =
        TrackingState(ride = ride, running = true, liveBus = live.copy(journeyKey = ride.departure.key),
            routeShape = RouteFixtures.shape(ride), gpsProgress = gps)
    private fun gps(offset: Double, at: java.time.Instant, ride: Ride = boarded) = GpsProgressState(journeyKey = ride.departure.key,
        active = true, offset = offset, confirmedAt = at, confirmedPoint = RouteFixtures.along(offset), status = GpsProgressStatus.TRACKING, exact = true)

    @Test fun `a bus chosen by hand gives the time to the exit from its position`() {
        val now = T0.plusSeconds(900) // long after the timetable: only the position counts
        val bus = RouteFixtures.bus("v7", 1000.0, 0.0, now.minusSeconds(10))
        val result = TravelEstimator.estimate(state(boarded, LiveBusState(vehicle = bus, selectedVehicleId = "v7", routeOffset = 1000.0)), now)!!
        assertEquals("bus", result.basis)
        // Stop 2 (1000 m) is planned at T0+240, the exit at T0+600: 360 s, minus 10 s since the measurement.
        assertEquals(350.0, result.seconds.toDouble(), 1.0)
        assertEquals(6, result.minutes)
        assertEquals(110.0, result.nextStopSeconds!!.toDouble(), 1.0) // stop 3 at 1500 m, 120 s further
    }

    @Test fun `an unassigned bus nearby is not used`() {
        val now = T0.plusSeconds(300)
        val bus = RouteFixtures.bus("v7", 1000.0, 0.0, now.minusSeconds(10))
        assertNull(TravelEstimator.estimate(state(boarded, LiveBusState(vehicle = bus, routeOffset = 1000.0)), now))
    }

    @Test fun `an old bus position falls back to the phone gps on board`() {
        val now = T0.plusSeconds(400)
        val bus = RouteFixtures.bus("v7", 1000.0, 0.0, now.minusSeconds(200))
        val result = TravelEstimator.estimate(state(boarded, LiveBusState(vehicle = bus, selectedVehicleId = "v7", routeOffset = 1000.0),
            gps(1500.0, now.minusSeconds(5))), now)!!
        assertEquals("gps", result.basis)
        assertEquals(235.0, result.seconds.toDouble(), 1.0) // stop 3 at T0+360 to exit at T0+600, minus 5 s
    }

    @Test fun `without any fresh position nothing is calculated`() {
        val now = T0.plusSeconds(400)
        assertNull(TravelEstimator.estimate(state(boarded), now))
        assertNull(TravelEstimator.estimate(state(boarded, gps = gps(1500.0, now.minusSeconds(120))), now))
        assertNull("GPS switched off", TravelEstimator.estimate(state(boarded, gps = gps(1500.0, now.minusSeconds(5))), now, gpsEnabled = false))
    }

    @Test fun `while waiting the chosen bus gives the time to my stop and phone gps does not`() {
        val now = T0.plusSeconds(60)
        val bus = RouteFixtures.bus("v7", 250.0, 0.0, now.minusSeconds(4))
        val result = TravelEstimator.estimate(state(waiting, LiveBusState(vehicle = bus, selectedVehicleId = "v7", routeOffset = 250.0)), now)!!
        assertEquals(56.0, result.seconds.toDouble(), 1.0) // half way between stop 0 and stop 1: 60 s, minus 4 s
        assertNull(result.nextStopSeconds)
        assertNull(TravelEstimator.estimate(state(waiting, gps = gps(250.0, now, waiting)), now))
    }

    @Test fun `a bus already past my stop gives no waiting time`() {
        val now = T0.plusSeconds(200)
        val bus = RouteFixtures.bus("v7", 900.0, 0.0, now.minusSeconds(4))
        assertNull(TravelEstimator.estimate(state(waiting, LiveBusState(vehicle = bus, selectedVehicleId = "v7", routeOffset = 900.0)), now))
    }

    @Test fun `demo and cancelled rides get no calculated time`() {
        val cancelled = boarded.copy(departure = boarded.departure.copy(cancelled = true))
        assertNull(TravelEstimator.estimate(state(cancelled, gps = gps(1500.0, T0, cancelled)), T0))
    }
}
