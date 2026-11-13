package de.luca.mitfahrt.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.PlannerForm
import de.luca.mitfahrt.data.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the new screens with synthetic data (no VRR request) and stores PNGs under
 * build/reports/screenshots. It also checks that the composables run without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w393dp-h900dp-xxhdpi")
class PlannerScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val t0 = TripFixtures.T0
    private val journeys = listOf(
        TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), t0).single(),
        TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainEstDep = 21, busEstArr = 17)), t0).single(),
        TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainEstDep = null, busEstArr = null)), t0).single(),
        TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainStatus = listOf("TRIP_CANCELLED"))), t0).single())
    private val bismarck = Stop("de:05116:7300", "MG Bismarckplatz", GeoPoint(51.192, 6.442), 180)
    private val hehn = Stop("de:05116:7310", "Mönchengladbach Hehn Kirche", GeoPoint(51.183, 6.43), 640)

    private fun departure(line: String, destination: String, stop: Stop, minutes: Long, delay: Long?) =
        Departure("new:80$line: :H:m26", line, destination, "${stop.id}:1:1", stop.name, "t$line$minutes", t0.plusSeconds(minutes * 60),
            delay?.let { t0.plusSeconds(minutes * 60 + it) }, "Bstg. 1", observedAt = t0)

    /**
     * The composition must settle and render (hard check). The picture is drawn with View.draw:
     * captureToImage waits for a hardware frame commit that Robolectric does not deliver.
     */
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().assertExists()
        val dir = File("build/reports/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        assertTrue(view.width > 0 && view.height > 0)
        runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { File(dir, "$name.txt").writeText("Bild konnte nicht gezeichnet werden: $it") }
    }

    private fun startPage() {
        val plan = RoutePlan(id = "schule", name = "Schule", origin = bismarck, destination = Stop("de:05116:23007", "MG Hbf /Europaplatz"))
        compose.setContent {
            MitfahrtTheme {
                Column(Modifier.fillMaxSize().background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Header("Mitfahrt", "", Icons.Outlined.DirectionsBus)
                    WhereToCard(PlannerForm(origin = TripPlace.MY_LOCATION), listOf(SavedPlace(TripPlace(PlaceKind.STOP, "de:05116:23007", "MG Hbf"), "Bahnhof"),
                        SavedPlace(TripPlace(PlaceKind.ADDRESS, "streetID:1", "Hauptstraße 10"), "Zuhause")), {}, {}, {}, {})
                    PlannedRoutesCard(listOf(plan), 1, mapOf("schule" to listOf(departure("017", "Rheydt Hbf", bismarck, 4, 120))), false, t0, null,
                        {}, { _, _ -> }, { _, _ -> }, {}, {})
                }
            }
        }
    }

    @Test fun `start page`() { startPage(); capture("1-start") }

    @Config(qualifiers = "+night")
    @Test fun `start page dark`() { startPage(); capture("1-start-dunkel") }

    @Test fun `start page without saved routes has no routes section`() {
        compose.setContent {
            MitfahrtTheme {
                Column(Modifier.fillMaxSize().background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    WhereToCard(PlannerForm(), emptyList(), {}, {}, {}, {})
                    PlannedRoutesCard(emptyList(), 0, emptyMap(), false, t0, null, {}, { _, _ -> }, { _, _ -> }, {}, {})
                }
            }
        }
        compose.onRoot().assertExists()
        capture("2-start-ohne-routen")
    }

    @Test fun `journey results`() {
        compose.setContent {
            MitfahrtTheme {
                Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    journeys.forEach { JourneyRow(it, t0) {} }
                }
            }
        }
        capture("3-verbindungen")
    }

    @Test fun `journey details`() {
        // Without coordinates: the map is left out in this rendering (no tile access in tests).
        val journey = journeys.first().let { j -> j.copy(legs = j.legs.map { leg ->
            leg.copy(origin = leg.origin.copy(point = null), destination = leg.destination.copy(point = null),
                intermediate = leg.intermediate.map { it.copy(point = null) }, path = emptyList())
        }) }
        compose.setContent {
            MitfahrtTheme {
                JourneyDetailsScreen(journey, TripResultsState(), t0, {}, {}, false, { null }, {}, false, {})
            }
        }
        capture("4-details")
    }

    @Test fun `nearby tab`() {
        val home = HomeState(NearbyStatus.READY, listOf(bismarck, hehn),
            listOf(departure("017", "Rheydt Hbf", bismarck, 3, 60), departure("007", "Hbf", bismarck, 6, null),
                departure("017", "Rheydt Hbf", hehn, 9, 0)), updatedAt = t0)
        compose.setContent {
            MitfahrtTheme {
                Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    NearbyOverview(home, t0, AppSettings(), SavedStopsState(favorites = listOf(bismarck)), null, false,
                        {}, {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        capture("5-in-der-naehe")
    }

    @Test fun `nearby tab without location`() {
        compose.setContent {
            MitfahrtTheme {
                Column(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
                    NearbyOverview(HomeState(NearbyStatus.NO_PERMISSION), t0, AppSettings(), SavedStopsState(), null, false,
                        {}, {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        capture("6-in-der-naehe-ohne-standort")
    }

    // --- journey report, boarding controls: synthetic data ---

    private val reportSample: de.luca.mitfahrt.reports.JourneyReport get() {
        val leg1 = de.luca.mitfahrt.reports.ReportLeg("a", line = "017", direction = "Rheydt Hbf", operator = "NEW", tracked = true, fetchedAt = t0.plusSeconds(600), stops = listOf(
            de.luca.mitfahrt.reports.ReportStop("1", "Mönchengladbach Bismarckplatz", "Bstg. 1", null, t0.plusSeconds(360), null, t0.plusSeconds(600), t0.plusSeconds(300)),
            de.luca.mitfahrt.reports.ReportStop("2", "MG Schillerplatz", plannedArrival = t0.plusSeconds(480)),
            de.luca.mitfahrt.reports.ReportStop("3", "MG Hbf /Europaplatz", plannedArrival = t0.plusSeconds(720), forecastArrival = t0.plusSeconds(1020), forecastFetchedAt = t0.plusSeconds(600))))
        val leg2 = de.luca.mitfahrt.reports.ReportLeg("leg/2", line = "RE8", direction = "Köln Messe/Deutz", operator = "DBNW", fetchedAt = t0, stops = listOf(
            de.luca.mitfahrt.reports.ReportStop("4", "Mönchengladbach Hbf", "Gleis 2", null, t0.plusSeconds(1200)),
            de.luca.mitfahrt.reports.ReportStop("5", "Köln Hbf", plannedArrival = t0.plusSeconds(4200))))
        fun event(id: String, type: de.luca.mitfahrt.reports.ReportEventType, source: de.luca.mitfahrt.reports.ReportSource, seconds: Long, text: String) =
            de.luca.mitfahrt.reports.ReportEvent(id, type, source, t0.plusSeconds(seconds), text, "a")
        return de.luca.mitfahrt.reports.JourneyReport("33333333-3333-4333-8333-333333333333", "journey/x", t0, t0.plusSeconds(1300),
            de.luca.mitfahrt.reports.ReportState.FINISHED, "Mönchengladbach Bismarckplatz", "Köln Hbf", listOf(leg1, leg2), listOf(
                event("s", de.luca.mitfahrt.reports.ReportEventType.TRACKING_STARTED, de.luca.mitfahrt.reports.ReportSource.APP, 0, "Begleitung gestartet"),
                event("b", de.luca.mitfahrt.reports.ReportEventType.BOARDED, de.luca.mitfahrt.reports.ReportSource.GPS, 640, "Einstieg in Linie 017 an Bismarckplatz"),
                event("x", de.luca.mitfahrt.reports.ReportEventType.EXITED, de.luca.mitfahrt.reports.ReportSource.GPS, 1100, "Ausstieg an Hbf /Europaplatz erkannt"),
                event("o", de.luca.mitfahrt.reports.ReportEventType.OBSERVATION, de.luca.mitfahrt.reports.ReportSource.OWN, 1250, "RE8 war schon weg")),
            exports = listOf(de.luca.mitfahrt.reports.ReportExport(1, t0.plusSeconds(900), true, "x.pdf")))
    }

    private fun reportScreen(report: de.luca.mitfahrt.reports.JourneyReport = reportSample) {
        compose.setContent { MitfahrtTheme {
            JourneyReportScreen(report, de.luca.mitfahrt.reports.ReportCustomization(), t0.plusSeconds(1300), false, {}, {}, {}, {}, {}, {}, {})
        } }
    }

    @Test fun `journey report`() { reportScreen(); capture("7-bericht") }

    @Config(qualifiers = "+night")
    @Test fun `journey report dark`() { reportScreen(); capture("7-bericht-dunkel") }

    @Config(qualifiers = "w320dp-h900dp-xxhdpi")
    @Test fun `journey report on a narrow screen with large font`() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        reportScreen(); capture("7-bericht-schmal-grosse-schrift")
    }

    @Test fun `journey report of a wait without a bus`() {
        val ride = RouteFixtures.ride().copy(trackingStartedAt = t0)
        var report = de.luca.mitfahrt.reports.JourneyReportRecorder.start("44444444-4444-4444-8444-444444444444", ride, null, t0)
        report = de.luca.mitfahrt.reports.JourneyReportRecorder.update(report, de.luca.mitfahrt.reports.ReportSnapshot(ride), t0)
        report = de.luca.mitfahrt.reports.JourneyReportRecorder.finish(report, ride, de.luca.mitfahrt.reports.RideEnd.MANUAL, t0.plusSeconds(1500), false)
        report = de.luca.mitfahrt.reports.JourneyReportRecorder.observe(report, "1", "Bus ist nicht gekommen", t0.plusSeconds(1510))
        reportScreen(report); capture("8-bericht-warten-ohne-bus")
    }

    private fun ridePage(ride: Ride, boarding: BoardingState?) {
        compose.setContent { MitfahrtTheme {
            Column(Modifier.fillMaxSize().background(Ink).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Header(if (ride.boarded) "Unterwegs" else "Deine Fahrt", "", Icons.Outlined.DirectionsBus)
                RideHero(ride, RouteFixtures.T0.plusSeconds(30), true)
                BoardingControls(ride, boarding, true, {}, {}, {}, {})
            }
        } }
    }

    @Test fun `ride page while waiting`() {
        ridePage(RouteFixtures.ride(delaySeconds = 120L), BoardingState(phase = BoardingPhase.POSSIBLE_BOARDING)); capture("9-fahrt-warten")
    }

    @Config(qualifiers = "+night")
    @Test fun `ride page after detected boarding dark`() {
        ridePage(RouteFixtures.ride(delaySeconds = 120L).board("auto", RouteFixtures.T0.plusSeconds(20)), BoardingState(phase = BoardingPhase.ON_BOARD))
        capture("9-fahrt-im-bus-dunkel")
    }

    @Test fun `ride page asks after an exit away from the chosen stop`() {
        ridePage(RouteFixtures.ride(delaySeconds = 120L).board("auto", RouteFixtures.T0.plusSeconds(20)), BoardingState(phase = BoardingPhase.ENDED))
        capture("9-fahrt-ausgestiegen")
    }

    private fun settingsPage() {
        compose.setContent { MitfahrtTheme {
            Box(Modifier.fillMaxSize().background(Ink)) {
                SettingsPage(Modifier, {}, de.luca.mitfahrt.updates.UpdateState(), {}, {}, {}, {}, de.luca.mitfahrt.alerts.HapticTestState(), {},
                    AlertPreferences(), {}, {}, {}, RoutePlansState(), {}, {}, {}, {}, {})
            }
        } }
    }

    @Test fun `settings page`() { settingsPage(); capture("10-einstellungen") }

    @Config(qualifiers = "+night")
    @Test fun `settings page dark`() { settingsPage(); capture("10-einstellungen-dunkel") }

    @Test fun `reports list with a running and a finished report and an old single stop report`() {
        val old = de.luca.mitfahrt.reports.DelayEvidence.fromRide(RouteFixtures.ride(boarded = true, delaySeconds = 300L), 1, t0)
        val running = reportSample.copy(id = "55555555-5555-4555-8555-555555555555", state = de.luca.mitfahrt.reports.ReportState.RUNNING,
            originName = "Mönchengladbach Hehn Kirche", destinationName = "Mönchengladbach Rheydt Hbf, Busbahnhof Steig 4")
        compose.setContent { MitfahrtTheme {
            Box(Modifier.fillMaxSize().background(Ink)) {
                ReportsPage(de.luca.mitfahrt.ReportsState(entries = listOf(old)), listOf(running, reportSample), null, Modifier, {}, {}, {}, {}, {}, {}, {})
            }
        } }
        capture("11-berichte")
    }
}
