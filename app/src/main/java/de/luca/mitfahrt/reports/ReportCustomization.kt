package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.AppSettings
import de.luca.mitfahrt.data.Ride
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReportCustomization(
    val name: String = "", val schoolClass: String = "", val note: String = "",
    val compact: Boolean = false,
    val fields: Set<String> = setOf("line", "route", "date", "operator_times", "own_arrival", "sources"),
    val filename: String = "Mitfahrt"
) {
    fun includes(field: String) = field in fields
    fun exportName(evidence: DelayEvidence): String {
        val prefix = filename.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
            .trim(' ', '.').take(60).ifBlank { "Mitfahrt" }
        val day = evidence.createdAt.atZone(ZoneId.of("Europe/Berlin"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
        return "$prefix-$day-${evidence.id.take(8)}.pdf"
    }
    fun exportName(report: JourneyReport, number: Int): String {
        val prefix = filename.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "_")
            .trim(' ', '.').take(60).ifBlank { "Mitfahrt" }
        val day = report.serviceDay.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        return "$prefix-Fahrtbericht-$day-${report.id.take(8)}-$number.pdf"
    }
    companion object {
        fun from(settings: AppSettings) = ReportCustomization(
            settings.string("report_name").trim().take(100).takeIf { settings.bool("report_personal", false) }.orEmpty(),
            settings.string("report_class").trim().take(60).takeIf { settings.bool("report_personal", false) }.orEmpty(),
            settings.string("report_note").trim().take(1000), settings.string("report_style", "full") == "compact",
            settings.string("report_fields", "line,route,date,operator_times,own_arrival,sources")
                .split(',').map(String::trim).filter(String::isNotBlank).toSet(),
            settings.string("report_filename", "Mitfahrt"))
    }
}

/** Offers only current original provider estimates; local GPS and timetables are never delay evidence. */
object ReportOfferPolicy {
    fun candidate(ride: Ride, settings: AppSettings, now: Instant): Int? {
        if (!settings.bool("report_offer", true) || ride.demo) return null
        val threshold = settings.int("report_offer_threshold", 5).coerceIn(1, 120) * 60L
        return (ride.boardingIndex..ride.stops.lastIndex).mapNotNull { index ->
            val timing = DelayEvidence.timingFor(ride, index) ?: return@mapNotNull null
            val observed = timing.observedAt ?: return@mapNotNull null
            if (now.epochSecond - observed.epochSecond !in -15..120 || timing.delaySeconds < threshold) null
            else index to timing.delaySeconds
        }.maxByOrNull { it.second }?.first
    }
}
