package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import de.luca.mitfahrt.data.backup.*
import de.luca.mitfahrt.reports.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic rides, reports and files. */
class ArchiveAndBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun ride(day: Long, delay: Long? = null, trip: String = "trip-$day") = RouteFixtures.ride(delaySeconds = delay,
        stops = RouteFixtures.stops(T0.plusSeconds(day * 86_400))).let { it.copy(departure = it.departure.copy(tripCode = trip), trackingStartedAt = it.departure.planned) }
    private fun archive(vararg rides: Ride): JourneyArchiveState = rides.fold(JourneyArchiveState()) { state, ride ->
        JourneyArchive.record(state, ride, ride.trackingStartedAt!!) }

    @Test fun `calendar month marks days with rides`() {
        val state = archive(ride(0), ride(0, trip = "b"), ride(2))
        val weeks = ArchiveCalendar.month(state.entries, YearMonth.of(2026, 11))
        assertTrue(weeks.all { it.size == 7 })
        val cells = weeks.flatten()
        assertEquals("1 Nov 2026 is a Sunday", 6, cells.indexOfFirst { it.day != null })
        assertEquals(2, cells.first { it.day == LocalDate.of(2026, 11, 9) }.rides)
        assertEquals(1, cells.first { it.day == LocalDate.of(2026, 11, 11) }.rides)
        assertEquals(0, cells.first { it.day == LocalDate.of(2026, 11, 10) }.rides)
    }

    @Test fun `punctuality keeps forecasts and own arrivals apart and leaves missing data out`() {
        val state = archive(ride(0, delay = 60), ride(1, delay = 400), ride(2, delay = null), ride(3, delay = 0))
        val exit = state.entries.first { it.ride.departure.tripCode == "trip-3" }.ride.exitTargetStop()!!
        val marks = listOf(ArrivalMark("m1", exit.id, exit.name, exit.arrival!!.plusSeconds(240)))
        val result = PunctualityStats.compute(state.entries, marks, null, LocalDate.of(2026, 11, 30), null)
        assertEquals(4, result.rides)
        assertEquals(3, result.forecastRides); assertEquals(1, result.forecastMissing); assertEquals(2, result.forecastPunctual)
        assertEquals(66, result.forecastRate)
        assertEquals(1, result.ownRides); assertEquals(0, result.ownPunctual); assertEquals(240L, result.ownMedianDelay)
        val week = PunctualityStats.compute(state.entries, marks, LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 30), null)
        assertEquals(2, week.rides)
        assertNull(PunctualityStats.compute(emptyList(), marks, null, LocalDate.of(2026, 11, 30), null).forecastRate)
    }

    @Test fun `report layout changes header and sections but keeps the private notice`() {
        val layout = ReportLayout("aaaaaaaa-0000-4000-8000-000000000001", " Gymnasium ", listOf("Max Muster", "Klasse 9c", "", "x".repeat(200)),
            "Gymnasium Odenkirchen", "9c", setOf("summary", "nonsense")).clean()
        assertEquals(setOf("summary"), layout.sections); assertEquals(3, layout.senderLines.size); assertEquals(80, layout.senderLines[2].length)
        val (decoded, selected) = ReportLayouts.decode(ReportLayouts.encode(listOf(layout), layout.id))
        assertEquals(listOf(layout), decoded); assertEquals(layout.id, selected)
        assertEquals(emptyList<ReportLayout>() to null, ReportLayouts.decode("{kaputt"))
        val report = JourneyReportRecorder.start("11111111-1111-4111-8111-111111111111", ride(0), null, T0)
        val options = ReportLayouts.apply(ReportCustomization(), layout)
        val texts = JourneyReportText.build(report, options, T0).map { it.toString() }
        assertTrue(texts.first().contains("Max Muster"))
        assertTrue(texts.any { it.contains("Gymnasium Odenkirchen") })
        assertTrue(texts.any { it.contains("Zusammenfassung") }); assertFalse(texts.any { it.contains("Heading(text=Verlauf)") })
        assertFalse(texts.any { it.contains("Heading(text=Verbindung)") })
        assertTrue(texts.any { it.contains("keine offizielle Bescheinigung") })
    }

    private fun reportsDir(): File = temporary.newFolder("reports-${System.nanoTime()}")
    private fun report(id: String, updated: Long, state: ReportState = ReportState.FINISHED) =
        JourneyReportRecorder.start(id, ride(0), null, T0).copy(updatedAt = T0.plusSeconds(updated), state = state)

    @Test fun `backup round trip and conflict-free merge`() {
        val source = reportsDir()
        val journeys = File(source, "journeys").apply { mkdirs() }
        val a = report("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 100)
        File(journeys, "${a.id}.json").writeText(a.copy(exports = listOf(ReportExport(1, T0, false, "a-1.pdf"))).encode())
        File(journeys, a.id).apply { mkdirs() }.let { File(it, "a-1.pdf").writeText("%PDF-1.4 a"); File(it, "export-1.json").writeText(a.encode()) }
        val legacy = DelayEvidence.fromRide(RouteFixtures.ride(boarded = true, delaySeconds = 300L), 1, T0)
        File(source, "${legacy.id}.json").writeText(legacy.encode()); File(source, "Mitfahrt-${legacy.id}.pdf").writeText("%PDF-1.4 old")
        val bytes = ByteArrayOutputStream().also { BackupWriter.write(it, archive(ride(0), ride(1)), source, T0) }.toByteArray()

        val contents = BackupReader.read(ByteArrayInputStream(bytes), T0)
        assertEquals(2, contents.archive.size); assertEquals(1, contents.reports.size); assertEquals(1, contents.legacy.size)

        // Target device: a newer and a running report already exist, and an export with the same name.
        val target = reportsDir()
        val targetJourneys = File(target, "journeys").apply { mkdirs() }
        val newer = report(a.id, 500)
        File(targetJourneys, "${a.id}.json").writeText(newer.encode())
        File(targetJourneys, a.id).apply { mkdirs() }.let { File(it, "a-1.pdf").writeText("%PDF-1.4 local") }
        val result = BackupMerge.reports(contents, target)
        assertEquals(0, result.reports); assertEquals(1, result.skipped)
        assertEquals(newer, JourneyReport.decode(File(targetJourneys, "${a.id}.json").readText()))
        assertEquals("existing PDF is never overwritten", "%PDF-1.4 local", File(targetJourneys, "${a.id}/a-1.pdf").readText())
        assertTrue(File(targetJourneys, "${a.id}/export-1.json").isFile)
        assertEquals(1, result.legacy); assertTrue(File(target, "Mitfahrt-${legacy.id}.pdf").isFile)
        // Importing again changes nothing and creates no duplicates.
        val again = BackupMerge.reports(contents, target)
        assertEquals(0, again.reports + again.legacy + again.exports)
        assertEquals(1, targetJourneys.listFiles()!!.count { it.extension == "json" })

        // A running local report is never replaced, even by a newer finished one.
        val running = reportsDir()
        File(running, "journeys").mkdirs()
        File(running, "journeys/${a.id}.json").writeText(report(a.id, 1, ReportState.RUNNING).encode())
        BackupMerge.reports(contents.copy(reports = listOf(a.copy(updatedAt = T0.plusSeconds(9999)))), running)
        assertEquals(ReportState.RUNNING, JourneyReport.decode(File(running, "journeys/${a.id}.json").readText()).state)
    }

    @Test fun `archive import takes only newer revisions and no duplicates`() {
        val store = JourneyArchiveStore(File(temporary.root, "archive.json")) { T0.plusSeconds(5 * 86_400) }
        store.record(ride(0))
        val incoming = archive(ride(0), ride(1)).entries.map { it.copy(updatedAt = it.updatedAt.minusSeconds(1)) }
        assertEquals(1, store.importEntries(incoming))
        assertEquals(2, store.state.value.entries.size)
        assertEquals(0, store.importEntries(incoming))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private val manifest = """{"format":"mitfahrt-sicherung","version":1,"createdAt":"2026-11-18T08:00:00Z"}""".toByteArray()
    private fun rejects(bytes: ByteArray) = try { BackupReader.read(ByteArrayInputStream(bytes), T0); false } catch (_: BackupException) { true }

    @Test fun `manipulated or damaged backups are rejected before anything is written`() {
        assertTrue("path traversal", rejects(zip("manifest.json" to manifest, "../../shared_prefs/x.xml" to "x".toByteArray())))
        assertTrue("absolute path", rejects(zip("manifest.json" to manifest, "/data/x.json" to "{}".toByteArray())))
        assertTrue("other folder", rejects(zip("manifest.json" to manifest, "reports/journeys/../../x.json" to "{}".toByteArray())))
        assertTrue("no manifest", rejects(zip("archive.json" to "{}".toByteArray())))
        assertTrue("foreign format", rejects(zip("manifest.json" to """{"format":"x","version":1,"createdAt":"2026-11-18T08:00:00Z"}""".toByteArray())))
        assertTrue("newer version", rejects(zip("manifest.json" to """{"format":"mitfahrt-sicherung","version":9,"createdAt":"2026-11-18T08:00:00Z"}""".toByteArray())))
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertTrue("broken report", rejects(zip("manifest.json" to manifest, "reports/journeys/$id.json" to "{kaputt".toByteArray())))
        assertTrue("pdf without report", rejects(zip("manifest.json" to manifest, "reports/journeys/$id/a.pdf" to "%PDF-1.4".toByteArray())))
        assertTrue("not a pdf", rejects(zip("manifest.json" to manifest, "reports/journeys/$id.json" to report(id, 1).encode().toByteArray(),
            "reports/journeys/$id/a.pdf" to "<html>".toByteArray())))
        assertTrue("id mismatch", rejects(zip("manifest.json" to manifest,
            "reports/journeys/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.json" to report(id, 1).encode().toByteArray())))
        assertTrue("oversized", rejects(zip("manifest.json" to manifest, "archive.json" to ByteArray(5 * 1024 * 1024) { ' '.code.toByte() })))
        assertTrue("not a zip", rejects("hello".toByteArray()))
        assertFalse(BackupFormat.allowed("reports/journeys/$id/./a.pdf"))
        assertTrue(BackupFormat.allowed("reports/journeys/$id/Mitfahrt-Fahrtbericht-2026-11-18-aaaaaaaa-1.pdf"))
    }
}
