package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
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
class PrivacyCustomizationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = Instant.parse("2026-11-08T07:00:00Z")
    private val stop = Stop("de:05116:23007", "Hbf")
    private fun settings() = AppSettingsStore(app)
    @Before fun clear() {
        listOf(AppSettingsStore.PREFERENCES, "route_plans", "saved_stops", "route_history", "arrivals").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun `five intentional views qualify and do not store station metadata earlier`() {
        assertTrue(settings().set("suggestion_threshold", "5"))
        val store = SavedStopsStore(app) { now }
        repeat(4) { store.observe(stop, "open-$it") }
        assertTrue(store.state.value.recent.isEmpty())
        assertFalse(app.getSharedPreferences("saved_stops", Context.MODE_PRIVATE).getString("state", "")!!.contains("Hbf"))
        store.observe(stop, "open-4")
        assertEquals(listOf(stop), store.state.value.recent)
        store.observe(stop, "open-4")
        assertEquals(1, store.state.value.recent.size)
    }
    @Test fun `stop learning switch stops counters preserves manual favorites and can mute a suggestion`() {
        val store = SavedStopsStore(app) { now }
        store.toggleFavorite(stop)
        settings().set("learn_stops", "false")
        repeat(10) { store.observe(stop, "off-$it") }
        assertTrue(store.state.value.recent.isEmpty())
        assertTrue(store.state.value.isFavorite(stop))
        settings().set("learn_stops", "true")
        repeat(3) { store.observe(stop, "on-$it") }
        assertEquals(1, store.state.value.recent.size)
        store.mute(stop)
        assertTrue(store.state.value.recent.isEmpty())
        assertTrue(store.state.value.isFavorite(stop))
        assertTrue(SavedStopsStore(app) { now }.state.value.recent.isEmpty())
        store.clearMuted()
        assertEquals(1, store.state.value.recent.size)
    }
    @Test fun `route threshold can increase without losing views and disabled route learning records nothing`() {
        settings().set("suggestion_threshold", "5")
        val ride = DemoRide.create(now).let { it.copy(demo = false,
            departure = it.departure.copy(lineId = "new006", stopId = stop.id)) }
        val store = RouteHistoryStore(app, null) { now }
        repeat(4) { store.observe(ride, "view-$it") }
        assertTrue(store.routes.value.isEmpty())
        val raw = app.getSharedPreferences("route_history", Context.MODE_PRIVATE).getString("visits", "")!!
        assertFalse(raw.contains(ride.departure.destination))
        store.observe(ride, "view-4")
        assertEquals(5, store.routes.value.single().count)
        settings().set("suggestion_threshold", "10")
        repeat(5) { store.observe(ride, "more-$it") }
        assertEquals(10, store.routes.value.single().count)
        settings().set("learn_routes", "false")
        store.observe(ride, "disabled")
        assertEquals(10, store.routes.value.single().count)
    }
    @Test fun `detected arrivals retain source reject future duplicate observations and respect storage toggle`() {
        val store = ArrivalStore(app) { now }
        store.recordDetected(stop, now.minusSeconds(5))
        store.recordDetected(stop, now)
        assertEquals(1, store.marks.value.size)
        assertEquals("location", store.marks.value.single().method)
        assertEquals("location", ArrivalMark.decode(store.marks.value.single().json()).method)
        store.recordDetected(stop.copy(id = "other"), now.plusSeconds(30))
        assertEquals(1, store.marks.value.size)
        settings().set("arrival_store", "false")
        store.record(stop)
        store.recordDetected(stop.copy(id = "other"), now)
        assertEquals(1, store.marks.value.size)
        store.clear()
        assertTrue(store.marks.value.isEmpty())
    }
    @Test fun `arrival retention removes expired records without rewriting their captured times`() {
        val store = ArrivalStore(app) { now }
        store.record(stop)
        val time = store.marks.value.single().at
        settings().set("arrival_retention_days", "7")
        now = now.plusSeconds(6 * 86400L)
        store.prune()
        assertEquals(time, store.marks.value.single().at)
        now = now.plusSeconds(2 * 86400L)
        store.prune()
        assertTrue(store.marks.value.isEmpty())
    }
}
