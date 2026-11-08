package de.luca.mitfahrt.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Pure decisions shared by the running journey, tests and optional companion delivery. */
data class RuntimeAlertOptions(
    val leads: List<Int> = listOf(60),
    val target: String = "both",
    val quietEnabled: Boolean = false,
    val quietStart: String = "22:00",
    val quietEnd: String = "07:00",
    val criticalDuringQuiet: Boolean = false
) {
    fun quiet(now: Instant): Boolean {
        if (!quietEnabled) return false
        val start = runCatching { LocalTime.parse(quietStart) }.getOrDefault(LocalTime.of(22, 0))
        val end = runCatching { LocalTime.parse(quietEnd) }.getOrDefault(LocalTime.of(7, 0))
        val time = now.atZone(ZoneId.of("Europe/Berlin")).toLocalTime()
        return when {
            start == end -> true
            start < end -> time >= start && time < end
            else -> time >= start || time < end
        }
    }

    fun allowed(kind: String, now: Instant): Boolean = !quiet(now) ||
        criticalDuringQuiet && kind in setOf("cancelled", "delay")

    fun phone(connectedWatch: Boolean): Boolean = when (target) {
        "watch", "off" -> false
        "auto" -> !connectedWatch
        else -> true
    }

    fun watch(connectedWatch: Boolean): Boolean = when (target) {
        "phone", "off" -> false
        "auto" -> connectedWatch
        else -> true
    }

    companion object {
        fun parseLeads(value: String): List<Int> = value.split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in AlertPreferences.LEAD_OPTIONS }.distinct().sortedDescending().ifEmpty { listOf(60) }

        fun from(settings: AppSettings) = RuntimeAlertOptions(
            parseLeads(settings.string("alert_leads", "60")),
            settings.string("alert_target", "both").let { if (it == "both" && settings.bool("watch_avoid_duplicates", false)) "auto" else it },
            settings.bool("quiet_enabled", false), settings.string("quiet_start", "22:00"),
            settings.string("quiet_end", "07:00"), settings.bool("quiet_critical", false))
    }
}
