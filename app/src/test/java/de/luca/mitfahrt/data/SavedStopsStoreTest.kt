package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SavedStopsStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("saved_stops", Context.MODE_PRIVATE)
    private var now = Instant.parse("2026-11-08T08:00:00Z")
    private val hbf = Stop("de:05116:23007", "MG Hbf /Europaplatz", GeoPoint(51.197, 6.445), 250)
    private fun store() = SavedStopsStore(app) { now }
    private fun qualify(store: SavedStopsStore, stop: Stop = hbf, prefix: String = "open") {
        repeat(3) { store.observe(stop, "$prefix/$it") }
    }
    private fun persisted() = prefs.all.values.joinToString("\n")

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
        app.getSharedPreferences("route_plans", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `three distinct openings are required and no favorite is created automatically`() {
        val store = store()
        store.observe(hbf, "first")
        assertTrue(store.state.value.recent.isEmpty())
        store.observe(hbf, "second")
        assertTrue(store.state.value.recent.isEmpty())
        store.observe(hbf, "third")
        assertEquals(listOf(hbf.copy(distance = null)), store.state.value.recent)
        assertTrue(store.state.value.favorites.isEmpty())
    }

    @Test fun `same opening and platform variants never count as new visits`() {
        val store = store()
        repeat(10) { store.observe(hbf.copy(id = hbf.id + ":1:$it"), "one screen") }
        assertTrue(store.state.value.recent.isEmpty())
        store.observe(hbf, "second screen")
        assertTrue(store.state.value.recent.isEmpty())
        store.observe(hbf.copy(id = hbf.id + ":1:2"), "third screen")
        assertEquals(listOf(hbf.id), store.state.value.recent.map { it.id })
    }

    @Test fun `before threshold only hashes and bounded observation metadata are persisted`() {
        val store = store()
        store.observe(hbf, "private visit token")
        store.observe(hbf, "second private token")
        val saved = persisted()
        listOf(hbf.id, hbf.name, "private visit token", "second private token", "51.197", "6.445", "distance").forEach {
            assertFalse(it, saved.contains(it))
        }
        val metadata = JSONArray(prefs.getString("observations", "[]"))
        assertEquals(1, metadata.length())
        val row = metadata.getJSONObject(0)
        assertTrue(row.getString("h").matches(Regex("[0-9a-f]{64}")))
        assertEquals(2, row.getJSONArray("v").length())
        assertEquals(now.toEpochMilli(), row.getLong("t"))
        assertEquals(SavedStopsState(), SavedStops.decode(prefs.getString("state", "{}")!!))
    }

    @Test fun `opening counts and qualified suggestions survive a store restart`() {
        store().observe(hbf, "one")
        store().observe(hbf, "two")
        val restarted = store()
        assertTrue(restarted.state.value.recent.isEmpty())
        restarted.observe(hbf, "three")
        assertEquals(hbf.id, store().state.value.recent.single().id)
        store().observe(hbf, "three")
        assertEquals(3, JSONArray(prefs.getString("observations", "[]")).getJSONObject(0).getJSONArray("v").length())
    }

    @Test fun `manual favorites are visible immediately and stay manual when automatic learning is off`() {
        val store = store()
        RoutePlansStore(app).setAutomaticLearning(false)
        assertTrue(store.toggleFavorite(hbf))
        assertTrue(store.state.value.isFavorite(hbf))
        repeat(3) { store.observe(hbf, "off/$it") }
        assertTrue(store.state.value.recent.isEmpty())
        assertEquals(0, JSONArray(prefs.getString("observations", "[]")).length())
        assertTrue(store().state.value.isFavorite(hbf))
        assertFalse(store.toggleFavorite(hbf))
        assertTrue(store.state.value.favorites.isEmpty())
    }

    @Test fun `learning off stops counts and hides previous suggestions without deleting explicit favorites`() {
        val store = store()
        qualify(store)
        assertTrue(store.toggleFavorite(Stop("other", "Other favorite")))
        val plans = RoutePlansStore(app)
        plans.setAutomaticLearning(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(store.state.value.recent.isEmpty())
        assertEquals("other", store.state.value.favorites.single().id)
        repeat(3) { store.observe(Stop("new", "Must not be remembered"), "off/$it") }
        assertFalse(persisted().contains("Must not be remembered"))
        plans.setAutomaticLearning(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(hbf.id, store.state.value.recent.single().id)
        store.observe(Stop("new", "New stop"), "first after on")
        assertEquals(listOf(hbf.id), store.state.value.recent.map { it.id })
    }

    @Test fun `legacy favorites survive while one off recents lose visible saved details`() {
        val favorite = Stop("pin", "A manual favorite", GeoPoint(51.2, 6.4))
        prefs.edit().putString("state", SavedStops.encode(SavedStopsState(listOf(favorite), listOf(hbf)))).commit()
        val migrated = store()
        assertEquals(listOf(favorite), migrated.state.value.favorites)
        assertTrue(migrated.state.value.recent.isEmpty())
        assertFalse(persisted().contains(hbf.name))
        assertFalse(persisted().contains(hbf.id))
        assertFalse(persisted().contains("51.197"))
        migrated.observe(hbf, "new one")
        assertTrue(migrated.state.value.recent.isEmpty())
        migrated.observe(hbf, "new two")
        assertEquals(hbf.id, migrated.state.value.recent.single().id)
    }

    @Test fun `legacy migration happens only once and does not manufacture additional visits`() {
        prefs.edit().putString("state", SavedStops.encode(SavedStopsState(recent = listOf(hbf)))).commit()
        repeat(4) { assertTrue(store().state.value.recent.isEmpty()) }
        val row = JSONArray(prefs.getString("observations", "[]")).getJSONObject(0)
        assertEquals(1, row.getJSONArray("v").length())
    }

    @Test fun `removing suggestion clears its evidence so one future opening cannot restore it`() {
        val store = store()
        qualify(store)
        store.removeRecent(hbf.copy(id = hbf.id + ":1:1"))
        assertTrue(store.state.value.recent.isEmpty())
        assertEquals(0, JSONArray(prefs.getString("observations", "[]")).length())
        assertFalse(persisted().contains(hbf.name))
        store().observe(hbf, "again one")
        store().observe(hbf, "again two")
        assertTrue(store().state.value.recent.isEmpty())
        store().observe(hbf, "again three")
        assertEquals(hbf.id, store().state.value.recent.single().id)
    }

    @Test fun `clear suggestions also resets unqualified observation counters but keeps pins`() {
        val store = store()
        store.toggleFavorite(hbf)
        store.observe(Stop("other", "Other"), "one")
        store.observe(Stop("other", "Other"), "two")
        store.clearRecent()
        assertTrue(store.state.value.isFavorite(hbf))
        assertEquals(0, JSONArray(prefs.getString("observations", "[]")).length())
        store.observe(Stop("other", "Other"), "three")
        assertTrue(store.state.value.recent.isEmpty())
    }

    @Test fun `observation tokens and stop counters are bounded without saving unqualified stop metadata`() {
        val store = store()
        repeat(150) { index -> now = now.plusSeconds(1); store.observe(Stop("stop-$index", "Secret name $index"), "visit-$index") }
        val rows = JSONArray(prefs.getString("observations", "[]"))
        assertEquals(100, rows.length())
        repeat(rows.length()) { assertTrue(rows.getJSONObject(it).getJSONArray("v").length() <= 3) }
        assertFalse(persisted().contains("Secret name"))
        assertFalse(persisted().contains("stop-"))
        assertTrue(store.state.value.recent.isEmpty())
    }

    @Test fun `old evidence expires after ninety days instead of qualifying a new one off view`() {
        val store = store()
        store.observe(hbf, "one"); store.observe(hbf, "two")
        now = now.plusSeconds(91 * 86400L)
        val restarted = store()
        restarted.observe(hbf, "three after months")
        assertTrue(restarted.state.value.recent.isEmpty())
        assertEquals(1, JSONArray(prefs.getString("observations", "[]")).getJSONObject(0).getJSONArray("v").length())
    }

    @Test fun `qualified recents expire while explicit favorites remain`() {
        val store = store()
        qualify(store)
        store.toggleFavorite(hbf)
        now = now.plusSeconds(91 * 86400L)
        val restarted = store()
        assertTrue(restarted.state.value.recent.isEmpty())
        assertTrue(restarted.state.value.isFavorite(hbf))
    }

    @Test fun `qualified metadata refreshes safely and recency remains bounded`() {
        val store = store()
        repeat(22) { i -> qualify(store, Stop("stop$i", "Stop $i"), "stop$i") }
        assertEquals(SavedStops.MAX_RECENT, store.state.value.recent.size)
        assertEquals("stop21", store.state.value.recent.first().id)
        store.observe(Stop("stop21", "Updated stop", GeoPoint(51.2, 6.4), 10), "new visit")
        assertEquals("Updated stop", store.state.value.recent.first().name)
        assertNull(store.state.value.recent.first().distance)
    }

    @Test fun `explicit select and recent undo remain compatible without automatic favorite creation`() {
        val store = store()
        store.select(hbf)
        assertEquals(hbf.id, store().state.value.recent.single().id)
        assertTrue(store.state.value.favorites.isEmpty())
        store.removeRecent(hbf)
        store.restoreRecent(hbf, 0)
        assertEquals(hbf.id, store().state.value.recent.single().id)
    }

    @Test fun `blank identifiers invalid stations and malformed stored counters cannot create suggestions`() {
        prefs.edit().putBoolean("observations_initialized", true).putString("observations",
            JSONArray().put(JSONObject().put("h", "not a hash").put("v", JSONArray().put("token")).put("t", now.toEpochMilli())).toString()).commit()
        val store = store()
        store.observe(hbf, " ")
        store.observe(Stop("demo:0", "Demo"), "a")
        store.observe(Stop("", "Missing"), "b")
        store.observe(Stop("id", " "), "c")
        assertTrue(store.state.value.recent.isEmpty())
        assertEquals(0, JSONArray(prefs.getString("observations", "[]")).length())
    }
}
