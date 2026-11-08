package de.luca.mitfahrt.data

import java.time.Instant
import java.util.Locale

/** Display filters never change provider timestamps or start tracking. */
object CustomizationPolicy {
    fun tokens(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
    private fun line(value: String) = value.replace(" ", "").uppercase(Locale.ROOT).let { raw ->
        if (raw.all(Char::isDigit)) raw.trimStart('0').ifEmpty { "0" } else raw
    }
    fun stops(stops: List<Stop>, settings: AppSettings, departures: List<Departure> = emptyList()): List<Stop> {
        val hidden = tokens(settings.string("excluded_stops")).map(::stopKey).toSet()
        val preferred = tokens(settings.string("preferred_stops")).map(::stopKey)
        val available = stops.filterNot { stopKey(it.id) in hidden }
        val radius = settings.int("nearby_radius", 500)
        val inside = available.filter { (it.distance ?: Int.MAX_VALUE) <= radius }
        val visible = if (inside.isEmpty() && settings.bool("nearby_expand", true)) available else inside
        return when (settings.string("stop_sort", "distance")) {
            "manual" -> visible.sortedWith(compareBy<Stop> { preferred.indexOf(stopKey(it.id)).takeIf { at -> at >= 0 } ?: Int.MAX_VALUE }.thenBy { it.distance ?: Int.MAX_VALUE })
            "departure" -> visible.sortedWith(compareBy<Stop> { stop -> departures.filter { stopKey(it.stopId) == stopKey(stop.id) }.minOfOrNull { it.effective } ?: Instant.MAX }.thenBy { it.distance ?: Int.MAX_VALUE })
            else -> visible.sortedWith(compareBy<Stop> { if (stopKey(it.id) in preferred) 0 else 1 }.thenBy { it.distance ?: Int.MAX_VALUE })
        }
    }
    fun departures(departures: List<Departure>, settings: AppSettings, now: Instant = Instant.now()): List<Departure> {
        val allowed = tokens(settings.string("line_allow")).map(::line).toSet()
        val blocked = tokens(settings.string("line_block")).map(::line).toSet()
        return departures.filter { departure ->
            val normalized = line(departure.line)
            val rail = Regex("^(S|U|RE|RB|ICE|IC|EC|FLX)\\s*\\d", RegexOption.IGNORE_CASE).containsMatchIn(departure.line)
            (allowed.isEmpty() || normalized in allowed) && normalized !in blocked &&
                (settings.bool("show_cancelled", true) || !departure.cancelled) &&
                (!settings.bool("live_only", false) || departure.liveEstimate(now) != null) &&
                when (settings.string("transport_filter", "all")) { "bus" -> !rail; "rail" -> rail; else -> true }
        }.sortedBy { it.effective }
    }
    fun active(plan: RoutePlan, now: Instant, stops: List<Stop>, settings: AppSettings): Boolean = RoutePlans.active(
        plan, now, stops, settings.bool("routes_school_paused", false), tokens(settings.string("routes_free_dates")).toSet())
    fun refreshMillis(settings: AppSettings, waiting: Boolean = true): Long {
        return when (settings.string("refresh_mode", "balanced")) {
            "frequent" -> 15_000L
            "saving" -> 60_000L
            else -> settings.int(if (waiting) "refresh_waiting" else "refresh_riding", if (waiting) 15 else 30).coerceIn(15, 120) * 1000L
        }
    }
}
