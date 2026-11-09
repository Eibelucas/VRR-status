package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import de.luca.mitfahrt.data.RouteFixtures.along
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VehicleMatchingTest {
    private val auto = AutoMatchOptions(enabled = true)

    /** Drives the real tracker with a scripted NEW feed; the fleet cache is 30 s, so steps are 35 s. */
    private class Feed(var now: Instant = T0) {
        var vehicles: List<NewVehicle> = emptyList()
        val tracker = LiveBusTracker({ vehicles }, { id -> vehicles.firstOrNull { it.id == id } }, { now })
    }

    private fun bus(id: String, offset: Double, at: Instant, delay: Int? = 0, side: Double = 0.0,
                    destination: String = "Ziel", reliable: Boolean = true) =
        NewVehicle(id, "006", destination, along(offset, side), at, delaySeconds = delay, directionReliable = reliable)

    private fun waitingRide(now: Instant, live: Boolean = true) =
        RouteFixtures.ride(delaySeconds = if (live) 0 else null, fetchedAt = now)

    // ---- Waiting -------------------------------------------------------------------------

    @Test fun `two buses of the same line close behind each other - the one matching this trip is chosen`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        var state = LiveBusState()
        val statuses = mutableListOf<AutoMatchStatus>()
        for (step in 0..2) {
            feed.now = T0.plusSeconds(step * 35L)
            val t = step * 35.0
            // Ours runs on time; the previous trip of the same line is ~55 s ahead of our timetable and reports +10 min.
            feed.vehicles = listOf(bus("ours", 4.1667 * t, feed.now, delay = 0), bus("previous", 230 + 4.1667 * t, feed.now, delay = 600))
            state = feed.tracker.refresh(waitingRide(feed.now), state, options = auto, shape = shape)
            statuses += state.auto.status
        }
        assertEquals(AutoMatchStatus.ASSIGNED, state.auto.status)
        assertEquals("ours", state.vehicle?.id)
        assertFalse("movement detection is not an operator confirmation", state.confirmed)
        assertEquals(AutoMatchBasis.APPROACH, state.auto.basis)
        // Never assigned on the first, single measurement.
        assertNotEquals(AutoMatchStatus.ASSIGNED, statuses.first())
    }

    @Test fun `without delay information two close buses are offered as a choice instead of guessing`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        var state = LiveBusState()
        for (step in 0..3) {
            feed.now = T0.plusSeconds(step * 35L)
            val t = step * 35.0
            feed.vehicles = listOf(bus("a", 4.1667 * t, feed.now, delay = null), bus("b", 200 + 4.1667 * t, feed.now, delay = null))
            state = feed.tracker.refresh(waitingRide(feed.now, live = false), state, options = auto, shape = shape)
            assertNotEquals(AutoMatchStatus.ASSIGNED, state.auto.status)
            assertNull(state.vehicle)
        }
        assertEquals(AutoMatchStatus.AMBIGUOUS, state.auto.status)
        assertEquals(setOf("a", "b"), state.auto.choices.toSet())
    }

    @Test fun `opposite direction at the same stop is never chosen for its proximity`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        var state = LiveBusState()
        for (step in 0..3) {
            feed.now = T0.plusSeconds(step * 35L)
            val t = step * 35.0
            feed.vehicles = listOf(
                // Reliable other headsign right at the boarding stop.
                bus("reverse-signed", 500.0, feed.now, destination = "Hbf"),
                // Unknown headsign driving away from the boarding stop along the same street.
                bus("reverse-unknown", 480 - 4.0 * t, feed.now, side = -6.0, destination = "", reliable = false))
            state = feed.tracker.refresh(waitingRide(feed.now), state, options = auto, shape = shape)
        }
        assertNull(state.vehicle)
        assertTrue(state.candidates.none { it.id == "reverse-signed" })
        assertTrue(state.auto.status in setOf(AutoMatchStatus.SEARCHING, AutoMatchStatus.NO_DATA))
    }

    @Test fun `bus on a parallel street is excluded when the real line is known`() {
        val ride = waitingRide(T0.plusSeconds(35))
        val reference = RouteReference.of(ride, RouteFixtures.shape())
        val parallel = bus("parallel", 300.0, T0.plusSeconds(35), side = 150.0)
        val scored = VehicleScoring.waiting(ride, reference, parallel,
            listOf(VehicleObservation(along(150.0, 150.0), T0)), T0.plusSeconds(35), false)
        assertEquals("abseits des Linienverlaufs", scored.excluded)
    }

    @Test fun `a bus that already left the boarding stop is not offered for waiting`() {
        val ride = waitingRide(T0)
        val scored = VehicleScoring.waiting(ride, RouteReference.of(ride, RouteFixtures.shape()),
            bus("gone", 900.0, T0), emptyList(), T0, false)
        assertEquals("bereits am Einstieg vorbei", scored.excluded)
    }

    @Test fun `without geometry the stop chain still orders vehicles but needs two measurements`() {
        val ride = waitingRide(T0.plusSeconds(35))
        val reference = RouteReference.of(ride, null)!!
        val single = VehicleScoring.waiting(ride, reference, bus("x", 150.0, T0.plusSeconds(35)), emptyList(), T0.plusSeconds(35), false)
        assertTrue(single.insufficient)
        val two = VehicleScoring.waiting(ride, reference, bus("x", 150.0, T0.plusSeconds(35)),
            listOf(VehicleObservation(along(0.0), T0)), T0.plusSeconds(35), false)
        assertFalse(two.insufficient)
        assertTrue(two.reasons.any { it.text == "nähert sich entlang der Strecke" })
        assertTrue(two.reasons.none { it.text == "auf dem Linienverlauf" })
    }

    @Test fun `existing manual selection is respected`() = runBlocking {
        val feed = Feed()
        val ride = waitingRide(T0)
        feed.vehicles = listOf(bus("manual", 300.0, T0), bus("ours", 0.0, T0))
        val manual = LiveBusState(journeyKey = ride.departure.key, candidates = feed.vehicles,
            vehicle = feed.vehicles.first(), selectedVehicleId = "manual")
        val result = feed.tracker.refresh(ride, manual, options = auto, shape = RouteFixtures.shape())
        assertEquals("manual", result.vehicle?.id)
        assertEquals("manual", result.selectedVehicleId)
        assertEquals(AutoMatchStatus.MANUAL, result.auto.status)
    }

    @Test fun `ask first only suggests and a rejected vehicle is not proposed again`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        var state = LiveBusState()
        val options = AutoMatchOptions(enabled = true, askFirst = true)
        for (step in 0..2) {
            feed.now = T0.plusSeconds(step * 35L)
            feed.vehicles = listOf(bus("ours", 4.1667 * step * 35, feed.now))
            state = feed.tracker.refresh(waitingRide(feed.now), state, options = options, shape = shape)
        }
        assertEquals(AutoMatchStatus.SUGGESTED, state.auto.status)
        assertEquals("ours", state.auto.vehicleId)
        assertNull("a suggestion is not shown as the ride's vehicle", state.vehicle)
        state = state.copy(auto = AutoMatchEngine.reject(state.auto, "ours"))
        feed.now = T0.plusSeconds(105)
        feed.vehicles = listOf(bus("ours", 4.1667 * 105, feed.now))
        state = feed.tracker.refresh(waitingRide(feed.now), state, options = options, shape = shape)
        assertNotEquals("ours", state.auto.vehicleId)
        assertTrue("ours" in state.auto.rejected)
    }

    @Test fun `detection off keeps the previous behaviour`() = runBlocking {
        val feed = Feed()
        feed.vehicles = listOf(bus("ours", 0.0, T0))
        val result = feed.tracker.refresh(waitingRide(T0), LiveBusState())
        assertEquals(AutoMatchStatus.OFF, result.auto.status)
        assertNull(result.vehicle)
    }

    // ---- Riding ---------------------------------------------------------------------------

    private val depart = T0.plusSeconds(120)
    private fun busOffset(t: Instant, lead: Double = 0.0) = 500.0 + lead + 6.0 * (t.epochSecond - depart.epochSecond)

    private fun phoneTrack(until: Instant, positionAt: (Instant) -> Double, side: Double = 6.0,
                           accuracy: Float = 12f, from: Instant = depart.minusSeconds(10)): List<PhoneFix> {
        val result = mutableListOf<PhoneFix>()
        var t = from
        while (!t.isAfter(until)) { result += RouteFixtures.fix(positionAt(t), t, accuracy, side); t = t.plusSeconds(5) }
        return result
    }

    @Test fun `after boarding the vehicle moving together with the phone is detected`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        val ride = RouteFixtures.ride(boarded = true, delaySeconds = 0, fetchedAt = depart)
        var state = LiveBusState()
        for (step in 1..3) {
            feed.now = depart.plusSeconds(step * 35L)
            feed.vehicles = listOf(bus("ours", busOffset(feed.now), feed.now), bus("ahead", busOffset(feed.now, 300.0), feed.now))
            // Phone reports 3 s later than NEW: time-shifted measurements must still pair up.
            val phone = phoneTrack(feed.now, { busOffset(it.minusSeconds(3)) })
            state = feed.tracker.refresh(ride.copy(fetchedAt = feed.now), state, phone, auto, shape)
        }
        assertEquals(AutoMatchStatus.ASSIGNED, state.auto.status)
        assertEquals(AutoMatchBasis.CO_MOVEMENT, state.auto.basis)
        assertEquals("ours", state.vehicle?.id)
        assertFalse(state.confirmed)
        assertNotNull("vehicle drawn on the verified line", state.routePoint)
    }

    @Test fun `walking next to the route is not counted as riding`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = depart)
        var state = LiveBusState()
        for (step in 1..4) {
            feed.now = depart.plusSeconds(step * 35L)
            feed.vehicles = listOf(bus("ours", busOffset(feed.now), feed.now))
            val phone = phoneTrack(feed.now, { 520.0 + 1.4 * (it.epochSecond - depart.epochSecond) }, side = 12.0)
            state = feed.tracker.refresh(ride.copy(fetchedAt = feed.now), state, phone, auto, shape)
            assertNotEquals(AutoMatchStatus.ASSIGNED, state.auto.status)
        }
        assertNull(state.vehicle)
    }

    @Test fun `crossing road - a single moment of proximity is not enough`() {
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = depart)
        val now = depart.plusSeconds(70)
        // Phone travels north on a crossing road at x=800 and meets the bus once at the junction.
        val phone = (0..14).map { i ->
            val t = depart.plusSeconds(i * 5L)
            PhoneFix(RouteFixtures.at(800.0, -300.0 + 8.0 * (t.epochSecond - depart.epochSecond)), 10f, t)
        }
        val history = listOf(VehicleObservation(along(busOffset(depart)), depart), VehicleObservation(along(busOffset(depart.plusSeconds(35))), depart.plusSeconds(35)))
        val scored = VehicleScoring.riding(ride, RouteReference.of(ride, RouteFixtures.shape()),
            bus("ours", busOffset(now), now), history, phone, now, false)
        assertTrue(scored.score < AutoMatchEngine.RIDING_ASSIGN)
    }

    @Test fun `inaccurate stale and too few measurements are insufficient`() {
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = depart)
        val now = depart.plusSeconds(70)
        val history = listOf(VehicleObservation(along(busOffset(depart.plusSeconds(35))), depart.plusSeconds(35)))
        val reference = RouteReference.of(ride, RouteFixtures.shape())
        val vehicle = bus("ours", busOffset(now), now)
        val inaccurate = phoneTrack(now, { busOffset(it) }, accuracy = 120f)
        assertTrue(VehicleScoring.riding(ride, reference, vehicle, history, inaccurate, now, false).insufficient)
        val stale = phoneTrack(now.minusSeconds(60), { busOffset(it) })
        assertTrue(VehicleScoring.riding(ride, reference, vehicle, history, stale, now, false).insufficient)
        assertTrue(VehicleScoring.riding(ride, reference, vehicle, emptyList(), phoneTrack(now, { busOffset(it) }), now, false).insufficient)
    }

    @Test fun `stable assignment survives a stopped bus with one GPS jump`() {
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = depart)
        var state = AutoMatchState(status = AutoMatchStatus.ASSIGNED, vehicleId = "ours", basis = AutoMatchBasis.CO_MOVEMENT,
            lastSupport = depart.plusSeconds(105))
        val now = depart.plusSeconds(140)
        val stoppedAt = busOffset(depart.plusSeconds(105))
        val history = listOf(VehicleObservation(along(busOffset(depart.plusSeconds(70))), depart.plusSeconds(70)),
            VehicleObservation(along(stoppedAt), depart.plusSeconds(105)))
        val phone = phoneTrack(now, { if (it == now) stoppedAt + 300 else minOf(busOffset(it), stoppedAt) })
        val reference = RouteReference.of(ride, RouteFixtures.shape())
        val other = bus("other", stoppedAt + 40, now)
        val scored = listOf(VehicleScoring.riding(ride, reference, bus("ours", stoppedAt, now), history, phone, now, false),
            VehicleScoring.riding(ride, reference, other, emptyList(), phone, now, false))
        state = AutoMatchEngine.step(state, scored, MatchMode.RIDING, now, auto, manual = false)
        assertEquals("ours", state.vehicleId)
        assertTrue(state.holdsVehicle)
    }

    @Test fun `loss is rechecked first, then dropped, and another bus needs full confirmation`() {
        val strong = { id: String, at: Instant -> ScoredVehicle(bus(id, 800.0, at), 90, emptyList()) }
        var now = depart
        var state = AutoMatchState(status = AutoMatchStatus.ASSIGNED, vehicleId = "ours", basis = AutoMatchBasis.CO_MOVEMENT, lastSupport = now)
        now = now.plusSeconds(35)
        state = AutoMatchEngine.step(state, listOf(strong("other", now)), MatchMode.RIDING, now, auto, false)
        assertEquals(AutoMatchStatus.RECHECKING, state.status)
        assertEquals("ours", state.vehicleId)
        // It reappears: restored without switching.
        now = now.plusSeconds(35)
        state = AutoMatchEngine.step(state, listOf(strong("ours", now), strong("other", now)), MatchMode.RIDING, now, auto, false)
        assertEquals(AutoMatchStatus.ASSIGNED, state.status)
        assertEquals("ours", state.vehicleId)
        // Gone for good: two misses and more than a minute.
        now = now.plusSeconds(35)
        state = AutoMatchEngine.step(state, listOf(strong("other", now)), MatchMode.RIDING, now, auto, false)
        assertEquals(AutoMatchStatus.RECHECKING, state.status)
        now = now.plusSeconds(35)
        state = AutoMatchEngine.step(state, listOf(strong("other", now)), MatchMode.RIDING, now, auto, false)
        assertNull(state.vehicleId)
        assertNotEquals(AutoMatchStatus.ASSIGNED, state.status)
        now = now.plusSeconds(35)
        state = AutoMatchEngine.step(state, listOf(strong("other", now)), MatchMode.RIDING, now, auto, false)
        assertEquals(AutoMatchStatus.ASSIGNED, state.status)
        assertEquals("other", state.vehicleId)
    }

    @Test fun `the same cached NEW measurement does not count twice`() {
        val vehicle = bus("ours", 300.0, T0)
        val scored = listOf(ScoredVehicle(vehicle, 80, emptyList()))
        var state = AutoMatchEngine.step(AutoMatchState(), scored, MatchMode.WAITING, T0, auto, false)
        state = AutoMatchEngine.step(state, scored, MatchMode.WAITING, T0.plusSeconds(20), auto, false)
        assertNotEquals(AutoMatchStatus.ASSIGNED, state.status)
        val fresh = listOf(ScoredVehicle(vehicle.copy(observedAt = T0.plusSeconds(20)), 80, emptyList()))
        state = AutoMatchEngine.step(state, fresh, MatchMode.WAITING, T0.plusSeconds(20), auto, false)
        assertEquals(AutoMatchStatus.ASSIGNED, state.status)
    }

    // ---- Journey changes ---------------------------------------------------------------------

    @Test fun `journey change during a running lookup discards the late result`() {
        val ride = RouteFixtures.ride()
        val running = TrackingState(ride = ride, running = true, liveBus = LiveBusState(journeyKey = ride.departure.key, revision = 4))
        assertTrue(LiveBusTracker.resultApplies(running, ride.departure.key, false, 4))
        val other = ride.copy(departure = ride.departure.copy(tripCode = "trip-2"))
        assertFalse(LiveBusTracker.resultApplies(running.copy(ride = other), ride.departure.key, false, 4))
        assertFalse(LiveBusTracker.resultApplies(running.copy(ride = ride.copy(boarded = true)), ride.departure.key, false, 4))
        assertFalse(LiveBusTracker.resultApplies(running.copy(liveBus = running.liveBus.copy(revision = 5)), ride.departure.key, false, 4))
        assertFalse(LiveBusTracker.resultApplies(running.copy(running = false), ride.departure.key, false, 4))
    }

    @Test fun `measurements of an earlier journey are not reused`() = runBlocking {
        val feed = Feed()
        val shape = RouteFixtures.shape()
        var state = LiveBusState()
        for (step in 0..1) {
            feed.now = T0.plusSeconds(step * 35L)
            feed.vehicles = listOf(bus("ours", 4.1667 * step * 35, feed.now))
            state = feed.tracker.refresh(waitingRide(feed.now), state, options = auto, shape = shape)
        }
        val next = waitingRide(T0.plusSeconds(70)).let { it.copy(departure = it.departure.copy(tripCode = "trip-2")) }
        feed.now = T0.plusSeconds(70)
        feed.vehicles = listOf(bus("ours", 4.1667 * 70, feed.now))
        val result = feed.tracker.refresh(next, state, options = auto, shape = null)
        assertNotEquals(AutoMatchStatus.ASSIGNED, result.auto.status)
        assertEquals(next.departure.key, result.journeyKey)
    }
}
