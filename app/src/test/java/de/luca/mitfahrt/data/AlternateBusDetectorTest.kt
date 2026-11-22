package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AlternateBusDetectorTest {
    private val ride = RouteFixtures.ride(boarded = false, target = 5)

    private fun movingFixes(start: Instant): List<PhoneFix> = listOf(
        RouteFixtures.fix(500.0, start.plusSeconds(0), accuracy = 10f),
        RouteFixtures.fix(660.0, start.plusSeconds(15), accuracy = 10f),
        RouteFixtures.fix(820.0, start.plusSeconds(30), accuracy = 10f),
        RouteFixtures.fix(980.0, start.plusSeconds(45), accuracy = 10f)
    )

    private fun coMovingBus(id: String, line: String, start: Instant, destination: String = "Halt Ost"): Pair<NewVehicle, List<VehicleObservation>> {
        val track = listOf(
            VehicleObservation(RouteFixtures.along(505.0), start.plusSeconds(0)),
            VehicleObservation(RouteFixtures.along(665.0), start.plusSeconds(15)),
            VehicleObservation(RouteFixtures.along(825.0), start.plusSeconds(30))
        )
        val latest = NewVehicle(id, line, destination, RouteFixtures.along(985.0), start.plusSeconds(45))
        return latest to track
    }

    @Test
    fun `detects alternate bus after two confirmations with fresh GPS fixes`() {
        val t1 = T0.plusSeconds(45)
        val (busB, trackB) = coMovingBus("bus-b", "017", T0)
        val fixes1 = movingFixes(T0)
        val step1 = AlternateBusDetector.step(
            previous = AlternateBusTrackerState(),
            ride = ride,
            currentVehicle = null,
            currentTrack = emptyList(),
            fleet = listOf(busB),
            fleetTracks = mapOf(busB.id to trackB),
            phoneFixes = fixes1,
            now = t1
        )
        assertNull("Single evaluation must not trigger immediately", step1.confirmed)
        assertEquals(1, step1.state.confirmations)
        assertEquals("bus-b", step1.state.candidateVehicleId)

        val t2 = T0.plusSeconds(60)
        val fixes2 = fixes1 + RouteFixtures.fix(1140.0, t2, accuracy = 10f)
        val busB2 = busB.copy(point = RouteFixtures.along(1145.0), observedAt = t2)
        val step2 = AlternateBusDetector.step(
            previous = step1.state,
            ride = ride,
            currentVehicle = null,
            currentTrack = emptyList(),
            fleet = listOf(busB2),
            fleetTracks = mapOf(busB.id to (trackB + VehicleObservation(busB.point, busB.observedAt))),
            phoneFixes = fixes2,
            now = t2
        )
        assertNotNull("Second confirmation with newer GPS fix should confirm alternate bus", step2.confirmed)
        assertEquals("bus-b", step2.confirmed!!.vehicle.id)
        assertEquals("017", step2.confirmed!!.vehicle.line)
    }

    @Test
    fun `does not trigger while co-moving with tracked bus or walking or meeting oncoming bus`() {
        val t1 = T0.plusSeconds(45)
        val fixes = movingFixes(T0)
        val (trackedBus, trackedTrack) = coMovingBus("bus-a", "006", T0, destination = "Ziel")
        val (otherBus, otherTrack) = coMovingBus("bus-b", "017", T0)

        // Co-moving with the already tracked bus -> never suggest switch.
        val whileTracked = AlternateBusDetector.step(
            AlternateBusTrackerState(ride.departure.key, "bus-b", 1, T0.plusSeconds(30)),
            ride, trackedBus, trackedTrack, listOf(trackedBus, otherBus),
            mapOf(trackedBus.id to trackedTrack, otherBus.id to otherTrack), fixes, t1
        )
        assertNull(whileTracked.confirmed)
        assertEquals(0, whileTracked.state.confirmations)

        // Walking pace (< 4.5 m/s) -> never suggest switch.
        val walking = listOf(
            RouteFixtures.fix(500.0, T0, accuracy = 10f),
            RouteFixtures.fix(520.0, T0.plusSeconds(15), accuracy = 10f),
            RouteFixtures.fix(540.0, T0.plusSeconds(30), accuracy = 10f),
            RouteFixtures.fix(560.0, T0.plusSeconds(45), accuracy = 10f)
        )
        val walkStep = AlternateBusDetector.step(
            AlternateBusTrackerState(), ride, null, emptyList(),
            listOf(otherBus), mapOf(otherBus.id to otherTrack), walking, t1
        )
        assertNull(walkStep.confirmed)
        assertEquals(0, walkStep.state.confirmations)

        // Oncoming bus moving in the opposite direction -> rejected by heading alignment.
        val oncomingTrack = listOf(
            VehicleObservation(RouteFixtures.along(985.0), T0.plusSeconds(0)),
            VehicleObservation(RouteFixtures.along(825.0), T0.plusSeconds(15)),
            VehicleObservation(RouteFixtures.along(665.0), T0.plusSeconds(30))
        )
        val oncoming = NewVehicle("bus-opp", "017", "Gegenrichtung", RouteFixtures.along(505.0), t1)
        val oppStep = AlternateBusDetector.step(
            AlternateBusTrackerState(), ride, null, emptyList(),
            listOf(oncoming), mapOf(oncoming.id to oncomingTrack), fixes, t1
        )
        assertNull(oppStep.confirmed)
        assertEquals(0, oppStep.state.confirmations)
    }

    @Test
    fun `matchDeparture and applySwitchedRide preserve exit target when served`() {
        val now = T0.plusSeconds(60)
        val vehicle = NewVehicle("bus-b", "017", "Halt 6", RouteFixtures.along(600.0), now)
        val dep17 = Departure("new:017:A", "017", "Halt 6", ride.departure.stopId, ride.departure.stopName,
            "trip-17", now.minusSeconds(30), now.minusSeconds(30), "2", observedAt = now)
        val dep06 = ride.departure
        val matched = AlternateBusDetector.matchDeparture(vehicle, listOf(dep06, dep17), ride.departure.key, now)
        assertEquals(dep17.key, matched?.key)

        val nextRideSameTarget = Ride(dep17, RouteFixtures.stops(now), boardingIndex = 1, fetchedAt = now, boarded = false)
        val switched = AlternateBusDetector.applySwitchedRide(ride, nextRideSameTarget, now)
        assertTrue(switched.boarded)
        assertEquals("auto", switched.boardedBy)
        assertEquals(ride.exitTargetStop()?.id, switched.exitTargetStop()?.id)

        // When the new bus does not serve the previous exit stop, exit target is cleared cleanly.
        val differentStops = RouteFixtures.stops(now).mapIndexed { i, s -> s.copy(id = "de:05116:${900 + i}:1:1") }
        val nextRideOtherStops = Ride(dep17, differentStops, boardingIndex = 1, fetchedAt = now, boarded = false)
        val switchedOther = AlternateBusDetector.applySwitchedRide(ride, nextRideOtherStops, now)
        assertTrue(switchedOther.boarded)
        assertNull(switchedOther.exitTargetStop())
    }
}
