package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test

/** Synthetic positions on a made-up street running east, then north. */
class MapFeaturesTest {
    private fun obs(east: Double, seconds: Long) = VehicleObservation(RouteFixtures.at(east, 0.0), T0.plusSeconds(seconds))

    @Test fun `heading needs clear movement or a verified line`() {
        val current = obs(600.0, 60)
        assertEquals(90.0, VehicleHeading.movement(listOf(obs(500.0, 30)), current)!!, 2.0)
        assertNull("30 m is within GPS noise", VehicleHeading.movement(listOf(obs(570.0, 30)), current))
        assertNull("too old", VehicleHeading.movement(listOf(obs(300.0, -300)), current))
        val line = RouteFixtures.shape().line
        val snap = line.project(current.point)!!
        assertEquals(90.0, VehicleHeading.of(snap, confirmedTrip = true, emptyList(), current)!!, 2.0)
        assertNull("snapped but neither confirmed nor moving: neutral", VehicleHeading.of(snap, false, emptyList(), current))
        val arrows = VehicleHeading.arrows(line, 0.0, line.length)
        assertTrue(arrows.isNotEmpty() && arrows.size <= 12)
        assertEquals(90.0, arrows.first().second, 2.0); assertEquals(0.0, arrows.last().second, 2.0)
    }

    private fun bus(id: String, line: String, destination: String, age: Long, reliable: Boolean = true) =
        RouteFixtures.bus(id, 100.0, 0.0, T0.minusSeconds(age), destination = destination, reliable = reliable, line = line)

    @Test fun `fleet filters by line and direction and drops old positions`() {
        val fleet = listOf(bus("a", "007", "Hbf", 5), bus("b", "007", "Rheydt", 50), bus("c", "017", "Hbf", 5),
            bus("d", "007", "5Hbf  Werbung", 5, reliable = false), bus("e", "007", "Hbf", 200))
        assertEquals(listOf("007", "017"), LiveFleet.lines(fleet.filter { LiveFleet.age(it, T0) != FleetAge.GONE }))
        assertEquals(listOf("Hbf", "Rheydt", LiveFleet.UNKNOWN), LiveFleet.directions(fleet.filter { it.id != "e" }, "007"))
        assertEquals(listOf("a"), LiveFleet.filter(fleet, T0, "007", "Hbf").map { it.id })
        assertEquals(listOf("d"), LiveFleet.filter(fleet, T0, "007", LiveFleet.UNKNOWN).map { it.id })
        assertEquals(FleetAge.OLDER, LiveFleet.age(fleet[1], T0))
        assertTrue(LiveFleet.filter(fleet, T0, null, null).none { it.id == "e" })
        val history = LiveFleet.remember(emptyMap(), listOf(RouteFixtures.bus("a", 100.0, 0.0, T0.minusSeconds(30))), T0)
        val moved = LiveFleet.remember(history, listOf(RouteFixtures.bus("a", 300.0, 0.0, T0)), T0)
        assertEquals(90.0, LiveFleet.headings(moved, listOf(RouteFixtures.bus("a", 300.0, 0.0, T0)))["a"]!!, 2.0)
    }

    private val ride = RouteFixtures.ride(boarded = false)

    @Test fun `same line number alone is not an assignment and ambiguity asks for a choice`() {
        val a = RouteFixtures.bus("a", 200.0, 0.0, T0); val b = RouteFixtures.bus("b", 400.0, 0.0, T0)
        val other = RouteFixtures.bus("c", 300.0, 0.0, T0, destination = "Halt 0")
        val plain = LiveBusState(journeyKey = ride.departure.key, candidates = listOf(a, b, other))
        val rows = VehicleComparison.rows(ride, plain, T0)
        assertEquals(setOf(AssignmentStatus.SAME_LINE, AssignmentStatus.WRONG_DIRECTION), rows.map { it.status }.toSet())
        assertEquals("c", rows.single { it.status == AssignmentStatus.WRONG_DIRECTION }.vehicle.id)
        assertTrue(VehicleComparison.needsChoice(rows))
        val chosen = VehicleComparison.rows(ride, plain.copy(vehicle = b, selectedVehicleId = "b"), T0)
        assertEquals(AssignmentStatus.CHOSEN, chosen.first().status); assertFalse(VehicleComparison.needsChoice(chosen))
        val confirmed = VehicleComparison.rows(ride, plain.copy(vehicle = a, confirmed = true), T0)
        assertEquals(AssignmentStatus.CONFIRMED, confirmed.first().status)
        val rejected = VehicleComparison.rows(ride, plain.copy(auto = AutoMatchState(rejected = setOf("a"))), T0)
        assertEquals(AssignmentStatus.REJECTED, rejected.first { it.vehicle.id == "a" }.status)
        val stale = VehicleComparison.rows(ride, plain.copy(candidates = listOf(RouteFixtures.bus("old", 200.0, 0.0, T0.minusSeconds(200)))), T0)
        assertTrue(stale.isEmpty())
    }

    @Test fun `position hint only for the assigned fresh bus`() {
        val near = RouteFixtures.bus("a", 200.0, 0.0, T0.minusSeconds(12))
        val base = TrackingState(ride = ride, running = true, liveBus = LiveBusState(journeyKey = ride.departure.key, vehicle = near))
        assertNull("not assigned", PositionHint.text(base, T0))
        val chosen = base.copy(liveBus = base.liveBus.copy(selectedVehicleId = "a"))
        assertEquals("Bus 300 m entfernt · vor 12 s", PositionHint.text(chosen, T0))
        assertNull("old", PositionHint.text(chosen, T0.plusSeconds(200)))
        val riding = chosen.copy(ride = ride.board("auto", T0))
        assertEquals("Bus laut NEW vor 12 s", PositionHint.text(riding, T0))
    }
}
