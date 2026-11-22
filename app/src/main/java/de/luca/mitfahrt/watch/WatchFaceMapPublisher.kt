package de.luca.mitfahrt.watch

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.shared.WatchFaceMapProtocol
import de.luca.mitfahrt.shared.WatchFaceMapSnapshot
import de.luca.mitfahrt.widgets.MapWidgetMode
import de.luca.mitfahrt.widgets.OsmCachedTileReader
import de.luca.mitfahrt.widgets.RouteSketch
import de.luca.mitfahrt.widgets.WidgetTilePrefetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * Renders and pushes full-bleed live ride street map snapshots (Bus-Focus or Overview) plus
 * next-stop / ETA fields to the paired Wear OS watch app and Mitfahrt Live Watch Face.
 */
class WatchFaceMapPublisher(
    context: Context,
    private val onTilesLoaded: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val client = Wearable.getDataClient(appContext)
    @Volatile private var lastFingerprint: String = ""
    @Volatile private var lastPublishedAt: Instant = Instant.EPOCH

    suspend fun publish(
        state: TrackingState,
        gpsEnabled: Boolean,
        now: Instant = Instant.now(),
        force: Boolean = false
    ) {
        val activeMode = mode(appContext)
        val snapshot = buildSnapshot(state, gpsEnabled, now, activeMode)
        val fingerprint = fingerprint(snapshot, activeMode)
        val elapsed = now.epochSecond - lastPublishedAt.epochSecond
        if (!force && fingerprint == lastFingerprint && elapsed in 0 until MIN_REFRESH_SECONDS) return

        val payload = WatchFaceMapProtocol.encode(snapshot)
        client.putDataItem(
            PutDataRequest.create(WatchFaceMapProtocol.PATH)
                .setData(payload)
                .setUrgent()
        ).await()
        lastFingerprint = fingerprint
        lastPublishedAt = now
    }

    internal suspend fun buildSnapshot(
        state: TrackingState,
        gpsEnabled: Boolean,
        now: Instant,
        activeMode: MapWidgetMode = mode(appContext)
    ): WatchFaceMapSnapshot {
        val ride = state.ride ?: return WatchFaceMapSnapshot(sentAt = now, active = false)
        val targetIdx = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val nextStopObj = ride.nextStopToTarget(now)
        val nextStopName = nextStopObj?.name?.let(::shortName) ?: shortName(ride.displayTarget().name)
        val nextStopAt = if (ride.boarded) {
            nextStopObj?.effective(ride.offsetSeconds)
        } else {
            ride.liveBoardingTime(now)?.at ?: ride.boardingTime().at
        }
        val arrivalAt = ride.displayTarget().effective(ride.offsetSeconds)
        val nextIdx = ride.nextIndex(now).coerceIn(ride.boardingIndex, targetIdx + 1)
        val remainingStops = (targetIdx - nextIdx + 1).coerceAtLeast(0)
        val totalStops = (targetIdx - ride.boardingIndex).coerceAtLeast(1)
        val progressPercent = (ride.progressToTarget(now) * 100f).toInt().coerceIn(0, 100)

        val mapBytes = if (state.running) {
            renderCompactMap(state, gpsEnabled, now, activeMode)
        } else {
            ByteArray(0)
        }

        return WatchFaceMapSnapshot(
            sentAt = now,
            active = true,
            running = state.running,
            line = ride.departure.line,
            destination = shortName(ride.displayTarget().name),
            nextStop = nextStopName,
            nextStopAt = nextStopAt,
            arrivalAt = arrivalAt,
            boarded = ride.boarded,
            remainingStops = remainingStops,
            totalStops = totalStops,
            progressPercent = progressPercent,
            mapPng = mapBytes
        )
    }

    private suspend fun renderCompactMap(
        state: TrackingState,
        gpsEnabled: Boolean,
        now: Instant,
        activeMode: MapWidgetMode
    ): ByteArray = withContext(Dispatchers.Default) {
        val model = RouteSketch.model(state, now, gpsEnabled = gpsEnabled) ?: return@withContext ByteArray(0)
        val result = OsmCachedTileReader.forContext(appContext).use { reader ->
            RouteSketch.renderDetailed(
                model = model,
                width = MAP_WIDTH,
                height = MAP_HEIGHT,
                dark = true,
                tileReader = reader,
                cornerRadiusPx = 0f,
                mode = activeMode,
                fullBleed = true
            )
        }
        if (result.missingTiles.isNotEmpty()) {
            WidgetTilePrefetcher.prefetch(appContext, result.missingTiles, onTilesLoaded)
        }
        try {
            encodeWithinBudget(result.bitmap)
        } finally {
            result.bitmap.recycle()
        }
    }

    private fun encodeWithinBudget(bitmap: Bitmap): ByteArray {
        val png = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        if (png.size <= WatchFaceMapProtocol.MAX_PNG_BYTES) return png

        // Progressive JPEG quality fallback so detailed 450x450 OSM tile maps always fit the Wear byte budget.
        for (quality in intArrayOf(82, 70, 56, 44)) {
            val jpeg = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            if (jpeg.size <= WatchFaceMapProtocol.MAX_PNG_BYTES) return jpeg
        }
        return ByteArray(0)
    }

    private fun fingerprint(s: WatchFaceMapSnapshot, activeMode: MapWidgetMode): String =
        "${activeMode.name}|${s.active}|${s.running}|${s.line}|${s.destination}|${s.nextStop}|${s.nextStopAt?.epochSecond}|${s.arrivalAt?.epochSecond}|${s.boarded}|${s.remainingStops}|${s.progressPercent}|${s.mapPng.size}"

    companion object {
        const val MAP_WIDTH = 450
        const val MAP_HEIGHT = 450
        private const val MIN_REFRESH_SECONDS = 12L
        private const val MODE_PREFS = "mitfahrt_watch_map_mode"

        fun mode(context: Context): MapWidgetMode {
            val raw = context.applicationContext
                .getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
                .getString("mode", null)
            return if (raw == MapWidgetMode.OVERVIEW.name) MapWidgetMode.OVERVIEW else MapWidgetMode.BUS_FOCUS
        }

        fun modeName(context: Context): String =
            if (mode(context) == MapWidgetMode.BUS_FOCUS) "focus" else "overview"

        fun setMode(context: Context, mode: MapWidgetMode) {
            context.applicationContext
                .getSharedPreferences(MODE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("mode", mode.name)
                .apply()
        }

        fun toggleMode(context: Context): MapWidgetMode {
            val next = if (mode(context) == MapWidgetMode.BUS_FOCUS) MapWidgetMode.OVERVIEW else MapWidgetMode.BUS_FOCUS
            setMode(context, next)
            return next
        }
    }
}
