package de.luca.mitfahrt.widgets

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class BusMapWidgetViewportTest {
    private val origin = Stop("de:05116:1001:1:1", "Halt Nord", GeoPoint(51.190, 6.440))
    private val normalDest = Stop("de:05116:1005:1:1", "Halt Sued", GeoPoint(51.198, 6.462))
    private val tempDest = Stop("de:05116:1003:1:1", "Halt Mitte", GeoPoint(51.193, 6.450))

    @Test
    fun `viewport zoom pan reset and route stepping behave deterministically`() {
        val initial = MapWidgetViewport()
        assertTrue(initial.isDefault)
        assertFalse(initial.isZoomed)
        assertEquals(initial, initial.pan(1f, -1f))

        val z1 = initial.zoomIn()
        assertTrue(z1.isZoomed)
        assertEquals("1.6×", z1.zoomLabel())
        val panned = z1.pan(1f, 0f)
        assertTrue(panned.panX > 0f)

        val zMax = initial.zoomIn().zoomIn().zoomIn().zoomIn()
        assertEquals(MapWidgetViewport.MAX_ZOOM, zMax.clampedZoom)
        assertEquals("4.2×", zMax.zoomLabel())

        val backToZero = panned.zoomOut()
        assertTrue(backToZero.isDefault)
        assertEquals(0f, backToZero.panX, 0.0001f)

        val stepped = initial.stepRoute(-1, 3)
        assertEquals(2, stepped.routeIndex)
        assertEquals(0, stepped.stepRoute(+1, 3).routeIndex)
    }

    @Test
    fun `idle widget renders full-bleed map for saved route without control buttons`() {
        val context = RuntimeEnvironment.getApplication()
        val today = T0.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        val plan = RoutePlan(
            id = "r1",
            name = "Morgenroute",
            origin = origin,
            destination = normalDest,
            match = RouteMatch.STOP,
            window = RouteWindow((1..7).toSet(), 0, 0),
            temporaryDestination = TemporaryDestination(tempDest, today, today.plusDays(5), "Woche")
        )
        val preview = RouteSketch.routePreviewModel(plan, today)!!
        assertEquals(tempDest.point, preview.exit)
        assertEquals("Halt Mitte", preview.exitName)
        assertTrue(preview.isRoutePreview)

        val planStore = RoutePlansStore(context)
        assertTrue(planStore.save(plan))
        val options = Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        val views = BusMapWidget.build(context, TrackingState(), T0, options, widgetId = 42)
        val root = views.apply(context, FrameLayout(context))
        assertEquals("Morgenroute", root.findViewById<TextView>(R.id.mitfahrt_map_widget_title).text.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.mitfahrt_map_widget_line).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.mitfahrt_map_widget_image).visibility)
        val status = root.findViewById<TextView>(R.id.mitfahrt_map_widget_status).text.toString()
        assertTrue(status, status.contains("Halt Mitte") && status.contains("Zeitweises Ziel"))
    }

    @Test
    fun `active ride widget shows LineSign badge and destination separately`() {
        val context = RuntimeEnvironment.getApplication()
        val ride = RouteFixtures.ride(boarded = true)
        val tracking = TrackingState(ride = ride, running = true, routeShape = RouteFixtures.shape(ride))
        val options = Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        val views = BusMapWidget.build(context, tracking, T0.plusSeconds(120), options, widgetId = 7)
        val root = views.apply(context, FrameLayout(context))
        val lineView = root.findViewById<TextView>(R.id.mitfahrt_map_widget_line)
        assertEquals(View.VISIBLE, lineView.visibility)
        assertEquals(ride.departure.line, lineView.text.toString())
        assertEquals(shortName(ride.displayTarget().name), root.findViewById<TextView>(R.id.mitfahrt_map_widget_title).text.toString())
    }

    @Test
    fun `render falls back to parent tiles when exact zoom tile is missing and reports missing coords`() {
        val ride = RouteFixtures.ride(boarded = true)
        val tracking = TrackingState(ride = ride, running = true, routeShape = RouteFixtures.shape(ride))
        val model = RouteSketch.model(tracking, T0.plusSeconds(120))!!
        val lookedUpZooms = mutableSetOf<Int>()
        var exactZoom: Int? = null
        val parentOnlyReader = WidgetTileReader { z, _, _ ->
            val target = exactZoom ?: z.also { exactZoom = it }
            lookedUpZooms += z
            // Only provide coarser parent zoom tiles so per-tile fallback is exercised.
            if (z == target - 1) Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(230, 235, 228)) }
            else null
        }
        val result = RouteSketch.renderDetailed(
            model = model,
            width = 560,
            height = 300,
            dark = true,
            tileReader = parentOnlyReader
        )
        assertEquals(560, result.bitmap.width)
        assertEquals(300, result.bitmap.height)
        assertTrue("Expected parent fallback to draw tiles", result.drewTiles)
        assertTrue("Expected missing higher-zoom tiles to be reported for background prefetch", result.missingTiles.isNotEmpty())
        assertTrue("Expected multiple zoom levels checked", lookedUpZooms.size >= 2)
    }

    @Test
    fun `active ride widget toggles cleanly between zoom-out overview and zoom-in bus focus modes`() {
        val context = RuntimeEnvironment.getApplication()
        val ride = RouteFixtures.ride(boarded = true)
        val bus = NewVehicle("2407", ride.departure.line, "Ziel", RouteFixtures.along(900.0), T0.plusSeconds(120))
        val tracking = TrackingState(
            ride = ride,
            running = true,
            routeShape = RouteFixtures.shape(ride),
            liveBus = LiveBusState(journeyKey = ride.departure.key, vehicle = bus, confirmed = true, fetchedAt = T0.plusSeconds(120))
        )
        val options = Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 320)
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
        }
        val widgetId = 99
        BusMapWidget.setMode(context, widgetId, MapWidgetMode.OVERVIEW)
        assertEquals(MapWidgetMode.OVERVIEW, BusMapWidget.mode(context, widgetId))

        val overviewRoot = BusMapWidget.build(context, tracking, T0.plusSeconds(120), options, widgetId = widgetId)
            .apply(context, FrameLayout(context))
        assertEquals(View.VISIBLE, overviewRoot.findViewById<View>(R.id.mitfahrt_map_widget_mode_chip).visibility)
        assertEquals("Bus", overviewRoot.findViewById<TextView>(R.id.mitfahrt_map_widget_mode_label).text.toString())

        val toggled = BusMapWidget.toggleMode(context, widgetId)
        assertEquals(MapWidgetMode.BUS_FOCUS, toggled)
        val focusRoot = BusMapWidget.build(context, tracking, T0.plusSeconds(120), options, widgetId = widgetId)
            .apply(context, FrameLayout(context))
        assertEquals("Strecke", focusRoot.findViewById<TextView>(R.id.mitfahrt_map_widget_mode_label).text.toString())

        // Focus mode zooms closer onto the bus than full-route overview.
        val model = RouteSketch.model(tracking, T0.plusSeconds(120))!!
        var overviewZoom = 0
        var focusZoom = 0
        RouteSketch.renderDetailed(model, 560, 300, dark = true, mode = MapWidgetMode.OVERVIEW,
            tileReader = WidgetTileReader { z, _, _ -> if (overviewZoom == 0) overviewZoom = z; null })
        RouteSketch.renderDetailed(model, 560, 300, dark = true, mode = MapWidgetMode.BUS_FOCUS,
            tileReader = WidgetTileReader { z, _, _ -> if (focusZoom == 0) focusZoom = z; null })
        assertTrue("Bus focus zoom ($focusZoom) should be >= overview zoom ($overviewZoom)", focusZoom >= overviewZoom)

        // Idle route preview hides the bus-focus toggle chip.
        val idleRoot = BusMapWidget.build(context, TrackingState(), T0, options, widgetId = 100)
            .apply(context, FrameLayout(context))
        assertEquals(View.GONE, idleRoot.findViewById<View>(R.id.mitfahrt_map_widget_mode_chip).visibility)
        BusMapWidget.clearMode(context, widgetId)
    }
}
