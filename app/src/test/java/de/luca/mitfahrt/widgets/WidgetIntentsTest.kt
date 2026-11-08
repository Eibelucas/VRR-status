package de.luca.mitfahrt.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WidgetIntentsTest {
    @Test fun `stop departures carry kind id and action`() {
        assertEquals(mapOf("widget_kind" to "stop", "widget_id" to "de:05116:7000", "widget_action" to "departures"),
            WidgetIntents.extras(WidgetAction.Open(WidgetKind.STOP, "de:05116:7000", "departures")))
    }

    @Test fun `map action adds open_live_map`() {
        val extras = WidgetIntents.extras(WidgetAction.Open(WidgetKind.ACTIVE, null, "map"))
        assertEquals(true, extras["open_live_map"])
        assertEquals("active", extras["widget_kind"])
        assertFalse(extras.containsKey("widget_id"))
    }

    @Test fun `route preview never includes a live map flag`() {
        val extras = WidgetIntents.extras(WidgetAction.Open(WidgetKind.ROUTE, "r1", "preview"))
        assertEquals("preview", extras["widget_action"])
        assertFalse(extras.containsKey("open_live_map"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown actions are rejected`() {
        WidgetIntents.extras(WidgetAction.Open(WidgetKind.ROUTE, "r1", "start"))
    }
}
