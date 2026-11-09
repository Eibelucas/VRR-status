package de.luca.mitfahrt.widgets

import de.luca.mitfahrt.data.*
import org.junit.Assert.*
import org.junit.Test

class WidgetDefaultsTest {
    private val origin = Stop("de:05116:1", "Hehn")
    private val target = Stop("de:05116:2", "Hbf")
    private val route = RoutePlan(id = "school", name = "Zur Schule", origin = origin, destination = target)

    @Test fun `central route selection resolves a readable name without storing user location`() {
        val defaults = WidgetDefaults.from(AppSettings(mapOf("widget_kind" to "route", "widget_route_id" to "school",
            "widget_action" to "preview", "widget_opacity" to "73", "widget_count" to "5")), listOf(route), listOf(origin))
        assertEquals("school", defaults.targetId); assertEquals("Zur Schule", defaults.targetName)
        assertTrue(defaults.useAppDefaults); assertEquals(5, defaults.rowCount); assertEquals(73, defaults.opacity)
        assertEquals("preview", defaults.action)
    }

    @Test fun `deleted explicitly selected route never falls through to another route`() {
        val defaults = WidgetDefaults.from(AppSettings(mapOf("widget_kind" to "route", "widget_route_id" to "deleted")),
            listOf(route), listOf(origin))
        assertNull(WidgetConfigs.clean(defaults))
    }

    @Test fun `public station aliases resolve to the saved station rather than another stop`() {
        val defaults = WidgetDefaults.from(AppSettings(mapOf("widget_kind" to "stop", "widget_stop_id" to "de:05116:1:2:1")),
            listOf(route), listOf(origin, target))
        assertEquals(origin.id, defaults.targetId); assertEquals(origin.name, defaults.targetName)
    }
}
