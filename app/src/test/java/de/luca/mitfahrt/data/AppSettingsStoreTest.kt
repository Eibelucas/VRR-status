package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import de.luca.mitfahrt.ui.SettingsTemplate
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
class AppSettingsStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        listOf(AppSettingsStore.PREFERENCES, "journey_alert_options", "appearance", "route_plans").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun `service and UI instances read persistent validated changes`() {
        val service = AppSettingsStore(app)
        val ui = AppSettingsStore(app)
        assertTrue(ui.set("map_zoom", "18"))
        assertFalse(ui.set("map_zoom", "200"))
        assertFalse(ui.set("unknown", "value"))
        assertEquals(18, service.current().int("map_zoom"))
        assertEquals(18, AppSettingsStore(app).current().int("map_zoom"))
        assertEquals("map_zoom", ui.current().recentKeys.first())
    }

    @Test fun `changes inside a profile do not overwrite standard or other profiles`() {
        val store = AppSettingsStore(app)
        store.set("theme", "light")
        val school = requireNotNull(store.addProfile("Schule"))
        store.set("theme", "dark")
        val free = requireNotNull(store.addProfile("Freizeit"))
        assertEquals("light", store.current().string("theme"))
        store.set("map_zoom", "17")
        store.selectProfile(school)
        assertEquals("dark", store.current().string("theme"))
        assertEquals(15, store.current().int("map_zoom"))
        store.selectProfile("base")
        assertEquals("light", store.current().string("theme"))
        store.deleteProfile(free)
        assertEquals(listOf(school), store.current().profiles.map { it.id })
    }

    @Test fun `reset group removes profile overrides while retaining standard and unrelated options`() {
        val store = AppSettingsStore(app)
        store.set("theme", "light")
        val school = requireNotNull(store.addProfile("Schule"))
        store.set("theme", "dark"); store.set("map_zoom", "18")
        store.resetGroup("appearance")
        assertEquals("light", store.current().string("theme"))
        assertEquals(18, store.current().int("map_zoom"))
        assertFalse(store.current().profiles.first { it.id == school }.values.containsKey("theme"))
        store.selectProfile("base"); store.resetGroup("appearance")
        assertEquals("system", store.current().string("theme"))
    }

    @Test fun `automatic selection changes with time without rewriting the saved manual mode`() {
        var now = Instant.parse("2026-11-09T05:59:00Z")
        val store = AppSettingsStore(app) { now }
        val id = requireNotNull(store.addProfile("Schule"))
        val profile = store.current().profiles.single()
        store.updateProfile(profile.copy(scheduled = true, days = setOf(1), startMinute = 420, endMinute = 540, values = mapOf("map_home" to "true")))
        store.selectProfile("auto")
        assertNull(store.current().activeProfileId)
        now = Instant.parse("2026-11-09T06:00:00Z")
        store.refresh()
        assertEquals(id, store.state.value.activeProfileId)
        assertTrue(store.state.value.bool("map_home"))
        assertEquals("auto", store.state.value.manualProfileId)
    }

    @Test fun `legacy controls migrate once and resetting never resurrects old disabled alerts`() {
        app.getSharedPreferences("journey_alert_options", Context.MODE_PRIVATE).edit()
            .putBoolean("arrival", false).putBoolean("exit", false).putInt("lead_seconds", 120).commit()
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("mode", "dark").commit()
        val store = AppSettingsStore(app)
        assertFalse(store.current().bool("alert_arrival"))
        assertFalse(store.current().bool("alert_exit"))
        assertEquals("120", store.current().string("alert_leads"))
        assertEquals("dark", store.current().string("theme"))
        store.resetGroup("alerts")
        assertTrue(AppSettingsStore(app).current().bool("alert_arrival"))
        assertEquals("60", AppSettingsStore(app).current().string("alert_leads"))
    }

    @Test fun `shared template omits personal identity and all history but retains deliberate routes`() {
        val store = AppSettingsStore(app)
        store.set("report_name", "Luca Privat"); store.set("report_class", "10b"); store.set("report_note", "Persönlich")
        val profile = requireNotNull(store.addProfile("Schule")); store.set("report_name", "Auch privat")
        val plan = RoutePlan(id = "school-route", name = "Schulweg", origin = Stop("de:05116:101", "Hehn"), destination = Stop("de:05116:102", "Hbf"))
        assertTrue(RoutePlansStore(app).save(plan))
        val exported = SettingsTemplate.export(app, store)
        assertFalse(exported.contains("Luca Privat")); assertFalse(exported.contains("Auch privat")); assertFalse(exported.contains("10b"))
        val parsed = SettingsTemplate.validate(exported)
        assertEquals(listOf(plan), parsed.second)
        assertEquals(profile, AppSettingsCodec.decode(parsed.first).profiles.single().id)
        assertEquals(setOf("kind", "version", "settings", "routes"), JSONObject(exported).keys().asSequence().toSet())
        assertEquals("base", AppSettingsCodec.decode(parsed.first).manualProfileId)
    }

    @Test fun `invalid template cannot partially replace settings or routes`() {
        val store = AppSettingsStore(app); store.set("theme", "dark")
        val data = JSONObject(SettingsTemplate.export(app, store))
        data.getJSONObject("settings").getJSONObject("values").put("theme", "light")
        data.getJSONObject("routes").put("routes", JSONArray().put(JSONObject().put("name", "Invalid route")))
        assertFalse(SettingsTemplate.importConfiguration(app, store, data.toString()))
        assertEquals("dark", store.current().string("theme"))
        assertTrue(RoutePlansStore(app).current().routes.isEmpty())
    }

    @Test fun `valid template applies settings and merges named routes without deleting history preferences`() {
        val store = AppSettingsStore(app)
        store.set("theme", "dark")
        val plan = RoutePlan(id = "school-route", name = "Schulweg", origin = Stop("de:05116:101", "Hehn"), destination = Stop("de:05116:102", "Hbf"))
        RoutePlansStore(app).save(plan)
        val data = SettingsTemplate.export(app, store)
        store.set("theme", "light")
        app.getSharedPreferences("ride_history", Context.MODE_PRIVATE).edit().putString("private", "keep").commit()
        assertTrue(SettingsTemplate.importConfiguration(app, store, data))
        assertEquals("dark", store.current().string("theme"))
        assertEquals(listOf(plan), RoutePlansStore(app).current().routes)
        assertEquals("keep", app.getSharedPreferences("ride_history", Context.MODE_PRIVATE).getString("private", null))
    }
}
