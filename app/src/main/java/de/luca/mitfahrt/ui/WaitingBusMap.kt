package de.luca.mitfahrt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.GeoPoint
import de.luca.mitfahrt.data.Stop
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The caller supplies a confirmed vehicle's reported point; this view never predicts movement. */
@Composable
fun WaitingBusMap(busPoint: GeoPoint, boarding: Stop?, vehicleLabel: String, positionAt: Instant,
    now: Instant, modifier: Modifier = Modifier) {
    if (!busPoint.mapValid() || now.epochSecond - positionAt.epochSecond !in -15..90) return
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var controller by remember { mutableStateOf<WaitingMapController?>(null) }
    var viewportVisible by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    fun open(url: String) {
        linkError = runCatching { uriHandler.openUri(url) }.isFailure
    }
    DisposableEffect(controller, lifecycle, viewportVisible) {
        val current = controller
        fun sync() { current?.setActive(viewportVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycle.removeObserver(observer); current?.setActive(false) }
    }
    Card(modifier.fillMaxWidth(), shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vehicleLabel, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(positionLabel(positionAt, now), color = Soft, fontSize = 12.sp)
                }
                IconButton(onClick = { controller?.recenter() }) {
                    Icon(Icons.Outlined.CenterFocusStrong, "Bus und Einstieg zentrieren", tint = Lime)
                }
            }
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp))) {
                AndroidView(modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val rootBounds = coordinates.findRootCoordinates().boundsInWindow()
                    viewportVisible = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 &&
                        rootBounds.width > 0 && rootBounds.height > 0 && bounds.overlaps(rootBounds)
                }, factory = { viewContext ->
                    WaitingMapController(viewContext).also {
                        controller = it
                        it.update(busPoint, boarding, vehicleLabel)
                    }.map
                }, update = { view ->
                    controller?.takeIf { it.map === view }?.update(busPoint, boarding, vehicleLabel)
                }, onReset = null, onRelease = { view ->
                    view.owner?.close()
                    if (controller?.map === view) controller = null
                })
                Surface(Modifier.align(Alignment.BottomEnd), color = Color.White.copy(alpha = .96f),
                    shape = RoundedCornerShape(topStart = 8.dp)) {
                    TextButton(onClick = { open("https://www.openstreetmap.org/copyright") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("© OpenStreetMap contributors", color = Color(0xFF263445), fontSize = 10.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DirectionsBus, null, tint = BrandBerry, modifier = Modifier.size(17.dp))
                Text(" Bus", color = Soft, fontSize = 12.sp)
                if (boarding?.point?.mapValid() == true) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.RadioButtonChecked, null, tint = Color(0xFF20824B), modifier = Modifier.size(16.dp))
                    Text(" Einstieg", color = Soft, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    open("https://www.google.com/maps/search/?api=1&query=${busPoint.lat},${busPoint.lon}")
                }) {
                    Text("Google Maps", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(15.dp))
                }
            }
            if (linkError) Text("Der Link konnte nicht geöffnet werden.", color = Amber, fontSize = 12.sp)
        }
    }
}

private fun positionLabel(at: Instant, now: Instant): String {
    val time = at.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.GERMANY))
    val age = now.epochSecond - at.epochSecond
    return when {
        age < -10 -> "Zeitpunkt unklar · $time"
        age < 5 -> "NEW · Stand $time"
        age < 60 -> "NEW · Stand $time · vor $age Sek."
        else -> "Letzte Position $time · vor ${age / 60} Min."
    }
}

private fun GeoPoint.mapValid(): Boolean = lat.isFinite() && lon.isFinite() &&
    lat in -85.05112878..85.05112878 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)

private class WaitingMapView(context: Context) : MapView(context) {
    var owner: WaitingMapController? = null
}

private class WaitingMapController(private val context: Context) {
    private var closed = false
    private var active = false
    private var cameraIdentity: String? = null
    private var needsFit = false
    private var busPoint: GeoPoint? = null
    private var boarding: Stop? = null
    private val density = context.resources.displayMetrics.density
    val map: WaitingMapView
    private val bus: Marker
    private val stop: Marker

    init {
        val base = File(context.cacheDir, "waiting-map").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = "Mitfahrt/${BuildConfig.VERSION_NAME} (https://github.com/Eibelucas/VRR-status)"
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileFileSystemCacheMaxBytes = 50L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 40L * 1024 * 1024
            tileDownloadThreads = 2.toShort()
        }
        map = WaitingMapView(context).apply {
            setDestroyMode(false) // AndroidView release owns the single, idempotent detach below.
            setTileSource(XYTileSource("Mitfahrt-OpenStreetMap", 0, 19, 256, ".png",
                arrayOf("https://tile.openstreetmap.org/"), "© OpenStreetMap contributors",
                TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or
                    TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)))
            setUseDataConnection(false)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(19.0)
            setTilesScaledToDpi(true)
            setVerticalMapRepetitionEnabled(false)
            setHorizontalMapRepetitionEnabled(false)
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (right > left && bottom > top) fitIfSized()
            }
        }
        map.owner = this
        stop = Marker(map).apply {
            icon = markerIcon(false)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }
        bus = Marker(map).apply {
            icon = markerIcon(true)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(stop)
        map.overlays.add(bus)
    }

    fun update(point: GeoPoint, boardingStop: Stop?, label: String) {
        if (closed || !point.mapValid()) return
        busPoint = point
        boarding = boardingStop?.takeIf { it.point?.mapValid() == true }
        bus.position = OsmPoint(point.lat, point.lon)
        bus.title = label
        stop.isEnabled = boarding != null
        boarding?.let {
            stop.position = OsmPoint(it.point!!.lat, it.point!!.lon)
            stop.title = "Einstieg: ${it.name}"
        }
        map.contentDescription = if (boarding != null) "$label und Einstieg ${boarding!!.name} auf der Karte" else "$label auf der Karte"
        val identity = "$label/${boardingStop?.id}/${boarding?.point}"
        if (identity != cameraIdentity) { cameraIdentity = identity; recenter() }
        map.invalidate() // Actual reports jump directly to their point; no invented travel animation.
    }

    fun recenter() {
        if (closed) return
        needsFit = true
        map.post { fitIfSized() }
    }

    private fun fitIfSized() {
        if (closed || !needsFit || map.width == 0 || map.height == 0) return
        val point = busPoint ?: return
        val points = listOfNotNull(OsmPoint(point.lat, point.lon), boarding?.point?.let { OsmPoint(it.lat, it.lon) })
        needsFit = false
        if (points.size == 1 || point.distanceTo(boarding!!.point!!) < 25) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(points.first())
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (48 * density).toInt(), 18.0, null)
        }
    }

    fun setActive(visible: Boolean) {
        if (closed || active == visible) return
        active = visible
        map.setUseDataConnection(visible)
        if (visible) { map.onResume(); map.invalidate() } else map.onPause()
    }

    fun close() {
        if (closed) return
        setActive(false)
        closed = true
        map.setUseDataConnection(false)
        map.owner = null
        map.onDetach()
    }

    private fun markerIcon(isBus: Boolean): BitmapDrawable {
        val size = ((if (isBus) 42 else 30) * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(center, center, center, paint)
        paint.color = if (isBus) 0xFFB40055.toInt() else 0xFFEAF6EA.toInt()
        canvas.drawCircle(center, center, center - 2 * density, paint)
        if (isBus) {
            context.getDrawable(R.drawable.ic_bus)?.mutate()?.let { icon ->
                val inset = (size * .23f).toInt()
                icon.setBounds(inset, inset, size - inset, size - inset)
                icon.draw(canvas)
            }
        } else {
            paint.color = 0xFF20824B.toInt()
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = size * .58f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("H", center, center - (paint.ascent() + paint.descent()) / 2, paint)
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
