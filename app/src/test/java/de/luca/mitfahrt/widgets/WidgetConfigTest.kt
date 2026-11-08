package de.luca.mitfahrt.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigTest {
    @Test fun `stop and route widgets need a readable target`() {
        assertNull(WidgetConfigs.clean(WidgetConfig(WidgetKind.STOP, "", "Hbf")))
        assertNull(WidgetConfigs.clean(WidgetConfig(WidgetKind.ROUTE, "id", "  ")))
        assertNull(WidgetConfigs.clean(WidgetConfig(WidgetKind.STOP, "demo:1", "Demo")))
        assertEquals("Hbf", WidgetConfigs.clean(WidgetConfig(WidgetKind.STOP, " de:1:2 ", " Hbf "))?.targetName)
    }

    @Test fun `active widget stores no target`() {
        val clean = WidgetConfigs.clean(WidgetConfig(WidgetKind.ACTIVE, "x", "y"))!!
        assertEquals("", clean.targetId)
        assertTrue(clean.isComplete())
    }

    @Test fun `opacity snaps to the offered steps`() {
        assertEquals(70, WidgetConfigs.clean(WidgetConfig(WidgetKind.ACTIVE, opacity = 73))?.opacity)
        assertEquals(50, WidgetConfigs.clean(WidgetConfig(WidgetKind.ACTIVE, opacity = 5))?.opacity)
    }

    @Test fun `unknown stored keys fall back to defaults`() {
        assertEquals(InfoLevel.NORMAL, InfoLevel.fromKey("gibt-es-nicht"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(WidgetAccent.BERRY, WidgetAccent.fromKey(""))
        assertNull(WidgetKind.fromKey("bus"))
        assertEquals(WidgetKind.ROUTE, WidgetKind.fromKey("route"))
    }

    @Test fun `palette alpha matches opacity`() {
        assertEquals(255, WidgetPalette.alpha(100))
        assertEquals(128, WidgetPalette.alpha(50))
    }
}
