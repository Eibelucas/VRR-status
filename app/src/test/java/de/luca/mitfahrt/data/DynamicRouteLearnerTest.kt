package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DynamicRouteLearnerTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val origin = Stop("de:05116:1001:1:1", "Halt Nord", GeoPoint(51.190, 6.440))
    private val normalDest = Stop("de:05116:1005:1:1", "Halt Sued", GeoPoint(51.195, 6.460))
    private val altDest = Stop("de:05116:1003:1:1", "Halt Mitte", GeoPoint(51.192, 6.450))
    private val today = LocalDate.of(2026, 11, 18)
    private val now = today.atTime(8, 0).atZone(berlin).toInstant()

    private fun plan(temp: TemporaryDestination? = null) = RoutePlan(
        id = "route-1",
        name = "Morgens",
        origin = origin,
        destination = normalDest,
        match = RouteMatch.STOP,
        window = RouteWindow((1..5).toSet(), 6 * 60, 10 * 60),
        temporaryDestination = temp
    )

    private fun archiveEntry(exitStop: Stop, daysAgo: Long, id: String): ArchivedJourney {
        val depAt = now.minusSeconds(daysAgo * 86_400L)
        val stops = listOf(
            RideStop(origin.id, origin.name, origin.point, depAt, depAt),
            RideStop(altDest.id, altDest.name, altDest.point, depAt.plusSeconds(240), depAt.plusSeconds(240)),
            RideStop(normalDest.id, normalDest.name, normalDest.point, depAt.plusSeconds(600), depAt.plusSeconds(600))
        )
        val dep = Departure("key-$id", "006", normalDest.name, origin.id, origin.name, "trip-$id", depAt, depAt, "1", observedAt = depAt)
        val exitIndex = stops.indexOfFirst { stopKey(it.id) == stopKey(exitStop.id) }
        val ride = Ride(dep, stops, boardingIndex = 0, fetchedAt = depAt, boarded = true).withExitTarget(exitIndex)
        return ArchivedJourney(id = id, ride = ride, startedAt = depAt, updatedAt = depAt)
    }

    @Test
    fun `temporary destination overrides effective destination only during active date range`() {
        val temp = TemporaryDestination(altDest, today.minusDays(1), today.plusDays(4), "Wochenziel")
        val route = plan(temp)
        assertEquals(altDest, route.effectiveDestination(today))
        assertEquals(temp, route.activeTemporaryDestination(today))
        assertEquals(normalDest, route.effectiveDestination(today.minusDays(2)))
        assertNull(route.activeTemporaryDestination(today.minusDays(2)))
        assertEquals(normalDest, route.effectiveDestination(today.plusDays(5)))
        assertNull(route.activeTemporaryDestination(today.plusDays(5)))
    }

    @Test
    fun `route plan JSON round trip preserves temporary destination and clean drops invalid ones`() {
        val temp = TemporaryDestination(altDest, today, today.plusDays(6), "Projekt")
        val original = RoutePlansState(routes = listOf(plan(temp)))
        val decoded = RoutePlans.decode(RoutePlans.encode(original))
        assertEquals(1, decoded.routes.size)
        val expectedTemp = temp.copy(stop = temp.stop.copy(id = stopKey(temp.stop.id)))
        assertEquals(expectedTemp, decoded.routes.first().temporaryDestination)

        // Temporary destination equal to origin or normal destination is stripped by clean().
        val invalidSameAsOrigin = plan(TemporaryDestination(origin, today, today.plusDays(2)))
        assertNull(RoutePlans.clean(invalidSameAsOrigin)!!.temporaryDestination)
        val invalidSameAsDest = plan(TemporaryDestination(normalDest, today, today.plusDays(2)))
        assertNull(RoutePlans.clean(invalidSameAsDest)!!.temporaryDestination)
    }

    @Test
    fun `learner suggests temporary destination after two recent exits at earlier stop`() {
        val entries = listOf(
            archiveEntry(altDest, daysAgo = 1, id = "1"),
            archiveEntry(altDest, daysAgo = 2, id = "2")
        )
        val suggestions = DynamicRouteLearner.suggestions(listOf(plan()), entries, now)
        assertEquals(1, suggestions.size)
        assertEquals("route-1", suggestions.first().routeId)
        assertEquals(stopKey(altDest.id), stopKey(suggestions.first().suggestedStop.id))
        assertEquals(2, suggestions.first().count)
    }

    @Test
    fun `learner ignores single ride, stale rides, dismissals, and routes with active temporary destination`() {
        val oneRide = listOf(archiveEntry(altDest, daysAgo = 1, id = "1"))
        assertTrue(DynamicRouteLearner.suggestions(listOf(plan()), oneRide, now).isEmpty())

        val staleRides = listOf(
            archiveEntry(altDest, daysAgo = 9, id = "1"),
            archiveEntry(altDest, daysAgo = 10, id = "2")
        )
        assertTrue(DynamicRouteLearner.suggestions(listOf(plan()), staleRides, now).isEmpty())

        val recent = listOf(
            archiveEntry(altDest, daysAgo = 1, id = "1"),
            archiveEntry(altDest, daysAgo = 2, id = "2")
        )
        val dismissed = mapOf(DynamicRouteLearner.dismissalKey("route-1", altDest.id) to now)
        assertTrue(DynamicRouteLearner.suggestions(listOf(plan()), recent, now, dismissed).isEmpty())

        val alreadyTemp = plan(TemporaryDestination(altDest, today, today.plusDays(3)))
        assertTrue(DynamicRouteLearner.suggestions(listOf(alreadyTemp), recent, now).isEmpty())
    }
}
