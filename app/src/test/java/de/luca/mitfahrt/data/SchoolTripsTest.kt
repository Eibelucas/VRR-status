package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SchoolTripsTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride() = DemoRide.create(now).copy(demo = false).withExitTarget(3)
    @Test fun `morning and afternoon recommendations use Gladbach local time`() {
        assertEquals(SchoolDirection.TO_SCHOOL, SchoolTrips.preferred(Instant.parse("2026-11-06T10:59:00Z")))
        assertEquals(SchoolDirection.HOME, SchoolTrips.preferred(Instant.parse("2026-11-06T11:00:00Z")))
        assertEquals(SchoolDirection.HOME, SchoolTrips.preferred(Instant.parse("2026-07-06T10:00:00Z")))
    }
    @Test fun `school trips preserve boarding direction destination and public stop geometry`() {
        val trip = SchoolTrips.fromRide(SchoolDirection.TO_SCHOOL, ride())!!
        assertEquals(ride().stops[3].id, trip.targetId)
        assertEquals(ride().departure.destination, trip.destination)
        assertEquals(trip, SchoolTrips.decode(SchoolTrips.encode(listOf(trip))).single())
        assertEquals(3, SchoolTrips.targetIndex(ride(), trip.targetId))
    }
    @Test fun `demo missing and cancelled destinations cannot become school routes`() {
        assertNull(SchoolTrips.fromRide(SchoolDirection.TO_SCHOOL, DemoRide.create(now).withExitTarget(3)))
        assertNull(SchoolTrips.fromRide(SchoolDirection.TO_SCHOOL, ride().withExitTarget(null)))
        assertNull(SchoolTrips.fromRide(SchoolDirection.TO_SCHOOL, ride().copy(stops = ride().stops.mapIndexed { i, s -> s.copy(cancelled = i == 3) })))
    }
    @Test fun `circular routes preserve the selected later occurrence`() {
        val base = ride()
        val loop = base.copy(stops = base.stops.mapIndexed { i, s -> if (i == 5) s.copy(id = base.stops[3].id) else s }).withExitTarget(5)
        val trip = SchoolTrips.fromRide(SchoolDirection.HOME, loop)!!
        assertEquals(1, trip.targetOccurrence)
        assertEquals(5, SchoolTrips.targetIndex(loop, trip.targetId, trip.targetOccurrence))
        assertNull(SchoolTrips.targetIndex(base, trip.targetId, trip.targetOccurrence))
    }
    @Test fun `chosen target affects remaining time without truncating report route data`() {
        assertEquals(ride().stops[3], ride().displayTarget())
        assertEquals(5, ride().minutesToTarget(now))
        assertEquals(7, ride().stops.size)
        assertEquals(12, ride().withExitTarget(null).minutesToTarget(now))
    }
}
