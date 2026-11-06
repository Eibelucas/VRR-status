package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RouteHistoryTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride() = DemoRide.create(now).let { r -> r.copy(demo = false,
        departure = r.departure.copy(lineId = "new:006:H", stopId = "de:05116:23007:1:6", destination = "Rheydt Hbf")) }
    private fun visit(count: Int = 1): List<RouteVisit> = (1..count).fold(emptyList()) { all, n ->
        RouteHistory.record(all, ride().let { it.copy(departure = it.departure.copy(tripCode = "trip$n")) }, now.minusSeconds((count - n + 1).toLong()))
    }
    private fun departure(stopId: String = "de:05116:23013:1:1") = ride().departure.copy(
        stopId = stopId, stopName = "Bismarckstraße", planned = now.plusSeconds(240), estimated = null, observedAt = now)

    @Test fun `refreshing or resuming one journey does not increase frequency`() {
        val first = RouteHistory.record(emptyList(), ride(), now)
        val again = RouteHistory.record(first, ride().copy(fetchedAt = now.plusSeconds(30), boarded = true), now.plusSeconds(30))
        assertEquals(first, again)
        assertEquals(1, RouteHistory.frequent(again, now.plusSeconds(30)).single().count)
    }
    @Test fun `demo and cancelled rides do not teach routes`() {
        assertTrue(RouteHistory.record(emptyList(), DemoRide.create(now), now).isEmpty())
        assertTrue(RouteHistory.record(emptyList(), ride().let { it.copy(departure = it.departure.copy(cancelled = true)) }, now).isEmpty())
    }
    @Test fun `real selections are grouped by direction and frequency`() {
        val visits = visit(4)
        val reverse = ride().let { it.copy(departure = it.departure.copy(destination = "Hbf Europaplatz", tripCode = "reverse")) }
        val routes = RouteHistory.frequent(RouteHistory.record(visits, reverse, now), now)
        assertEquals(listOf(4, 1), routes.map { it.count })
        assertEquals(2, routes.size)
    }
    @Test fun `a frequent route can start from a different nearby stop today`() {
        val routes = RouteHistory.frequent(visit(3), now)
        val board = departure()
        val stop = Stop("de:05116:23013", "Bismarckstraße", distance = 150)
        val match = RouteHistory.suggestions(routes, listOf(stop), listOf(board), now).single()
        assertEquals(board, match.departure)
        assertEquals(stop, match.stop)
        assertNotEquals(routes.single().visit.stop.id, stop.id)
    }
    @Test fun `opposite direction cancellations old forecasts and departures outside nearby stops are excluded`() {
        val routes = RouteHistory.frequent(visit(), now)
        val stop = Stop("de:05116:23013", "Bismarckstraße", distance = 150)
        val d = departure()
        val rejected = listOf(d.copy(destination = "Other end"), d.copy(lineId = "new:other"), d.copy(cancelled = true),
            d.copy(observedAt = null), d.copy(observedAt = now.minusSeconds(121)), d.copy(observedAt = now.plusSeconds(60)),
            d.copy(planned = now.minusSeconds(1)), d.copy(planned = now.plusSeconds(3601)), d.copy(stopId = "de:05116:99999"))
        assertTrue(RouteHistory.suggestions(routes, listOf(stop), rejected, now).isEmpty())
    }
    @Test fun `closest boarding stop wins and earliest departure breaks distance ties`() {
        val near = Stop("de:05116:23013", "Near", distance = 100)
        val far = Stop("de:05116:23014", "Far", distance = 500)
        val d = departure()
        val chosen = RouteHistory.suggestions(RouteHistory.frequent(visit(), now), listOf(near, far),
            listOf(d.copy(stopId = far.id, planned = now.plusSeconds(60)), d.copy(planned = now.plusSeconds(600)), d), now).single()
        assertEquals(d, chosen.departure)
    }
    @Test fun `history roundtrip stores public stop identity without GPS or departure forecasts`() {
        val visits = visit(2)
        val encoded = RouteHistory.encode(visits)
        assertEquals(visits, RouteHistory.decode(encoded))
        assertFalse(encoded.contains("\"lat\""))
        assertFalse(encoded.contains("estimated"))
        assertEquals(visits, RouteHistory.decode(encoded.dropLast(1) + ",{},null]"))
    }
    @Test fun `old routes expire from suggestions and storage is bounded`() {
        val old = visit().single().copy(at = now.minusSeconds(91 * 86400L))
        assertTrue(RouteHistory.frequent(listOf(old), now).isEmpty())
        val many = (1..220).map { visit().single().copy(journey = "journey$it", at = now.minusSeconds(it.toLong())) }
        assertEquals(200, RouteHistory.record(many, ride(), now).size)
    }
}
