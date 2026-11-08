package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CustomizationPolicyTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private fun departure(line: String, live: Boolean = true, cancelled: Boolean = false, age: Long = 0) =
        Departure("new:$line", line, "Hbf", "stop", "Hehn", line, now.plusSeconds(180),
            if (live) now.plusSeconds(240) else null, "", cancelled, observedAt = now.minusSeconds(age))

    @Test fun `line filters normalize leading zeros and exclusions win`() {
        val settings = AppSettings(mapOf("line_allow" to "7,017", "line_block" to "017"))
        assertEquals(listOf("007"), CustomizationPolicy.departures(listOf(departure("007"), departure("017"), departure("006")), settings, now).map { it.line })
    }
    @Test fun `live only hides timetable stale forecasts and cancelled journeys`() {
        val settings = AppSettings(mapOf("live_only" to "true"))
        val live = departure("007")
        assertEquals(listOf(live), CustomizationPolicy.departures(listOf(live, departure("006", live = false), departure("008", age = 121), departure("017", cancelled = true)), settings, now))
    }
    @Test fun `radius expands only if allowed and no remaining stops are inside`() {
        val stops = listOf(Stop("a", "Nah", distance = 200), Stop("b", "Fern", distance = 900))
        val settings = AppSettings(mapOf("nearby_radius" to "500", "excluded_stops" to "a", "nearby_expand" to "true"))
        assertEquals(listOf("b"), CustomizationPolicy.stops(stops, settings).map { it.id })
        assertTrue(CustomizationPolicy.stops(stops, settings.copy(values = settings.values + ("nearby_expand" to "false"))).isEmpty())
    }
    @Test fun `manual stop order follows user priority and exclusions`() {
        val stops = listOf(Stop("a", "A", distance = 100), Stop("b", "B", distance = 300), Stop("c", "C", distance = 200))
        val settings = AppSettings(mapOf("stop_sort" to "manual", "preferred_stops" to "b,a", "excluded_stops" to "c"))
        assertEquals(listOf("b", "a"), CustomizationPolicy.stops(stops, settings).map { it.id })
    }
    @Test fun `departure stop sorting follows actual fetched departures`() {
        val stops = listOf(Stop("a", "A", distance = 100), Stop("b", "B", distance = 300))
        val settings = AppSettings(mapOf("stop_sort" to "departure"))
        assertEquals(listOf("b", "a"), CustomizationPolicy.stops(stops, settings, listOf(departure("7").copy(stopId = "b"))).map { it.id })
    }
}
