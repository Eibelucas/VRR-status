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
        assertTrue(status, status.contains("Bus laut NEW") && status.contains("noch etwa 4 Min"))
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
}
