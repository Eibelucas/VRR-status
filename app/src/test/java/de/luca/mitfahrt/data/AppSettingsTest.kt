package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AppSettingsTest {
    @Test fun `every catalog key is unique and its documented default is valid`() {
        assertEquals(SettingsCatalog.definitions.size, SettingsCatalog.byKey.size)
        SettingsCatalog.definitions.forEach { definition ->
            assertEquals(definition.key, definition.default, definition.clean(definition.default))
            assertTrue(definition.group in SettingsCatalog.groups)
        }
    }

    @Test fun `untrusted imported values cannot disable validation or inject unknown settings`() {
        val value = JSONObject().put("version", 1).put("values", JSONObject()
            .put("theme", "surprise").put("map_zoom", "9999").put("alert_arrival", "yes")
            .put("unrecognized", "secret").put("nearby_radius", "1000")).toString()
        val settings = AppSettingsCodec.decode(value)
        assertEquals("system", settings.string("theme"))
        assertEquals(15, settings.int("map_zoom"))
        assertTrue(settings.bool("alert_arrival"))
        assertFalse(settings.values.containsKey("unrecognized"))
        assertEquals(1000, settings.int("nearby_radius"))
    }

    @Test fun `scheduled profile crosses midnight on its starting weekday and only overrides its own keys`() {
        val profile = SettingsProfile("night", "Nacht", mapOf("theme" to "dark"), true, setOf(1), 22 * 60, 8 * 60)
        val encoded = AppSettingsCodec.encode(mapOf("theme" to "light", "map_zoom" to "17"), listOf(profile), "auto", emptyList(), false)
        val monday = AppSettingsCodec.decode(encoded, Instant.parse("2026-11-09T22:00:00Z"))
        val tuesday = AppSettingsCodec.decode(encoded, Instant.parse("2026-11-10T06:59:00Z"))
        val ended = AppSettingsCodec.decode(encoded, Instant.parse("2026-11-10T07:00:00Z"))
        assertEquals("night", monday.activeProfileId)
        assertEquals("night", tuesday.activeProfileId)
        assertEquals("dark", tuesday.string("theme"))
        assertEquals(17, tuesday.int("map_zoom"))
        assertNull(ended.activeProfileId)
        assertEquals("light", ended.string("theme"))
    }

    @Test fun `manual profile wins over schedule and manual standard suppresses schedules`() {
        val morning = SettingsProfile("school", "Schule", mapOf("map_home" to "true"), true, (1..7).toSet(), 0, 0)
        val weekend = SettingsProfile("free", "Freizeit", mapOf("map_home" to "false"))
        fun read(manual: String) = AppSettingsCodec.decode(AppSettingsCodec.encode(emptyMap(), listOf(morning, weekend), manual, emptyList(), false))
        assertEquals("school", read("auto").activeProfileId)
        assertEquals("free", read("free").activeProfileId)
        assertFalse(read("free").bool("map_home"))
        assertNull(read("base").activeProfileId)
        assertNull(read("deleted-profile").activeProfileId)
    }

    @Test fun `overlapping schedules have a stable first-created winner`() {
        val first = SettingsProfile("first", "Erstes", mapOf("theme" to "dark"), true, (1..7).toSet(), 0, 0)
        val second = first.copy(id = "second", name = "Zweites", values = mapOf("theme" to "light"))
        val encoded = AppSettingsCodec.encode(emptyMap(), listOf(first, second), "auto", emptyList(), false)
        assertEquals("first", AppSettingsCodec.decode(encoded).activeProfileId)
    }

    @Test fun `lists cannot introduce unknown navigation destinations or repeated cards`() {
        assertEquals("ride,settings,stops,reports", SettingsCatalog.byKey.getValue("nav_order").clean("ride,settings,evil,ride"))
        assertEquals("map,nearby", SettingsCatalog.byKey.getValue("home_cards").clean("map,unknown,nearby,map"))
        assertEquals("board,arrival,map", SettingsCatalog.byKey.getValue("notification_actions").clean("board,arrival,map,stop"))
    }

    @Test fun `dates and alert timings reject unusable inputs`() {
        assertNull(SettingsCatalog.byKey.getValue("quiet_start").clean("29:99"))
        assertNull(SettingsCatalog.byKey.getValue("routes_free_dates").clean("2026-02-30"))
        assertNull(SettingsCatalog.byKey.getValue("alert_leads").clean("12,13"))
        assertEquals("300,60", SettingsCatalog.byKey.getValue("alert_leads").clean("60,300,60"))
        assertEquals("2026-11-10", SettingsCatalog.byKey.getValue("routes_free_dates").clean("2026-11-10,2026-11-10"))
    }

    @Test fun `import files have a strict size and format version bound`() {
        assertThrows(IllegalArgumentException::class.java) { AppSettingsCodec.decode(" ".repeat(AppSettingsCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { AppSettingsCodec.decode("{\"version\":999}") }
        val profiles = JSONArray((1..30).map { JSONObject().put("id", "p$it").put("name", "Profil $it") })
        assertEquals(AppSettingsCodec.MAX_PROFILES, AppSettingsCodec.decode(JSONObject().put("profiles", profiles).toString()).profiles.size)
    }
}
