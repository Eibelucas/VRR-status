package de.luca.mitfahrt.reports

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import de.luca.mitfahrt.data.AppSettingsStore

class ReportFiles(private val context: Context) {
    private val directory = File(context.filesDir, "reports")
    private val settings = AppSettingsStore(context)
    fun list(): List<DelayEvidence> {
        prune()
        return directory.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { runCatching { DelayEvidence.decode(it.readText()).takeIf { e -> file(e.id).isFile } }.getOrNull() }
        .sortedByDescending { it.createdAt }
    }

    fun prune(now: Instant = Instant.now()) {
        val days = settings.current().int("report_retention_days", 0).coerceIn(0, 3650)
        if (days == 0) return
        directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { source ->
            runCatching { DelayEvidence.decode(source.readText()) }.getOrNull()?.let { evidence ->
                if (evidence.createdAt < now.minusSeconds(days * 86400L)) runCatching { delete(evidence.id) }
            }
        }
    }

    /** Separate user-facing copy; existing frozen reports keep their original contents. */
    fun exportFile(evidence: DelayEvidence): File {
        val source = file(evidence.id)
        require(source.isFile) { "Der Bericht ist nicht mehr gespeichert." }
        val folder = File(File(directory, "exports"), evidence.id).apply { check(isDirectory || mkdirs()) }
        val target = File(folder, ReportCustomization.from(settings.current()).exportName(evidence))
        source.copyTo(target, overwrite = true)
        folder.listFiles().orEmpty().filter { it != target && it.lastModified() < System.currentTimeMillis() - 86400_000L }.forEach(File::delete)
        return target
    }

    fun exportTo(evidence: DelayEvidence, uri: android.net.Uri) {
        val source = file(evidence.id)
        require(source.isFile) { "Der Bericht ist nicht mehr gespeichert." }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Der Speicherort ist nicht verfügbar.")
        output.use { target -> source.inputStream().use { it.copyTo(target) } }
    }

    fun clear(): Boolean = directory.listFiles().orEmpty().map { it.deleteRecursively() }.all { it }

    fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Ungültiger Bericht." }
        return File(directory, "Mitfahrt-$id.pdf")
    }

    /** Missing files are already deleted. On failure, still try the other file and return false. */
    fun delete(id: String): Boolean {
        val targets = listOf(file(id), File(directory, "$id.json"))
        var success = true
        for (target in targets) {
            try {
                Files.deleteIfExists(target.toPath())
            } catch (_: IOException) {
                success = false
            } catch (_: SecurityException) {
                success = false
            }
        }
        val exported = File(File(directory, "exports"), id)
        if (exported.exists() && !exported.deleteRecursively()) success = false
        return success
    }

    fun create(evidence: DelayEvidence): File {
        check(directory.isDirectory || directory.mkdirs()) { "Bericht konnte nicht gespeichert werden." }
        val target = file(evidence.id)
        val temporary = File(directory, "${evidence.id}.tmp")
        val json = File(directory, "${evidence.id}.json")
        try {
            render(evidence, temporary, ReportCustomization.from(settings.current()))
            check(temporary.renameTo(target)) { "Bericht konnte nicht gespeichert werden." }
            json.writeText(evidence.encode())
            return target
        } catch (e: Exception) {
            temporary.delete(); target.delete(); json.delete()
            throw e
        }
    }

    private fun render(e: DelayEvidence, target: File, options: ReportCustomization) {
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
                        y += size * if (options.compact) 1.25f else 1.45f
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
            if (options.name.isNotBlank()) field("Name", options.name)
            if (options.schoolClass.isNotBlank()) field("Klasse", options.schoolClass)
            if (options.includes("line")) field("Linie / Richtung", "${e.line} → ${e.destination}")
            if (options.includes("route")) field("Haltestelle · ${e.event}", e.stopName)
            if (options.includes("operator_times")) {
                field("Geplante ${e.event}", timestamp(e.planned))
                field("Gemeldete ${e.event} des Busses · VRR-Prognose", timestamp(e.estimated))
            }
            if (options.includes("operator_times") && !options.compact && e.event != "Ankunft") {
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
            if (options.includes("own_arrival")) {
                text("Meine Ankunft an der Haltestelle", 14f, bold = true); y += 5
                if (e.personalArrivals.isEmpty()) text("Keine eigene Ankunft festgehalten.", 10f)
                else e.personalArrivals.forEach { mark ->
                    field(mark.stopName, timestamp(mark.at))
                    text(if (mark.method == "location") "Anhand des Gerätestandorts festgehalten; Näherung nach Gerätezeit."
                        else "Selbst per Tastendruck festgehalten, nach Gerätezeit.", 9f)
                }
                text("Eigene Erfassung, keine Ankunftsbestätigung durch den Verkehrsbetreiber.", 9f)
                y += 14
            }
            if (options.note.isNotBlank()) { field("Eigene Notiz", options.note) }
            if (!options.compact && e.notices.isNotEmpty()) {
                text("Verkehrsmeldungen", bold = true)
                e.notices.forEach { text(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString(), 10f) }
                y += 10
            }
            text("Quelle: VRR EFA · openservice.vrr.de", 9f, bold = true)
            if (options.includes("date")) text("Fahrttag ${e.serviceDate}", 8f)
            if (options.includes("sources")) {
                text("Linienkennung ${e.lineId} · Fahrt ${e.tripCode}", 8f)
                text(e.sourceUrl, 8f); y += 12
            }
            if (options.includes("sources") && e.arrivalSourceUrl != null && e.arrivalSourceUrl != e.sourceUrl) {
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
