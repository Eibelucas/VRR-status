package de.luca.mitfahrt.reports

import android.app.Application
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/** All rides and journeys here are synthetic test data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class JourneyReportTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now: Instant = T0
    private val options = ReportCustomization()
    private fun store(dir: File = File(temporary.root, "journeys"), retention: Int = 0) =
        JourneyReportStore(dir, { options }, { retention }, { now })

    private fun ride(delay: Long? = null, boarded: Boolean = false) =
        RouteFixtures.ride(boarded = false, delaySeconds = delay).copy(trackingStartedAt = T0)
            .let { if (boarded) it.board("auto", T0.plusSeconds(130)) else it }
    private fun text(report: JourneyReport) = JourneyReportText.build(report, options, now).joinToString("\n") { block ->
        when (block) {
            is ReportBlock.Title -> block.text + " " + block.subtitle
            is ReportBlock.Heading -> block.text
            is ReportBlock.Paragraph -> block.text
            is ReportBlock.Field -> block.label + ": " + block.value
            is ReportBlock.Event -> "${block.time} ${block.text} [${block.source}]"
            is ReportBlock.LegHeader -> block.text + " " + block.detail
            is ReportBlock.StopRow -> "${block.name} | ${block.planned} | ${block.forecast} | ${block.note}"
            is ReportBlock.StopHeader -> block.name
            is ReportBlock.Small -> block.text
        }
    }

    @Test fun `report without any operator forecast is complete and says so`() {
        val reports = store()
        val waiting = ride()
        reports.sync(ReportSnapshot(waiting), auto = true)
        now = T0.plusSeconds(140)
        reports.sync(ReportSnapshot(waiting.board("manual", now)), auto = true)
        now = T0.plusSeconds(700)
        reports.noteEnd(RideEnd.SCHEDULE)
        reports.sync(ReportSnapshot(null), auto = true)
        val report = reports.state.value.single()
        assertEquals(ReportState.FINISHED, report.state)
        assertTrue(report.transit.single().stops.none { it.hasForecast })
        val content = text(report)
        assertTrue(content, content.contains("Eine VRR-Prognose lag nicht vor"))
        assertTrue(content.contains("nur der Fahrplan"))
        assertTrue("manual boarding is the user's own statement", content.contains("[Eigene Angabe]"))
        assertTrue("no exit was observed", content.contains("ein Ausstieg wurde nicht erfasst"))
        assertTrue(content.contains("keine offizielle Bescheinigung"))
        assertEquals("Nur Fahrplandaten, keine Prognose", JourneyReportText.headline(report))
        // All stops of my part of the trip, boarding (1) to exit (5), without picking one.
        assertEquals(5, report.transit.single().stops.size)
    }

    @Test fun `refreshes and restarts never create a second report`() {
        val dir = File(temporary.root, "journeys")
        var reports = store(dir)
        repeat(5) { step ->
            now = T0.plusSeconds(step * 30L)
            reports.sync(ReportSnapshot(ride(delay = 120L).copy(fetchedAt = now)), auto = true)
        }
        reports = store(dir) // app restart: new store on the same files
        now = T0.plusSeconds(200)
        reports.sync(ReportSnapshot(ride(delay = 180L, boarded = true).copy(fetchedAt = now)), auto = true)
        assertEquals(1, reports.state.value.size)
        assertEquals(1, dir.listFiles()!!.count { it.extension == "json" })
        val report = reports.state.value.single()
        assertEquals(1, report.events(ReportEventType.BOARDED).size)
        assertEquals(ReportSource.GPS, report.event(ReportEventType.BOARDED)!!.source)
        assertEquals(1, report.events(ReportEventType.TRACKING_STARTED).size)
    }

    @Test fun `a forecast seen earlier is kept with its fetch time when a later fetch has none`() {
        val reports = store()
        reports.sync(ReportSnapshot(ride(delay = 240L)), auto = true)
        now = T0.plusSeconds(300)
        val report = reports.sync(ReportSnapshot(ride().copy(fetchedAt = now)), auto = true)!!
        val boarding = report.transit.single().stops.first()
        assertEquals(boarding.plannedDeparture!!.plusSeconds(240), boarding.forecastDeparture)
        assertEquals(T0, boarding.forecastFetchedAt)
        assertEquals("Bis +4 Min laut VRR-Prognose", JourneyReportText.headline(report))
    }

    @Test fun `waiting without a detected bus is documented as a wait, not as a cancellation`() {
        val reports = store()
        reports.sync(ReportSnapshot(ride()), auto = true)
        now = T0.plusSeconds(1500)
        reports.noteEnd(RideEnd.MANUAL)
        reports.sync(ReportSnapshot(null), auto = true)
        val report = reports.state.value.single()
        assertEquals(ReportState.ABORTED, report.state)
        assertEquals(T0, report.waitFrom); assertEquals(now, report.waitUntil)
        assertTrue(report.events.none { it.type == ReportEventType.CANCELLED })
        val content = text(report)
        assertTrue(content, content.contains("Gewartet von 08:00 bis 08:25"))
        assertTrue(content.contains("kein passendes Fahrzeug erkannt"))
        assertTrue(content.contains("keine Aussage des Betreibers über einen Ausfall"))
        assertFalse(content.contains("ausgefallen"))
        // The user's own statement appears as such.
        assertTrue(reports.observe(report.id, "Bus ist nicht gekommen"))
        now = now.plusSeconds(60)
        val own = text(reports.state.value.single())
        assertTrue(own, own.contains("Bus ist nicht gekommen [Eigene Angabe]"))
        assertTrue(own.contains("Eigene Angabe: Bus ist nicht gekommen"))
    }

    @Test fun `a reported cancellation is an operator statement`() {
        val reports = store()
        val cancelled = ride().let { it.copy(departure = it.departure.copy(cancelled = true)) }
        val report = reports.sync(ReportSnapshot(cancelled), auto = true)!!
        val event = report.event(ReportEventType.CANCELLED)!!
        assertEquals(ReportSource.OPERATOR, event.source)
        assertEquals("Ausfall gemeldet", JourneyReportText.headline(report))
    }

    @Test fun `journey with a transfer stays one report over both vehicles`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), TripFixtures.T0).single()
        val started = JourneyCompanions.start(journey, T0)!!
        val firstRide = ride(delay = 60L, boarded = true)
        var companion = JourneyCompanions.tracking(started, firstRide.departure.key)
        val reports = store()
        val first = reports.sync(ReportSnapshot(firstRide, companion), auto = true)!!
        assertEquals("all legs of the search are part of the report from the start", journey.legs.size, first.legs.size)
        // Exit detected, transfer: no ride for a moment.
        now = T0.plusSeconds(600)
        reports.noteEnd(RideEnd.GPS_EXIT)
        reports.sync(ReportSnapshot(null, companion), auto = true)
        assertEquals(ReportState.RUNNING, reports.state.value.single().state)
        companion = JourneyCompanions.advance(companion)!!
        reports.sync(ReportSnapshot(null, companion), auto = true)
        val second = RouteFixtures.ride(boarded = false).let {
            it.copy(departure = it.departure.copy(lineId = "ddb:90E08", line = "RE8", tripCode = "trip-2", operator = "DBNW"), trackingStartedAt = now)
        }
        companion = JourneyCompanions.tracking(companion, second.departure.key)
        now = T0.plusSeconds(700)
        reports.sync(ReportSnapshot(second, companion), auto = true)
        reports.sync(ReportSnapshot(second.board("manual", now), companion), auto = true)
        now = T0.plusSeconds(2000)
        reports.noteEnd(RideEnd.MANUAL)
        reports.sync(ReportSnapshot(null, companion), auto = true)
        reports.sync(ReportSnapshot(null, null), auto = true)
        val report = reports.state.value.single()
        assertEquals(ReportState.FINISHED, report.state)
        assertEquals(journey.legs.size, report.legs.size)
        assertEquals(2, report.transit.count { it.tracked })
        assertEquals(2, report.events(ReportEventType.BOARDED).size)
        assertEquals(ReportSource.GPS, report.events(ReportEventType.EXITED).first().source)
        assertEquals(ReportSource.OWN, report.events(ReportEventType.EXITED).last().source)
        assertTrue(text(report).contains("Umstieg:"))
    }

    @Test fun `with automatic reports off nothing is stored until the report is requested`() {
        val reports = store()
        assertNull(reports.sync(ReportSnapshot(ride()), auto = false))
        assertTrue(reports.state.value.isEmpty())
        val report = reports.ensure(ReportSnapshot(ride()))!!
        now = T0.plusSeconds(60)
        reports.sync(ReportSnapshot(ride(boarded = true)), auto = false)
        assertEquals(report.id, reports.state.value.single().id)
        assertNotNull(reports.state.value.single().event(ReportEventType.BOARDED))
    }

    @Test fun `demo rides never produce a report`() {
        val reports = store()
        assertNull(reports.sync(ReportSnapshot(DemoRide.create(T0)), auto = true))
        assertNull(reports.ensure(ReportSnapshot(DemoRide.create(T0))))
        assertTrue(reports.state.value.isEmpty())
    }

    /** Draws the same layout code onto bitmaps; Robolectric has no native PDF writer. */
    private class BitmapPages(private val name: String) : JourneyReportPdf.Pages {
        val files = mutableListOf<File>()
        private var bitmap: android.graphics.Bitmap? = null
        override fun start(number: Int): android.graphics.Canvas {
            val created = android.graphics.Bitmap.createBitmap(JourneyReportPdf.PAGE_WIDTH * 2, JourneyReportPdf.PAGE_HEIGHT * 2, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap = created
            return android.graphics.Canvas(created).apply { drawColor(android.graphics.Color.WHITE); scale(2f, 2f) }
        }
        override fun finish() {
            val dir = File("build/reports/screenshots").apply { mkdirs() }
            val file = File(dir, "$name-seite-${files.size + 1}.png")
            file.outputStream().use { bitmap!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            files += file
        }
    }

    @Test fun `an export is frozen with the data it was made from and marked preliminary while the ride runs`() {
        val dir = File(temporary.root, "journeys")
        // The PDF writer itself needs a device; here the export bookkeeping is checked with a stand-in writer.
        val reports = JourneyReportStore(dir, { options }, { 0 }, { now }) { blocks, target ->
            target.writeText(blocks.joinToString("\n")); 1 }
        val report = reports.sync(ReportSnapshot(ride(delay = 120L)), auto = true)!!
        val first = reports.export(report.id)
        val bytes = first.readBytes()
        assertTrue(String(bytes).contains("Vorläufiger Stand"))
        assertTrue(reports.state.value.single().exports.single().preliminary)
        // The ride goes on and ends; the report changes, the exported file does not.
        now = T0.plusSeconds(900)
        reports.sync(ReportSnapshot(ride(delay = 300L, boarded = true).copy(fetchedAt = now)), auto = true)
        reports.noteEnd(RideEnd.SCHEDULE)
        reports.sync(ReportSnapshot(null), auto = true)
        reports.observe(report.id, "Bus war zu voll")
        assertArrayEquals(bytes, first.readBytes())
        val frozen = JourneyReport.decode(File(first.parentFile, "export-1.json").readText())
        assertEquals(ReportState.RUNNING, frozen.state)
        val second = reports.export(report.id)
        assertNotEquals(first.name, second.name)
        assertFalse(String(second.readBytes()).contains("Vorläufiger Stand"))
        assertTrue(String(second.readBytes()).contains("Bus war zu voll"))
        val exports = reports.state.value.single().exports
        assertEquals(listOf(1, 2), exports.map { it.number })
        assertFalse(exports.last().preliminary)
        assertArrayEquals(bytes, first.readBytes())
        // Nothing is exported or shared without the explicit call.
        assertEquals(2, File(dir, report.id).listFiles()!!.count { it.extension == "pdf" })
    }

    @Test fun `long report breaks over several pages without cutting rows`() {
        val stops = (0 until 70).map { index ->
            ReportStop("s$index", "Haltestelle mit einem ziemlich langen Namen Nummer $index, Mönchengladbach-Rheydt",
                plannedArrival = T0.plusSeconds(index * 60L), forecastArrival = T0.plusSeconds(index * 60L + 90).takeIf { index % 3 == 0 },
                forecastFetchedAt = T0.takeIf { index % 3 == 0 })
        }
        val events = (0 until 40).map { ReportEvent("e$it", ReportEventType.OBSERVATION, ReportSource.OWN, T0.plusSeconds(it * 30L), "Beobachtung $it ".repeat(12)) }
        val report = JourneyReport("11111111-1111-4111-8111-111111111111", "ride/x", T0, T0, ReportState.FINISHED, "Start", "Ziel",
            listOf(ReportLeg("k", line = "017", direction = "Rheydt", stops = stops, tracked = true, fetchedAt = T0)), events)
        val pages = BitmapPages("bericht-lang")
        val count = JourneyReportPdf.render(JourneyReportText.build(report, options, T0), pages)
        assertTrue("pages: $count", count >= 3)
        assertEquals(count, pages.files.size)
    }

    @Test fun `typical report with a transfer and missing forecasts needs at most two pages`() {
        val leg1 = ReportLeg("a", line = "017", direction = "Rheydt Hbf", operator = "NEW", tracked = true, fetchedAt = T0.plusSeconds(600), stops = listOf(
            ReportStop("1", "Mönchengladbach Bismarckplatz", "Bstg. 1", null, T0.plusSeconds(360), null, T0.plusSeconds(600), T0.plusSeconds(300)),
            ReportStop("2", "MG Schillerplatz", plannedArrival = T0.plusSeconds(480)),
            ReportStop("3", "MG Hbf /Europaplatz", plannedArrival = T0.plusSeconds(720), forecastArrival = T0.plusSeconds(1020), forecastFetchedAt = T0.plusSeconds(600))))
        val walk = ReportLeg("leg/1", walk = true, walkSeconds = 240, stops = listOf(ReportStop("3", "MG Hbf /Europaplatz"), ReportStop("4", "Mönchengladbach Hbf")))
        val leg2 = ReportLeg("leg/2", line = "RE8", direction = "Köln Messe/Deutz", operator = "DBNW", fetchedAt = T0, stops = listOf(
            ReportStop("4", "Mönchengladbach Hbf", "Gleis 2", null, T0.plusSeconds(1200)), ReportStop("5", "Köln Hbf", plannedArrival = T0.plusSeconds(4200))))
        val report = JourneyReport("33333333-3333-4333-8333-333333333333", "journey/x", T0, T0.plusSeconds(1300), ReportState.FINISHED,
            "Mönchengladbach Bismarckplatz", "Köln Hbf", listOf(leg1, walk, leg2), listOf(
                ReportEvent("start", ReportEventType.TRACKING_STARTED, ReportSource.APP, T0, "Begleitung gestartet"),
                ReportEvent("arr", ReportEventType.ARRIVED_AT_STOP, ReportSource.GPS, T0.plusSeconds(200), "An der Haltestelle Bismarckplatz"),
                ReportEvent("b", ReportEventType.BOARDED, ReportSource.GPS, T0.plusSeconds(640), "Einstieg in Linie 017 an Bismarckplatz", "a"),
                ReportEvent("t", ReportEventType.TRANSFER_RISK, ReportSource.OPERATOR, T0.plusSeconds(700), "Anschluss laut Prognosen nicht erreichbar", "a"),
                ReportEvent("x", ReportEventType.EXITED, ReportSource.GPS, T0.plusSeconds(1100), "Ausstieg an Hbf /Europaplatz erkannt", "a"),
                ReportEvent("o", ReportEventType.OBSERVATION, ReportSource.OWN, T0.plusSeconds(1250), "RE8 war schon weg")))
        val pages = BitmapPages("bericht-umstieg")
        assertTrue(JourneyReportPdf.render(JourneyReportText.build(report, ReportCustomization(name = "Luca Beispiel", schoolClass = "10b"), T0.plusSeconds(1300)), pages) <= 2)
    }

    @Test fun `old single stop reports stay readable and are told apart from journey reports`() {
        val evidence = DelayEvidence.fromRide(RouteFixtures.ride(boarded = true, delaySeconds = 300L), 1, T0)
        assertEquals(evidence, DelayEvidence.decode(evidence.encode()))
        assertTrue(runCatching { JourneyReport.decode(evidence.encode()) }.isFailure)
        val report = JourneyReportRecorder.start("22222222-2222-4222-8222-222222222222", ride(), null, T0)
        assertEquals(report, JourneyReport.decode(report.encode()))
    }

    @Test fun `reports from the archive state what was not recorded and are created once`() {
        val reports = store()
        val archived = RouteFixtures.ride(boarded = true, delaySeconds = 120L)
        val first = reports.forArchived(archived, emptyList())!!
        assertEquals(first.id, reports.forArchived(archived, emptyList())!!.id)
        assertEquals(1, reports.state.value.size)
        assertTrue(text(first).contains("Einstieg und Ausstieg wurden für diese Fahrt nicht erfasst"))
    }

    @Test fun `retention removes finished reports only`() {
        val dir = File(temporary.root, "journeys")
        val reports = store(dir, retention = 7)
        reports.sync(ReportSnapshot(ride()), auto = true)
        reports.noteEnd(RideEnd.MANUAL)
        reports.sync(ReportSnapshot(null), auto = true)
        now = T0.plusSeconds(3 * 86_400)
        val running = RouteFixtures.ride().copy(trackingStartedAt = now)
        reports.sync(ReportSnapshot(running), auto = true)
        now = T0.plusSeconds(9 * 86_400)
        reports.prune()
        assertEquals(listOf(ReportState.RUNNING), reports.state.value.map { it.state })
    }

    @Test fun `ride codec keeps boarding origin and reads rides stored before it existed`() {
        val boarded = ride(boarded = true)
        assertEquals(boarded, RideCodec.decode(RideCodec.encode(boarded)))
        val legacy = org.json.JSONObject(RideCodec.encode(boarded)).apply { remove("boardedAt"); remove("boardedBy") }.toString()
        val decoded = RideCodec.decode(legacy)
        assertTrue(decoded.boarded); assertNull(decoded.boardedAt); assertNull(decoded.boardedBy)
    }
    @Test fun `taking boarding back corrects future reports and preserves the frozen export`() {
        val reports = JourneyReportStore(File(temporary.root, "corrections"), { options }, { 0 }, { now }) { blocks, file ->
            file.writeText(blocks.joinToString("\n")); 1
        }
        val waiting = ride()
        val id = reports.sync(ReportSnapshot(waiting), true)!!.id
        now = T0.plusSeconds(150)
        reports.sync(ReportSnapshot(waiting.board("auto", now)), true)
        val frozen = reports.export(id)
        val bytes = frozen.readBytes()
        now = now.plusSeconds(10)
        val corrected = reports.sync(ReportSnapshot(waiting.unboard()), true)!!
        assertTrue(corrected.events(ReportEventType.BOARDED).isEmpty())
        assertEquals(ReportSource.OWN, corrected.event(ReportEventType.BOARDING_CORRECTED)!!.source)
        assertNull(corrected.waitUntil)
        assertArrayEquals(bytes, frozen.readBytes())
        val correctedPdf = reports.export(id).readText()
        assertTrue(correctedPdf.contains("Einstieg zurückgenommen"))
        now = now.plusSeconds(60)
        val boardedAgain = reports.sync(ReportSnapshot(waiting.board("manual", now)), true)!!
        assertEquals(now, boardedAgain.event(ReportEventType.BOARDED)!!.at)
        assertEquals(1, boardedAgain.events(ReportEventType.BOARDING_CORRECTED).size)
        assertEquals(2, JourneyReport.decode(boardedAgain.encode()).exports.size)
    }

    @Test fun `deleted running reports stay deleted after updates and a process restart`() {
        val dir = File(temporary.root, "deleted")
        var reports = store(dir)
        val waiting = ride()
        val id = reports.sync(ReportSnapshot(waiting), true)!!.id
        assertTrue(reports.delete(id))
        assertNull(reports.sync(ReportSnapshot(waiting), true))
        reports = store(dir)
        assertNull(reports.sync(ReportSnapshot(waiting.board("manual", T0.plusSeconds(200))), true))
        assertTrue(reports.state.value.isEmpty())
        val newSession = waiting.copy(trackingStartedAt = T0.plusSeconds(600))
        assertNotNull(reports.sync(ReportSnapshot(newSession), true))
    }

    @Test fun `clearing reports also suppresses the active session across restart`() {
        val dir = File(temporary.root, "cleared")
        val reports = store(dir)
        reports.sync(ReportSnapshot(ride()), true)
        assertTrue(reports.clear())
        assertNull(store(dir).sync(ReportSnapshot(ride()), true))
    }

    @Test fun `failed report writes return no success and leave the previous record readable`() {
        val dir = File(temporary.root, "blocked")
        dir.writeText("not a directory")
        val reports = store(dir)
        assertNull(reports.sync(ReportSnapshot(ride()), true))
        assertTrue(reports.state.value.isEmpty())
        assertNotNull(reports.error.value)
        reports.clearError()
        assertNull(reports.error.value)
    }

    @Test fun `failed export bookkeeping rolls back the new files and reports an error`() {
        val dir = File(temporary.root, "export-failure")
        val reports = JourneyReportStore(dir, { options }, { 0 }, { now }) { _, file -> file.writeText("pdf"); 1 }
        val report = reports.sync(ReportSnapshot(ride()), true)!!
        val record = File(dir, "${report.id}.json")
        record.delete()
        record.mkdir()
        File(record, "block").writeText("do not replace")
        assertThrows(Exception::class.java) { reports.export(report.id) }
        assertTrue(reports.state.value.single().exports.isEmpty())
        assertNotNull(reports.error.value)
        assertTrue(File(dir, report.id).listFiles().orEmpty().isEmpty())
    }

    @Test fun `an unindexed export is never overwritten or removed on retry`() {
        val dir = File(temporary.root, "existing-export")
        val reports = JourneyReportStore(dir, { options }, { 0 }, { now }) { _, file -> file.writeText("new"); 1 }
        val report = reports.sync(ReportSnapshot(ride()), true)!!
        val folder = File(dir, report.id).apply { mkdirs() }
        val previous = File(folder, options.exportName(report, 1)).apply { writeText("original") }
        assertThrows(Exception::class.java) { reports.export(report.id) }
        assertEquals("original", previous.readText())
    }

    @Test fun `transfer warnings preserve data time and distinguish app assessment from cancellation`() {
        val reports = store()
        val snapshot = ReportSnapshot(ride(), transfer = CompanionTransfer(45, TransferRisk.TIGHT, false),
            transferObservedAt = T0.minusSeconds(30))
        val report = reports.sync(snapshot, true)!!
        val event = report.event(ReportEventType.TRANSFER_RISK)!!
        assertEquals(ReportSource.APP, event.source)
        assertEquals(T0.minusSeconds(30), event.at)
        assertEquals(now, event.recordedAt)
        val cancelled = reports.sync(snapshot.copy(transfer = CompanionTransfer(-1, TransferRisk.MISSED, true)), true)!!
        assertEquals(ReportSource.OPERATOR, cancelled.event(ReportEventType.TRANSFER_RISK)!!.source)
    }

}
