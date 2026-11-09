package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant

class RouteDayOverridesTest {
    private val zone = RouteDayOverrides.zone
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()
    private fun times(route: String, date: String, vararg windows: Pair<Int, Int>) =
        RouteDayOverride(id = "$route/$date", routeId = route, date = LocalDate.parse(date), mode = DayOverrideMode.TIMES,
            windows = windows.map { DayTimeWindow(it.first, it.second) })
    private fun hidden(route: String, date: String) =
        RouteDayOverride(id = "$route/$date/h", routeId = route, date = LocalDate.parse(date), mode = DayOverrideMode.HIDDEN)

    // 2026-11-09 is a Monday.
    private val schoolMornings = listOf(RouteWindow((1..5).toSet(), 7 * 60, 8 * 60))

    @Test fun `no exception leaves the normal rule to the caller`() {
        assertNull(RouteDayOverrides.decision(emptyList(), "school", at("2026-11-09T07:30")))
    }

    @Test fun `hidden day wins over everything`() {
        val list = listOf(hidden("school", "2026-11-09"))
        assertEquals(false, RouteDayOverrides.decision(list, "school", at("2026-11-09T07:30"), schoolMornings))
        assertNull(RouteDayOverrides.decision(list, "school", at("2026-11-10T07:30"), schoolMornings))
    }

    @Test fun `different times replace the weekly window on that date`() {
        val list = listOf(times("school", "2026-11-09", 9 * 60 to 10 * 60))
        assertEquals(false, RouteDayOverrides.decision(list, "school", at("2026-11-09T07:30")))
        assertEquals(true, RouteDayOverrides.decision(list, "school", at("2026-11-09T09:15")))
        assertEquals(false, RouteDayOverrides.decision(list, "school", at("2026-11-09T10:00")))
    }

    @Test fun `several windows on one day`() {
        val list = listOf(times("school", "2026-11-09", 13 * 60 to 14 * 60, 9 * 60 to 9 * 60 + 30))
        assertEquals(true, RouteDayOverrides.decision(list, "school", at("2026-11-09T09:10")))
        assertEquals(false, RouteDayOverrides.decision(list, "school", at("2026-11-09T11:00")))
        assertEquals(true, RouteDayOverrides.decision(list, "school", at("2026-11-09T13:59")))
    }

    @Test fun `outward and return routes are independent`() {
        val list = listOf(hidden("to-school", "2026-11-09"))
        assertEquals(false, RouteDayOverrides.decision(list, "to-school", at("2026-11-09T07:30")))
        assertNull(RouteDayOverrides.decision(list, "home", at("2026-11-09T13:30")))
    }

    @Test fun `overnight exception belongs to its start date and carries over`() {
        val list = listOf(times("party", "2026-12-31", 22 * 60 to 2 * 60))
        assertEquals(true, RouteDayOverrides.decision(list, "party", at("2026-12-31T23:30")))
        // Month and year change.
        assertEquals(true, RouteDayOverrides.decision(list, "party", at("2027-01-01T01:59")))
        assertNull(RouteDayOverrides.decision(list, "party", at("2027-01-01T02:00")))
    }

    @Test fun `hidden day is not shown again by the previous night`() {
        val list = listOf(times("late", "2026-11-09", 23 * 60 to 3 * 60), hidden("late", "2026-11-10"))
        assertEquals(false, RouteDayOverrides.decision(list, "late", at("2026-11-10T01:00")))
    }

    @Test fun `normal overnight carry from a replaced day is suppressed`() {
        val nightly = listOf(RouteWindow((1..7).toSet(), 22 * 60, 2 * 60))
        val list = listOf(hidden("night", "2026-11-09"))
        assertEquals(false, RouteDayOverrides.decision(list, "night", at("2026-11-10T01:00"), nightly))
        // The next evening uses the normal rule again.
        assertNull(RouteDayOverrides.decision(list, "night", at("2026-11-10T22:30"), nightly))
    }

    @Test fun `normal window of the following day itself is not suppressed`() {
        val windows = listOf(RouteWindow((1..7).toSet(), 22 * 60, 2 * 60), RouteWindow(setOf(2), 0, 3 * 60))
        val list = listOf(hidden("night", "2026-11-09"))
        assertNull(RouteDayOverrides.decision(list, "night", at("2026-11-10T01:00"), windows))
    }

    @Test fun `exception day replaces the normal carry-over of the previous night`() {
        val nightly = listOf(RouteWindow((1..7).toSet(), 22 * 60, 2 * 60))
        val list = listOf(times("night", "2026-11-10", 9 * 60 to 10 * 60))
        assertEquals(false, RouteDayOverrides.decision(list, "night", at("2026-11-10T01:00"), nightly))
    }

    @Test fun `wall clock windows survive daylight saving changes`() {
        // 2026-03-29: clocks jump from 02:00 to 03:00; 2026-10-25: from 03:00 back to 02:00.
        val spring = listOf(times("r", "2026-03-29", 1 * 60 + 30 to 3 * 60 + 30))
        assertEquals(true, RouteDayOverrides.decision(spring, "r", at("2026-03-29T03:10")))
        assertEquals(false, RouteDayOverrides.decision(spring, "r", at("2026-03-29T03:30")))
        val autumn = listOf(times("r", "2026-10-25", 2 * 60 to 2 * 60 + 30))
        val first = LocalDateTime.parse("2026-10-25T02:15").atZone(zone).withEarlierOffsetAtOverlap().toInstant()
        val second = LocalDateTime.parse("2026-10-25T02:15").atZone(zone).withLaterOffsetAtOverlap().toInstant()
        assertEquals(true, RouteDayOverrides.decision(autumn, "r", first))
        assertEquals(true, RouteDayOverrides.decision(autumn, "r", second))
    }

    @Test fun `saving the same route and date replaces and keeps the id`() {
        val today = LocalDate.parse("2026-11-09")
        val first = RouteDayOverrides.upsert(emptyList(), hidden("school", "2026-11-12"), today)!!
        val second = RouteDayOverrides.upsert(first, times("school", "2026-11-12", 540 to 600).copy(id = "other"), today)!!
        assertEquals(1, second.size)
        assertEquals(first.single().id, second.single().id)
        assertEquals(DayOverrideMode.TIMES, second.single().mode)
    }

    @Test fun `edited exception moved to another date leaves the old date`() {
        val today = LocalDate.parse("2026-11-09")
        val start = RouteDayOverrides.upsert(listOf(), hidden("school", "2026-11-12"), today)!!
        val moved = RouteDayOverrides.upsert(start, start.single().copy(date = LocalDate.parse("2026-11-13")), today)!!
        assertEquals(listOf("2026-11-13"), moved.map { it.date.toString() })
    }

    @Test fun `past and invalid exceptions are rejected or pruned`() {
        val today = LocalDate.parse("2026-11-09")
        assertNull(RouteDayOverrides.upsert(emptyList(), hidden("school", "2026-11-08"), today))
        assertNull(RouteDayOverrides.upsert(emptyList(), times("school", "2026-11-10"), today))
        assertNull(RouteDayOverrides.upsert(emptyList(), times("school", "2026-11-10", 540 to 1440), today))
        assertNull(RouteDayOverrides.upsert(emptyList(), hidden(" ", "2026-11-10"), today))
        assertNull(RouteDayOverrides.upsert(emptyList(), hidden("x".repeat(129), "2026-11-10"), today))
        assertNull(RouteDayOverrides.upsert(emptyList(), times("s", "2026-11-10", *Array(7) { 60 * it to 60 * it + 30 }), today))
        val pruned = RouteDayOverrides.prune(listOf(hidden("a", "2026-11-07"), hidden("a", "2026-11-08"), hidden("a", "2026-11-09")), today)
        assertEquals(listOf("2026-11-08", "2026-11-09"), pruned.map { it.date.toString() })
    }

    @Test fun `expired exception no longer affects later days`() {
        val list = RouteDayOverrides.prune(listOf(hidden("school", "2026-11-02")), LocalDate.parse("2026-11-09"))
        assertTrue(list.isEmpty())
        assertNull(RouteDayOverrides.decision(list, "school", at("2026-11-09T07:30"), schoolMornings))
    }

    @Test fun `encoding round trip and broken data`() {
        val list = listOf(times("school", "2026-11-12", 540 to 600, 22 * 60 to 60), hidden("home", "2026-11-13"))
        assertEquals(list.map { RouteDayOverrides.clean(it) }, RouteDayOverrides.decode(RouteDayOverrides.encode(list)))
        assertTrue(RouteDayOverrides.decode("{kaputt").isEmpty())
        assertTrue(RouteDayOverrides.decode("""[{"id":"a","route":"r","date":"2026-02-30","mode":"HIDDEN"}]""").isEmpty())
        assertTrue(RouteDayOverrides.decode("""[{"id":"a","route":"r","date":"2026-11-12","mode":"TIMES","windows":[[1.5,2]]}]""").isEmpty())
    }

    @Test fun `labels are readable`() {
        val today = LocalDate.parse("2026-11-09")
        assertEquals("Heute", RouteDayOverrides.dateLabel(today, today))
        assertEquals("Morgen", RouteDayOverrides.dateLabel(today.plusDays(1), today))
        assertTrue(RouteDayOverrides.dateLabel(LocalDate.parse("2026-11-12"), today).contains("12. Nov"))
        assertTrue(RouteDayOverrides.dateLabel(LocalDate.parse("2027-01-04"), today).contains("2027"))
        assertEquals("09:00–10:00, 22:00–01:00 (bis Folgetag)",
            RouteDayOverrides.summary(RouteDayOverrides.clean(times("s", "2026-11-12", 22 * 60 to 60, 540 to 600))!!))
        assertNotNull(RouteDayOverrides.summary(hidden("s", "2026-11-12")))
        assertFalse(RouteDayOverrides.summary(hidden("s", "2026-11-12")).contains("-"))
    }

    @Test fun `expired entries do not consume capacity for a new exception`() {
        val today = LocalDate.parse("2026-11-09")
        val expired = (0 until RouteDayOverrides.MAX_OVERRIDES).map { hidden("route-$it", "2026-11-01") }
        val saved = RouteDayOverrides.upsert(expired, hidden("school", "2026-11-09"), today)
        assertEquals(listOf("school"), saved?.map { it.routeId })
    }

    @Test fun `invalid raw windows cannot activate routes and malformed window pairs are rejected`() {
        assertFalse(DayTimeWindow(-1, 60).coversOwnDay(30))
        assertFalse(DayTimeWindow(0, 0).coversOwnDay(-1))
        assertFalse(DayTimeWindow(0, 0).coversOwnDay(1440))
        assertFalse(DayTimeWindow(1320, 60).coversNextDay(-1))
        val invalid = listOf(times("r", "2026-11-09", -1 to 60))
        assertNull(RouteDayOverrides.decision(invalid, "r", at("2026-11-09T00:30")))
        assertTrue(RouteDayOverrides.decode("""[{"id":"a","route":"r","date":"2026-11-12","mode":"TIMES","windows":[[540,600,700]]}]""").isEmpty())
    }

    @Test fun `an exception id cannot erase an unrelated route and duplicate storage rows stay unique`() {
        val first = hidden("out", "2026-11-09").copy(id = "shared")
        val other = hidden("back", "2026-11-10").copy(id = "shared")
        assertNull(RouteDayOverrides.upsert(listOf(first), other, LocalDate.parse("2026-11-09")))
        assertEquals(listOf(first), RouteDayOverrides.prune(listOf(first, other), LocalDate.parse("2026-11-09")))
        assertEquals(listOf(first), RouteDayOverrides.decode(RouteDayOverrides.encode(listOf(first, other, first))))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RouteDayOverridesStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private fun item(route: String, date: String = "2026-11-09") = RouteDayOverride(
        id = route, routeId = route, date = LocalDate.parse(date), mode = DayOverrideMode.HIDDEN)

    @Before fun reset() { app.getSharedPreferences("route_day_overrides", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `stale store instances retain other edits and undo respects a replacement`() {
        val first = RouteDayOverridesStore(app) { now }
        val second = RouteDayOverridesStore(app) { now }
        assertTrue(first.save(item("out")))
        assertTrue(second.save(item("back")))
        assertEquals(setOf("out", "back"), first.current().map { it.routeId }.toSet())
        val removed = first.remove("out")!!
        assertTrue(second.save(item("sport")))
        assertTrue(first.restore(removed))
        assertEquals(setOf("out", "back", "sport"), second.current().map { it.routeId }.toSet())
        first.remove("out")
        assertTrue(second.save(item("out").copy(mode = DayOverrideMode.TIMES, windows = listOf(DayTimeWindow(600, 660)))))
        assertFalse(first.restore(removed))
        assertEquals(DayOverrideMode.TIMES, first.current().first { it.routeId == "out" }.mode)
    }

    @Test fun `running stores preserve yesterday overnight windows then prune after the following day`() {
        var instant = Instant.parse("2026-11-09T18:00:00Z")
        val store = RouteDayOverridesStore(app) { instant }
        assertTrue(store.save(item("night").copy(mode = DayOverrideMode.TIMES, windows = listOf(DayTimeWindow(1320, 120)))))
        instant = Instant.parse("2026-11-10T00:30:00Z")
        assertEquals(true, store.decision("night"))
        assertEquals(1, store.current().size)
        instant = Instant.parse("2026-11-11T08:00:00Z")
        store.prune()
        assertTrue(store.current().isEmpty())
        assertTrue(RouteDayOverridesStore(app) { instant }.current().isEmpty())
    }
}
