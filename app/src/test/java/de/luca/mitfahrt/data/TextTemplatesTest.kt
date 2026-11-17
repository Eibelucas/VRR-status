package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test

class TextTemplatesTest {
    private val values = mapOf("linie" to "017", "ziel" to "Hbf", "minuten" to "", "verspaetung" to "+2 Min", "status" to "Bus kommt in 6:00")

    @Test fun `placeholders are replaced`() {
        assertEquals("017 → Hbf", TextTemplates.render("{linie} → {ziel}", values))
    }

    @Test fun `a part with a missing value is left out instead of showing a gap`() {
        assertEquals("017 · +2 Min", TextTemplates.render("{linie} · {minuten} Min · {verspaetung}", values))
        assertEquals("", TextTemplates.render("{minuten} Min", values))
    }

    @Test fun `unknown names are reported and never rendered as a value`() {
        assertEquals(listOf("linje"), TextTemplates.unknown("{linje} {ziel}"))
        assertEquals("Hbf", TextTemplates.render("{linje} · {ziel}", values))
        assertTrue(TextTemplates.unknown("{linie} · {ziel}").isEmpty())
    }

    @Test fun `own texts apply only when switched on and written`() {
        val off = AppSettings(mapOf("tpl_nowbar_chip" to "{linie}!"))
        assertNull(TextTemplates.custom(off, "tpl_nowbar_chip", values))
        val on = AppSettings(mapOf("templates_enabled" to "true", "tpl_nowbar_chip" to "{linie}!", "tpl_nowbar_title_wait" to "{minuten}"))
        assertEquals("017!", TextTemplates.custom(on, "tpl_nowbar_chip", values))
        assertNull("empty template keeps the built-in text", TextTemplates.custom(on, "tpl_nowbar_text_wait", values))
        assertNull("nothing left after dropping parts keeps the built-in text", TextTemplates.custom(on, "tpl_nowbar_title_wait", values))
    }

    @Test fun `length limits and line breaks are enforced`() {
        assertEquals("a b", TextTemplates.clean("a\nb", 10))
        assertEquals(24, TextTemplates.clean("x".repeat(80), 24).length)
        val on = AppSettings(mapOf("templates_enabled" to "true", "tpl_nowbar_chip" to "{status} {status} {status}"))
        assertTrue(TextTemplates.custom(on, "tpl_nowbar_chip", values)!!.length <= 24)
    }

    @Test fun `every default and every sample is valid`() {
        TextTemplates.slots.forEach { slot ->
            assertTrue(slot.key, TextTemplates.unknown(slot.default).isEmpty())
            assertTrue(slot.key, TextTemplates.render(slot.default, TextTemplates.sample).isNotBlank())
            assertNotNull(slot.key, SettingsCatalog.definitions.firstOrNull { it.key == slot.key })
        }
    }
}
