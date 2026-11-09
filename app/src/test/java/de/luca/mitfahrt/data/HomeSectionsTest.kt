package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HomeSectionsTest {
    /** Tuesday 10.11.2026, 07:30 in Mönchengladbach. */
    private val now = Instant.parse("2026-11-10T06:30:00Z")
    private val settings = AppSettings()
    private val origin = Stop("de:05116:7300", "Bismarckplatz")
    private fun plan(id: String, start: Int, end: Int, nearbyOnly: Boolean = false, school: Boolean = false) = RoutePlan(id = id, name = id,
        origin = origin, destination = Stop("de:05116:23007", "Hbf"), window = RouteWindow((1..5).toSet(), start, end),
        nearbyOnly = nearbyOnly, schoolOnly = school)

    private fun sections(plans: List<RoutePlan>, order: List<String> = AppSettings().csv("home_cards"),
        overrides: List<RouteDayOverride> = emptyList(), calendar: SchoolCalendarSettings? = SchoolCalendarSettings(),
        nearby: List<Stop> = emptyList(), schoolTrips: Int = 0, suggestions: Int = 0, last: Boolean = false): List<String> {
        val active = HomeSections.activePlans(plans, now, nearby, settings, overrides, calendar)
        return HomeSections.visible(order, settings, active, now, calendar, schoolTrips, suggestions, last)
    }

    @Test fun `without saved routes the routes section does not exist at all`() {
        assertEquals(emptyList<String>(), sections(emptyList()))
        assertFalse("planned" in sections(emptyList(), order = listOf("planned", "last", "map")))
    }

    @Test fun `routes follow their time windows`() {
        assertEquals(listOf("planned"), sections(listOf(plan("morgens", 7 * 60, 8 * 60))))
        assertEquals(emptyList<String>(), sections(listOf(plan("abends", 17 * 60, 18 * 60))))
    }

    @Test fun `day overrides and the school calendar hide routes for the day`() {
        val route = plan("schule", 7 * 60, 8 * 60, school = true)
        val hidden = RouteDayOverride(routeId = "schule", date = LocalDate.of(2026, 11, 10), mode = DayOverrideMode.HIDDEN)
        assertEquals(emptyList<String>(), sections(listOf(route), overrides = listOf(hidden)))
        val freeDay = SchoolCalendarSettings(freeDays = mapOf(LocalDate.of(2026, 11, 10) to "Brückentag"))
        assertEquals(emptyList<String>(), sections(listOf(route), calendar = freeDay))
        // Different times for today only: shown inside the replacement window.
        val later = RouteDayOverride(routeId = "schule", date = LocalDate.of(2026, 11, 10), mode = DayOverrideMode.TIMES,
            windows = listOf(DayTimeWindow(7 * 60 + 15, 9 * 60)))
        assertEquals(listOf("planned"), sections(listOf(plan("schule", 12 * 60, 13 * 60, school = true)), overrides = listOf(later)))
    }

    @Test fun `nearby-only routes need the stop nearby`() {
        val route = plan("nah", 7 * 60, 8 * 60, nearbyOnly = true)
        assertEquals(emptyList<String>(), sections(listOf(route)))
        assertEquals(listOf("planned"), sections(listOf(route), nearby = listOf(origin.copy(id = "de:05116:7300:1:2"))))
    }

    @Test fun `nearby stops and favourites never appear on the start page`() {
        val legacy = listOf("nearby", "favorites", "planned", "suggestions", "school", "last", "map")
        assertEquals(listOf("planned", "suggestions", "school", "last", "map"),
            sections(listOf(plan("a", 7 * 60, 8 * 60)), order = legacy, schoolTrips = 1, suggestions = 2, last = true))
    }

    @Test fun `empty sections are skipped and the user's order is kept`() {
        assertEquals(listOf("map", "suggestions"), sections(emptyList(), order = listOf("map", "school", "suggestions", "last"), suggestions = 1))
        val manual = AppSettings(mapOf("manual_only" to "true"))
        assertEquals(emptyList<String>(), HomeSections.visible(listOf("suggestions"), manual, emptyList(), now, null, 0, 3, false))
        val paused = AppSettings(mapOf("routes_school_paused" to "true"))
        assertEquals(emptyList<String>(), HomeSections.visible(listOf("school"), paused, emptyList(), now, null, 1, 0, false))
    }
}
