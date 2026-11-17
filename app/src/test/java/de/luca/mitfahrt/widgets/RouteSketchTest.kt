package de.luca.mitfahrt.widgets

import android.app.Application
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic ride on a made-up street. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class RouteSketchTest {
    private val ride = RouteFixtures.ride(boarded = false).board("auto", T0.plusSeconds(120))
    private val now = T0.plusSeconds(300)
    private fun state(shape: Boolean = true, live: LiveBusState = LiveBusState()) = TrackingState(ride = ride, running = true,
        liveBus = live.copy(journeyKey = ride.departure.key), routeShape = if (shape) RouteFixtures.shape(ride) else null)
    private fun save(name: String, bitmap: android.graphics.Bitmap) {
        val dir = File("build/reports/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `sketch uses the verified line between boarding and exit and the assigned bus`() {
        val bus = RouteFixtures.bus("v7", 1500.0, 0.0, now.minusSeconds(8))
        val tracking = state(live = LiveBusState(vehicle = bus, selectedVehicleId = "v7", routeOffset = 1500.0))
        val model = RouteSketch.model(tracking, now)!!
        assertTrue(model.line.size >= 2)
        assertEquals(5, model.stops.size)
        assertEquals(bus.point, model.position); assertFalse(model.positionIsGps)
        val (title, status) = RouteSketch.texts(tracking, model, now)
        assertEquals("006 · Halt 5", title)
        assertTrue(status, status.contains("Bus laut NEW") && status.contains("etwa 4 Min"))
        save("12-widget-buskarte", RouteSketch.render(model, 640, 300, dark = false))
        save("12-widget-buskarte-dunkel", RouteSketch.render(model, 640, 300, dark = true))
    }

    @Test fun `without verified geometry stops are not joined and the text says so`() {
        val model = RouteSketch.model(state(shape = false), now)!!
        assertTrue(model.line.isEmpty())
        assertNull(model.position)
        assertTrue(RouteSketch.texts(state(shape = false), model, now).second.contains("Skizze ohne Straßenkarte"))
        save("12-widget-buskarte-ohne-strecke", RouteSketch.render(model, 640, 300, dark = false))
    }

    @Test fun `an unassigned or old bus is not drawn and no ride gives no sketch`() {
        val unassigned = RouteFixtures.bus("v7", 1500.0, 0.0, now.minusSeconds(8))
        assertNull(RouteSketch.model(state(live = LiveBusState(vehicle = unassigned)), now)!!.position)
        val old = RouteFixtures.bus("v7", 1500.0, 0.0, now.minusSeconds(300))
        assertNull(RouteSketch.model(state(live = LiveBusState(vehicle = old, selectedVehicleId = "v7")), now)!!.position)
        assertNull(RouteSketch.model(TrackingState(), now))
        assertEquals("Buskarte", RouteSketch.texts(TrackingState(), null, now).first)
    }
    @Test fun `route clipping keeps a real loop instead of joining shortcuts`() {
        val points = RouteFixtures.street.take(7) + listOf(RouteFixtures.at(400.0, 0.0), RouteFixtures.at(200.0, 0.0),
            RouteFixtures.at(400.0, 0.0), RouteFixtures.at(600.0, 0.0)) + RouteFixtures.street.drop(7)
        val shape = RouteShape.verify(ride, points, RouteShapeSource.TRIP)!!
        val model = RouteSketch.model(state().copy(routeShape = shape), now)!!
        assertTrue(model.line.contains(RouteFixtures.at(200.0, 0.0)))
        assertEquals(shape.line.pointAt(shape.offsetOf(ride.boardingIndex)!!), model.line.first())
    }

    @Test fun `gps privacy and journey identity apply to the widget`() {
        val gps = GpsProgressState(journeyKey = ride.departure.key, active = true, offset = 1500.0, confirmedAt = now,
            confirmedPoint = RouteFixtures.along(1500.0), status = GpsProgressStatus.TRACKING, exact = true)
        val tracking = state().copy(gpsProgress = gps)
        assertNotNull(RouteSketch.model(tracking, now)!!.position)
        assertNull(RouteSketch.model(tracking, now, gpsEnabled = false)!!.position)
        assertNull(RouteSketch.model(tracking.copy(gpsProgress = gps.copy(journeyKey = "another-ride")), now)!!.position)
        assertNull(RouteSketch.model(tracking.copy(running = false), now)!!.position)
        assertNull(RouteSketch.model(tracking, now.plusSeconds(46))!!.position)
    }

    @Test fun `widget remote views obey disabled calculation and gps settings`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        try {
            settings.set("gps_travel_time", "false")
            settings.set("gps_next_stop", "false")
            val gps = GpsProgressState(journeyKey = ride.departure.key, active = true, offset = 1500.0, confirmedAt = now,
                confirmedPoint = RouteFixtures.along(1500.0), status = GpsProgressStatus.TRACKING, exact = true)
            val views = BusMapWidget.build(context, state().copy(gpsProgress = gps), now, android.os.Bundle())
            val widget = views.apply(context, android.widget.FrameLayout(context))
            val text = widget.findViewById<android.widget.TextView>(de.luca.mitfahrt.R.id.mitfahrt_map_widget_status).text.toString()
            assertFalse(text.contains("Fahrzeit"))
            assertTrue(text.contains("Keine aktuelle Position"))
            assertEquals(android.view.View.GONE, widget.findViewById<android.widget.Chronometer>(de.luca.mitfahrt.R.id.mitfahrt_map_widget_age).visibility)
        } finally { settings.close() }
    }

}
