package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class DayOverrideMode { HIDDEN, TIMES }

/**
 * Wall-clock window on one calendar date in Europe/Berlin. A window with
 * start > end runs past midnight and belongs to the date it starts on.
 * start == end covers the whole date. Date-specific windows have no weekdays,
 * so [RouteWindow] is only used for the normal weekly rule.
 */
data class DayTimeWindow(val startMinute: Int, val endMinute: Int) {
    val overnight: Boolean get() = startMinute > endMinute
    val wholeDay: Boolean get() = startMinute == endMinute
    fun valid(): Boolean = startMinute in 0..1439 && endMinute in 0..1439

    /** Covers [minute] of its own start date. */
    fun coversOwnDay(minute: Int): Boolean = when {
        wholeDay -> true
        overnight -> minute >= startMinute
        else -> minute in startMinute until endMinute
    }

    /** Covers [minute] of the following date. */
    fun coversNextDay(minute: Int): Boolean = overnight && minute < endMinute

    fun label(): String = when {
        wholeDay -> "Ganztägig"
        else -> "${RouteDayOverrides.time(startMinute)}–${RouteDayOverrides.time(endMinute)}" +
            if (overnight) " (bis Folgetag)" else ""
    }
}

data class RouteDayOverride(
    val id: String = UUID.randomUUID().toString(),
    /** Each saved route has its own id; outward and return routes are therefore independent. */
    val routeId: String,
    val date: LocalDate,
    val mode: DayOverrideMode,
    val windows: List<DayTimeWindow> = emptyList()
)

/**
 * Pure rules for date-specific route exceptions.
 *
 * Order for route R at instant t (local date D, local minute m, Europe/Berlin):
 * 1. D has an exception HIDDEN → `false`. Nothing from the previous night can show R again.
 * 2. D has an exception TIMES → `true` if one of its windows covers m on D, or an
 *    overnight window of an exception on D-1 reaches m; otherwise `false`. An exception
 *    replaces the normal weekly rule for the whole calendar date, including a normal
 *    overnight carry-over from D-1.
 * 3. D has no exception but D-1 has one → `true` if an overnight window of D-1's exception
 *    reaches m. `false` if only the normal rule's carry-over from D-1 would make R active
 *    (D-1's normal windows were replaced). Otherwise `null`.
 * 4. Otherwise `null`: the caller applies its normal rule.
 *
 * Enabled state, nearby stops, manual display and everything else stay with the caller.
 * Exceptions of past dates (before D-1) never influence later dates.
 */
object RouteDayOverrides {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    const val MAX_OVERRIDES = 400
    const val MAX_WINDOWS = 6
    const val MAX_DAYS_AHEAD = 400L
    private const val MAX_ID = 128

    fun decision(overrides: List<RouteDayOverride>, routeId: String, now: Instant,
                 normalWindows: List<RouteWindow> = emptyList()): Boolean? {
        val local = now.atZone(zone)
        val date = local.toLocalDate()
        val minute = local.hour * 60 + local.minute
        val today = find(overrides, routeId, date)
        if (today?.mode == DayOverrideMode.HIDDEN) return false
        val yesterday = find(overrides, routeId, date.minusDays(1))
        val carried = yesterday?.mode == DayOverrideMode.TIMES && yesterday.windows.any { it.coversNextDay(minute) }
        if (today != null) return today.windows.any { it.coversOwnDay(minute) } || carried
        if (yesterday != null) {
            if (carried) return true
            val ownNormal = normalWindows.any { coversOwnDay(it, date.dayOfWeek, minute) }
            val normalCarry = normalWindows.any { carriesInto(it, date.minusDays(1).dayOfWeek, minute) }
            if (normalCarry && !ownNormal) return false
        }
        return null
    }

    private fun find(overrides: List<RouteDayOverride>, routeId: String, date: LocalDate): RouteDayOverride? =
        overrides.firstOrNull { it.routeId == routeId && it.date == date }

    private fun coversOwnDay(window: RouteWindow, day: DayOfWeek, minute: Int): Boolean {
        if (!window.valid() || day.value !in window.days) return false
        return DayTimeWindow(window.startMinute, window.endMinute).coversOwnDay(minute)
    }

    private fun carriesInto(window: RouteWindow, startDay: DayOfWeek, minute: Int): Boolean =
        window.valid() && startDay.value in window.days &&
            DayTimeWindow(window.startMinute, window.endMinute).coversNextDay(minute)

    /** A storable exception or null. Windows are sorted and duplicates removed. */
    fun clean(override: RouteDayOverride): RouteDayOverride? {
        val id = override.id.trim()
        val routeId = override.routeId.trim()
        if (id.isEmpty() || id.length > MAX_ID || routeId.isEmpty() || routeId.length > MAX_ID) return null
        if (override.date.year !in 2000..2200) return null
        val windows = override.windows.distinct().sortedBy { it.startMinute }
        return when (override.mode) {
            DayOverrideMode.HIDDEN -> override.copy(id = id, routeId = routeId, windows = emptyList())
            DayOverrideMode.TIMES -> {
                if (windows.isEmpty() || windows.size > MAX_WINDOWS || windows.any { !it.valid() }) return null
                if (windows.any { it.wholeDay } && windows.size > 1) return null
                override.copy(id = id, routeId = routeId, windows = windows)
            }
        }
    }

    /** Keeps yesterday (overnight carry-over) and future dates; drops everything older. */
    fun prune(overrides: List<RouteDayOverride>, today: LocalDate): List<RouteDayOverride> =
        overrides.mapNotNull(::clean)
            .filter { !it.date.isBefore(today.minusDays(1)) && !it.date.isAfter(today.plusDays(MAX_DAYS_AHEAD)) }
            .distinctBy { it.routeId to it.date }
            .sortedWith(compareBy<RouteDayOverride> { it.date }.thenBy { it.routeId })
            .take(MAX_OVERRIDES)

    /** One exception per route and date: saving replaces the previous one and keeps its id. */
    fun upsert(overrides: List<RouteDayOverride>, override: RouteDayOverride, today: LocalDate): List<RouteDayOverride>? {
        val clean = clean(override) ?: return null
        if (clean.date.isBefore(today) || clean.date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) return null
        val previous = overrides.firstOrNull { it.routeId == clean.routeId && it.date == clean.date }
        val stored = if (previous != null) clean.copy(id = previous.id) else clean
        // An edited exception that moves to another date leaves its old date.
        val others = overrides.filterNot { it.id == clean.id || it.id == stored.id || (it.routeId == clean.routeId && it.date == clean.date) }
        if (previous == null && others.size >= MAX_OVERRIDES) return null
        return prune(others + stored, today)
    }

    fun upcoming(overrides: List<RouteDayOverride>, routeId: String?, today: LocalDate): List<RouteDayOverride> =
        overrides.filter { (routeId == null || it.routeId == routeId) && !it.date.isBefore(today) }
            .sortedWith(compareBy<RouteDayOverride> { it.date }.thenBy { it.routeId })

    fun time(minute: Int): String = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)

    private val weekdayDate = DateTimeFormatter.ofPattern("EE, d. MMM", Locale.GERMAN)
    private val fullDate = DateTimeFormatter.ofPattern("EE, d. MMM yyyy", Locale.GERMAN)

    fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        today.minusDays(1) -> "Gestern"
        else -> (if (date.year == today.year) weekdayDate else fullDate).format(date)
    }

    fun summary(override: RouteDayOverride): String = when (override.mode) {
        DayOverrideMode.HIDDEN -> "Ausgeblendet"
        DayOverrideMode.TIMES -> override.windows.joinToString(", ") { it.label() }
    }

    fun encode(overrides: List<RouteDayOverride>): String = JSONArray(overrides.mapNotNull(::clean).map { item ->
        JSONObject().apply {
            put("id", item.id); put("route", item.routeId); put("date", item.date.toString())
            put("mode", item.mode.name)
            put("windows", JSONArray(item.windows.map { JSONArray(listOf(it.startMinute, it.endMinute)) }))
        }
    }).toString()

    fun decode(value: String): List<RouteDayOverride> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val windows = item.optJSONArray("windows")
                clean(RouteDayOverride(
                    id = item.getString("id"), routeId = item.getString("route"),
                    date = LocalDate.parse(item.getString("date")),
                    mode = DayOverrideMode.valueOf(item.getString("mode")),
                    windows = (0 until (windows?.length() ?: 0)).map { w ->
                        val pair = windows!!.getJSONArray(w)
                        DayTimeWindow(integer(pair.get(0)), integer(pair.get(1)))
                    }))
            }.getOrNull()
        }.take(MAX_OVERRIDES)
    }.getOrDefault(emptyList())

    private fun integer(value: Any): Int {
        val number = value as? Number ?: error("Kein Zahlenwert")
        require(number.toDouble() == number.toInt().toDouble())
        return number.toInt()
    }
}

/** Private, validated store for date-specific route exceptions. */
class RouteDayOverridesStore(context: Context, private val clock: () -> Instant = Instant::now) {
    private val prefs = context.applicationContext.getSharedPreferences("route_day_overrides", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RouteDayOverrides.prune(
        RouteDayOverrides.decode(runCatching { prefs.getString("overrides", "[]") }.getOrNull() ?: "[]"), today()))
    val state: StateFlow<List<RouteDayOverride>> = mutable.asStateFlow()

    private fun today(): LocalDate = clock().atZone(RouteDayOverrides.zone).toLocalDate()

    /** Saves or replaces the exception for the route and date; returns false if invalid. */
    @Synchronized fun save(override: RouteDayOverride): Boolean {
        val updated = RouteDayOverrides.upsert(mutable.value, override, today()) ?: return false
        persist(updated)
        return true
    }

    @Synchronized fun hideToday(routeId: String): Boolean =
        save(RouteDayOverride(routeId = routeId, date = today(), mode = DayOverrideMode.HIDDEN))

    /** Removes an exception so the normal weekly rule applies again; returns it for undo. */
    @Synchronized fun remove(id: String): RouteDayOverride? {
        val removed = mutable.value.firstOrNull { it.id == id } ?: return null
        persist(mutable.value.filterNot { it.id == id })
        return removed
    }

    /** Undo never replaces an exception saved for the same route and date in the meantime. */
    @Synchronized fun restore(override: RouteDayOverride): Boolean {
        if (mutable.value.any { it.id == override.id || (it.routeId == override.routeId && it.date == override.date) })
            return false
        return save(override)
    }

    /** Removes all exceptions of a route that no longer exists. */
    @Synchronized fun removeRoute(routeId: String) { persist(mutable.value.filterNot { it.routeId == routeId }) }

    fun decision(routeId: String, now: Instant = clock(), normalWindows: List<RouteWindow> = emptyList()): Boolean? =
        RouteDayOverrides.decision(mutable.value, routeId, now, normalWindows)

    @Synchronized fun prune() { persist(mutable.value) }

    private fun persist(values: List<RouteDayOverride>) {
        val clean = RouteDayOverrides.prune(values, today())
        prefs.edit().putString("overrides", RouteDayOverrides.encode(clean)).apply()
        mutable.value = clean
    }
}
