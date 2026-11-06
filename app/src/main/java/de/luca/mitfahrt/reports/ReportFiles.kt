package de.luca.mitfahrt.reports

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ReportFiles(context: Context) {
    private val directory = File(context.filesDir, "reports")
    fun list(): List<DelayEvidence> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { runCatching { DelayEvidence.decode(it.readText()).takeIf { e -> file(e.id).isFile } }.getOrNull() }
        .sortedByDescending { it.createdAt }

    fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Ungültiger Bericht." }
        return File(directory, "Mitfahrt-$id.pdf")
    }

    fun create(evidence: DelayEvidence): File {
        check(directory.isDirectory || directory.mkdirs()) { "Bericht konnte nicht gespeichert werden." }
        val target = file(evidence.id)
        val temporary = File(directory, "${evidence.id}.tmp")
        val json = File(directory, "${evidence.id}.json")
        try {
            render(evidence, temporary)
            check(temporary.renameTo(target)) { "Bericht konnte nicht gespeichert werden." }
            json.writeText(evidence.encode())
            return target
        } catch (e: Exception) {
            temporary.delete(); target.delete(); json.delete()
            throw e
        }
    }

    private fun render(e: DelayEvidence, target: File) {
        val document = PdfDocument()
        try {
            var number = 0
            var page: PdfDocument.Page? = null
            var y = 0f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun newPage() {
                page?.let(document::finishPage)
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++number).create())
                y = 52f
            }
            fun text(value: String, size: Float = 11f, bold: Boolean = false, color: Int = Color.rgb(30, 45, 50)) {
                paint.textSize = size
                paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
                paint.color = color
                for (paragraph in value.split('\n')) {
                    var remaining = paragraph
                    do {
                        if (y > 778) newPage()
                        val count = paint.breakText(remaining, true, 491f, null).coerceAtLeast(1).coerceAtMost(remaining.length)
                        val end = if (count < remaining.length) remaining.lastIndexOf(' ', count).takeIf { it > 0 } ?: count else count
                        page!!.canvas.drawText(remaining.take(end), 52f, y, paint)
                        remaining = remaining.drop(end).trimStart()
                        y += size * 1.45f
                    } while (remaining.isNotEmpty())
                }
            }
            fun field(label: String, value: String) {
                text(label, 9f, color = Color.rgb(90, 105, 110))
                text(value, 12f, bold = true); y += 9
            }
            fun timestamp(time: Instant?): String = time?.atZone(ZoneId.of("Europe/Berlin"))
                ?.format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm:ss z")) ?: "Zeitpunkt nicht gespeichert"
            newPage()
            text("MITFAHRT", 10f, bold = true); y += 14
            text("Verspätungsbericht", 25f, bold = true)
            text("NEW Busverkehr · Mönchengladbach", 11f); y += 22
            field("Linie / Richtung", "${e.line} → ${e.destination}")
            field("Haltestelle · ${e.event}", e.stopName)
            field("Geplante ${e.event}", timestamp(e.planned))
            field("Gemeldete ${e.event} des Busses · VRR-Prognose", timestamp(e.estimated))
            if (e.event != "Ankunft") {
                field("Ankunft des Busses an dieser Haltestelle · Fahrplan", e.arrivalPlanned?.let(::timestamp) ?: "Nicht verfügbar")
                field("Ankunft des Busses an dieser Haltestelle · VRR-Prognose", e.arrivalEstimated?.let(::timestamp) ?: "Nicht verfügbar")
                if (e.arrivalEstimated != null) text("Ankunftsprognose abgerufen: ${timestamp(e.arrivalObservedAt)}", 9f)
            }
            field("Abweichung vom Fahrplan", e.delayText)
            if (e.cancelled) { text("Fahrt oder Halt als ausgefallen gemeldet.", bold = true); y += 8 }
            field("Daten abgerufen", timestamp(e.observedAt))
            field("Bericht erstellt", timestamp(e.createdAt))
            if (e.isOld()) {
                text("Gespeicherter Datenstand: Beim Erstellen lag keine aktuelle Abfrage vor.", 10f, bold = true); y += 10
            }
            y += 8
            text("Meine Ankunft an der Haltestelle", 14f, bold = true); y += 5
            if (e.personalArrivals.isEmpty()) text("Keine eigene Ankunft festgehalten.", 10f)
            else e.personalArrivals.forEach { mark -> field(mark.stopName, timestamp(mark.at)) }
            text("Selbst per Tastendruck erfasst, nach Gerätezeit. Keine Bestätigung durch GPS oder den Verkehrsbetreiber.", 9f)
            y += 14
            if (e.notices.isNotEmpty()) {
                text("Verkehrsmeldungen", bold = true)
                e.notices.forEach { text(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString(), 10f) }
                y += 10
            }
            text("Quelle: VRR EFA · openservice.vrr.de", 9f, bold = true)
            text("Fahrttag ${e.serviceDate} · Linienkennung ${e.lineId} · Fahrt ${e.tripCode}", 8f)
            text(e.sourceUrl, 8f); y += 12
            if (e.arrivalSourceUrl != null && e.arrivalSourceUrl != e.sourceUrl) {
                text("Quellabfrage Ankunftsprognose:", 8f, bold = true)
                text(e.arrivalSourceUrl, 8f); y += 8
            }
            text("Privat erstellter Bericht anhand von VRR-Fahrtdaten. Keine offizielle Bescheinigung des Verkehrsunternehmens. Die Prognose ist keine bestätigte tatsächliche Ankunfts- oder Abfahrtszeit.", 9f)
            y += 8
            text("Bericht-ID: ${e.id}", 8f)
            document.finishPage(page!!)
            target.outputStream().use(document::writeTo)
        } finally { document.close() }
    }
}
