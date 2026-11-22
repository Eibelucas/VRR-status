package de.luca.mitfahrt.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.tan

/** Two map widget framing modes: full route overview (Zoom Out) or street-level bus focus (Zoom In). */
enum class MapWidgetMode {
    OVERVIEW,
    BUS_FOCUS
}

/** Viewport parameters for map framing (default fits the full route cleanly). */
data class MapWidgetViewport(
    val zoomLevel: Int = 0,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val routeIndex: Int = 0
) {
    val clampedZoom: Int get() = zoomLevel.coerceIn(0, MAX_ZOOM)
    val isZoomed: Boolean get() = clampedZoom > 0
    val isDefault: Boolean get() = clampedZoom == 0 && panX == 0f && panY == 0f

    fun scaleFactor(): Float = ZOOM_SCALES[clampedZoom]

    fun zoomLabel(): String = ZOOM_LABELS[clampedZoom]

    fun zoomIn(): MapWidgetViewport = copy(zoomLevel = (clampedZoom + 1).coerceAtMost(MAX_ZOOM))

    fun zoomOut(): MapWidgetViewport {
        val next = (clampedZoom - 1).coerceAtLeast(0)
        return if (next == 0) copy(zoomLevel = 0, panX = 0f, panY = 0f)
        else copy(zoomLevel = next, panX = panX.coerceIn(-1f, 1f), panY = panY.coerceIn(-1f, 1f))
    }

    fun pan(dx: Float, dy: Float): MapWidgetViewport {
        if (clampedZoom == 0) return this
        val step = 0.35f / scaleFactor()
        return copy(
            panX = (panX + dx * step).coerceIn(-1f, 1f),
            panY = (panY + dy * step).coerceIn(-1f, 1f)
        )
    }

    fun resetView(): MapWidgetViewport = copy(zoomLevel = 0, panX = 0f, panY = 0f)

    fun stepRoute(delta: Int, routeCount: Int): MapWidgetViewport {
        if (routeCount <= 0) return copy(routeIndex = 0)
        val next = ((routeIndex + delta) % routeCount + routeCount) % routeCount
        return copy(routeIndex = next, zoomLevel = 0, panX = 0f, panY = 0f)
    }

    companion object {
        const val MAX_ZOOM = 3
        private val ZOOM_SCALES = floatArrayOf(1f, 1.6f, 2.6f, 4.2f)
        private val ZOOM_LABELS = arrayOf("1×", "1.6×", "2.6×", "4.2×")
    }
}

/** Tile coordinate in standard Slippy Map XYZ indexing. */
internal data class TileCoord(val zoom: Int, val x: Int, val y: Int)

/** Synchronous tile reader used by RouteSketch.render (never performs network IO inside render). */
fun interface WidgetTileReader {
    fun readTile(zoom: Int, x: Int, y: Int): Bitmap?
}

/** Reads locally cached OSM tiles from the widget disk cache and osmdroid's SQLite cache. */
internal class OsmCachedTileReader(
    private val cacheDb: File,
    private val widgetTileDir: File? = null
) : WidgetTileReader, AutoCloseable {
    private var db: SQLiteDatabase? = null
    private var opened = false

    private fun open(): SQLiteDatabase? {
        if (opened) return db
        opened = true
        if (!cacheDb.isFile || cacheDb.length() <= 0L) return null
        db = runCatching {
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        }.getOrNull()
        return db
    }

    override fun readTile(zoom: Int, x: Int, y: Int): Bitmap? {
        widgetTileDir?.let { dir ->
            val file = File(dir, "${zoom}_${x}_${y}.png")
            if (file.isFile && file.length() > 0L) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.let { return it }
            }
        }
        val database = open() ?: return null
        val key = tileIndex(zoom, x, y)
        return runCatching {
            database.rawQuery("SELECT tile FROM tiles WHERE key = ? LIMIT 1", arrayOf(key.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    val bytes = cursor.getBlob(0)
                    if (bytes != null && bytes.isNotEmpty()) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                } else null
            }
        }.getOrNull()
    }

    override fun close() {
        runCatching { db?.close() }
        db = null
    }

    companion object {
        fun widgetTileDir(context: Context): File = File(context.cacheDir, "widget-tiles")

        fun forContext(context: Context): OsmCachedTileReader =
            OsmCachedTileReader(
                cacheDb = File(context.cacheDir, "waiting-map/tiles/cache.db"),
                widgetTileDir = widgetTileDir(context)
            )

        /** Standard osmdroid MapTileIndex key format. */
        fun tileIndex(zoom: Int, x: Int, y: Int): Long =
            (((zoom.toLong() shl zoom) + x.toLong()) shl zoom) + y.toLong()
    }
}

/**
 * Fetches missing OSM tiles for the visible widget viewport on a single background thread
 * (strictly capped, cached on disk, proper User-Agent) so the map never has blank gaps.
 */
internal object WidgetTilePrefetcher {
    private const val MAX_TILES_PER_BATCH = 16
    private const val MAX_CACHE_FILES = 220
    private const val RETRY_COOLDOWN_MS = 30_000L
    private val recentAttempts = ConcurrentHashMap<TileCoord, Long>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mitfahrt-widget-tiles").apply { isDaemon = true }
    }

    /** Computes distinct OSM tile coordinates along a verified route corridor (capped at 16 tiles). */
    fun corridorTiles(points: List<GeoPoint>, zoom: Int = 15): List<TileCoord> {
        if (points.isEmpty()) return emptyList()
        val z = zoom.coerceIn(13, 16)
        val n = 1 shl z
        return points.asSequence()
            .filter(Geo::valid)
            .map { pt ->
                val x = floor(((pt.lon + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
                val latRad = Math.toRadians(pt.lat.coerceIn(-85.05112878, 85.05112878))
                val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
                TileCoord(z, x, y)
            }
            .distinct()
            .take(MAX_TILES_PER_BATCH)
            .toList()
    }

    /** One-time background corridor tile prefetch when a verified RouteShape arrives. */
    fun prefetchCorridor(context: Context, points: List<GeoPoint>, zoom: Int = 15, onLoaded: () -> Unit = {}) {
        val tiles = corridorTiles(points, zoom)
        if (tiles.isNotEmpty()) prefetch(context, tiles, onLoaded)
    }

    fun prefetch(context: Context, missing: List<TileCoord>, onLoaded: () -> Unit) {
        if (missing.isEmpty()) return
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val toFetch = missing.distinct()
            .filter { coord ->
                val prev = recentAttempts[coord]
                prev == null || now - prev >= RETRY_COOLDOWN_MS
            }
            .take(MAX_TILES_PER_BATCH)
        if (toFetch.isEmpty()) return
        toFetch.forEach { recentAttempts[it] = now }

        executor.execute {
            val dir = OsmCachedTileReader.widgetTileDir(app).apply { mkdirs() }
            var downloaded = 0
            val userAgent = "Mitfahrt/${BuildConfig.VERSION_NAME} (https://github.com/Eibelucas/VRR-status)"
            for (coord in toFetch) {
                val target = File(dir, "${coord.zoom}_${coord.x}_${coord.y}.png")
                if (target.isFile && target.length() > 0L) continue
                val ok = runCatching {
                    val conn = (URL("https://tile.openstreetmap.org/${coord.zoom}/${coord.x}/${coord.y}.png")
                        .openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5_000
                        readTimeout = 5_000
                        setRequestProperty("User-Agent", userAgent)
                        setRequestProperty("Accept", "image/png,image/*;q=0.8")
                    }
                    try {
                        if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching false
                        val bytes = conn.inputStream.use { it.readBytes() }
                        if (bytes.size < 64) return@runCatching false
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching false
                        val tmp = File(dir, "${coord.zoom}_${coord.x}_${coord.y}.tmp")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(target)
                    } finally {
                        conn.disconnect()
                    }
                }.getOrDefault(false)
                if (ok) downloaded++
            }
            pruneCache(dir)
            if (downloaded > 0) onLoaded()
        }
    }

    private fun pruneCache(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHE_FILES)
            .forEach { runCatching { it.delete() } }
    }
}

/** What the map shows. Only measured positions and verified line geometry; nothing is invented. */
data class RouteSketchModel(
    /** Real line between boarding and exit, empty if no verified geometry exists. */
    val line: List<GeoPoint>,
    val stops: List<GeoPoint>,
    val boarding: GeoPoint?,
    val exit: GeoPoint?,
    /** Position of the assigned bus (NEW) or, on board without one, the phone's confirmed GPS position. */
    val position: GeoPoint?,
    val positionIsGps: Boolean,
    val measuredAt: Instant? = null,
    val boardingName: String? = null,
    val exitName: String? = null,
    val isRoutePreview: Boolean = false,
    /** Framing anchor points when showing a city area without an active ride. */
    val frameBounds: List<GeoPoint> = emptyList()
)

internal data class MapRenderResult(
    val bitmap: Bitmap,
    val missingTiles: List<TileCoord>,
    val drewTiles: Boolean
)

/** Draws a full-bleed map with verified route geometry, endpoints and live bus/GPS position. */
object RouteSketch {
    /** Default Mönchengladbach framing bounds for an idle widget without saved routes. */
    private val idleAreaBounds = listOf(
        GeoPoint(51.182, 6.416),
        GeoPoint(51.209, 6.468)
    )

    fun model(state: TrackingState, now: Instant, gpsEnabled: Boolean = true): RouteSketchModel? {
        val ride = state.ride?.takeUnless { it.demo } ?: return null
        val key = ride.departure.key
        val goal = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val shape = state.routeShape?.takeIf { it.journeyKey == key }
        val from = shape?.offsetOf(ride.boardingIndex)
        val to = shape?.offsetOf(goal)
        val line = if (shape != null && from != null && to != null) shape.line.slice(from, to) else emptyList()
        val live = state.liveBus
        val bus = live.vehicle?.takeIf {
            state.running && live.journeyKey == key && it.isFresh(now) &&
                (live.selectedVehicleId == it.id || live.confirmed || live.auto.holdsVehicle)
        }
        val gps = state.gpsProgress?.takeIf {
            gpsEnabled && state.running && ride.boarded && it.journeyKey == key && GpsStopProgress.confident(it, now)
        }
        val stops = (ride.boardingIndex..goal).mapNotNull { ride.stops[it].point }.filter(Geo::valid)
        if (stops.isEmpty() && line.isEmpty()) return null
        val boardingStop = ride.stops[ride.boardingIndex]
        val exitStop = ride.stops[goal]
        return RouteSketchModel(
            line = line,
            stops = stops,
            boarding = boardingStop.point,
            exit = exitStop.point,
            position = (live.routePoint?.takeIf { bus != null && !live.offRoute } ?: bus?.point) ?: gps?.confirmedPoint,
            positionIsGps = bus == null && gps?.confirmedPoint != null,
            measuredAt = bus?.observedAt ?: gps?.confirmedAt,
            boardingName = shortName(boardingStop.name),
            exitName = shortName(exitStop.name),
            isRoutePreview = false
        )
    }

    /** Builds an honest stop-pin preview for a saved favorite route (respecting active temporary destinations). */
    fun routePreviewModel(plan: RoutePlan, today: LocalDate): RouteSketchModel? {
        val effectiveDest = plan.effectiveDestination(today)
        val originPoint = plan.origin.point?.takeIf(Geo::valid)
        val destPoint = effectiveDest.point?.takeIf(Geo::valid)
        val stops = listOfNotNull(originPoint, destPoint)
        if (stops.isEmpty()) return null
        return RouteSketchModel(
            line = emptyList(),
            stops = stops,
            boarding = originPoint,
            exit = destPoint,
            position = null,
            positionIsGps = false,
            measuredAt = null,
            boardingName = shortName(plan.origin.name),
            exitName = shortName(effectiveDest.name),
            isRoutePreview = true
        )
    }

    /** Neutral Mönchengladbach map framing when idle with no saved route coordinates. */
    internal fun idleMapModel(): RouteSketchModel = RouteSketchModel(
        line = emptyList(),
        stops = emptyList(),
        boarding = null,
        exit = null,
        position = null,
        positionIsGps = false,
        frameBounds = idleAreaBounds
    )

    private fun mercatorX(lon: Double): Double = ((lon + 180.0) / 360.0).coerceIn(0.0, 1.0)

    private fun mercatorY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val rad = Math.toRadians(clamped)
        return ((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0).coerceIn(0.0, 1.0)
    }

    @JvmOverloads
    fun render(
        model: RouteSketchModel,
        width: Int,
        height: Int,
        dark: Boolean,
        viewport: MapWidgetViewport = MapWidgetViewport(),
        tileReader: WidgetTileReader? = null,
        cornerRadiusPx: Float? = null,
        mode: MapWidgetMode = MapWidgetMode.OVERVIEW,
        fullBleed: Boolean = false
    ): Bitmap = renderDetailed(model, width, height, dark, viewport, tileReader, cornerRadiusPx, mode, fullBleed).bitmap

    internal fun renderDetailed(
        model: RouteSketchModel,
        width: Int,
        height: Int,
        dark: Boolean,
        viewport: MapWidgetViewport = MapWidgetViewport(),
        tileReader: WidgetTileReader? = null,
        cornerRadiusPx: Float? = null,
        mode: MapWidgetMode = MapWidgetMode.OVERVIEW,
        fullBleed: Boolean = false
    ): MapRenderResult {
        val w = width.coerceIn(64, 1200)
        val h = height.coerceIn(64, 1200)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val all = (model.line + model.stops + listOfNotNull(model.position) + model.frameBounds).filter(Geo::valid)
        if (all.isEmpty()) return MapRenderResult(bitmap, emptyList(), false)

        val unit = minOf(w / 180f, h / 110f).coerceAtLeast(0.75f)
        val radius = (cornerRadiusPx ?: (18f * unit)).coerceIn(0f, minOf(w, h) * 0.25f)
        val clipPath = Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // Pure neutral slate/paper base: zero purple cast.
        val surfaceColor = if (dark) Color.rgb(26, 29, 35) else Color.rgb(242, 243, 245)
        val borderColor = if (dark) Color.rgb(52, 57, 68) else Color.rgb(214, 217, 224)
        val ink = if (dark) Color.rgb(244, 245, 248) else Color.rgb(26, 30, 38)
        val soft = if (dark) Color.rgb(168, 174, 186) else Color.rgb(98, 104, 116)
        val berry = if (dark) Color.rgb(255, 92, 168) else Color.rgb(180, 0, 85)
        val casing = if (dark) Color.rgb(20, 22, 27) else Color.WHITE
        val pillBg = if (dark) Color.argb(228, 26, 29, 35) else Color.argb(238, 255, 255, 255)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(surfaceColor)

        // Asymmetric padding leaves clean breathing room for widget chips; fullBleed centers directly for watch/lockscreen.
        val hasOverlayChrome = !fullBleed && model.frameBounds.isEmpty()
        val padX = if (fullBleed) (12f * unit).coerceAtMost(w * 0.10f) else (22f * unit).coerceAtMost(w * 0.18f)
        val padTop = if (hasOverlayChrome) (44f * unit).coerceAtMost(h * 0.28f) else (12f * unit).coerceAtMost(h * 0.12f)
        val padBottom = if (hasOverlayChrome) (36f * unit).coerceAtMost(h * 0.24f) else (12f * unit).coerceAtMost(h * 0.12f)

        val xs = all.map { mercatorX(it.lon) }
        val ys = all.map { mercatorY(it.lat) }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()

        val rawSpanX = maxOf(maxX - minX, 0.00022)
        val rawSpanY = maxOf(maxY - minY, 0.00022)
        val usableW = (w - 2f * padX).coerceAtLeast(24f)
        val usableH = (h - padTop - padBottom).coerceAtLeast(24f)
        val baseWorldPerPx = maxOf(rawSpanX / usableW, rawSpanY / usableH)

        val focusAnchor = if (mode == MapWidgetMode.BUS_FOCUS) {
            model.position?.takeIf(Geo::valid)
                ?: model.boarding?.takeIf(Geo::valid)
                ?: model.stops.firstOrNull()?.takeIf(Geo::valid)
        } else null

        val worldPerPx: Double
        val centerX: Double
        val centerY: Double
        if (focusAnchor != null) {
            // Street-level zoom (~650 m across shorter usable axis, OSM zoom 15-16) locked onto the bus.
            val latRad = Math.toRadians(focusAnchor.lat.coerceIn(-85.0, 85.0))
            val metersPerWorld = 40_075_016.686 * cos(latRad)
            val focusWorldPerPx = (650.0 / metersPerWorld) / minOf(usableW, usableH)
            worldPerPx = minOf(baseWorldPerPx, focusWorldPerPx)
            val centerOffsetY = ((padTop - padBottom) / 2.0) * worldPerPx
            centerX = mercatorX(focusAnchor.lon).coerceIn(0.0, 1.0)
            centerY = (mercatorY(focusAnchor.lat) - centerOffsetY).coerceIn(0.0, 1.0)
        } else {
            val scale = viewport.scaleFactor().toDouble()
            worldPerPx = baseWorldPerPx / scale
            val centerOffsetY = ((padTop - padBottom) / 2.0) * worldPerPx
            val baseCenterX = (minX + maxX) / 2.0
            val baseCenterY = (minY + maxY) / 2.0 - centerOffsetY
            val baseViewW = baseWorldPerPx * w
            val baseViewH = baseWorldPerPx * h
            centerX = (baseCenterX + viewport.panX * baseViewW * 0.45).coerceIn(0.0, 1.0)
            centerY = (baseCenterY + viewport.panY * baseViewH * 0.45).coerceIn(0.0, 1.0)
        }

        fun px(point: GeoPoint): Float = w / 2f + ((mercatorX(point.lon) - centerX) / worldPerPx).toFloat()
        fun py(point: GeoPoint): Float = h / 2f + ((mercatorY(point.lat) - centerY) / worldPerPx).toFloat()

        val tileResult = tileReader?.let { reader ->
            drawTilesWithFallback(canvas, reader, w, h, centerX, centerY, worldPerPx, dark)
        } ?: TileDrawResult(drewTiles = false, missingTiles = emptyList())

        // Verified route geometry line (only when real VRR polyline exists).
        if (model.line.size >= 2) {
            val path = Path()
            model.line.forEachIndexed { index, point ->
                val x = px(point)
                val y = py(point)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = 7.0f * unit
            paint.color = casing
            canvas.drawPath(path, paint)
            paint.strokeWidth = 4.2f * unit
            paint.color = berry
            canvas.drawPath(path, paint)
        }

        // Intermediate stops: decluttered along verified geometry so dots never turn the line into a worm.
        val boardingPt = model.boarding?.takeIf(Geo::valid)
        val exitPt = model.exit?.takeIf(Geo::valid)
        val boardingPx = boardingPt?.let { px(it) to py(it) }
        val exitPx = exitPt?.let { px(it) to py(it) }
        val minStopSpacing = if (model.line.size >= 2) 18f * unit else 0f
        var lastStopX = Float.NaN
        var lastStopY = Float.NaN
        paint.style = Paint.Style.FILL
        model.stops.forEach { stop ->
            val sx = px(stop)
            val sy = py(stop)
            if (minStopSpacing > 0f) {
                val nearBoarding = boardingPx != null && hypot((sx - boardingPx.first).toDouble(), (sy - boardingPx.second).toDouble()) < minStopSpacing
                val nearExit = exitPx != null && hypot((sx - exitPx.first).toDouble(), (sy - exitPx.second).toDouble()) < minStopSpacing
                val nearPrev = !lastStopX.isNaN() && hypot((sx - lastStopX).toDouble(), (sy - lastStopY).toDouble()) < minStopSpacing
                if (nearBoarding || nearExit || nearPrev) return@forEach
            }
            lastStopX = sx
            lastStopY = sy
            paint.color = casing
            canvas.drawCircle(sx, sy, 3.0f * unit, paint)
            paint.color = if (model.line.size >= 2) berry else soft
            canvas.drawCircle(sx, sy, 1.8f * unit, paint)
        }

        fun drawEndpoint(point: GeoPoint?, isExit: Boolean) {
            if (point == null || !Geo.valid(point)) return
            val ex = px(point)
            val ey = py(point)
            paint.style = Paint.Style.FILL
            paint.color = casing
            canvas.drawCircle(ex, ey, 6.8f * unit, paint)
            if (isExit) {
                paint.color = berry
                canvas.drawCircle(ex, ey, 4.9f * unit, paint)
                paint.color = casing
                canvas.drawCircle(ex, ey, 1.8f * unit, paint)
            } else {
                paint.color = ink
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.4f * unit
                canvas.drawCircle(ex, ey, 4.0f * unit, paint)
            }
        }
        drawEndpoint(boardingPt, isExit = false)
        drawEndpoint(exitPt, isExit = true)

        fun inFrame(pt: Pair<Float, Float>): Boolean =
            pt.first in 8f..(w - 8f) && pt.second in 8f..(h - 8f)

        // Subtle stop labels only when the endpoint is actually visible in the frame and clear of floating chips.
        if (w >= 260 && h >= 140) {
            val tooClose = exitPx != null && boardingPx != null &&
                hypot((exitPx.first - boardingPx.first).toDouble(), (exitPx.second - boardingPx.second).toDouble()) < 56f * unit
            if (exitPx != null && inFrame(exitPx) && !model.exitName.isNullOrBlank()) {
                val above = exitPx.second > padTop + 16f * unit
                val ly = if (above) exitPx.second - 13f * unit else exitPx.second + 15f * unit
                drawStopLabel(canvas, model.exitName, exitPx.first, ly, w, h, padTop, padBottom, unit, pillBg, ink, borderColor)
            }
            if (!tooClose && boardingPx != null && inFrame(boardingPx) && !model.boardingName.isNullOrBlank()) {
                val below = boardingPx.second < h - padBottom - 16f * unit
                val ly = if (below) boardingPx.second + 15f * unit else boardingPx.second - 13f * unit
                drawStopLabel(canvas, model.boardingName, boardingPx.first, ly, w, h, padTop, padBottom, unit, pillBg, ink, borderColor)
            }
        }

        // Live bus (white square inside berry beacon) or GPS position (white dot inside berry beacon).
        model.position?.takeIf(Geo::valid)?.let { point ->
            val bx = px(point)
            val by = py(point)
            paint.style = Paint.Style.FILL
            if (mode == MapWidgetMode.BUS_FOCUS) {
                paint.color = Color.argb(if (dark) 72 else 52, Color.red(berry), Color.green(berry), Color.blue(berry))
                canvas.drawCircle(bx, by, 17f * unit, paint)
            }
            paint.color = casing
            canvas.drawCircle(bx, by, 9.6f * unit, paint)
            paint.color = berry
            canvas.drawCircle(bx, by, 7.4f * unit, paint)
            paint.color = Color.WHITE
            if (model.positionIsGps) {
                canvas.drawCircle(bx, by, 2.8f * unit, paint)
            } else {
                canvas.drawRect(bx - 2.9f * unit, by - 2.9f * unit, bx + 2.9f * unit, by + 2.9f * unit, paint)
            }
        }

        // Honest OpenStreetMap credit (bottom-right) only when OSM tiles were drawn.
        if (tileResult.drewTiles && w >= 160) {
            drawCornerCredit(canvas, "© OSM", w, h, unit, pillBg, soft)
        }

        canvas.restore()
        return MapRenderResult(bitmap, tileResult.missingTiles, tileResult.drewTiles)
    }

    private data class TileDrawResult(val drewTiles: Boolean, val missingTiles: List<TileCoord>)

    private fun drawTilesWithFallback(
        canvas: Canvas,
        reader: WidgetTileReader,
        w: Int,
        h: Int,
        centerX: Double,
        centerY: Double,
        worldPerPx: Double,
        dark: Boolean
    ): TileDrawResult {
        var zoom = floor(log2(1.0 / (worldPerPx * 256.0))).toInt().coerceIn(11, 17)
        val leftWorld = centerX - (w / 2.0) * worldPerPx
        val rightWorld = centerX + (w / 2.0) * worldPerPx
        val topWorld = centerY - (h / 2.0) * worldPerPx
        val bottomWorld = centerY + (h / 2.0) * worldPerPx

        // Cap visible grid to <= 12 tiles so tile fetches stay fast and OSM-friendly.
        while (zoom > 11) {
            val n = 1 shl zoom
            val count = (floor(rightWorld * n).toInt().coerceIn(0, n - 1) - floor(leftWorld * n).toInt().coerceIn(0, n - 1) + 1) *
                (floor(bottomWorld * n).toInt().coerceIn(0, n - 1) - floor(topWorld * n).toInt().coerceIn(0, n - 1) + 1)
            if (count <= 12) break
            zoom--
        }

        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (dark) {
                // Luminance-based neutral dark graphite inversion: removes the purple/cyan cast of raw RGB inversion.
                val lR = 0.299f
                val lG = 0.587f
                val lB = 0.114f
                val k = -0.82f
                val b = 224f
                val matrix = ColorMatrix(floatArrayOf(
                    lR * k, lG * k, lB * k, 0f, b - 4f,
                    lR * k, lG * k, lB * k, 0f, b - 1f,
                    lR * k, lG * k, lB * k, 0f, b + 5f,
                    0f,     0f,     0f,     1f, 0f
                ))
                colorFilter = ColorMatrixColorFilter(matrix)
                alpha = 255
            } else {
                // Slightly muted light cartography so the berry route pops cleanly without clashing.
                val sat = ColorMatrix().apply { setSaturation(0.75f) }
                colorFilter = ColorMatrixColorFilter(sat)
                alpha = 255
            }
        }

        val n = 1 shl zoom
        val minTx = floor(leftWorld * n).toInt().coerceIn(0, n - 1)
        val maxTx = floor(rightWorld * n).toInt().coerceIn(0, n - 1)
        val minTy = floor(topWorld * n).toInt().coerceIn(0, n - 1)
        val maxTy = floor(bottomWorld * n).toInt().coerceIn(0, n - 1)

        var drawn = 0
        val missing = mutableListOf<TileCoord>()

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val x0 = w / 2f + ((tx.toDouble() / n - centerX) / worldPerPx).toFloat()
                val y0 = h / 2f + ((ty.toDouble() / n - centerY) / worldPerPx).toFloat()
                val x1 = w / 2f + (((tx + 1).toDouble() / n - centerX) / worldPerPx).toFloat()
                val y1 = h / 2f + (((ty + 1).toDouble() / n - centerY) / worldPerPx).toFloat()
                // Overlap by 0.5px to prevent sub-pixel hairline seams between tiles.
                val dst = RectF(x0 - 0.5f, y0 - 0.5f, x1 + 0.5f, y1 + 0.5f)

                val exact = reader.readTile(zoom, tx, ty)
                if (exact != null) {
                    canvas.drawBitmap(exact, null, dst, tilePaint)
                    exact.recycle()
                    drawn++
                    continue
                }
                missing += TileCoord(zoom, tx, ty)

                // Per-tile parent fallback: upscale the exact quadrant from a coarser cached tile if present.
                var fallbackDrawn = false
                for (delta in 1..3) {
                    val pz = zoom - delta
                    if (pz < 10) break
                    val factor = 1 shl delta
                    val ptx = tx shr delta
                    val pty = ty shr delta
                    val parent = reader.readTile(pz, ptx, pty) ?: continue
                    val subX = tx - (ptx shl delta)
                    val subY = ty - (pty shl delta)
                    val cellW = parent.width / factor
                    val cellH = parent.height / factor
                    val src = Rect(subX * cellW, subY * cellH, (subX + 1) * cellW, (subY + 1) * cellH)
                    canvas.drawBitmap(parent, src, dst, tilePaint)
                    parent.recycle()
                    drawn++
                    fallbackDrawn = true
                    break
                }
                if (!fallbackDrawn && zoom < 17) {
                    // Check one zoom deeper if the user viewed the app zoomed in closely.
                    val cz = zoom + 1
                    for (dx in 0..1) for (dy in 0..1) {
                        val child = reader.readTile(cz, tx * 2 + dx, ty * 2 + dy) ?: continue
                        val midX = (x0 + x1) / 2f
                        val midY = (y0 + y1) / 2f
                        val subDst = RectF(
                            (if (dx == 0) x0 else midX) - 0.5f,
                            (if (dy == 0) y0 else midY) - 0.5f,
                            (if (dx == 0) midX else x1) + 0.5f,
                            (if (dy == 0) midY else y1) + 0.5f
                        )
                        canvas.drawBitmap(child, null, subDst, tilePaint)
                        child.recycle()
                        drawn++
                    }
                }
            }
        }
        return TileDrawResult(drewTiles = drawn > 0, missingTiles = missing)
    }

    private fun drawStopLabel(
        canvas: Canvas,
        rawLabel: String,
        cx: Float,
        cy: Float,
        w: Int,
        h: Int,
        padTop: Float,
        padBottom: Float,
        unit: Float,
        bg: Int,
        fg: Int,
        border: Int
    ) {
        val label = if (rawLabel.length > 18) rawLabel.take(17).trimEnd() + "…" else rawLabel
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = (7.2f * unit).coerceIn(11f, 22f)
            isFakeBoldText = true
        }
        val tw = textPaint.measureText(label)
        val padH = 5f * unit
        val padV = 2.6f * unit
        val pillW = tw + 2 * padH
        val fm = textPaint.fontMetrics
        val pillH = (fm.descent - fm.ascent) + 2 * padV
        val marginX = 8f * unit
        val minY = (padTop - 8f * unit).coerceAtLeast(6f * unit)
        val maxY = (h - padBottom - pillH + 6f * unit).coerceAtLeast(minY)
        val left = (cx - pillW / 2f).coerceIn(marginX, (w - pillW - marginX).coerceAtLeast(marginX))
        val top = (cy - pillH / 2f).coerceIn(minY, maxY)
        val rect = RectF(left, top, left + pillW, top + pillH)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = bg }
        val r = 5f * unit
        canvas.drawRoundRect(rect, r, r, boxPaint)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = maxOf(1f, 0.7f * unit)
        boxPaint.color = border
        canvas.drawRoundRect(rect, r, r, boxPaint)
        canvas.drawText(label, left + padH, top + padV - fm.ascent, textPaint)
    }

    private fun drawCornerCredit(
        canvas: Canvas,
        label: String,
        w: Int,
        h: Int,
        unit: Float,
        bg: Int,
        fg: Int
    ) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = (6.0f * unit).coerceIn(10f, 18f)
        }
        val tw = textPaint.measureText(label)
        val padH = 4f * unit
        val padV = 2f * unit
        val pillW = tw + 2 * padH
        val fm = textPaint.fontMetrics
        val pillH = (fm.descent - fm.ascent) + 2 * padV
        val margin = 8f * unit
        val x = (w - pillW - margin).coerceAtLeast(margin)
        val y = (h - pillH - margin).coerceAtLeast(margin)
        val rect = RectF(x, y, x + pillW, y + pillH)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = bg }
        canvas.drawRoundRect(rect, 4f * unit, 4f * unit, boxPaint)
        canvas.drawText(label, x + padH, y + padV - fm.ascent, textPaint)
    }

    /** Title and status line for the widget; ages are written out, never animated. */
    fun texts(state: TrackingState, model: RouteSketchModel?, now: Instant, travelEnabled: Boolean = true, gpsEnabled: Boolean = true): Pair<String, String> {
        val ride = state.ride?.takeUnless { it.demo } ?: return "Buskarte" to "Keine aktive Fahrt · Tippen für Buskarte"
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
            "Skizze ohne Straßenkarte".takeIf { model != null && model.line.isEmpty() }
        ).joinToString(" · ")
        return title to status
    }
}

class BusMapWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BusMapWidget.ACTION_EXPIRE -> BusMapWidget.update(context, force = true)
            BusMapWidget.ACTION_TOGGLE_MODE -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    BusMapWidget.toggleMode(context, id)
                }
                BusMapWidget.update(context, force = true)
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) =
        BusMapWidget.update(context, force = true)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        BusMapWidget.update(context, force = true)

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { BusMapWidget.clearMode(context, it) }
        super.onDeleted(context, appWidgetIds)
    }
}

object BusMapWidget {
    const val ACTION_EXPIRE = "de.luca.mitfahrt.widgets.MAP_EXPIRE"
    const val ACTION_TOGGLE_MODE = "de.luca.mitfahrt.widgets.MAP_TOGGLE_MODE"
    private const val MODE_PREFS = "mitfahrt_map_widget_mode"

    private var lastKey: String? = null
    private var lastRender = 0L

    fun mode(context: Context, widgetId: Int): MapWidgetMode {
        val raw = context.applicationContext
            .getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .getString("mode_$widgetId", null)
        return if (raw == MapWidgetMode.BUS_FOCUS.name) MapWidgetMode.BUS_FOCUS else MapWidgetMode.OVERVIEW
    }

    fun setMode(context: Context, widgetId: Int, mode: MapWidgetMode) {
        context.applicationContext
            .getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("mode_$widgetId", mode.name)
            .apply()
    }

    fun toggleMode(context: Context, widgetId: Int): MapWidgetMode {
        val next = if (mode(context, widgetId) == MapWidgetMode.OVERVIEW) {
            MapWidgetMode.BUS_FOCUS
        } else {
            MapWidgetMode.OVERVIEW
        }
        setMode(context, widgetId, next)
        return next
    }

    fun clearMode(context: Context, widgetId: Int) {
        context.applicationContext
            .getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("mode_$widgetId")
            .apply()
    }

    private fun loadEnabledRoutes(context: Context): List<RoutePlan> {
        val app = context.applicationContext as? MitfahrtApplication
        val plans = app?.routePlans?.current() ?: RoutePlansStore(context.applicationContext).current()
        return plans.routes.filter { it.enabled }.sortedBy { it.order }
    }

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
            lastKey = key
            lastRender = elapsed
        }
        val missingTiles = mutableListOf<TileCoord>()
        ids.forEach { id ->
            runCatching {
                val (views, missing) = buildWithMissing(app, state, now, manager.getAppWidgetOptions(id), id)
                manager.updateAppWidget(id, views)
                missingTiles += missing
            }
        }
        if (missingTiles.isNotEmpty()) {
            WidgetTilePrefetcher.prefetch(app, missingTiles) { update(app, force = true) }
        }
        val model = RouteSketch.model(state, now)
        val at = model?.measuredAt
        val expires = at?.plusSeconds(if (model.positionIsGps) 46 else 91)?.takeIf { it.isAfter(now) }
        val alarms = app.getSystemService(AlarmManager::class.java)
        val expiry = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, BusMapWidgetProvider::class.java).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (expires == null) alarms?.cancel(expiry) else alarms?.set(AlarmManager.RTC, expires.toEpochMilli(), expiry)
    }

    @JvmOverloads
    internal fun build(app: Context, state: TrackingState, now: Instant, options: Bundle, widgetId: Int = 0): RemoteViews =
        buildWithMissing(app, state, now, options, widgetId).first

    private fun buildWithMissing(
        app: Context,
        state: TrackingState,
        now: Instant,
        options: Bundle,
        widgetId: Int
    ): Pair<RemoteViews, List<TileCoord>> {
        val sharedStore = (app.applicationContext as? MitfahrtApplication)?.settingsStore
        val settings = if (sharedStore != null) sharedStore.current() else {
            val local = AppSettingsStore(app)
            try { local.current() } finally { local.close() }
        }
        val gps = settings.bool("gps_next_stop", true) && settings.string("location_mode") != "off"
        val theme = settings.string("widget_theme").takeUnless { it == "system" } ?: settings.string("theme")
        val dark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val today = now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
        val ride = state.ride?.takeUnless { it.demo }
        val rideModel = RouteSketch.model(state, now, gps)
        val selectedRoute = if (rideModel == null) loadEnabledRoutes(app).firstOrNull() else null
        val previewModel = if (rideModel == null && selectedRoute != null) RouteSketch.routePreviewModel(selectedRoute, today) else null
        val mapModel = rideModel ?: previewModel ?: RouteSketch.idleMapModel()
        val activeMode = if (rideModel != null) mode(app, widgetId) else MapWidgetMode.OVERVIEW

        var lineBadge: String? = null
        var (title, status) = if (rideModel != null && ride != null) {
            lineBadge = ride.departure.line
            val (_, s) = RouteSketch.texts(state, rideModel, now, settings.bool("gps_travel_time", true), gps)
            shortName(ride.displayTarget().name) to s
        } else if (selectedRoute != null) {
            val activeTemp = selectedRoute.activeTemporaryDestination(today)
            val dest = selectedRoute.effectiveDestination(today)
            val tempNote = if (activeTemp != null) " · Zeitweises Ziel" else ""
            selectedRoute.name to "${shortName(selectedRoute.origin.name)} → ${shortName(dest.name)}$tempNote"
        } else {
            RouteSketch.texts(state, null, now, settings.bool("gps_travel_time", true), gps)
        }

        if (ride != null) {
            val values = TextTemplates.values(
                state,
                (app.applicationContext as? MitfahrtApplication)?.companion?.state?.value,
                now,
                gps,
                settings.bool("gps_travel_time", true)
            ) + mapOf(
                "quelle" to when {
                    rideModel?.position == null -> "Keine Position"
                    rideModel.positionIsGps -> "GPS"
                    else -> "NEW"
                },
                "status" to status
            )
            TextTemplates.custom(settings, "tpl_mapwidget_title", values)?.let {
                title = it
                lineBadge = null
            }
            TextTemplates.custom(settings, "tpl_mapwidget_status", values)?.let { status = it }
        }

        val density = app.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280).coerceIn(110, 600)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).coerceIn(64, 520)
        // Small lock-screen or 2x1 slots: no map bitmap, just crisp line/target and status text.
        val compact = heightDp < 100 || widthDp < 140 ||
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1) == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD && heightDp < 150

        val views = RemoteViews(app.packageName, R.layout.mitfahrt_map_widget)
        val textColor = if (dark) Color.rgb(244, 245, 248) else Color.rgb(26, 30, 38)
        val muted = if (dark) Color.rgb(186, 192, 204) else Color.rgb(88, 94, 106)
        val chipBg = if (dark) R.drawable.mitfahrt_map_chip_dark else R.drawable.mitfahrt_map_chip_light

        views.setInt(R.id.mitfahrt_map_widget_background, "setColorFilter", if (dark) Color.rgb(26, 29, 35) else Color.rgb(242, 243, 245))
        views.setInt(R.id.mitfahrt_map_widget_background, "setImageAlpha", settings.int("widget_opacity", 100).coerceIn(20, 100) * 255 / 100)
        views.setInt(R.id.mitfahrt_map_widget_header_chip, "setBackgroundResource", chipBg)
        views.setInt(R.id.mitfahrt_map_widget_status_chip, "setBackgroundResource", chipBg)

        if (!lineBadge.isNullOrBlank()) {
            views.setViewVisibility(R.id.mitfahrt_map_widget_line, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_map_widget_line, lineBadge)
        } else {
            views.setViewVisibility(R.id.mitfahrt_map_widget_line, View.GONE)
        }

        views.setTextColor(R.id.mitfahrt_map_widget_title, textColor)
        views.setTextColor(R.id.mitfahrt_map_widget_status, muted)
        views.setTextColor(R.id.mitfahrt_map_widget_age, muted)
        views.setTextViewText(R.id.mitfahrt_map_widget_title, title)
        views.setTextViewText(R.id.mitfahrt_map_widget_status, status)
        views.setTextViewTextSize(R.id.mitfahrt_map_widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 11f)

        // Single clean 2-mode toggle chip during active rides: Zoom Out (Strecke) <-> Zoom In (Bus-Fokus).
        if (rideModel != null && !compact) {
            views.setViewVisibility(R.id.mitfahrt_map_widget_mode_chip, View.VISIBLE)
            val focused = activeMode == MapWidgetMode.BUS_FOCUS
            views.setInt(
                R.id.mitfahrt_map_widget_mode_chip,
                "setBackgroundResource",
                if (focused) R.drawable.mitfahrt_map_chip_active else chipBg
            )
            views.setImageViewResource(
                R.id.mitfahrt_map_widget_mode_icon,
                if (focused) R.drawable.mitfahrt_widget_ic_overview else R.drawable.mitfahrt_widget_ic_focus
            )
            val modeFg = if (focused) Color.WHITE else textColor
            views.setInt(R.id.mitfahrt_map_widget_mode_icon, "setColorFilter", modeFg)
            views.setTextColor(R.id.mitfahrt_map_widget_mode_label, modeFg)
            views.setTextViewText(R.id.mitfahrt_map_widget_mode_label, if (focused) "Strecke" else "Bus")
            views.setContentDescription(
                R.id.mitfahrt_map_widget_mode_chip,
                if (focused) "Ganze Strecke zeigen" else "Auf Bus fokussieren"
            )
            val toggleIntent = Intent(app, BusMapWidgetProvider::class.java)
                .setAction(ACTION_TOGGLE_MODE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            val togglePending = PendingIntent.getBroadcast(
                app,
                widgetId * 31 + 23,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.mitfahrt_map_widget_mode_chip, togglePending)
        } else {
            views.setViewVisibility(R.id.mitfahrt_map_widget_mode_chip, View.GONE)
        }

        var missingTiles: List<TileCoord> = emptyList()
        if (!compact) {
            val scale = minOf(density, 2f).coerceAtLeast(1.5f)
            val result = OsmCachedTileReader.forContext(app).use { reader ->
                RouteSketch.renderDetailed(
                    model = mapModel,
                    width = (widthDp * scale).toInt(),
                    height = (heightDp * scale).toInt(),
                    dark = dark,
                    tileReader = reader,
                    cornerRadiusPx = 24f * scale,
                    mode = activeMode
                )
            }
            missingTiles = result.missingTiles
            views.setImageViewBitmap(R.id.mitfahrt_map_widget_image, result.bitmap)
            views.setViewVisibility(R.id.mitfahrt_map_widget_image, View.VISIBLE)
            views.setContentDescription(R.id.mitfahrt_map_widget_image, "Buskarte. $status")
        } else {
            views.setViewVisibility(R.id.mitfahrt_map_widget_image, View.GONE)
        }

        // Live position age chronometer.
        val measured = rideModel?.measuredAt?.takeIf { rideModel.position != null }
        views.setViewVisibility(R.id.mitfahrt_map_widget_age, if (measured != null) View.VISIBLE else View.GONE)
        views.setChronometer(
            R.id.mitfahrt_map_widget_age,
            SystemClock.elapsedRealtime() - (measured?.let { java.time.Duration.between(it, now).toMillis() } ?: 0L).coerceAtLeast(0),
            "Stand vor %s",
            measured != null
        )

        // Tapping anywhere on the map opens the active ride or fullscreen interactive bus map.
        val openAction = if (state.ride != null) {
            WidgetAction.Open(WidgetKind.ACTIVE, null, "ride")
        } else {
            WidgetAction.Open(WidgetKind.ACTIVE, null, "map")
        }
        val mainIntent = WidgetIntents.mainActivity(app, openAction)
        val mainPending = PendingIntent.getActivity(
            app,
            widgetId * 31 + 11,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.mitfahrt_map_widget_root, mainPending)
        views.setOnClickPendingIntent(R.id.mitfahrt_map_widget_image, mainPending)
        return views to missingTiles
    }
}
