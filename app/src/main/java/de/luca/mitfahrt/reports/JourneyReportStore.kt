package de.luca.mitfahrt.reports

import android.content.Context
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.ArrivalMark
import de.luca.mitfahrt.data.Ride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Journey reports on the device. The JSON file is the living record; every export writes a new
 * PDF plus the exact data it was made from, and neither is touched again afterwards.
 */
class JourneyReportStore(private val directory: File, private val options: () -> ReportCustomization,
    private val retentionDays: () -> Int = { 0 }, private val clock: () -> Instant = Instant::now,
    private val writer: (List<ReportBlock>, File) -> Int = JourneyReportPdf::render) {
    constructor(context: Context) : this(context, AppSettingsStore(context))
    private constructor(context: Context, settings: AppSettingsStore) : this(File(File(context.filesDir, "reports"), "journeys"),
        { ReportCustomization.from(settings.current()) }, { settings.current().int("report_retention_days", 0) })

    private val mutable = MutableStateFlow(read())
    val state: StateFlow<List<JourneyReport>> = mutable.asStateFlow()
    /** Last ride seen per report; needed to word the end of a ride. In memory only. */
    private var lastRide: Pair<String, Ride>? = null
    private var pendingEnd: RideEnd? = null

    private fun read(): List<JourneyReport> = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
        .mapNotNull { runCatching { JourneyReport.decode(it.readText()) }.getOrNull() }
        .sortedByDescending { it.serviceDay }

    @Synchronized fun reload() { mutable.value = read() }

    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}"))) { "Ungültiger Bericht." }
        return File(directory, "$id.json")
    }
    private fun exportFolder(id: String) = File(directory, id.also { file(it) })

    private fun save(report: JourneyReport): Boolean = runCatching {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "${report.id}.tmp")
        temporary.writeText(report.encode())
        check(temporary.renameTo(file(report.id)) || run { file(report.id).delete(); temporary.renameTo(file(report.id)) })
        mutable.value = (mutable.value.filterNot { it.id == report.id } + report).sortedByDescending { it.serviceDay }
    }.isSuccess

    /** The service names the reason right before it clears the ride. */
    @Synchronized fun noteEnd(end: RideEnd) { pendingEnd = end }

    /**
     * Brings the running report in line with the accompaniment. With [auto] off a report only
     * exists after [ensure]. Called on every state change; writes only when content changed.
     */
    @Synchronized fun sync(snapshot: ReportSnapshot, auto: Boolean): JourneyReport? {
        val now = clock()
        val ride = snapshot.ride?.takeUnless { it.demo }
        val current = ride?.let { JourneyReportRecorder.sessionKey(it, snapshot.companion) }
            ?: snapshot.companion?.let(JourneyReportRecorder::journeyKey)
        mutable.value.filter { it.state == ReportState.RUNNING && it.sessionKey != current }.forEach { report ->
            save(JourneyReportRecorder.finish(report, lastRide?.takeIf { it.first == report.id }?.second,
                pendingEnd ?: RideEnd.INTERRUPTED, now, moreLegs = false))
        }
        val existing = mutable.value.firstOrNull { it.sessionKey == current && it.state == ReportState.RUNNING }
        if (ride == null) {
            // Between two vehicles of one journey: note the end of the last ride, keep the report open.
            if (existing != null) lastRide?.takeIf { it.first == existing.id }?.let { (_, last) ->
                val noted = JourneyReportRecorder.finish(existing, last, pendingEnd ?: RideEnd.INTERRUPTED, now, moreLegs = last.boarded && snapshot.companion?.nextIndex != null)
                if (noted != existing) save(noted)
                lastRide = null
            }
            pendingEnd = null
            return mutable.value.firstOrNull { it.id == existing?.id }
        }
        pendingEnd = null
        // A finished report of the same accompaniment is never reopened or doubled.
        if (existing == null && (!auto || mutable.value.any { it.sessionKey == current })) return null
        val base = existing ?: JourneyReportRecorder.start(UUID.randomUUID().toString(), ride, snapshot.companion, now)
        val updated = JourneyReportRecorder.update(base, snapshot, now)
        lastRide = updated.id to ride
        if (existing == null || updated.copy(updatedAt = existing.updatedAt) != existing) save(updated)
        return updated
    }

    /** Explicit "Bericht" for the running accompaniment, also when automatic preparation is off. */
    @Synchronized fun ensure(snapshot: ReportSnapshot): JourneyReport? {
        val ride = snapshot.ride?.takeUnless { it.demo } ?: return null
        val key = JourneyReportRecorder.sessionKey(ride, snapshot.companion)
        return mutable.value.firstOrNull { it.sessionKey == key } ?: sync(snapshot, auto = true)
    }

    /** One report per archived ride; opening it again returns the same one. */
    @Synchronized fun forArchived(ride: Ride, arrivals: List<ArrivalMark>): JourneyReport? {
        if (ride.demo) return null
        val key = ride.departure.key
        mutable.value.firstOrNull { report -> report.sessionKey == "archive/$key" || report.legs.any { it.key == key } }?.let { return it }
        val report = JourneyReportRecorder.fromArchive(UUID.randomUUID().toString(), ride, arrivals, clock())
        return report.takeIf(::save)
    }

    @Synchronized fun observe(id: String, text: String): Boolean {
        val report = mutable.value.firstOrNull { it.id == id } ?: return false
        val updated = JourneyReportRecorder.observe(report, UUID.randomUUID().toString().take(8), text, clock())
        return updated != report && save(updated)
    }

    @Synchronized fun removeObservation(id: String, eventId: String) {
        val report = mutable.value.firstOrNull { it.id == id } ?: return
        JourneyReportRecorder.removeObservation(report, eventId, clock()).takeIf { it != report }?.let(::save)
    }

    /**
     * Writes a new PDF from the current data. Earlier exports stay as they are; a preliminary
     * export of a running ride is labelled as such inside the PDF.
     */
    @Synchronized fun export(id: String): File {
        val report = mutable.value.firstOrNull { it.id == id } ?: error("Der Bericht ist nicht mehr gespeichert.")
        val now = clock()
        val folder = exportFolder(id).apply { check(isDirectory || mkdirs()) { "Bericht konnte nicht gespeichert werden." } }
        val number = (report.exports.maxOfOrNull { it.number } ?: 0) + 1
        val custom = options()
        val name = custom.exportName(report, number)
        val target = File(folder, name)
        val temporary = File(folder, "$name.tmp")
        try {
            writer(JourneyReportText.build(report, custom, now), temporary)
            check(temporary.renameTo(target)) { "Bericht konnte nicht gespeichert werden." }
            File(folder, "export-$number.json").writeText(report.encode())
        } catch (e: Exception) { temporary.delete(); throw e }
        save(report.copy(exports = report.exports + ReportExport(number, now, report.state == ReportState.RUNNING, name)))
        return target
    }

    fun exportFile(id: String, export: ReportExport): File? = File(exportFolder(id), export.fileName).takeIf { it.isFile }

    @Synchronized fun delete(id: String): Boolean {
        val json = file(id)
        val folder = exportFolder(id)
        val done = (!json.exists() || json.delete()) && (!folder.exists() || folder.deleteRecursively())
        mutable.value = read()
        return done
    }

    /** Uses the existing report retention setting; a running report is never pruned. */
    @Synchronized fun prune() {
        val days = retentionDays().coerceIn(0, 3650)
        if (days == 0) return
        val limit = clock().minusSeconds(days * 86_400L)
        mutable.value.filter { it.state != ReportState.RUNNING && it.updatedAt < limit }.forEach { delete(it.id) }
    }
}
