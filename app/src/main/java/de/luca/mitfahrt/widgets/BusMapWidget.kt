package de.luca.mitfahrt.widgets

import android.app.AlarmManager
import android.content.Intent
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import java.time.Instant
import kotlin.math.cos

/** What the sketch shows. Only measured positions and verified line geometry; nothing is invented. */
data class RouteSketchModel(
    /** Real line between boarding and exit, empty if no verified geometry exists. */
    val line: List<GeoPoint>,
    val stops: List<GeoPoint>,
    val boarding: GeoPoint?,
    val exit: GeoPoint?,
    /** Position of the assigned bus (NEW) or, on board without one, the phone's confirmed GPS position. */
    val position: GeoPoint?,
    val positionIsGps: Boolean,
    val measuredAt: Instant? = null
)

/** Draws the route as a plain line sketch without map tiles, so it works offline and in a widget. */
object RouteSketch {
    fun model(state: TrackingState, now: Instant, gpsEnabled: Boolean = true): RouteSketchModel? {
        val ride = state.ride?.takeUnless { it.demo } ?: return null
        val key = ride.departure.key
        val goal = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val shape = state.routeShape?.takeIf { it.journeyKey == key }
        val from = shape?.offsetOf(ride.boardingIndex)
        val to = shape?.offsetOf(goal)
        val line = if (shape != null && from != null && to != null) shape.line.slice(from, to) else emptyList()
        val live = state.liveBus
        val bus = live.vehicle?.takeIf { state.running && live.journeyKey == key && it.isFresh(now) &&
            (live.selectedVehicleId == it.id || live.confirmed || live.auto.holdsVehicle) }
        val gps = state.gpsProgress?.takeIf { gpsEnabled && state.running && ride.boarded && it.journeyKey == key && GpsStopProgress.confident(it, now) }
        val stops = (ride.boardingIndex..goal).mapNotNull { ride.stops[it].point }.filter(Geo::valid)
        if (stops.isEmpty() && line.isEmpty()) return null
        return RouteSketchModel(line, stops, ride.stops[ride.boardingIndex].point, ride.stops[goal].point,
            (live.routePoint?.takeIf { bus != null && !live.offRoute } ?: bus?.point) ?: gps?.confirmedPoint,
            bus == null && gps?.confirmedPoint != null, bus?.observedAt ?: gps?.confirmedAt)
    }

    fun render(model: RouteSketchModel, width: Int, height: Int, dark: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceIn(64, 1200), height.coerceIn(64, 1200), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val all = (model.line + model.stops + listOfNotNull(model.position)).filter(Geo::valid)
        if (all.isEmpty()) return bitmap
        val unit = minOf(bitmap.width / 160f, bitmap.height / 100f)
        val pad = 14f * unit
        val midLat = all.map { it.lat }.average()
        val scaleX = cos(Math.toRadians(midLat))
        val minX = all.minOf { it.lon } * scaleX; val maxX = all.maxOf { it.lon } * scaleX
        val minY = all.minOf { it.lat }; val maxY = all.maxOf { it.lat }
        val span = maxOf((maxX - minX) / (bitmap.width - 2 * pad), (maxY - minY) / (bitmap.height - 2 * pad), 1e-9)
        val offsetX = (bitmap.width - (maxX - minX) / span).toFloat() / 2f
        val offsetY = (bitmap.height - (maxY - minY) / span).toFloat() / 2f
        fun x(point: GeoPoint) = offsetX + ((point.lon * scaleX - minX) / span).toFloat()
        fun y(point: GeoPoint) = bitmap.height - offsetY - ((point.lat - minY) / span).toFloat()
        val ink = if (dark) Color.rgb(248, 242, 248) else Color.rgb(34, 38, 47)
        val soft = if (dark) Color.rgb(136, 118, 141) else Color.rgb(141, 132, 147)
        val berry = if (dark) Color.rgb(255, 155, 199) else Color.rgb(180, 0, 85)
        val ground = if (dark) Color.rgb(39, 29, 44) else Color.WHITE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (model.line.size >= 2) {
            val path = Path()
            model.line.forEachIndexed { index, point -> if (index == 0) path.moveTo(x(point), y(point)) else path.lineTo(x(point), y(point)) }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.2f * unit; paint.color = soft
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
        }
        paint.style = Paint.Style.FILL
        // Without verified geometry the stops stay unconnected dots.
        model.stops.forEach { stop -> paint.color = ground; canvas.drawCircle(x(stop), y(stop), 3.4f * unit, paint)
            paint.color = soft; canvas.drawCircle(x(stop), y(stop), 2.2f * unit, paint) }
        fun ring(point: GeoPoint?, filled: Boolean) {
            if (point == null || !Geo.valid(point)) return
            paint.style = Paint.Style.FILL; paint.color = ground; canvas.drawCircle(x(point), y(point), 6.5f * unit, paint)
            paint.color = ink
            if (filled) canvas.drawCircle(x(point), y(point), 5f * unit, paint)
            else { paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.2f * unit; canvas.drawCircle(x(point), y(point), 4.2f * unit, paint) }
        }
        ring(model.boarding, filled = false)
        ring(model.exit, filled = true)
        model.position?.takeIf(Geo::valid)?.let { point ->
            paint.style = Paint.Style.FILL; paint.color = ground; canvas.drawCircle(x(point), y(point), 9f * unit, paint)
            paint.color = berry; canvas.drawCircle(x(point), y(point), 7f * unit, paint)
            // A square mark for a bus, a dot for the own position: not told apart by colour alone.
            paint.color = ground
            if (model.positionIsGps) canvas.drawCircle(x(point), y(point), 2.6f * unit, paint)
            else canvas.drawRect(x(point) - 2.8f * unit, y(point) - 2.8f * unit, x(point) + 2.8f * unit, y(point) + 2.8f * unit, paint)
        }
        return bitmap
    }

    /** Title and status line for the widget; ages are written out, never animated. */
    fun texts(state: TrackingState, model: RouteSketchModel?, now: Instant, travelEnabled: Boolean = true, gpsEnabled: Boolean = true): Pair<String, String> {
        val ride = state.ride?.takeUnless { it.demo } ?: return "Buskarte" to "Keine aktive Fahrt. Starte eine Begleitung in Mitfahrt."
        val title = "${ride.departure.line} · ${shortName(ride.displayTarget().name)}"
        val estimate = TravelEstimator.estimate(state, now, gpsEnabled).takeIf { travelEnabled }
        val observed = model?.measuredAt
        val status = listOfNotNull(
            when {
                !state.running -> "Fahrt pausiert"
                model?.position == null -> "Keine aktuelle Position"
                model.positionIsGps -> "Deine Position per GPS"
                else -> "Bus laut NEW, Stand ${clockTime(observed)}"
            },
            estimate?.let { if (ride.boarded) "Fahrzeit ab Position etwa ${it.minutes} Min" else "Ab Position etwa ${it.minutes} Min bis zum Einstieg" },
            "Skizze ohne Straßenkarte".takeIf { model != null && model.line.isEmpty() }).joinToString(" · ")
        return title to status
    }
}

class BusMapWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BusMapWidget.ACTION_EXPIRE) BusMapWidget.update(context, force = true)
        else super.onReceive(context, intent)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = BusMapWidget.update(context, force = true)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        BusMapWidget.update(context, force = true)
}

object BusMapWidget {
    const val ACTION_EXPIRE = "de.luca.mitfahrt.widgets.MAP_EXPIRE"
    private var lastKey: String? = null
    private var lastRender = 0L

    /** Called on tracking changes; redraws at most every 10 s unless the shown facts changed. */
    fun update(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(app, BusMapWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = runCatching { (app as MitfahrtApplication).store.state.value }.getOrNull() ?: TrackingState()
        val now = Instant.now()
        val key = state.ride?.let { "${it.departure.key}/${it.boarded}/${it.gpsPassedIndex}/${state.liveBus.vehicle?.observedAt}/${state.routeShape != null}" }
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (!force && key == lastKey && elapsed - lastRender < 10_000) return
            lastKey = key; lastRender = elapsed
        }
        ids.forEach { id ->
            runCatching { manager.updateAppWidget(id, build(app, state, now, manager.getAppWidgetOptions(id))) }
        }
        val model = RouteSketch.model(state, now)
        val at = model?.measuredAt
        val expires = at?.plusSeconds(if (model.positionIsGps) 46 else 91)?.takeIf { it.isAfter(now) }
        val alarms = app.getSystemService(AlarmManager::class.java)
        val expiry = PendingIntent.getBroadcast(app, 0, Intent(app, BusMapWidgetProvider::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (expires == null) alarms?.cancel(expiry) else alarms?.set(AlarmManager.RTC, expires.toEpochMilli(), expiry)
    }

    internal fun build(app: Context, state: TrackingState, now: Instant, options: Bundle): RemoteViews {
        val store = AppSettingsStore(app)
        val settings = try { store.current() } finally { store.close() }
        val gps = settings.bool("gps_next_stop", true) && settings.string("location_mode") != "off"
        val theme = settings.string("widget_theme").takeUnless { it == "system" } ?: settings.string("theme")
        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val model = RouteSketch.model(state, now, gps)
        var (title, status) = RouteSketch.texts(state, model, now, settings.bool("gps_travel_time", true), gps)
        if (state.ride?.demo == false) {
            val values = TextTemplates.values(state, (app.applicationContext as? MitfahrtApplication)?.companion?.state?.value,
                now, gps, settings.bool("gps_travel_time", true)) + mapOf("quelle" to when {
                    model?.position == null -> "Keine Position"; model.positionIsGps -> "GPS"; else -> "NEW"
                }, "status" to status)
            TextTemplates.custom(settings, "tpl_mapwidget_title", values)?.let { title = it }
            TextTemplates.custom(settings, "tpl_mapwidget_status", values)?.let { status = it }
        }
        val density = app.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceIn(110, 600)
        val heightDp = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180) - 76).coerceIn(60, 500)
        val views = RemoteViews(app.packageName, R.layout.mitfahrt_map_widget)
        views.setInt(R.id.mitfahrt_map_widget_background, "setColorFilter", if (dark) Color.rgb(39, 29, 44) else Color.WHITE)
        views.setInt(R.id.mitfahrt_map_widget_background, "setImageAlpha", settings.int("widget_opacity", 100).coerceIn(20, 100) * 255 / 100)
        views.setTextColor(R.id.mitfahrt_map_widget_title, if (dark) Color.rgb(248, 242, 248) else Color.rgb(34, 38, 47))
        val muted = if (dark) Color.rgb(205, 190, 211) else Color.rgb(95, 91, 105)
        views.setTextColor(R.id.mitfahrt_map_widget_status, muted)
        views.setTextColor(R.id.mitfahrt_map_widget_age, muted)
        views.setTextViewText(R.id.mitfahrt_map_widget_title, title)
        views.setTextViewText(R.id.mitfahrt_map_widget_status, status)
        if (model != null) {
            val scale = minOf(density, 2f)
            views.setImageViewBitmap(R.id.mitfahrt_map_widget_image, RouteSketch.render(model, (widthDp * scale).toInt(), (heightDp * scale).toInt(), dark))
            views.setViewVisibility(R.id.mitfahrt_map_widget_image, android.view.View.VISIBLE)
            views.setContentDescription(R.id.mitfahrt_map_widget_image, "Streckenskizze. $status")
        } else views.setViewVisibility(R.id.mitfahrt_map_widget_image, android.view.View.GONE)
        val measured = model?.measuredAt?.takeIf { model.position != null }
        views.setViewVisibility(R.id.mitfahrt_map_widget_age, if (measured != null) android.view.View.VISIBLE else android.view.View.GONE)
        views.setChronometer(R.id.mitfahrt_map_widget_age, SystemClock.elapsedRealtime() -
            (measured?.let { java.time.Duration.between(it, now).toMillis() } ?: 0L).coerceAtLeast(0), "Positionsstand vor %s", measured != null)
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            views.setOnClickPendingIntent(R.id.mitfahrt_map_widget_root,
                PendingIntent.getActivity(app, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return views
    }
}
