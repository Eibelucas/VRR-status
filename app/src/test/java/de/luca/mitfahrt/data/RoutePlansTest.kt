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

class RoutePlansTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val origin = Stop("de:05116:23007", "Hehn", GeoPoint(51.18, 6.36), distance = 100)
    private val target = Stop("de:05116:23001", "Hbf / Europaplatz", GeoPoint(51.20, 6.45))
    private fun plan() = RoutePlan(id = "school", name = "Zur Schule", origin = origin, destination = target)
    private fun departure(line: String = "006", seconds: Long = 240) = DemoRide.create(now).departure.copy(
        lineId = "new:$line", line = line, tripCode = "trip-$line-$seconds", stopId = "${origin.id}:1:6",
        stopName = origin.name, planned = now.plusSeconds(seconds), estimated = null, observedAt = now)

    @Test fun `Berlin weekday window includes its start and excludes its end`() {
        val window = RouteWindow(setOf(5), 7 * 60, 9 * 60)
        assertTrue(window.active(Instant.parse("2026-11-06T06:00:00Z")))
        assertTrue(window.active(Instant.parse("2026-11-06T07:59:59Z")))
        assertFalse(window.active(now)) // 09:00 in Berlin.
        assertFalse(window.active(Instant.parse("2026-11-07T06:30:00Z")))
        assertEquals("Fr · 07:00–09:00", window.label())
        assertEquals("Mo–Fr · 07:00–09:00", window.copy(days = (1..5).toSet()).label())
    }

    @Test fun `equal times mean an entire selected local day`() {
        val window = RouteWindow(setOf(5), 420, 420)
        assertTrue(window.active(Instant.parse("2026-11-05T23:00:00Z")))
        assertTrue(window.active(Instant.parse("2026-11-06T22:59:59Z")))
        assertFalse(window.active(Instant.parse("2026-11-06T23:00:00Z")))
        assertEquals("Täglich · Ganztägig", RouteWindow().label())
        assertEquals("Fr · Ganztägig", window.label())
    }

    @Test fun `overnight window follows the starting weekday including Sunday rollover`() {
        val monday = RouteWindow(setOf(1), 22 * 60, 2 * 60)
        assertTrue(monday.active(Instant.parse("2026-11-02T21:00:00Z")))
        assertTrue(monday.active(Instant.parse("2026-11-03T00:59:59Z")))
        assertFalse(monday.active(Instant.parse("2026-11-03T01:00:00Z")))
        assertFalse(monday.active(Instant.parse("2026-11-03T21:00:00Z")))
        assertFalse(monday.active(Instant.parse("2026-11-02T00:30:00Z")))
        assertTrue(monday.copy(days = setOf(7)).active(Instant.parse("2026-11-02T00:30:00Z")))
        assertEquals("Mo · 22:00–02:00", monday.label())
    }

    @Test fun `Berlin summer time gap and repeated winter hour obey wall clock schedule`() {
        val spring = RouteWindow(setOf(7), 90, 210)
        assertTrue(spring.active(Instant.parse("2026-03-29T00:45:00Z"))) // 01:45 CET.
        assertTrue(spring.active(Instant.parse("2026-03-29T01:15:00Z"))) // 03:15 CEST.
        assertFalse(spring.active(Instant.parse("2026-03-29T01:30:00Z")))
        val autumn = RouteWindow(setOf(7), 120, 180)
        assertTrue(autumn.active(Instant.parse("2026-10-25T00:30:00Z"))) // First 02:30.
        assertTrue(autumn.active(Instant.parse("2026-10-25T01:30:00Z"))) // Second 02:30.
        assertFalse(autumn.active(Instant.parse("2026-10-25T02:00:00Z")))
        assertFalse(RouteWindow(emptySet()).active(now))
        assertFalse(RouteWindow(setOf(8)).active(now))
        assertFalse(RouteWindow(startMinute = 1440).active(now))
    }

    @Test fun `nearby activation requires its own origin and can be disabled per route`() {
        assertTrue(RoutePlans.active(plan(), now, listOf(origin.copy(id = "${origin.id}:1:2"))))
        assertFalse(RoutePlans.active(plan(), now, listOf(target)))
        assertTrue(RoutePlans.active(plan().copy(nearbyOnly = false), now, emptyList()))
        assertFalse(RoutePlans.active(plan().copy(enabled = false), now, listOf(origin)))
        assertFalse(RoutePlans.active(plan().copy(window = RouteWindow(setOf(1))), now, listOf(origin)))
    }

    @Test fun `line selection treats 006 as 6 without accepting another line or boarding stop`() {
        val selected = plan().copy(match = RouteMatch.LINE, line = " 006 ")
        val six = departure("6", 180)
        val zeroSix = departure("006", 60)
        val rejected = listOf(departure("SB6"), departure("60"), departure("6").copy(stopId = target.id))
        assertEquals(listOf(zeroSix, six), RoutePlans.candidates(selected, listOf(origin), rejected + six + zeroSix, now))
    }

    @Test fun `stop selection permits every line at that origin but cannot assert the chosen target`() {
        val one = departure("N7", 60).copy(destination = "A different advertised terminus")
        val two = departure("SB50", 90)
        val outside = departure("006", 30).copy(stopId = target.id)
        assertEquals(listOf(one, two), RoutePlans.candidates(plan(), listOf(origin, target), listOf(two, outside, one), now))
        assertEquals(listOf(one), RoutePlans.candidates(plan(), emptyList(), listOf(one), now))
        assertEquals(listOf(one), RoutePlans.candidates(plan().copy(nearbyOnly = false), emptyList(), listOf(one), now))
    }

    @Test fun `only fresh noncancelled departures within the next hour become candidates`() {
        val next = departure(seconds = 3600)
        val rejected = listOf(next.copy(cancelled = true), next.copy(observedAt = null),
            next.copy(observedAt = now.minusSeconds(121)), next.copy(observedAt = now.plusSeconds(31)),
            departure(seconds = -1), departure(seconds = 3601), next.copy(stopId = ""))
        assertTrue(RoutePlans.candidates(plan(), listOf(origin), rejected, now).isEmpty())
        assertEquals(listOf(next), RoutePlans.candidates(plan(), listOf(origin), listOf(next, next), now))
        assertEquals(listOf(next), RoutePlans.candidates(plan().copy(enabled = false, window = RouteWindow(setOf(1))),
            emptyList(), listOf(next), now)) // Explicit opening works beyond home-display conditions.
    }

    @Test fun `arrival estimates rather than old planned time determine the next departures`() {
        val delayed = departure(seconds = -120).copy(estimated = now.plusSeconds(90))
        val early = departure(seconds = 180).copy(estimated = now.minusSeconds(1))
        assertEquals(listOf(delayed), RoutePlans.candidates(plan(), listOf(origin), listOf(early, delayed), now))
    }

    @Test fun `roundtrip stores public station coordinates without distance and preserves independent direction settings`() {
        val out = plan().copy(match = RouteMatch.LINE, line = "006", window = RouteWindow((1..5).toSet(), 420, 540))
        val back = plan().copy(id = "home", name = "Nach Hause", origin = target, destination = origin,
            window = RouteWindow(setOf(1, 3, 5), 720, 900), enabled = false, nearbyOnly = false)
        val expected = RoutePlansState(false, listOf(out.copy(origin = origin.copy(distance = null)),
            back.copy(destination = origin.copy(distance = null))))
        val encoded = RoutePlans.encode(RoutePlansState(false, listOf(out, back)))
        assertEquals(expected, RoutePlans.decode(encoded))
        assertFalse(encoded.contains("distance"))
        assertTrue(encoded.contains("\"lat\""))
    }

    @Test fun `corrupt rows are isolated and invalid station coordinates are discarded`() {
        val good = JSONObject(RoutePlans.encode(RoutePlansState(routes = listOf(plan()))))
            .getJSONArray("routes").getJSONObject(0)
        val invalid = JSONObject(good.toString()).put("id", "invalid").put("window", JSONObject()
            .put("days", JSONArray(listOf(9))).put("startMinute", 420).put("endMinute", 540))
        good.getJSONObject("origin").put("lat", 999).put("distance", 5000)
        val decoded = RoutePlans.decode(JSONObject().put("automaticLearning", false)
            .put("routes", JSONArray().put(JSONObject.NULL).put(invalid).put(good).put(good).put(JSONObject())).toString())
        assertFalse(decoded.automaticLearning)
        assertEquals(1, decoded.routes.size)
        assertNull(decoded.routes.single().origin.point)
        assertNull(decoded.routes.single().origin.distance)
        assertEquals(RoutePlansState(), RoutePlans.decode("not json"))
        assertEquals(RoutePlansState(), RoutePlans.decode("{}"))
    }

    @Test fun `decoding legacy optional fields uses explicit safe defaults and caps unique plans`() {
        val one = JSONObject(RoutePlans.encode(RoutePlansState(routes = listOf(plan())))).getJSONArray("routes").getJSONObject(0)
        listOf("match", "line", "window", "enabled", "nearbyOnly").forEach(one::remove)
        val many = JSONArray((1..30).map { JSONObject(one.toString()).put("id", "route-$it") })
        val decoded = RoutePlans.decode(JSONObject().put("routes", many).toString())
        assertTrue(decoded.automaticLearning)
        assertEquals(RoutePlans.MAX_ROUTES, decoded.routes.size)
        assertEquals(RouteMatch.STOP, decoded.routes.first().match)
        assertEquals(RouteWindow(), decoded.routes.first().window)
        assertTrue(decoded.routes.first().enabled)
        assertTrue(decoded.routes.first().nearbyOnly)
    }

    @Test fun `malformed schedule fields cannot silently broaden a selected time window`() {
        val one = JSONObject(RoutePlans.encode(RoutePlansState(routes = listOf(plan())))).getJSONArray("routes").getJSONObject(0)
        val badWindows = listOf(JSONObject().put("days", "Mo"), JSONObject().put("days", JSONArray(listOf(1.5))),
            JSONObject().put("startMinute", "early"), JSONObject().put("endMinute", 1440))
        badWindows.forEach { window ->
            val row = JSONObject(one.toString()).put("window", window)
            assertTrue(RoutePlans.decode(JSONObject().put("routes", JSONArray().put(row)).toString()).routes.isEmpty())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoutePlansStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun plan(id: String = "school") = RoutePlan(id, "Zur Schule", Stop("hehn", "Hehn"), Stop("hbf", "Hbf"))

    @Before fun reset() { app.getSharedPreferences("route_plans", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `flag and edited plans survive restart and stale instances retain each others changes`() {
        val first = RoutePlansStore(app)
        val second = RoutePlansStore(app)
        first.setAutomaticLearning(false)
        assertFalse(second.current().automaticLearning)
        assertTrue(second.save(plan()))
        assertFalse(second.state.value.automaticLearning)
        first.setAutomaticLearning(true)
        assertEquals(listOf(plan()), first.state.value.routes)
        assertTrue(second.current().automaticLearning)
        assertTrue(second.save(plan().copy(name = "Morgens", enabled = false)))
        assertEquals("Morgens", RoutePlansStore(app).current().routes.single().name)
        assertFalse(RoutePlansStore(app).current().routes.single().enabled)
    }

    @Test fun `saving up to limit never displaces another route but allows edits`() {
        val store = RoutePlansStore(app)
        (1..RoutePlans.MAX_ROUTES).forEach { assertTrue(store.save(plan("route-$it"))) }
        assertFalse(store.save(plan("one too many")))
        assertTrue(store.save(plan("route-1").copy(name = "Edited")))
        val restart = RoutePlansStore(app).current()
        assertEquals(RoutePlans.MAX_ROUTES, restart.routes.size)
        assertEquals("Edited", restart.routes.first().name)
        assertFalse(store.save(plan("invalid").copy(match = RouteMatch.LINE, line = " ")))
        assertFalse(store.save(plan("invalid").copy(window = RouteWindow(emptySet()))))
        val removed = store.remove("route-1")!!
        assertTrue(store.save(plan("replacement")))
        assertFalse(store.restore(removed))
        assertEquals(RoutePlans.MAX_ROUTES, RoutePlansStore(app).current().routes.size)
    }

    @Test fun `undo preserves new and edited routes without overwriting their ids`() {
        val store = RoutePlansStore(app)
        store.save(plan())
        val removed = store.remove("school")!!
        assertTrue(RoutePlansStore(app).current().routes.isEmpty())
        store.save(plan("home").copy(name = "Nach Hause"))
        assertTrue(store.restore(removed))
        assertEquals(setOf("home", "school"), RoutePlansStore(app).current().routes.map { it.id }.toSet())
        store.save(plan().copy(name = "New name"))
        assertFalse(store.restore(removed))
        assertEquals("New name", store.current().routes.first { it.id == "school" }.name)
        assertNull(store.remove("unknown"))
    }
}
