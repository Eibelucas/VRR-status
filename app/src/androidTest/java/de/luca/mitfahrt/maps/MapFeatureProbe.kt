package de.luca.mitfahrt.maps

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.ui.LiveBusCard
import de.luca.mitfahrt.ui.MitfahrtTheme
import de.luca.mitfahrt.widgets.BusMapWidget
import java.io.File
import java.time.Instant

/** Synthetic positions; exercises the real MapView dialog and RemoteViews on Android. */
object MapFeatureProbe {
    fun run(test: Instrumentation, activity: Activity, directory: File) {
        val now = Instant.now()
        val points = (0..4).map { GeoPoint(51.19 + it * .001, 6.44) }
        val stops = points.mapIndexed { i, p -> RideStop("map-$i", "Testhalt $i", p,
            now.plusSeconds(i * 120L), now.plusSeconds(i * 120L)) }
        val departure = Departure("test:020", "020", "Kartentest", stops[0].id, stops[0].name, "map-test", now, null, "1")
        val ride = Ride(departure, stops, 0, now, boarded = true).withExitTarget(4)
        val shape = checkNotNull(RouteShape.verify(ride, points, RouteShapeSource.TRIP))
        val bus = NewVehicle("test-bus", "020", "Kartentest", points[1], now)
        val live = LiveBusState(journeyKey = departure.key, vehicle = bus, selectedVehicleId = bus.id)
        val tracking = TrackingState(ride = ride, running = true, liveBus = live, routeShape = shape)
        test.runOnMainSync {
            val remote = BusMapWidget.build(activity, tracking, now, Bundle())
            val view = remote.apply(activity, FrameLayout(activity))
            val width = (320 * activity.resources.displayMetrics.density).toInt()
            val height = (220 * activity.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            check(view.findViewById<View>(de.luca.mitfahrt.R.id.mitfahrt_map_widget_image).visibility == View.VISIBLE)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "map-widget.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            (activity as ComponentActivity).setContent {
                MitfahrtTheme { Surface { Column { LiveBusCard(ride, live, now, {}, {}, {}, shape = shape) } } }
            }
        }
        // Compose exposes icon descriptions on virtual children; the click action can belong
        // to their parent. Android's find-by-text does not consistently search descriptions.
        fun node(label: String): AccessibilityNodeInfo? {
            fun visit(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (current.isVisibleToUser && (current.text?.toString() == label ||
                        current.contentDescription?.toString() == label)) {
                    var action: AccessibilityNodeInfo? = current
                    while (action != null) {
                        if (action.isVisibleToUser && action.isClickable) return action
                        action = action.parent
                    }
                }
                for (index in 0 until current.childCount) {
                    current.getChild(index)?.let { visit(it)?.let { found -> return found } }
                }
                return null
            }
            return test.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun waitNode(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                node(label)?.let { return it }
                SystemClock.sleep(100)
            }
            error("Control not visible: $label")
        }
        check(waitNode("Karte im Vollbild öffnen").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitNode("Vollbild schließen")
        test.uiAutomation.waitForIdle(500, 5_000)
        val bounds = Rect()
        checkNotNull(test.uiAutomation.rootInActiveWindow).getBoundsInScreen(bounds)
        check(bounds.height() >= activity.resources.displayMetrics.heightPixels * .85)
        SystemClock.sleep(500)
        val image = checkNotNull(test.uiAutomation.takeScreenshot())
        File(directory, "fullscreen-map.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        check(waitNode("Vollbild schließen").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitNode("Karte im Vollbild öffnen")
    }
}
