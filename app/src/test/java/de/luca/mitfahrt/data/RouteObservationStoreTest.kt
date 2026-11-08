package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RouteObservationStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("route_history", Context.MODE_PRIVATE)
    private var now = Instant.parse("2026-11-08T08:00:00Z")

    @Before fun reset() {
        listOf("route_history", "route_plans").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun ride(destination: String = "Rheydt Hauptbahnhof", trip: String = "private-trip-code"): Ride {
        val departure = Departure("new:private-line:H", "025", destination, "de:05116:23370:1:1",
            "Private boarding stop", trip, now.plusSeconds(300), now.plusSeconds(420), "1", observedAt = now)
        val stops = listOf(
            RideStop(departure.stopId, departure.stopName, GeoPoint(51.18, 6.37), departure.planned, departure.planned),
            RideStop("de:05116:23007:1:1", destination, GeoPoint(51.19, 6.44), now.plusSeconds(900), now.plusSeconds(900)))
        return Ride(departure, stops, 0, now, boarded = false)
    }

    private fun store() = RouteHistoryStore(app, null) { now }
    private fun counts() = JSONArray(prefs.getString("view_counts", "[]"))
    private fun visits() = RouteHistory.decode(prefs.getString("visits", "[]")!!)
    private fun view(store: RouteHistoryStore, ride: Ride, token: String) {
        now = now.plusSeconds(1)
        store.observe(ride, token)
    }
    private fun qualify(store: RouteHistoryStore, ride: Ride, prefix: String = "view") {
        (1..3).forEach { view(store, ride, "$prefix-$it") }
    }

    @Test fun `first two previews store only hashes and times without route or bus metadata`() {
        val store = store()
        val ride = ride()
        view(store, ride, "first-private-view")
        view(store, ride, "second-private-view")

        assertTrue(store.routes.value.isEmpty())
        assertTrue(visits().isEmpty())
        assertEquals(2, counts().length())
        for (item in counts().objects()) {
            assertEquals(setOf("route", "token", "at"), item.keys().asSequence().toSet())
            assertTrue(item.getString("route").matches(Regex("[0-9a-f]{64}")))
            assertTrue(item.getString("token").matches(Regex("[0-9a-f]{64}")))
        }
        val saved = prefs.all.toString()
        listOf(ride.departure.key, ride.departure.lineId, ride.departure.destination, ride.departure.stopId,
            ride.departure.stopName, "first-private-view", "second-private-view", "estimated", "\"lat\"").forEach {
            assertFalse(it, saved.contains(it))
        }
    }

    @Test fun `third distinct preview materializes three view events rather than bus journeys`() {
        val store = store()
        val ride = ride()
        qualify(store, ride)
        assertEquals(RouteHistory.MIN_SUGGESTION_VIEWS, store.routes.value.single().count)
        assertEquals(3, visits().size)
        assertTrue(visits().all { it.journey.matches(Regex("view/[0-9a-f]{64}")) })
        assertTrue(visits().none { it.journey == ride.departure.key })
        assertEquals(0, counts().length())
        assertEquals(3, store().routes.value.single().count)
    }

    @Test fun `one view token cannot count again before or after qualification`() {
        val store = store()
        val ride = ride()
        repeat(4) { view(store, ride, "same-view") }
        assertEquals(1, counts().length())
        assertTrue(visits().isEmpty())
        view(store, ride, "second")
        view(store, ride, "third")
        repeat(4) { view(store, ride.copy(fetchedAt = now), "same-view") }
        assertEquals(3, store.routes.value.single().count)
        view(store, ride, "fourth")
        assertEquals(4, store.routes.value.single().count)
    }

    @Test fun `live preference changes prevent latent counting while learning is disabled`() {
        val store = store()
        val ride = ride()
        view(store, ride, "enabled-first")
        RoutePlansStore(app).setAutomaticLearning(false)
        view(store, ride, "disabled-second")
        view(store, ride, "disabled-third")
        assertEquals(1, counts().length())
        assertTrue(visits().isEmpty())
        RoutePlansStore(app).setAutomaticLearning(true)
        view(store, ride, "enabled-second")
        assertTrue(visits().isEmpty())
        view(store, ride, "enabled-third")
        assertEquals(3, store.routes.value.single().count)
        val stored = prefs.all.toString()
        assertFalse(stored.contains("disabled-second"))
        assertFalse(stored.contains("disabled-third"))
    }

    @Test fun `pending views survive restart without becoming a suggestion early`() {
        val ride = ride()
        val first = store()
        view(first, ride, "first")
        view(first, ride, "second")
        val restarted = store()
        assertTrue(restarted.routes.value.isEmpty())
        view(restarted, ride, "third")
        assertEquals(3, restarted.routes.value.single().count)
    }

    @Test fun `deletion resets qualification and old preview tokens cannot resurrect the route`() {
        val ride = ride()
        val store = store()
        qualify(store, ride, "old")
        val removed = store.remove(store.routes.value.single().key)
        assertEquals(3, removed.size)
        assertTrue(store.routes.value.isEmpty())
        val restarted = store()
        view(restarted, ride, "old-1")
        assertEquals(0, counts().length())
        view(restarted, ride, "new-1")
        view(restarted, ride, "new-2")
        assertTrue(restarted.routes.value.isEmpty())
        assertTrue(visits().isEmpty())
        view(restarted, ride, "new-3")
        assertEquals(3, restarted.routes.value.single().count)
        restarted.restore(removed)
        assertEquals(6, restarted.routes.value.single().count)
        assertEquals(6, store().routes.value.single().count)
    }

    @Test fun `removing a route also clears its unqualified pending counter`() {
        val store = store()
        val ride = ride()
        view(store, ride, "old-1")
        view(store, ride, "old-2")
        val key = RouteVisit("", ride.departure.lineId, ride.departure.line, ride.departure.destination,
            Stop(ride.departure.stopId, ride.departure.stopName), now).routeKey
        assertTrue(store.remove(key).isEmpty())
        assertEquals(0, counts().length())
        view(store, ride, "old-1")
        assertEquals(0, counts().length())
        view(store, ride, "new-1")
        assertEquals(1, counts().length())
        assertTrue(visits().isEmpty())
    }

    @Test fun `complete clear invalidates earlier undo and clears unrelated pending previews`() {
        val store = store()
        val ride = ride()
        qualify(store, ride)
        val removed = store.remove(store.routes.value.single().key)
        val other = ride(destination = "Other direction")
        view(store, other, "pending-1")
        view(store, other, "pending-2")
        now = now.plusSeconds(1)
        store.clear()
        store.restore(removed)
        assertTrue(store.routes.value.isEmpty())
        assertEquals(0, counts().length())
        view(store, other, "pending-1")
        assertEquals(0, counts().length())
        view(store, other, "fresh-1")
        assertEquals(1, counts().length())
    }

    @Test fun `pending and qualified data expire after ninety days on restart`() {
        val store = store()
        qualify(store, ride(), "qualified")
        val pending = ride(destination = "Pending route")
        view(store, pending, "pending-1")
        view(store, pending, "pending-2")
        now = now.plusSeconds(91 * 86400L)
        val restarted = store()
        assertTrue(restarted.routes.value.isEmpty())
        assertTrue(visits().isEmpty())
        assertEquals(0, counts().length())
        view(restarted, ride(), "after-expiry")
        assertEquals(1, counts().length())
    }

    @Test fun `qualified and unqualified events share a two hundred entry limit`() {
        val store = store()
        qualify(store, ride(), "qualified")
        for (index in 1..101) {
            val pending = ride(destination = "Unqualified route $index")
            view(store, pending, "route-$index-first")
            view(store, pending, "route-$index-second")
        }
        assertTrue(visits().size + counts().length() <= 200)
        assertTrue(visits().isEmpty())
        assertTrue(store.routes.value.isEmpty())
    }

    @Test fun `already qualified legacy routes accept one new unique view without losing history`() {
        val store = store()
        (1..3).forEach { store.record(ride(trip = "actual-$it")) }
        assertEquals(3, store.routes.value.single().count)
        view(store, ride(), "new-preview")
        assertEquals(4, store.routes.value.single().count)
        assertEquals(3, visits().count { !it.journey.startsWith("view/") })
    }

    @Test fun `legacy one or two actual rides cannot replace the three explicit preview threshold`() {
        val store = store()
        store.record(ride(trip = "actual"))
        view(store, ride(), "first")
        view(store, ride(), "second")
        assertEquals(1, store.routes.value.single().count)
        assertEquals(1, visits().size)
        view(store, ride(), "third")
        assertEquals(4, store.routes.value.single().count)
    }

    @Test fun `demos cancelled stops malformed views and cancelled rides are never observed`() {
        val store = store()
        val ride = ride()
        val cancelledStop = ride.copy(stops = ride.stops.mapIndexed { index, stop ->
            if (index == ride.boardingIndex) stop.copy(cancelled = true) else stop })
        listOf(ride.copy(demo = true), cancelledStop,
            ride.copy(departure = ride.departure.copy(cancelled = true)), ride.copy(boardingIndex = 99)).forEachIndexed { i, invalid ->
            view(store, invalid, "invalid-$i")
        }
        view(store, ride, "")
        view(store, ride, " ")
        view(store, ride, "x".repeat(1025))
        assertTrue(visits().isEmpty())
        assertEquals(0, counts().length())
    }

    @Test fun `corrupt counter entries are skipped while valid pending views remain usable`() {
        val store = store()
        val ride = ride()
        view(store, ride, "first")
        view(store, ride, "second")
        val data = counts().put(JSONObject().put("route", "raw route").put("token", "raw token").put("at", now.toString()))
            .put(JSONObject().put("route", "a".repeat(64)).put("token", "b".repeat(64)).put("at", "not-an-instant"))
            .put(JSONObject.NULL)
        prefs.edit().putString("view_counts", data.toString()).commit()
        val restarted = store()
        assertEquals(2, counts().length())
        view(restarted, ride, "third")
        assertEquals(3, restarted.routes.value.single().count)
    }
}
