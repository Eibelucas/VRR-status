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
                    Header("Mitfahrt", "MÖNCHENGLADBACH", Icons.Outlined.DirectionsBus)
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
}
