package de.luca.mitfahrt.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalContext
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
import de.luca.mitfahrt.data.AppSettings
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.GeoPoint
import de.luca.mitfahrt.data.NewVehicle
import de.luca.mitfahrt.data.RouteShape
import de.luca.mitfahrt.data.RouteShapeSource
import de.luca.mitfahrt.data.Stop
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Overlay data for the map. Built only from verified geometry and real observations. */
data class MapRoute(
    /** Verified line geometry; null means no line is drawn at all. */
    val shape: RouteShape? = null,
    val target: Stop? = null,
    /** Section of the line the rider uses (boarding to target), as offsets on [shape]. */
    val sectionFrom: Double? = null,
    val sectionTo: Double? = null,
    /** Part of the section already travelled, drawn muted. */
    val passedTo: Double? = null,
    /** NEW position projected onto the line; only when close and heading the same way. */
    val projected: GeoPoint? = null,
    /** NEW reports positions away from the line: shown raw, never pulled back. */
    val offRoute: Boolean = false,
    /** Fresh phone position, shown on the device only. */
    val phone: GeoPoint? = null
)

/** Real observations only. Stops are never joined into a pretend street route. */
@Composable
fun WaitingBusMap(busPoint: GeoPoint, boarding: Stop?, vehicleLabel: String, positionAt: Instant,
    now: Instant, modifier: Modifier = Modifier, route: MapRoute = MapRoute(),
    routeStops: List<Stop> = emptyList(), otherVehicles: List<NewVehicle> = emptyList(),
    selectedVehicleId: String? = null, overview: Boolean = false, onSelectVehicle: ((NewVehicle) -> Unit)? = null) {
    if (!busPoint.mapValid() || now.epochSecond - positionAt.epochSecond !in -15..90) return
    val context = LocalContext.current
    val settings by remember(context) { AppSettingsStore(context) }.state.collectAsState()
    val dark = when (settings.string("map_theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var controller by remember { mutableStateOf<WaitingMapController?>(null) }
    var viewportVisible by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    val vehicles = otherVehicles.filter { it.isFresh(now) && it.point.mapValid() && it.id != selectedVehicleId }
    val visibleVehicles = if (overview || settings.bool("map_other_buses", false)) vehicles else emptyList()
    val shape = route.shape?.takeIf { settings.bool("map_route", true) && it.points.all { point -> point.mapValid() } }
    val mapRoute = route.copy(shape = shape, projected = route.projected?.takeIf { shape != null && it.mapValid() },
        phone = route.phone?.takeIf { it.mapValid() })
    val stops = if (settings.bool("map_stops", true)) routeStops.filter { it.point?.mapValid() == true } else emptyList()
    fun open(url: String) { linkError = runCatching { uriHandler.openUri(url) }.isFailure }
    DisposableEffect(controller, lifecycle, viewportVisible) {
        val current = controller
        fun sync() { current?.setActive(viewportVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        lifecycle.addObserver(observer)
        sync()
        onDispose { lifecycle.removeObserver(observer); current?.setActive(false) }
    }
    AppCard(modifier.fillMaxWidth(), shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(vehicleLabel, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(positionLabel(positionAt, now), color = Soft, fontSize = 12.sp)
                }
                IconButton(onClick = { controller?.recenter() }) {
                    Icon(Icons.Outlined.CenterFocusStrong, "Karte zentrieren", tint = Lime)
                }
            }
            Box(Modifier.fillMaxWidth().height(if (overview || settings.string("map_size", "normal") == "large") 400.dp else 260.dp)
                .clip(RoundedCornerShape(16.dp))) {
                AndroidView(modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val rootBounds = coordinates.findRootCoordinates().boundsInWindow()
                    viewportVisible = coordinates.isAttached && bounds.width > 0 && bounds.height > 0 &&
                        rootBounds.width > 0 && rootBounds.height > 0 && bounds.overlaps(rootBounds)
                }, factory = { viewContext ->
                    WaitingMapController(viewContext).also {
                        controller = it
                        it.update(busPoint, boarding, vehicleLabel, settings, dark, mapRoute, stops,
                            visibleVehicles, onSelectVehicle, overview)
                    }.map
                }, update = { view ->
                    controller?.takeIf { it.map === view }?.update(busPoint, boarding, vehicleLabel, settings, dark,
                        mapRoute, stops, visibleVehicles, onSelectVehicle, overview)
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
                if (boarding?.point?.mapValid() == true && settings.bool("map_stops", true)) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.RadioButtonChecked, null, tint = Color(0xFF20824B), modifier = Modifier.size(16.dp))
                    Text(" Einstieg", color = Soft, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    linkError = runCatching {
                        when (settings.string("map_external", "chooser")) {
                            "google" -> uriHandler.openUri("https://www.google.com/maps/search/?api=1&query=${busPoint.lat},${busPoint.lon}")
                            "osm" -> uriHandler.openUri("https://www.openstreetmap.org/?mlat=${busPoint.lat}&mlon=${busPoint.lon}#map=16/${busPoint.lat}/${busPoint.lon}")
                            else -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW,
                                Uri.parse("geo:${busPoint.lat},${busPoint.lon}?q=${busPoint.lat},${busPoint.lon}(${Uri.encode(vehicleLabel)})")), "Karten-App wählen"))
                        }
                    }.isFailure
                }) {
                    Text(when (settings.string("map_external", "chooser")) {
                        "google" -> "Google Maps"
                        "osm" -> "OpenStreetMap"
                        else -> "Öffnen"
                    }, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(15.dp))
                }
            }
            routeNote(mapRoute, stops.isNotEmpty())?.let { Text(it, color = if (mapRoute.offRoute) Amber else Soft, fontSize = 11.sp, lineHeight = 15.sp) }
            if (linkError) Text("Die Karten-App konnte nicht geöffnet werden.", color = Amber, fontSize = 12.sp)
        }
    }
}

/** Explains where the drawn line comes from, or that there is none. */
private fun routeNote(route: MapRoute, stopsShown: Boolean): String? {
    val lines = mutableListOf<String>()
    when (route.shape?.source) {
        RouteShapeSource.TRIP -> lines += "Linienverlauf dieser Fahrt laut VRR."
        RouteShapeSource.VARIANT -> lines += "Linienverlauf einer früheren VRR-Fahrt mit gleicher Haltestellenfolge."
        null -> if (stopsShown) lines += "Kein Streckenverlauf verfügbar – nur Haltestellen."
    }
    if (route.offRoute) lines += "Der Bus weicht vom bekannten Verlauf ab (z. B. Umleitung). Gezeigt wird seine gemeldete Position."
    else if (route.projected != null) lines += "Heller Punkt: gemeldete Position auf die Linie übertragen."
    return lines.joinToString(" ").ifBlank { null }
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

private class WaitingMapView(context: Context) : MapView(context) { var owner: WaitingMapController? = null }

private class WaitingMapController(private val context: Context) {
    private var closed = false
    private var active = false
    private var cameraIdentity: String? = null
    private var needsFit = false
    private var manualSince = 0L
    private var busPoint: GeoPoint? = null
    private var boarding: Stop? = null
    private var routePoints: List<GeoPoint> = emptyList()
    private var stopPoints: List<GeoPoint> = emptyList()
    private var others: List<NewVehicle> = emptyList()
    private var camera = "bus"
    private var zoom = 15
    private var resumeSeconds = 30
    private var overview = false
    private val density = context.resources.displayMetrics.density
    private val route: Polyline
    private val section: Polyline
    private val passed: Polyline
    private val target: Marker
    private val projectedMarker: Marker
    private val phoneMarker: Marker
    private val extraMarkers = mutableListOf<Marker>()
    private val stationMarkers = mutableListOf<Marker>()
    private var overlayIdentity: Any? = null
    val map: WaitingMapView
    private val bus: Marker
    private val stop: Marker

    init {
        val base = File(context.cacheDir, "waiting-map").apply { mkdirs() }
        Configuration.getInstance().apply {
            userAgentValue = "Mitfahrt/${BuildConfig.VERSION_NAME} (https://github.com/Eibelucas/VRR-status)"
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
            tileDownloadThreads = 2.toShort()
        }
        map = WaitingMapView(context).apply {
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
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (right > left && bottom > top) fitIfSized()
            }
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                    manualSince = SystemClock.elapsedRealtime()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                false
            }
        }
        map.owner = this
        route = Polyline(map).apply {
            outlinePaint.color = 0x66B40055; outlinePaint.strokeWidth = 3 * density
            infoWindow = null
        }
        section = Polyline(map).apply {
            outlinePaint.color = 0xFFB40055.toInt(); outlinePaint.strokeWidth = 6 * density
            infoWindow = null
        }
        passed = Polyline(map).apply {
            outlinePaint.color = 0xFF9A93A6.toInt(); outlinePaint.strokeWidth = 6 * density
            infoWindow = null
        }
        target = Marker(map).apply {
            icon = dotIcon(0xFFB40055.toInt(), 22); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; setOnMarkerClickListener { _, _ -> true }
        }
        projectedMarker = Marker(map).apply {
            icon = dotIcon(0xFFFFFFFF.toInt(), 14, 0xFFB40055.toInt()); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; setOnMarkerClickListener { _, _ -> true }
        }
        phoneMarker = Marker(map).apply {
            icon = dotIcon(0xFF2F6FDB.toInt(), 16); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; setOnMarkerClickListener { _, _ -> true }; title = "Dein Standort"
        }
        stop = Marker(map).apply {
            icon = markerIcon(false); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; setOnMarkerClickListener { _, _ -> true }
        }
        bus = Marker(map).apply {
            icon = markerIcon(true); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null; setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(route); map.overlays.add(section); map.overlays.add(passed)
        map.overlays.add(stop); map.overlays.add(target); map.overlays.add(phoneMarker)
        map.overlays.add(projectedMarker); map.overlays.add(bus)
    }

    fun update(point: GeoPoint, boardingStop: Stop?, label: String, settings: AppSettings, dark: Boolean,
        mapRoute: MapRoute, stops: List<Stop>, vehicles: List<NewVehicle>,
        onSelect: ((NewVehicle) -> Unit)?, fleetOverview: Boolean) {
        if (closed || !point.mapValid()) return
        val changedPoint = busPoint != point
        busPoint = point
        boarding = boardingStop?.takeIf { it.point?.mapValid() == true }
        val shape = mapRoute.shape
        routePoints = shape?.points.orEmpty()
        stopPoints = stops.mapNotNull { it.point }
        others = vehicles
        camera = settings.string("map_camera", "bus")
        zoom = settings.int("map_zoom", 15).coerceIn(10, 19)
        resumeSeconds = settings.int("map_follow_resume", 30).coerceIn(0, 300)
        overview = fleetOverview
        val cache = settings.int("map_cache_mb", 64).coerceIn(16, 256).toLong() * 1024 * 1024
        Configuration.getInstance().apply {
            tileFileSystemCacheMaxBytes = cache; tileFileSystemCacheTrimBytes = cache * 4 / 5
        }
        map.overlayManager.tilesOverlay?.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        bus.position = OsmPoint(point.lat, point.lon); bus.title = label
        stop.isEnabled = settings.bool("map_stops", true) && boarding != null
        boarding?.let { stop.position = OsmPoint(it.point!!.lat, it.point!!.lon); stop.title = "Einstieg: ${it.name}" }
        target.isEnabled = mapRoute.target?.point?.mapValid() == true
        mapRoute.target?.let { stopTarget ->
            stopTarget.point?.takeIf { it.mapValid() }?.let { target.position = OsmPoint(it.lat, it.lon); target.title = "Ziel: ${stopTarget.name}" }
        }
        // The projected point is a second, clearly different marker; the bus marker stays at the raw position.
        val projected = mapRoute.projected?.takeIf { !mapRoute.offRoute && it.distanceTo(point) >= 8.0 }
        projectedMarker.isEnabled = projected != null
        projected?.let { projectedMarker.position = OsmPoint(it.lat, it.lon); projectedMarker.title = "Auf die Linie übertragen" }
        phoneMarker.isEnabled = mapRoute.phone != null
        mapRoute.phone?.let { phoneMarker.position = OsmPoint(it.lat, it.lon) }
        val line = shape?.line
        val from = mapRoute.sectionFrom
        val to = mapRoute.sectionTo
        // Rounded so that a new GPS fix does not rebuild the overlays every few seconds.
        val passedKey = mapRoute.passedTo?.let { (it / 25.0).toInt() }
        val identity = listOf(shape?.journeyKey, shape?.source, routePoints.size, from?.toInt(), to?.toInt(), passedKey, stops, vehicles)
        if (identity != overlayIdentity) {
            overlayIdentity = identity
            route.setPoints(routePoints.map { OsmPoint(it.lat, it.lon) }); route.isEnabled = routePoints.size > 1
            val sectionPoints = if (line != null && from != null && to != null) line.slice(from, to) else emptyList()
            section.setPoints(sectionPoints.map { OsmPoint(it.lat, it.lon) }); section.isEnabled = sectionPoints.size > 1
            val passedPoints = if (line != null && from != null && to != null && mapRoute.passedTo != null)
                line.slice(from, minOf(to, mapRoute.passedTo)) else emptyList()
            passed.setPoints(passedPoints.map { OsmPoint(it.lat, it.lon) }); passed.isEnabled = passedPoints.size > 1
            extraMarkers.forEach { map.overlays.remove(it) }; extraMarkers.clear()
            stationMarkers.forEach { map.overlays.remove(it) }; stationMarkers.clear()
            stops.filter { it.id != boarding?.id }.take(100).forEach { station ->
                Marker(map).apply {
                    position = OsmPoint(station.point!!.lat, station.point!!.lon)
                    title = station.name; icon = markerIcon(false)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(this); stationMarkers.add(this)
                }
            }
            vehicles.take(200).forEach { vehicle ->
                Marker(map).apply {
                    position = OsmPoint(vehicle.point.lat, vehicle.point.lon)
                    title = "Linie ${vehicle.line} · Fahrzeug ${vehicle.id}"
                    icon = markerIcon(true); alpha = .65f
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ -> onSelect?.invoke(vehicle); true }
                    map.overlays.add(this); extraMarkers.add(this)
                }
            }
            map.overlays.remove(bus); map.overlays.add(bus)
        }
        map.contentDescription = if (boarding != null) "$label und Einstieg ${boarding!!.name} auf der Karte" else "$label auf der Karte"
        val cameraKey = "$label/${boardingStop?.id}/$camera/$zoom/$fleetOverview"
        val canFollow = manualSince == 0L || resumeSeconds > 0 &&
            SystemClock.elapsedRealtime() - manualSince >= resumeSeconds * 1000L
        if (cameraKey != cameraIdentity) {
            cameraIdentity = cameraKey; recenter()
        } else if (canFollow && changedPoint) {
            needsFit = true; fitIfSized()
        }
        map.invalidate()
    }

    fun recenter() {
        if (closed) return
        manualSince = 0L; needsFit = true
        map.post { fitIfSized() }
    }

    private fun fitIfSized() {
        if (closed || !needsFit || map.width == 0 || map.height == 0) return
        val point = busPoint ?: return
        needsFit = false
        val points = when {
            overview -> (listOf(point) + others.map { it.point })
            camera == "route" -> (routePoints.ifEmpty { stopPoints } + listOfNotNull(point, boarding?.point)).distinct()
            camera == "boarding" -> listOf(boarding?.point ?: point)
            else -> listOf(point)
        }.map { OsmPoint(it.lat, it.lon) }
        if (points.size > 1) {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (48 * density).toInt(), 18.0, null)
        } else {
            map.controller.setZoom(zoom.toDouble()); map.controller.setCenter(points.first())
        }
    }

    fun setActive(visible: Boolean) {
        if (closed || active == visible) return
        active = visible; map.setUseDataConnection(visible)
        if (visible) { map.onResume(); map.invalidate() } else map.onPause()
    }

    fun close() {
        if (closed) return
        setActive(false); closed = true; map.setUseDataConnection(false); map.owner = null; map.onDetach()
    }

    private fun dotIcon(fill: Int, sizeDp: Int, ring: Int = android.graphics.Color.WHITE): BitmapDrawable {
        val size = (sizeDp * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG); val center = size / 2f
        paint.color = ring; canvas.drawCircle(center, center, center, paint)
        paint.color = fill; canvas.drawCircle(center, center, center - 2.5f * density, paint)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun markerIcon(isBus: Boolean): BitmapDrawable {
        val size = ((if (isBus) 42 else 30) * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG); val center = size / 2f
        paint.color = android.graphics.Color.WHITE; canvas.drawCircle(center, center, center, paint)
        paint.color = if (isBus) 0xFFB40055.toInt() else 0xFFEAF6EA.toInt()
        canvas.drawCircle(center, center, center - 2 * density, paint)
        if (isBus) context.getDrawable(R.drawable.ic_bus)?.mutate()?.let { icon ->
            val inset = (size * .23f).toInt(); icon.setBounds(inset, inset, size - inset, size - inset); icon.draw(canvas)
        } else {
            paint.color = 0xFF20824B.toInt(); paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = size * .58f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("H", center, center - (paint.ascent() + paint.descent()) / 2, paint)
        }
        return BitmapDrawable(context.resources, bitmap)
    }
}
