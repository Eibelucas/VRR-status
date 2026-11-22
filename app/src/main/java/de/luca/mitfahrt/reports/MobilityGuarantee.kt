package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.shortName
import java.time.Instant

/**
 * Evaluates whether a journey report may qualify for the NRW Mobilitätsgarantie
 * (official VRR departure delay >= 20 minutes or official cancellation).
 * Always phrased cautiously ("könnte greifen") and never asserts legal entitlement.
 */
data class MobilityGuaranteeInfo(
    val line: String,
    val stopName: String,
    val plannedTime: String,
    val forecastOrStatus: String,
    val delayMinutes: Int?,
    val cancelled: Boolean,
    val capturedAt: String,
    val tripCode: String,
    val summaryBlock: String,
    val officialUrl: String = OFFICIAL_URL
) {
    val headline: String
        get() = if (cancelled) {
            "Ausfall gemeldet · Mobilitätsgarantie NRW könnte greifen"
        } else {
            "+${delayMinutes ?: 20} Min am Einstieg · Mobilitätsgarantie NRW könnte greifen"
        }

    companion object {
        const val THRESHOLD_MINUTES = 20
        const val OFFICIAL_URL = "https://www.mobil.nrw/fahren/mobigarantie.html"
    }
}

object MobilityGuarantee {
    /**
     * Checks the first transit leg's official VRR data (never relies on user-only observations or stale guesses).
     * Returns null if delay is below 20 minutes and no operator cancellation was reported.
     */
    fun check(report: JourneyReport): MobilityGuaranteeInfo? {
        val firstLeg = report.transit.firstOrNull() ?: return null
        val origin = firstLeg.origin ?: return null
        val cancelled = firstLeg.cancelled || origin.cancelled ||
            report.events.any { it.type == ReportEventType.CANCELLED && it.source == ReportSource.OPERATOR }
        val planned = origin.plannedDeparture ?: origin.plannedArrival
        val forecast = origin.forecastDeparture ?: origin.forecastArrival
        val delayMin = if (planned != null && forecast != null) {
            ((forecast.epochSecond - planned.epochSecond) / 60).toInt()
        } else null

        if (!cancelled && (delayMin == null || delayMin < MobilityGuaranteeInfo.THRESHOLD_MINUTES)) {
            return null
        }

        val plannedStr = JourneyReport.clock(planned) ?: "unbekannt"
        val statusStr = when {
            cancelled -> "Ausfall (laut VRR)"
            forecast != null && delayMin != null -> "${JourneyReport.clock(forecast)} (+$delayMin Min laut VRR)"
            else -> "Verspätung >= 20 Min (laut VRR)"
        }
        val capturedInstant: Instant = origin.forecastFetchedAt ?: report.updatedAt
        val capturedStr = "${JourneyReport.day(report.serviceDay)}, ${JourneyReport.clock(capturedInstant) ?: ""}".trimEnd(',', ' ')
        val stopShort = shortName(origin.name)
        val block = buildString {
            appendLine("Mobilitätsgarantie NRW – Datenzusammenfassung (Mitfahrt)")
            appendLine("Datum: ${JourneyReport.day(report.serviceDay)}")
            appendLine("Linie: ${firstLeg.line}" + if (firstLeg.direction.isNotBlank()) " Richtung ${shortName(firstLeg.direction)}" else "")
            appendLine("Einstieg: $stopShort")
            appendLine("Planmäßige Abfahrt: $plannedStr Uhr")
            appendLine("VRR-Meldung: $statusStr")
            if (firstLeg.key.isNotBlank()) appendLine("Fahrtkennung: ${firstLeg.key}")
            appendLine("Abrufzeitpunkt: $capturedStr Uhr")
            append("Hinweis: Automatisierte Zusammenfassung aus VRR-Live-Daten. Anspruch bitte anhand der offiziellen Bedingungen prüfen (${MobilityGuaranteeInfo.OFFICIAL_URL}).")
        }
        return MobilityGuaranteeInfo(
            line = firstLeg.line,
            stopName = stopShort,
            plannedTime = plannedStr,
            forecastOrStatus = statusStr,
            delayMinutes = delayMin,
            cancelled = cancelled,
            capturedAt = capturedStr,
            tripCode = firstLeg.key,
            summaryBlock = block
        )
    }
}
