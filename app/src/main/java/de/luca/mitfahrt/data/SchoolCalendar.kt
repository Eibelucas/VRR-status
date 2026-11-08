package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SchoolHolidayPeriod(val name: String, val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/**
 * NRW school holidays as published in the official collection of school regulations.
 * Only dates printed in the source are included; nothing outside [coverageStart]..[coverageEnd]
 * is known. Movable holidays ("bewegliche Ferientage") are set by each school and are
 * therefore not included; users add them as their own free days.
 */
object NrwSchoolHolidays {
    const val SOURCE_TITLE = "Ordnung der Ferien und Termine für die Aushändigung der Halbjahreszeugnisse (BASS 12-65 Nr. 1)"
    const val SOURCE_URL = "https://bass.schule.nrw/191.htm"
    /** RdErl. vom 19.05.2022, zuletzt geändert durch RdErl. vom 14.10.2024 (ABl. NRW. 11/24). */
    val SOURCE_AMENDED: LocalDate = LocalDate.of(2024, 10, 14)
    val RETRIEVED: LocalDate = LocalDate.of(2026, 11, 8)
    const val SCOPE = "Alle Schulen in Nordrhein-Westfalen, ohne schulinterne bewegliche Ferientage"

    val periods: List<SchoolHolidayPeriod> = listOf(
        p("Sommerferien", "2026-07-20", "2026-09-01"),
        p("Herbstferien", "2026-10-17", "2026-10-31"),
        p("Weihnachtsferien", "2026-12-23", "2027-01-06"),
        p("Osterferien", "2027-03-22", "2027-04-03"),
        p("Pfingstferien", "2027-05-18", "2027-05-18"),
        p("Sommerferien", "2027-07-19", "2027-08-31"),
        p("Herbstferien", "2027-10-23", "2027-11-06"),
        p("Weihnachtsferien", "2027-12-24", "2028-01-08"),
        p("Osterferien", "2028-04-10", "2028-04-22"),
        p("Sommerferien", "2028-07-10", "2028-08-22"),
        p("Herbstferien", "2028-10-23", "2028-11-04"),
        p("Weihnachtsferien", "2028-12-21", "2029-01-05"),
        p("Osterferien", "2029-03-26", "2029-04-07"),
        p("Pfingstferien", "2029-05-22", "2029-05-22"),
        p("Sommerferien", "2029-07-02", "2029-08-14"),
        p("Herbstferien", "2029-10-15", "2029-10-27"),
        p("Weihnachtsferien", "2029-12-20", "2030-01-04"),
        p("Osterferien", "2030-04-15", "2030-04-27")
    )
    /** First day covered by the source data (start of summer holidays 2026). */
    val coverageStart: LocalDate = LocalDate.of(2026, 7, 20)
    /** The summer holidays 2030 are not yet published; later dates are unknown. */
    val coverageEnd: LocalDate = LocalDate.of(2030, 4, 27)

    private fun p(name: String, start: String, end: String) = SchoolHolidayPeriod(name, LocalDate.parse(start), LocalDate.parse(end))
}

/** Statutory public holidays in North Rhine-Westphalia, computed from the Gregorian Easter date. */
object NrwPublicHolidays {
    fun easterSunday(year: Int): LocalDate {
        // Anonymous Gregorian algorithm (Meeus/Jones/Butcher).
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    fun holidays(year: Int): Map<LocalDate, String> {
        val easter = easterSunday(year)
        return sortedMapOf(
            LocalDate.of(year, 1, 1) to "Neujahr",
            easter.minusDays(2) to "Karfreitag",
            easter.plusDays(1) to "Ostermontag",
            LocalDate.of(year, 5, 1) to "Tag der Arbeit",
            easter.plusDays(39) to "Christi Himmelfahrt",
            easter.plusDays(50) to "Pfingstmontag",
            easter.plusDays(60) to "Fronleichnam",
            LocalDate.of(year, 10, 3) to "Tag der Deutschen Einheit",
            LocalDate.of(year, 11, 1) to "Allerheiligen",
            LocalDate.of(year, 12, 25) to "1. Weihnachtstag",
            LocalDate.of(year, 12, 26) to "2. Weihnachtstag"
        )
    }

    fun name(date: LocalDate): String? = holidays(date.year)[date]
}

enum class SchoolDayReason { SCHOOL_DAY, WEEKEND, PUBLIC_HOLIDAY, SCHOOL_HOLIDAY, CUSTOM_FREE, CUSTOM_SCHOOL_DAY, CALENDAR_OFF }

data class SchoolDayStatus(
    val date: LocalDate,
    val free: Boolean,
    val reason: SchoolDayReason,
    /** Holiday or vacation name, or the user's own label. */
    val label: String? = null,
    /** False when the date lies outside the published holiday data. */
    val holidayDataKnown: Boolean = true
) {
    fun text(): String = when (reason) {
        SchoolDayReason.CALENDAR_OFF -> "Schulkalender aus"
        SchoolDayReason.CUSTOM_SCHOOL_DAY -> "Schultag (eigene Angabe)"
        SchoolDayReason.CUSTOM_FREE -> label?.let { "Frei: $it" } ?: "Frei (eigene Angabe)"
        SchoolDayReason.PUBLIC_HOLIDAY -> "Feiertag: $label"
        SchoolDayReason.SCHOOL_HOLIDAY -> label ?: "Ferien"
        SchoolDayReason.WEEKEND -> "Wochenende"
        SchoolDayReason.SCHOOL_DAY -> if (holidayDataKnown) "Schultag" else "Schultag? Ferientermine fehlen"
    }
}

data class SchoolCalendarSettings(
    val enabled: Boolean = true,
    /** Own free days with an optional short label, e.g. a movable holiday. */
    val freeDays: Map<LocalDate, String> = emptyMap(),
    /** Days explicitly marked as school days, even during holidays. */
    val schoolDays: Set<LocalDate> = emptySet()
)

data class FreePeriod(val start: LocalDate, val end: LocalDate, val label: String)

/**
 * Pure school-day query. Order: calendar off → own school day → own free day →
 * public holiday → school holidays → weekend → school day.
 */
object SchoolCalendar {
    const val MAX_CUSTOM_DAYS = 400
    const val MAX_LABEL = 40

    fun status(
        date: LocalDate, settings: SchoolCalendarSettings,
        periods: List<SchoolHolidayPeriod> = NrwSchoolHolidays.periods,
        coverageStart: LocalDate = NrwSchoolHolidays.coverageStart,
        coverageEnd: LocalDate = NrwSchoolHolidays.coverageEnd
    ): SchoolDayStatus {
        val known = !date.isBefore(coverageStart) && !date.isAfter(coverageEnd)
        if (!settings.enabled) return SchoolDayStatus(date, false, SchoolDayReason.CALENDAR_OFF, holidayDataKnown = known)
        if (date in settings.schoolDays) return SchoolDayStatus(date, false, SchoolDayReason.CUSTOM_SCHOOL_DAY, holidayDataKnown = known)
        settings.freeDays[date]?.let { label ->
            return SchoolDayStatus(date, true, SchoolDayReason.CUSTOM_FREE, label.ifBlank { null }, known)
        }
        NrwPublicHolidays.name(date)?.let { return SchoolDayStatus(date, true, SchoolDayReason.PUBLIC_HOLIDAY, it, known) }
        periods.firstOrNull { date in it }?.let { return SchoolDayStatus(date, true, SchoolDayReason.SCHOOL_HOLIDAY, it.name, known) }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)
            return SchoolDayStatus(date, true, SchoolDayReason.WEEKEND, holidayDataKnown = known)
        return SchoolDayStatus(date, false, SchoolDayReason.SCHOOL_DAY, holidayDataKnown = known)
    }

    /** Consecutive free school-week days with the same reason, ignoring plain weekends. */
    fun freePeriods(from: LocalDate, days: Int, settings: SchoolCalendarSettings): List<FreePeriod> {
        val result = mutableListOf<FreePeriod>()
        var current: FreePeriod? = null
        for (offset in 0 until days.coerceIn(0, 800)) {
            val date = from.plusDays(offset.toLong())
            val status = status(date, settings)
            val label = if (status.free && status.reason != SchoolDayReason.WEEKEND) status.text() else null
            val open = current
            when {
                label != null && open != null && open.label == label && onlyWeekendBetween(open.end, date) ->
                    current = open.copy(end = date)
                label != null -> { open?.let(result::add); current = FreePeriod(date, date, label) }
                status.reason == SchoolDayReason.WEEKEND && open != null -> Unit // weekends do not split a holiday
                else -> { open?.let(result::add); current = null }
            }
        }
        current?.let(result::add)
        return result
    }

    private fun onlyWeekendBetween(end: LocalDate, next: LocalDate): Boolean {
        var day = end.plusDays(1)
        while (day.isBefore(next)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) return false
            day = day.plusDays(1)
        }
        return true
    }

    fun withFreeDay(settings: SchoolCalendarSettings, date: LocalDate, label: String): SchoolCalendarSettings? {
        if (!validDate(date)) return null
        if (date !in settings.freeDays && settings.freeDays.size >= MAX_CUSTOM_DAYS) return null
        return settings.copy(freeDays = settings.freeDays + (date to cleanLabel(label)), schoolDays = settings.schoolDays - date)
    }

    fun withSchoolDay(settings: SchoolCalendarSettings, date: LocalDate): SchoolCalendarSettings? {
        if (!validDate(date)) return null
        if (date !in settings.schoolDays && settings.schoolDays.size >= MAX_CUSTOM_DAYS) return null
        return settings.copy(schoolDays = settings.schoolDays + date, freeDays = settings.freeDays - date)
    }

    private fun validDate(date: LocalDate) = date.year in 2000..2200
    private fun cleanLabel(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    private val shortDate = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)
    private val longDate = DateTimeFormatter.ofPattern("EE, d. MMM yyyy", Locale.GERMAN)
    fun periodLabel(period: FreePeriod): String =
        if (period.start == period.end) longDate.format(period.start)
        else "${shortDate.format(period.start)}–${shortDate.format(period.end)}"
    fun dateLabel(date: LocalDate): String = longDate.format(date)

    fun encode(settings: SchoolCalendarSettings): String = JSONObject().apply {
        put("enabled", settings.enabled)
        put("free", JSONArray(settings.freeDays.entries.sortedBy { it.key }.take(MAX_CUSTOM_DAYS).map { (date, label) ->
            JSONObject().apply { put("date", date.toString()); put("label", cleanLabel(label)) }
        }))
        put("school", JSONArray(settings.schoolDays.sorted().take(MAX_CUSTOM_DAYS).map { it.toString() }))
    }.toString()

    fun decode(value: String): SchoolCalendarSettings = runCatching {
        val obj = JSONObject(value)
        val free = obj.optJSONArray("free")
        val school = obj.optJSONArray("school")
        val freeDays = (0 until (free?.length() ?: 0)).mapNotNull { index ->
            runCatching {
                val item = free!!.getJSONObject(index)
                LocalDate.parse(item.getString("date")).takeIf(::validDate)?.let { it to cleanLabel(item.optString("label", "")) }
            }.getOrNull()
        }.take(MAX_CUSTOM_DAYS).toMap()
        val schoolDays = (0 until (school?.length() ?: 0)).mapNotNull { index ->
            runCatching { LocalDate.parse(school!!.getString(index)).takeIf(::validDate) }.getOrNull()
        }.take(MAX_CUSTOM_DAYS).toSet() - freeDays.keys
        SchoolCalendarSettings(obj.optBoolean("enabled", true), freeDays, schoolDays)
    }.getOrDefault(SchoolCalendarSettings())
}

/** Local-only store; works offline with the bundled holiday data. */
class SchoolCalendarStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("school_calendar", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SchoolCalendar.decode(runCatching { prefs.getString("settings", "{}") }.getOrNull() ?: "{}"))
    val state: StateFlow<SchoolCalendarSettings> = mutable.asStateFlow()

    fun status(date: LocalDate): SchoolDayStatus = SchoolCalendar.status(date, mutable.value)

    @Synchronized fun setEnabled(enabled: Boolean) = persist(mutable.value.copy(enabled = enabled))
    @Synchronized fun addFreeDay(date: LocalDate, label: String = ""): Boolean =
        SchoolCalendar.withFreeDay(mutable.value, date, label)?.also(::persist) != null
    @Synchronized fun removeFreeDay(date: LocalDate) = persist(mutable.value.copy(freeDays = mutable.value.freeDays - date))
    @Synchronized fun markSchoolDay(date: LocalDate): Boolean =
        SchoolCalendar.withSchoolDay(mutable.value, date)?.also(::persist) != null
    @Synchronized fun removeSchoolDay(date: LocalDate) = persist(mutable.value.copy(schoolDays = mutable.value.schoolDays - date))

    private fun persist(settings: SchoolCalendarSettings) {
        prefs.edit().putString("settings", SchoolCalendar.encode(settings)).apply()
        mutable.value = settings
    }
}
