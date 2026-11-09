package de.luca.mitfahrt.widgets

import android.app.Application
import android.content.Context
import de.luca.mitfahrt.data.*
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
class WidgetStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val original = WidgetConfig(WidgetKind.STOP, "de:05116:1", "Hehn")
    private val now = Instant.parse("2026-11-09T07:00:00Z")
    private val snapshot = WidgetSnapshot(now, listOf(Departure("line", "015", "Hbf", "de:05116:1", "Hehn", "trip",
        now.plusSeconds(600), now.plusSeconds(660), "1", observedAt = now)))

    @Before fun clear() {
        WidgetStore(app).removeAll()
        app.getSharedPreferences(AppSettingsStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `late response cannot appear below a reconfigured stop title`() {
        val worker = WidgetStore(app)
        val editor = WidgetStore(app)
        assertTrue(editor.saveConfig(42, original))
        val request = worker.revision(42)
        assertTrue(editor.saveConfig(42, original.copy(targetId = "de:05116:2", targetName = "Hbf")))
        assertFalse(worker.saveSnapshotIfCurrent(42, original, request, snapshot))
        assertTrue(editor.snapshot(42).departures.isEmpty())
    }

    @Test fun `deleted and recreated widget rejects its previous in flight response`() {
        val store = WidgetStore(app)
        store.saveConfig(42, original)
        val request = store.revision(42)
        store.remove(42)
        assertFalse(store.saveSnapshotIfCurrent(42, original, request, snapshot))
        store.saveConfig(42, original)
        assertFalse(store.saveSnapshotIfCurrent(42, original, request, snapshot))
    }

    @Test fun `app widget settings really change defaults and invalidate old requests`() {
        val settings = AppSettingsStore(app)
        settings.set("widget_kind", "active"); settings.set("widget_opacity", "73"); settings.set("widget_count", "5")
        settings.set("widget_action", "map"); settings.set("widget_theme", "dark")
        val store = WidgetStore(app)
        store.saveConfig(42, WidgetConfig(WidgetKind.ACTIVE, useAppDefaults = true))
        val config = requireNotNull(store.config(42))
        assertEquals(73, config.opacity); assertEquals(5, config.rowCount)
        assertEquals("map", config.action); assertEquals(ThemeMode.DARK, config.theme)
        val request = store.revision(42)
        settings.set("widget_theme", "light")
        assertFalse(store.saveSnapshotIfCurrent(42, config, request, snapshot))
        assertEquals(ThemeMode.LIGHT, store.config(42)?.theme)
        settings.close()
    }

    @Test fun `per widget overrides keep independence from app defaults`() {
        val store = WidgetStore(app)
        store.saveConfig(42, original.copy(opacity = 85, rowCount = 2, theme = ThemeMode.LIGHT, action = "departures"))
        val settings = AppSettingsStore(app)
        settings.set("widget_kind", "active"); settings.set("widget_theme", "dark"); settings.set("widget_action", "map")
        assertEquals(WidgetKind.STOP, store.config(42)?.kind)
        assertEquals(ThemeMode.LIGHT, store.config(42)?.theme)
        assertEquals("departures", store.config(42)?.action)
        settings.close()
    }

    @Test fun `active display refresh never creates a persistent second journey history`() {
        val store = WidgetStore(app)
        val config = WidgetConfig(WidgetKind.ACTIVE)
        store.saveConfig(42, config)
        val ride = Ride(snapshot.departures.single(), listOf(RideStop("de:05116:1", "Hehn", null, now, now)), 0, now)
        assertTrue(store.saveSnapshotIfCurrent(42, config, store.revision(42), WidgetSnapshot(now, ride = ride)))
        assertEquals(ride, store.snapshot(42).ride)
        assertFalse(app.getSharedPreferences("mitfahrt_widgets", Context.MODE_PRIVATE).contains("42.snapshot"))
        store.clearActiveSnapshots()
        assertNull(store.snapshot(42).ride)
    }

    @Test fun `clearing cache preserves widget configuration and personal app stores`() {
        val store = WidgetStore(app)
        store.saveConfig(42, original)
        assertTrue(store.saveSnapshotIfCurrent(42, original, store.revision(42), snapshot))
        app.getSharedPreferences("saved_stops", Context.MODE_PRIVATE).edit().putString("sentinel", "keep").commit()
        store.clearSnapshots()
        assertEquals(original, store.config(42)); assertTrue(store.snapshot(42).departures.isEmpty())
        assertEquals("keep", app.getSharedPreferences("saved_stops", Context.MODE_PRIVATE).getString("sentinel", null))
    }
}
