package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.shortName
import java.time.Instant

/** Layout-neutral content of a report. The screen and the PDF draw the same blocks. */
sealed interface ReportBlock {
    data class Title(val text: String, val subtitle: String) : ReportBlock
    data class Heading(val text: String) : ReportBlock
    data class Paragraph(val text: String, val strong: Boolean = false) : ReportBlock
    data class Field(val label: String, val value: String) : ReportBlock
    /** One row of the course of events: time, statement and who it comes from. */
    data class Event(val time: String, val text: String, val source: String) : ReportBlock
    data class LegHeader(val text: String, val detail: String) : ReportBlock
    /** Missing times are written as "–"; the caption below the table explains it. */
    data class StopRow(val name: String, val planned: String, val forecast: String, val note: String = "") : ReportBlock
    data class StopHeader(val name: String = "Haltestelle", val planned: String = "Fahrplan", val forecast: String = "VRR-Prognose") : ReportBlock
    data class Small(val text: String) : ReportBlock
}

object JourneyReportText {
    const val MISSING = "nicht erfasst"
    const val UNAVAILABLE = "nicht verfügbar"

    private fun clock(time: Instant?) = JourneyReport.clock(time)

    private fun delta(planned: Instant?, forecast: Instant?): String? {
        if (planned == null || forecast == null) return null
        val seconds = forecast.epochSecond - planned.epochSecond
        val minutes = seconds / 60
        return when {
            seconds in -59..59 -> "pünktlich"
            seconds > 0 -> "+$minutes Min"
            else -> "$minutes Min"
        }
    }

    /** Short sentences for the front of the report and for the list entry. */
    fun summary(report: JourneyReport): List<String> {
        val lines = mutableListOf<String>()
        val transit = report.transit
        val first = transit.firstOrNull()
        val last = transit.lastOrNull()
        val route = buildString {
            append("${JourneyReport.day(report.serviceDay)}: ${shortName(report.originName)} nach ${shortName(report.destinationName)}")
            if (transit.isNotEmpty()) append(" mit " + transit.joinToString(", dann ") { "Linie ${it.line}" + if (it.direction.isNotBlank()) " Richtung ${shortName(it.direction)}" else "" })
            append(".")
        }
        lines += route
        if (transit.size > 1) lines += "Umstieg: " + transit.dropLast(1).joinToString(", ") { shortName(it.destination?.name.orEmpty()) } + "."
        first?.origin?.let { stop ->
            val planned = clock(stop.plannedDeparture)
            val forecast = clock(stop.forecastDeparture ?: stop.forecastArrival)
            lines += when {
                planned == null -> "Abfahrt am Einstieg: Fahrplanzeit $UNAVAILABLE."
                forecast == null -> "Abfahrt am Einstieg laut Fahrplan $planned. Eine VRR-Prognose lag nicht vor."
                else -> "Abfahrt am Einstieg laut Fahrplan $planned, VRR-Prognose $forecast (${delta(stop.plannedDeparture, stop.forecastDeparture ?: stop.forecastArrival)})."
            }
        }
        val cancelled = report.events(ReportEventType.CANCELLED)
        if (cancelled.isNotEmpty()) lines += cancelled.joinToString(" ") { it.text + " (VRR-Angabe)." }
        val boarded = report.events(ReportEventType.BOARDED)
        when {
            report.fromArchive -> lines += "Einstieg und Ausstieg wurden für diese Fahrt nicht erfasst."
            boarded.isNotEmpty() -> boarded.forEach { lines += "${it.text} um ${clock(it.at)} (${it.source.label})." }
            report.state == ReportState.ABORTED -> {
                val from = clock(report.waitFrom); val until = clock(report.waitUntil)
                lines += if (from != null && until != null) "Gewartet von $from bis $until. Ein Einstieg wurde nicht erfasst."
                    else "Ein Einstieg wurde nicht erfasst."
                if (report.events.any { it.id.startsWith("nobus/") }) lines += "Die App hat in dieser Zeit kein passendes Fahrzeug erkannt. Das ist keine Aussage des Betreibers über einen Ausfall."
            }
            else -> lines += "Einstieg: $MISSING."
        }
        if (report.state != ReportState.ABORTED) last?.destination?.let { stop ->
            val planned = clock(stop.plannedArrival ?: stop.plannedDeparture)
            val forecast = clock(stop.forecastArrival ?: stop.forecastDeparture)
            lines += when {
                planned == null -> "Ankunft am Ziel: Fahrplanzeit $UNAVAILABLE."
                forecast == null -> "Ankunft am Ziel laut Fahrplan $planned. Eine VRR-Prognose lag nicht vor."
                else -> "Ankunft am Ziel laut Fahrplan $planned, VRR-Prognose $forecast (${delta(stop.plannedArrival ?: stop.plannedDeparture, stop.forecastArrival ?: stop.forecastDeparture)})."
            }
        }
        report.events(ReportEventType.TRANSFER_RISK).lastOrNull()?.let { lines += it.text + " (${it.source.label})." }
        val own = report.events(ReportEventType.OBSERVATION)
        if (own.size <= 2) own.forEach { lines += "Eigene Angabe: ${it.text}" }
        else lines += "${own.size} eigene Angaben, siehe Verlauf."
        return lines
    }

    /** One line for lists: the largest reported deviation or the missing data. */
    fun headline(report: JourneyReport): String {
        if (report.events.any { it.type == ReportEventType.CANCELLED }) return "Ausfall gemeldet"
        if (report.state == ReportState.ABORTED) return "Gewartet, kein Einstieg erfasst"
        val worst = report.transit.flatMap { leg -> leg.stops.mapNotNull { stop ->
            val planned = stop.plannedArrival ?: stop.plannedDeparture
            val forecast = stop.forecastArrival ?: stop.forecastDeparture
            if (planned == null || forecast == null) null else forecast.epochSecond - planned.epochSecond
        } }.maxOrNull()
        return when {
            worst == null -> "Nur Fahrplandaten, keine Prognose"
            worst >= 60 -> "Bis +${worst / 60} Min laut VRR-Prognose"
            else -> "Pünktlich laut VRR-Prognose"
        }
    }

    fun build(report: JourneyReport, options: ReportCustomization, generatedAt: Instant): List<ReportBlock> {
        val blocks = mutableListOf<ReportBlock>()
        val preliminary = report.state == ReportState.RUNNING
        blocks += ReportBlock.Title("Fahrtbericht", report.title)
        if (preliminary) blocks += ReportBlock.Paragraph("Vorläufiger Stand von ${clock(generatedAt)} Uhr. Die Fahrt läuft noch; spätere Angaben fehlen.", strong = true)
        if (options.name.isNotBlank()) blocks += ReportBlock.Field("Name", options.name)
        if (options.schoolClass.isNotBlank()) blocks += ReportBlock.Field("Klasse", options.schoolClass)

        blocks += ReportBlock.Heading("Zusammenfassung")
        summary(report).forEach { blocks += ReportBlock.Paragraph(it) }
        if (options.note.isNotBlank()) blocks += ReportBlock.Field("Eigene Notiz", options.note)

        blocks += ReportBlock.Heading("Verlauf")
        val shown = report.events.filter { it.type != ReportEventType.NOTICE }.sortedBy { it.at }
        if (shown.isEmpty()) blocks += ReportBlock.Paragraph("Für diese Fahrt wurden keine Ereignisse erfasst.")
        shown.forEach { blocks += ReportBlock.Event(clock(it.at).orEmpty(), it.text, it.source.label) }
        val expected = listOf(ReportEventType.ARRIVED_AT_STOP to "Ankunft an der Einstiegshaltestelle",
            ReportEventType.BOARDED to "Einstieg", ReportEventType.EXITED to "Ausstieg")
        val missing = expected.filter { (type, _) -> report.events.none { it.type == type } }
            .filterNot { (type, _) -> report.state == ReportState.ABORTED && type != ReportEventType.ARRIVED_AT_STOP }
        if (missing.isNotEmpty()) blocks += ReportBlock.Small(missing.joinToString(", ") { it.second } + ": $MISSING.")

        blocks += ReportBlock.Heading("Verbindung")
        report.legs.forEach { leg ->
            if (leg.walk) {
                val minutes = (leg.walkSeconds + 59) / 60
                blocks += ReportBlock.LegHeader("Fußweg" + if (minutes > 0) " · etwa $minutes Min" else "",
                    listOfNotNull(leg.origin?.name?.let(::shortName), leg.destination?.name?.let(::shortName)).distinct().joinToString(" → "))
                return@forEach
            }
            blocks += ReportBlock.LegHeader("Linie ${leg.line}" + if (leg.direction.isNotBlank()) " Richtung ${shortName(leg.direction)}" else "",
                listOfNotNull(leg.operator.takeIf { it.isNotBlank() && it != "?" },
                    if (leg.tracked) "begleitet" else "nicht begleitet, Daten aus der Verbindungssuche",
                    "ausgefallen gemeldet".takeIf { leg.cancelled }).joinToString(" · "))
            blocks += ReportBlock.StopHeader()
            val all = if (options.compact && leg.stops.size > 2) listOf(leg.stops.first(), leg.stops.last()) else leg.stops
            all.forEachIndexed { index, stop ->
                val first = index == 0
                val last = index == all.lastIndex
                val planned = if (first) clock(stop.plannedDeparture ?: stop.plannedArrival) else clock(stop.plannedArrival ?: stop.plannedDeparture)
                val forecast = if (first) clock(stop.forecastDeparture ?: stop.forecastArrival) else clock(stop.forecastArrival ?: stop.forecastDeparture)
                blocks += ReportBlock.StopRow(shortName(stop.name) + if (stop.platform.isNotBlank() && first) " · ${stop.platform}" else "",
                    planned ?: "–", forecast ?: "–",
                    listOfNotNull("Einstieg".takeIf { first }, "Ausstieg".takeIf { last && !first }, "Halt entfällt".takeIf { stop.cancelled }).joinToString(", "))
            }
            if (options.compact && leg.stops.size > 2) blocks += ReportBlock.Small("${leg.stops.size - 2} Zwischenhalte ausgelassen (kompakter Bericht).")
            val fetched = leg.stops.mapNotNull { it.forecastFetchedAt }.maxOrNull()
            blocks += ReportBlock.Small(if (fetched != null) "Prognosen zuletzt abgerufen ${JourneyReport.day(fetched)} ${JourneyReport.clockSeconds(fetched)}. „–“ bedeutet: vom VRR nicht geliefert."
                else "Für diese Fahrt lag keine VRR-Prognose vor, nur der Fahrplan.")
            leg.notices.forEach { blocks += ReportBlock.Small("Meldung: $it") }
        }

        if (options.includes("sources")) {
            blocks += ReportBlock.Heading("Datenquellen")
            blocks += ReportBlock.Small("Fahrplan, Prognosen, Ausfälle und Meldungen: VRR (openservice.vrr.de).")
            report.transit.filter { it.lineId.isNotBlank() }.forEach { leg ->
                blocks += ReportBlock.Small("Linie ${leg.line}: Kennung ${leg.lineId}, Fahrt ${leg.tripCode.ifBlank { UNAVAILABLE }}, zuletzt abgerufen ${leg.fetchedAt?.let { JourneyReport.day(it) + " " + JourneyReport.clockSeconds(it) } ?: UNAVAILABLE}.")
            }
            if (report.events.any { it.type == ReportEventType.VEHICLE_ASSIGNED })
                blocks += ReportBlock.Small("Fahrzeugpositionen: NEW (livebus.new.de). Die Zuordnung zum Fahrzeug ist eine Erkennung der App.")
            if (report.events.any { it.source == ReportSource.GPS })
                blocks += ReportBlock.Small("GPS-Erkennung: Standort dieses Geräts, nach Gerätezeit. Es werden nur die Ereignisse gespeichert, kein Bewegungsverlauf.")
        }
        blocks += ReportBlock.Small("Privat mit der App Mitfahrt erstellt, keine offizielle Bescheinigung. Eine Prognose ist keine bestätigte Ankunfts- oder Abfahrtszeit.")
        blocks += ReportBlock.Small("Bericht ${report.id.take(8)} · Stand ${JourneyReport.day(generatedAt)} ${JourneyReport.clockSeconds(generatedAt)}")
        return blocks
    }
}
