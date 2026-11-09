package de.luca.mitfahrt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.data.Geo
import de.luca.mitfahrt.data.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/** A line that VRR actually delivered. Stops are never joined into a pretend route. */
data class MapLine(val points: List<GeoPoint>, val color: Long, val dashed: Boolean = false, val width: Float = 5f)

enum class PinKind { STOP, BOARD, TRANSFER, EXIT, ME }
data class MapPin(val point: GeoPoint, val title: String, val kind: PinKind, val id: String = title)

/** Small map for journey details and nearby stops; shows only the given real data. */
@Composable
fun PlainMap(lines: List<MapLine>, pins: List<MapPin>, description: String, modifier: Modifier = Modifier,
    height: Dp = 260.dp, onPin: ((MapPin) -> Unit)? = null) {
    val context = LocalContext.current
    val settings = LocalAppSettings.current
    val dark = when (settings.string("map_theme", "system")) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val validLines = lines.filter { line -> line.points.size > 1 && line.points.all(Geo::valid) }
    val validPins = pins.filter { Geo.valid(it.point) }
    var controller by remember { mutableStateOf<PlainMapController?>(null) }
    DisposableEffect(controller, lifecycle) {
        val current = controller
        val observer = LifecycleEventObserver { _, _ -> current?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        lifecycle.addObserver(observer)
        current?.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); current?.setActive(false) }
    }
    if (validPins.isEmpty() && validLines.isEmpty()) return
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = description }) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
            PlainMapController(viewContext).also { controller = it; it.update(validLines, validPins, dark, onPin) }.map
        }, update = { view ->
            controller?.takeIf { it.map === view }?.update(validLines, validPins, dark, onPin)
        }, onRelease = { view -> controller?.takeIf { it.map === view }?.close(); controller = null })
        Surface(Modifier.align(Alignment.BottomEnd), color = Color.White.copy(alpha = .96f), shape = RoundedCornerShape(topStart = 8.dp)) {
            TextButton(onClick = { runCatching { uriHandler.openUri("https://www.openstreetmap.org/copyright") } },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("© OpenStreetMap contributors", color = Color(0xFF263445), fontSize = 10.sp)
            }
        }
    }
}

private class PlainMapController(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private var closed = false
    private var active = false
    private var identity: Any? = null
    private var needsFit = true
    private var points: List<GeoPoint> = emptyList()
    private val overlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()
    val map: MapView

    init {
        val base = File(context.cacheDir, "waiting-map").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = "Mitfahrt/${BuildConfig.VERSION_NAME} (https://github.com/Eibelucas/VRR-status)"
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileDownloadThreads = 2.toShort()
        }
        map = MapView(context).apply {
            setDestroyMode(false)
            setTileSource(XYTileSource("Mitfahrt-OpenStreetMap", 0, 19, 256, ".png",
                arrayOf("https://tile.openstreetmap.org/"), "© OpenStreetMap contributors",
                TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or
                    TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)))
            setUseDataConnection(false)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(3.0); setMaxZoomLevel(19.0)
            setTilesScaledToDpi(true)
            setVerticalMapRepetitionEnabled(false); setHorizontalMapRepetitionEnabled(false)
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ -> if (right > left && bottom > top) fit() }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                false
            }
        }
    }

    fun update(lines: List<MapLine>, pins: List<MapPin>, dark: Boolean, onPin: ((MapPin) -> Unit)?) {
        if (closed) return
        map.overlayManager.tilesOverlay?.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        val key = listOf(lines, pins)
        if (key == identity) return
        identity = key
        overlays.forEach { map.overlays.remove(it) }; overlays.clear()
        lines.forEach { line ->
            Polyline(map).apply {
                setPoints(line.points.map { OsmPoint(it.lat, it.lon) })
                outlinePaint.color = line.color.toInt(); outlinePaint.strokeWidth = line.width * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                if (line.dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(4 * density, 6 * density), 0f)
                infoWindow = null
                map.overlays.add(this); overlays.add(this)
            }
        }
        pins.take(150).forEach { pin ->
            Marker(map).apply {
                position = OsmPoint(pin.point.lat, pin.point.lon); title = pin.title
                icon = pinIcon(pin.kind); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> onPin?.invoke(pin); true }
                map.overlays.add(this); overlays.add(this)
            }
        }
        points = lines.flatMap { it.points } + pins.map { it.point }
        needsFit = true
        map.post { fit() }
        map.invalidate()
    }

    private fun fit() {
        if (closed || !needsFit || map.width == 0 || map.height == 0 || points.isEmpty()) return
        needsFit = false
        val all = points.map { OsmPoint(it.lat, it.lon) }
        if (all.distinct().size > 1) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), false, (40 * density).toInt(), 17.0, null)
        else { map.controller.setZoom(16.0); map.controller.setCenter(all.first()) }
    }

    fun setActive(visible: Boolean) {
        if (closed || active == visible) return
        active = visible; map.setUseDataConnection(visible)
        if (visible) { map.onResume(); map.invalidate() } else map.onPause()
    }

    fun close() {
        if (closed) return
        setActive(false); closed = true; map.setUseDataConnection(false); map.onDetach()
    }

    private fun pinIcon(kind: PinKind): BitmapDrawable {
        val sizeDp = when (kind) { PinKind.STOP -> 16; PinKind.ME -> 16; else -> 22 }
        val fill = when (kind) {
            PinKind.BOARD -> 0xFF20824B.toInt()
            PinKind.TRANSFER -> 0xFFE09B00.toInt()
            PinKind.EXIT -> 0xFFB40055.toInt()
            PinKind.ME -> 0xFF2F6FDB.toInt()
            PinKind.STOP -> 0xFF6B6474.toInt()
        }
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG); val center = size / 2f
        paint.color = android.graphics.Color.WHITE; canvas.drawCircle(center, center, center, paint)
        paint.color = fill; canvas.drawCircle(center, center, center - 2.5f * density, paint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
