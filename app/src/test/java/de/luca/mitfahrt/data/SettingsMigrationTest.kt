package de.luca.mitfahrt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsMigrationTest {
    private fun clean(key: String, value: String) = SettingsCatalog.byKey.getValue(key).clean(value)

    @Test fun `old navigation order keeps the user's order with the new tabs`() {
        assertEquals("home,nearby,reports,settings", clean("nav_order", "ride,stops,reports,settings"))
        assertEquals("settings,home,nearby,reports", clean("nav_order", "settings,ride,stops,reports"))
        assertEquals("reports,nearby,home,settings", clean("nav_order", "reports,stops"))
        // Old and new names together never duplicate a tab.
        assertEquals("home,nearby,reports,settings", clean("nav_order", "ride,home,stops,nearby"))
    }

    @Test fun `old start page values are mapped instead of falling back silently`() {
        assertEquals("home", clean("start_page", "ride"))
        assertEquals("nearby", clean("start_page", "stops"))
        assertEquals("routes", clean("start_page", "routes"))
        assertEquals("map", clean("start_page", "map"))
        assertNull(clean("start_page", "evil"))
        assertEquals("home", AppSettings().string("start_page"))
    }

    @Test fun `nearby and favorites leave the start page cards`() {
        assertEquals("planned,suggestions,school,last", clean("home_cards", "planned,nearby,favorites,suggestions,school,last"))
        assertEquals("", clean("home_cards", "nearby,favorites"))
        assertEquals(listOf("planned", "school", "suggestions"), AppSettings().csv("home_cards"))
    }

    @Test fun `stored legacy settings are migrated when they are read`() {
        val stored = JSONObject().put("version", 1).put("values", JSONObject()
            .put("nav_order", "stops,ride,settings,reports").put("start_page", "stops")
            .put("home_cards", "nearby,planned,favorites,map").put("nearby_radius", "800")).toString()
        val settings = AppSettingsCodec.decode(stored)
        assertEquals(listOf("nearby", "home", "settings", "reports"), settings.csv("nav_order"))
        assertEquals("nearby", settings.string("start_page"))
        assertEquals(listOf("planned", "map"), settings.csv("home_cards"))
        assertEquals(800, settings.int("nearby_radius"))
    }

    @Test fun `trip options come from settings and only chosen options are active`() {
        val defaults = TripSettings.options(AppSettings())
        assertEquals(TripOptions(), defaults)
        assertFalse(defaults.changedFromDefault)
        val custom = TripSettings.options(AppSettings(mapOf("trip_modes" to "bus,on_demand", "trip_max_changes" to "1",
            "trip_walk_speed" to "fast", "trip_max_walk" to "15", "trip_route_type" to "leastwalking", "trip_wheelchair" to "true")))
        assertEquals(setOf(ProductGroup.TRAIN, ProductGroup.LIGHT_RAIL, ProductGroup.OTHER), custom.excluded)
        assertEquals(1, custom.maxChanges); assertEquals(WalkSpeed.FAST, custom.walkSpeed); assertEquals(15, custom.maxWalkMinutes)
        assertEquals(RoutePreference.LEAST_WALKING, custom.preference); assertTrue(custom.wheelchair); assertFalse(custom.noSolidStairs)
        // Round trip through the settings values used by the options sheet.
        val values = TripSettings.values(custom)
        assertEquals(custom, TripSettings.options(AppSettings(SettingsCatalog.clean(values))))
        assertNull(clean("trip_modes", "evil"))
        assertEquals("bus", clean("trip_modes", "bus,bus,evil"))
    }

    @Test fun `search history setting exists and defaults to on`() {
        assertTrue(AppSettings().bool("trip_history"))
        assertEquals("privacy", SettingsCatalog.byKey.getValue("trip_history").group)
        assertTrue(SettingsCatalog.definitions.all { it.group in SettingsCatalog.groups })
    }
}
