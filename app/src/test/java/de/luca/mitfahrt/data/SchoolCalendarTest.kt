package de.luca.mitfahrt.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

class SchoolCalendarTest {
    private fun d(text: String) = LocalDate.parse(text)
    private val on = SchoolCalendarSettings()

    @Test fun `easter dates match known years`() {
        assertEquals(d("2024-03-31"), NrwPublicHolidays.easterSunday(2024))
        assertEquals(d("2025-04-20"), NrwPublicHolidays.easterSunday(2025))
        assertEquals(d("2026-04-05"), NrwPublicHolidays.easterSunday(2026))
        assertEquals(d("2027-03-28"), NrwPublicHolidays.easterSunday(2027))
        assertEquals(d("2038-04-25"), NrwPublicHolidays.easterSunday(2038))
    }

    @Test fun `known NRW public holidays 2026`() {
        val h = NrwPublicHolidays.holidays(2026)
        assertEquals(11, h.size)
        assertEquals("Karfreitag", h[d("2026-04-03")])
        assertEquals("Ostermontag", h[d("2026-04-06")])
        assertEquals("Christi Himmelfahrt", h[d("2026-05-14")])
        assertEquals("Pfingstmontag", h[d("2026-05-25")])
        assertEquals("Fronleichnam", h[d("2026-06-04")])
        assertEquals("Allerheiligen", h[d("2026-11-01")])
        // Not statutory in NRW.
        assertNull(h[d("2026-10-31")])
        assertNull(h[d("2026-01-06")])
    }

    @Test fun `public holiday is free with reason`() {
        val status = SchoolCalendar.status(d("2026-12-25"), on)
        assertTrue(status.free)
        assertEquals(SchoolDayReason.PUBLIC_HOLIDAY, status.reason)
        assertEquals("Feiertag: 1. Weihnachtstag", status.text())
    }

    @Test fun `vacation across the year change`() {
        assertEquals("Weihnachtsferien", SchoolCalendar.status(d("2026-12-23"), on).label)
        assertEquals(SchoolDayReason.PUBLIC_HOLIDAY, SchoolCalendar.status(d("2027-01-01"), on).reason)
        assertEquals("Weihnachtsferien", SchoolCalendar.status(d("2027-01-06"), on).label)
        assertEquals(SchoolDayReason.SCHOOL_DAY, SchoolCalendar.status(d("2027-01-07"), on).reason)
    }

    @Test fun `normal weekday and weekend`() {
        assertEquals(SchoolDayReason.SCHOOL_DAY, SchoolCalendar.status(d("2026-11-09"), on).reason)
        assertFalse(SchoolCalendar.status(d("2026-11-09"), on).free)
        assertEquals(SchoolDayReason.WEEKEND, SchoolCalendar.status(d("2026-11-14"), on).reason)
    }

    @Test fun `own free day and explicit school day override`() {
        val custom = SchoolCalendar.withFreeDay(on, d("2027-02-08"), "  Rosenmontag  ")!!
        val rosenmontag = SchoolCalendar.status(d("2027-02-08"), custom)
        assertEquals(SchoolDayReason.CUSTOM_FREE, rosenmontag.reason)
        assertEquals("Frei: Rosenmontag", rosenmontag.text())
        val school = SchoolCalendar.withSchoolDay(custom, d("2026-10-20"))!!
        val inHoliday = SchoolCalendar.status(d("2026-10-20"), school)
        assertFalse(inHoliday.free)
        assertEquals(SchoolDayReason.CUSTOM_SCHOOL_DAY, inHoliday.reason)
        // Marking a free day as school day removes the free day and vice versa.
        val swapped = SchoolCalendar.withSchoolDay(custom, d("2027-02-08"))!!
        assertTrue(d("2027-02-08") !in swapped.freeDays)
    }

    @Test fun `calendar can be switched off`() {
        val status = SchoolCalendar.status(d("2026-12-25"), on.copy(enabled = false))
        assertFalse(status.free)
        assertEquals(SchoolDayReason.CALENDAR_OFF, status.reason)
    }

    @Test fun `missing vacation data is reported honestly`() {
        val status = SchoolCalendar.status(d("2030-07-15"), on)
        assertFalse(status.holidayDataKnown)
        assertEquals(SchoolDayReason.SCHOOL_DAY, status.reason)
        assertTrue(status.text().contains("Ferientermine fehlen"))
        // Public holidays are still computed outside the vacation data.
        assertEquals(SchoolDayReason.PUBLIC_HOLIDAY, SchoolCalendar.status(d("2031-10-03"), on).reason)
        assertTrue(SchoolCalendar.status(d("2026-11-09"), on).holidayDataKnown)
    }

    @Test fun `holiday data is consistent`() {
        val periods = NrwSchoolHolidays.periods
        assertTrue(periods.all { !it.end.isBefore(it.start) })
        assertTrue(periods.zipWithNext().all { (a, b) -> a.end.isBefore(b.start) })
        assertEquals(NrwSchoolHolidays.coverageStart, periods.first().start)
        assertEquals(NrwSchoolHolidays.coverageEnd, periods.last().end)
    }

    @Test fun `free periods group consecutive days`() {
        val periods = SchoolCalendar.freePeriods(d("2026-12-20"), 25, on)
        assertEquals("Weihnachtsferien", periods.first().label)
        assertEquals(d("2026-12-23"), periods.first().start)
        assertEquals(d("2026-12-24"), periods.first().end)
        assertTrue(periods.any { it.label == "Feiertag: 1. Weihnachtstag" })
        assertEquals(d("2027-01-06"), periods.last().end)
        val bridge = SchoolCalendar.withFreeDay(SchoolCalendar.withFreeDay(on, d("2026-11-13"), "Brückentag")!!, d("2026-11-16"), "Brückentag")!!
        val grouped = SchoolCalendar.freePeriods(d("2026-11-12"), 6, bridge)
        assertEquals(1, grouped.size)
        assertEquals("13.11.–16.11.", SchoolCalendar.periodLabel(grouped.single()))
    }

    @Test fun `encoding validates data and limits`() {
        val settings = SchoolCalendarSettings(false, mapOf(d("2027-02-08") to "Rosenmontag"), setOf(d("2026-10-20")))
        assertEquals(settings, SchoolCalendar.decode(SchoolCalendar.encode(settings)))
        assertEquals(SchoolCalendarSettings(), SchoolCalendar.decode("nicht json"))
        val broken = SchoolCalendar.decode("""{"free":[{"date":"2027-02-30","label":"x"},{"date":"2027-02-09","label":"${"y".repeat(80)}"}],"school":["kaputt"]}""")
        assertEquals(setOf(d("2027-02-09")), broken.freeDays.keys)
        assertEquals(40, broken.freeDays.values.single().length)
        assertTrue(broken.schoolDays.isEmpty())
        var many = on
        repeat(SchoolCalendar.MAX_CUSTOM_DAYS) { many = SchoolCalendar.withFreeDay(many, d("2027-01-01").plusDays(it.toLong()), "")!! }
        assertNull(SchoolCalendar.withFreeDay(many, d("2029-01-01"), ""))
    }

    @Test fun `published no-Pentecost-vacation years remain normal school days on Tuesday`() {
        // BASS 12-65 Nr. 1 explicitly lists no Pentecost school holidays in 2028 and 2030.
        assertEquals(SchoolDayReason.PUBLIC_HOLIDAY, SchoolCalendar.status(d("2028-06-05"), on).reason)
        assertEquals(SchoolDayReason.SCHOOL_DAY, SchoolCalendar.status(d("2028-06-06"), on).reason)
        assertEquals(SchoolDayReason.PUBLIC_HOLIDAY, SchoolCalendar.status(d("2030-06-10"), on).reason)
        assertEquals(SchoolDayReason.SCHOOL_DAY, SchoolCalendar.status(d("2030-06-11"), on).reason)
    }

    @Test fun `explicit school-day precedence survives imported conflicting entries`() {
        val settings = SchoolCalendar.decode("""{"free":[{"date":"2026-12-25","label":"Frei"}],"school":["2026-12-25"]}""")
        assertFalse(d("2026-12-25") in settings.freeDays)
        assertEquals(SchoolDayReason.CUSTOM_SCHOOL_DAY, SchoolCalendar.status(d("2026-12-25"), settings).reason)
        assertEquals(settings, SchoolCalendar.decode(SchoolCalendar.encode(settings)))
    }

    @Test fun `dates outside the supported public-holiday algorithm do not crash the calendar query`() {
        assertNull(NrwPublicHolidays.name(d("0001-01-01")))
        assertFalse(SchoolCalendar.status(d("0001-01-01"), on).holidayDataKnown)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SchoolCalendarStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { app.getSharedPreferences("school_calendar", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `stale instances retain enabled choice free days and explicit school days`() {
        val first = SchoolCalendarStore(app)
        val second = SchoolCalendarStore(app)
        val free = LocalDate.parse("2027-02-08")
        val school = LocalDate.parse("2026-12-25")
        first.setEnabled(false)
        assertTrue(second.addFreeDay(free, "Rosenmontag"))
        assertTrue(first.markSchoolDay(school))
        val saved = SchoolCalendarStore(app).current()
        assertFalse(saved.enabled)
        assertEquals(mapOf(free to "Rosenmontag"), saved.freeDays)
        assertEquals(setOf(school), saved.schoolDays)
        second.setEnabled(true)
        assertEquals(SchoolDayReason.CUSTOM_FREE, first.status(free).reason)
        first.removeFreeDay(free)
        second.removeSchoolDay(school)
        assertEquals(SchoolCalendarSettings(), SchoolCalendarStore(app).current())
    }
}
