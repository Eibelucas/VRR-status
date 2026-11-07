package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
    @Test fun `removing one route keeps its opposite direction and all unrelated visits`() {
        val visits = visit(3)
        val reverse = visits.first().copy(journey = "reverse", destination = "Hbf Europaplatz")
        val (remaining, removed) = RouteHistory.remove(visits + reverse, visits.first().routeKey)
        assertEquals(listOf(reverse), remaining)
        assertEquals(visits, removed)
        assertEquals(visits + reverse, RouteHistory.remove(visits + reverse, "unknown").first)
    }
    @Test fun `undo merges selections made since removal without replacing newer metadata`() {
        val removed = visit(2)
        val newerCopy = removed.first().copy(stop = Stop("new", "New name"), at = now)
        val newJourney = removed.first().copy(journey = "new selection", at = now)
        val merged = RouteHistory.restore(listOf(newerCopy, newJourney), removed, now)
        assertEquals(3, merged.size)
        assertEquals(newerCopy, merged.first { it.journey == newerCopy.journey })
        assertTrue(newJourney in merged)
        assertEquals(3, RouteHistory.frequent(merged, now).single().count)
        assertEquals(merged, RouteHistory.restore(merged, removed, now))
    }
    @Test fun `old routes expire from suggestions and storage is bounded`() {
        val old = visit().single().copy(at = now.minusSeconds(91 * 86400L))
        assertTrue(RouteHistory.frequent(listOf(old), now).isEmpty())
        val many = (1..220).map { visit().single().copy(journey = "journey$it", at = now.minusSeconds(it.toLong())) }
        assertEquals(200, RouteHistory.record(many, ride(), now).size)
    }
}


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RouteHistoryStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun ride(trip: String, destination: String = "Rheydt Hbf"): Ride {
        val base = DemoRide.create(Instant.now())
        return base.copy(demo = false, departure = base.departure.copy(lineId = "new:006:H", tripCode = trip,
            destination = destination, stopId = "de:05116:23007:1:6"))
    }

    @Test fun `removed route stays removed after restart and resume but a new trip can relearn it`() {
        val old = ride("old")
        val store = RouteHistoryStore(app, null)
        store.record(old)
        val key = store.routes.value.single().key
        val removed = store.remove(key)
        assertEquals(1, removed.size)
        assertTrue(store.routes.value.isEmpty())
        val restarted = RouteHistoryStore(app, old)
        assertTrue(restarted.routes.value.isEmpty())
        restarted.record(old.copy(boarded = true, fetchedAt = Instant.now()))
        assertTrue(restarted.routes.value.isEmpty())
        restarted.record(ride("later"))
        assertEquals(1, restarted.routes.value.single().count)
        restarted.restore(removed)
        assertEquals(2, restarted.routes.value.single().count)
        assertEquals(2, RouteHistoryStore(app, old).routes.value.single().count)
    }

    @Test fun `single deletion keeps other direction and undo does not erase new records`() {
        val out = ride("out")
        val back = ride("back", "Hbf Europaplatz")
        val store = RouteHistoryStore(app, null)
        store.record(out); store.record(back)
        val key = store.routes.value.first { it.visit.destination == "Rheydt Hbf" }.key
        val removed = store.remove(key)
        assertEquals("Hbf Europaplatz", store.routes.value.single().visit.destination)
        store.record(ride("new out"))
        store.restore(removed)
        assertEquals(2, store.routes.value.size)
        assertEquals(2, store.routes.value.first { it.key == key }.count)
        assertEquals(1, store.routes.value.first { it.key != key }.count)
        assertTrue(store.remove("unknown").isEmpty())
    }

    @Test fun `clearing history cannot immediately resurrect the current ride`() {
        val current = ride("active")
        val store = RouteHistoryStore(app, null)
        store.record(current); store.clear()
        val restarted = RouteHistoryStore(app, current)
        restarted.record(current)
        assertTrue(restarted.routes.value.isEmpty())
        val journal = app.getSharedPreferences("route_history", Context.MODE_PRIVATE).getString("deleted_journeys", "")!!
        assertFalse(journal.contains(current.departure.key))
    }
}
