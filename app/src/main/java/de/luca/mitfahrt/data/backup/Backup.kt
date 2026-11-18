package de.luca.mitfahrt.data.backup

import de.luca.mitfahrt.data.ArchivedJourney
import de.luca.mitfahrt.data.JourneyArchive
import de.luca.mitfahrt.data.JourneyArchiveState
import de.luca.mitfahrt.reports.DelayEvidence
import de.luca.mitfahrt.reports.JourneyReport
import de.luca.mitfahrt.reports.ReportState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local backup of the journey archive and reports, as a ZIP file the user saves or opens with
 * Android's file picker. Format described in docs/SICHERUNG.md. Nothing is sent anywhere.
 */
object BackupFormat {
    const val FORMAT = "mitfahrt-sicherung"
    const val VERSION = 1
    const val MAX_ENTRIES = 3000
    const val MAX_TOTAL_BYTES = 60L * 1024 * 1024
    const val MAX_FILE_BYTES = 15L * 1024 * 1024
    const val MAX_JSON_BYTES = 4L * 1024 * 1024

    private const val ID = "[0-9a-f-]{36}"
    val MANIFEST = Regex("manifest\\.json")
    val ARCHIVE = Regex("archive\\.json")
    val JOURNEY = Regex("reports/journeys/($ID)\\.json")
    val JOURNEY_EXPORT = Regex("reports/journeys/($ID)/export-([0-9]{1,4})\\.json")
    val JOURNEY_PDF = Regex("reports/journeys/($ID)/([\\p{L}\\p{N} _.-]{1,120}\\.pdf)")
    val LEGACY = Regex("reports/($ID)\\.json")
    val LEGACY_PDF = Regex("reports/Mitfahrt-($ID)\\.pdf")
    private val all = listOf(MANIFEST, ARCHIVE, JOURNEY, JOURNEY_EXPORT, JOURNEY_PDF, LEGACY, LEGACY_PDF)

    /** Only names of this format; anything else (absolute paths, "..", other folders) makes the file invalid. */
    fun allowed(name: String): Boolean = !name.contains("..") && !name.startsWith("/") && !name.contains('\\') &&
        all.any { it.matches(name) } && !name.split('/').any { it == "." || it.isEmpty() }
}

class BackupException(message: String) : Exception(message)

/** Fully checked content of a backup; nothing has been written yet. */
data class BackupContents(
    val createdAt: Instant,
    val archive: List<ArchivedJourney>,
    val reports: List<JourneyReport>,
    /** report id → (file name → bytes) for frozen exports. */
    val exports: Map<String, Map<String, ByteArray>>,
    val legacy: List<Pair<DelayEvidence, ByteArray?>>
)

object BackupWriter {
    /** Writes archive, reports, frozen exports and old single-stop reports into one ZIP. */
    fun write(out: OutputStream, archive: JourneyArchiveState, reportsDir: File, now: Instant): Map<String, Int> {
        val counts = linkedMapOf("rides" to archive.entries.size, "reports" to 0, "exports" to 0, "legacy" to 0)
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                check(BackupFormat.allowed(name)) { name }
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            put("archive.json", JourneyArchive.encode(archive).toByteArray())
            val journeys = File(reportsDir, "journeys")
            journeys.listFiles().orEmpty().filter { it.isFile && BackupFormat.JOURNEY.matches("reports/journeys/${it.name}") }.forEach { json ->
                put("reports/journeys/${json.name}", json.readBytes()); counts["reports"] = counts["reports"]!! + 1
                File(journeys, json.nameWithoutExtension).listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    val name = "reports/journeys/${json.nameWithoutExtension}/${file.name}"
                    if (BackupFormat.allowed(name) && !name.endsWith(".tmp")) {
                        put(name, file.readBytes()); if (file.extension == "pdf") counts["exports"] = counts["exports"]!! + 1
                    }
                }
            }
            reportsDir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                val name = "reports/${file.name}"
                if (BackupFormat.LEGACY.matches(name) || BackupFormat.LEGACY_PDF.matches(name)) {
                    put(name, file.readBytes()); if (file.extension == "json") counts["legacy"] = counts["legacy"]!! + 1
                }
            }
            put("manifest.json", JSONObject().apply {
                put("format", BackupFormat.FORMAT); put("version", BackupFormat.VERSION); put("createdAt", now.toString())
                counts.forEach { (k, v) -> put(k, v) }
            }.toString(2).toByteArray())
        }
        return counts
    }
}

object BackupReader {
    /**
     * Reads and checks the whole file in memory. Any unknown or manipulated path, an oversized
     * entry, a broken JSON or a PDF without PDF header rejects the backup as a whole.
     */
    fun read(input: InputStream, now: Instant): BackupContents {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (!BackupFormat.allowed(name)) throw BackupException("Die Datei enthält unerwartete Einträge.")
                if (name in files) throw BackupException("Die Datei enthält doppelte Einträge.")
                if (files.size >= BackupFormat.MAX_ENTRIES) throw BackupException("Die Sicherung ist zu groß.")
                val limit = if (name.endsWith(".json")) BackupFormat.MAX_JSON_BYTES else BackupFormat.MAX_FILE_BYTES
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buffer); if (n < 0) break
                    if (bytes.size() + n > limit || total + n > BackupFormat.MAX_TOTAL_BYTES) throw BackupException("Die Sicherung ist zu groß.")
                    bytes.write(buffer, 0, n); total += n
                }
                files[name] = bytes.toByteArray()
            }
        }
        val manifest = files["manifest.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
            ?: throw BackupException("Das ist keine Mitfahrt-Sicherung.")
        if (manifest.optString("format") != BackupFormat.FORMAT) throw BackupException("Das ist keine Mitfahrt-Sicherung.")
        if (manifest.optInt("version") !in 1..BackupFormat.VERSION) throw BackupException("Diese Sicherung stammt aus einer neueren App-Version.")
        val created = runCatching { Instant.parse(manifest.getString("createdAt")) }.getOrElse { throw BackupException("Die Sicherung ist beschädigt.") }
        fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)
        val archive = files["archive.json"]?.let { JourneyArchive.decode(text(it), now).entries }.orEmpty()
        val reports = files.filterKeys { BackupFormat.JOURNEY.matches(it) }.map { (name, bytes) ->
            val report = runCatching { JourneyReport.decode(text(bytes)) }.getOrElse { throw BackupException("Ein Bericht in der Sicherung ist beschädigt.") }
            if (BackupFormat.JOURNEY.matchEntire(name)!!.groupValues[1] != report.id) throw BackupException("Ein Bericht passt nicht zu seinem Dateinamen.")
            report
        }
        val exports = linkedMapOf<String, MutableMap<String, ByteArray>>()
        files.forEach { (name, bytes) ->
            BackupFormat.JOURNEY_EXPORT.matchEntire(name)?.let { match ->
                runCatching { JourneyReport.decode(text(bytes)) }.getOrElse { throw BackupException("Ein PDF-Stand in der Sicherung ist beschädigt.") }
                exports.getOrPut(match.groupValues[1]) { linkedMapOf() }[name.substringAfterLast('/')] = bytes
            }
            BackupFormat.JOURNEY_PDF.matchEntire(name)?.let { match ->
                if (!isPdf(bytes)) throw BackupException("Eine PDF in der Sicherung ist beschädigt.")
                exports.getOrPut(match.groupValues[1]) { linkedMapOf() }[match.groupValues[2]] = bytes
            }
        }
        if (exports.keys.any { id -> reports.none { it.id == id } }) throw BackupException("Zu einer PDF fehlt der Bericht.")
        val legacy = files.filterKeys { BackupFormat.LEGACY.matches(it) }.map { (name, bytes) ->
            val evidence = runCatching { DelayEvidence.decode(text(bytes)) }.getOrElse { throw BackupException("Ein älterer Beleg ist beschädigt.") }
            if (BackupFormat.LEGACY.matchEntire(name)!!.groupValues[1] != evidence.id) throw BackupException("Ein Beleg passt nicht zu seinem Dateinamen.")
            val pdf = files["reports/Mitfahrt-${evidence.id}.pdf"]?.also { if (!isPdf(it)) throw BackupException("Eine PDF in der Sicherung ist beschädigt.") }
            evidence to pdf
        }
        return BackupContents(created, archive, reports, exports, legacy)
    }

    private fun isPdf(bytes: ByteArray) = bytes.size > 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
}

data class ImportResult(val rides: Int, val reports: Int, val exports: Int, val legacy: Int, val skipped: Int)

/**
 * Merges a checked backup into the reports folder. Nothing on the device is deleted. A report
 * is replaced only by a newer, finished one; a running report is never touched; existing PDFs
 * are never overwritten. Every file is written to a temporary name first.
 */
object BackupMerge {
    fun reports(contents: BackupContents, reportsDir: File): ImportResult {
        val journeys = File(reportsDir, "journeys").apply { check(isDirectory || mkdirs()) }
        var reports = 0; var exports = 0; var legacy = 0; var skipped = 0
        fun write(target: File, bytes: ByteArray) {
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) { temporary.delete(); error("write failed") }
        }
        contents.reports.forEach { incoming ->
            val file = File(journeys, "${incoming.id}.json")
            val existing = if (file.isFile) runCatching { JourneyReport.decode(file.readText()) }.getOrNull() else null
            val take = existing == null || (existing.state != ReportState.RUNNING && incoming.state != ReportState.RUNNING &&
                incoming.updatedAt.isAfter(existing.updatedAt))
            if (take) {
                // Keep the record of PDFs made on this device as well as those from the backup.
                val merged = if (existing == null) incoming else incoming.copy(exports = (existing.exports + incoming.exports).distinctBy { it.number }.sortedBy { it.number })
                write(file, merged.encode().toByteArray()); reports++
            } else skipped++
        }
        contents.exports.forEach { (id, files) ->
            val folder = File(journeys, id).apply { check(isDirectory || mkdirs()) }
            files.forEach { (name, bytes) -> val target = File(folder, name)
                if (!target.exists()) { write(target, bytes); if (name.endsWith(".pdf")) exports++ } }
        }
        contents.legacy.forEach { (evidence, pdf) ->
            val json = File(reportsDir, "${evidence.id}.json")
            if (json.exists()) { skipped++; return@forEach }
            pdf?.let { bytes -> File(reportsDir, "Mitfahrt-${evidence.id}.pdf").takeIf { !it.exists() }?.let { write(it, bytes) } }
            write(json, evidence.encode().toByteArray()); legacy++
        }
        return ImportResult(0, reports, exports, legacy, skipped)
    }
}
