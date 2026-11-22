package de.luca.mitfahrt.wear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import de.luca.mitfahrt.shared.WatchFaceMapSnapshot
import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** Formatted transit text for the lower watch face slot (next stop, countdown, arrival ETA). */
data class WatchFaceTransitCopy(
    val primaryText: String,
    val secondaryTitle: String,
    val shortText: String,
    val shortTitle: String,
    val description: String,
    val active: Boolean
)

/** Pure JVM formatter for the Mitfahrt Live Watch Face transit details complication. */
object WatchFaceTransitFormat {
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun format(
        map: WatchFaceMapSnapshot?,
        ride: WatchRide?,
        now: Instant,
        zone: ZoneId = ZoneId.of("Europe/Berlin")
    ): WatchFaceTransitCopy {
        val activeMap = map?.takeIf { it.active }
        val activeRide = ride?.takeIf { it.active }
        if (activeMap == null && activeRide == null) {
            return WatchFaceTransitCopy(
                primaryText = "Keine aktive Fahrt",
                secondaryTitle = "Mitfahrt öffnen",
                shortText = "Keine",
                shortTitle = "Fahrt",
                description = "Mitfahrt: Keine aktive Fahrt",
                active = false
            )
        }

        val line = (activeMap?.line?.ifBlank { null } ?: activeRide?.line).orEmpty().ifBlank { "Bus" }
        val destination = (activeMap?.destination?.ifBlank { null } ?: activeRide?.destination).orEmpty()
        val nextStop = (activeMap?.nextStop?.ifBlank { null } ?: activeRide?.nextStop).orEmpty()
            .ifBlank { destination.ifBlank { "Nächster Halt" } }
        val boarded = activeMap?.boarded ?: activeRide?.boarded ?: false
        val nextStopAt = activeMap?.nextStopAt ?: if (!boarded) activeRide?.boardingAt else null
        val arrivalAt = activeMap?.arrivalAt ?: activeRide?.terminalAt
        val remainingStops = activeMap?.remainingStops ?: 0

        val arrivalClock = arrivalAt?.atZone(zone)?.format(clockFormatter)
        val nextMin = nextStopAt?.let { minutesUntil(it, now) }

        return if (boarded) {
            val nextLabel = when {
                nextMin == null -> nextStop
                nextMin <= 0 -> "$nextStop · jetzt"
                else -> "$nextStop · in $nextMin Min"
            }
            val titleParts = buildList {
                add(line)
                if (arrivalClock != null) add("Ankunft $arrivalClock")
                else if (destination.isNotBlank()) add(destination)
                if (remainingStops == 1) add("1 Halt") else if (remainingStops > 1) add("$remainingStops Halte")
            }
            val secondary = titleParts.joinToString(" · ")
            WatchFaceTransitCopy(
                primaryText = nextLabel,
                secondaryTitle = secondary,
                shortText = when {
                    nextMin != null && nextMin <= 0 -> "jetzt"
                    nextMin != null -> "$nextMin Min"
                    arrivalClock != null -> arrivalClock
                    else -> line.take(7)
                },
                shortTitle = line.take(7),
                description = "Linie $line, nächster Halt $nextLabel, $secondary",
                active = true
            )
        } else {
            val waitLabel = when {
                nextMin == null -> "$line · $nextStop"
                nextMin <= 0 -> "$line jetzt · $nextStop"
                else -> "$line in $nextMin Min · $nextStop"
            }
            val titleParts = buildList {
                if (arrivalClock != null) add("Ankunft $arrivalClock")
                if (destination.isNotBlank()) add(destination)
            }
            val secondary = titleParts.joinToString(" · ").ifBlank { "Abfahrt verfolgt" }
            WatchFaceTransitCopy(
                primaryText = waitLabel,
                secondaryTitle = secondary,
                shortText = when {
                    nextMin != null && nextMin <= 0 -> "jetzt"
                    nextMin != null -> "$nextMin Min"
                    arrivalClock != null -> arrivalClock
                    else -> line.take(7)
                },
                shortTitle = line.take(7),
                description = "$waitLabel, $secondary",
                active = true
            )
        }
    }

    internal fun minutesUntil(target: Instant, now: Instant): Int =
        ceil((target.epochSecond - now.epochSecond) / 60.0).toInt().coerceAtLeast(0)
}

/** The live route map (or the route diagram fallback) as a PHOTO_IMAGE for the full-face slot of Mitfahrt Live. */
class RideMapComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        if (request.complicationType != ComplicationType.PHOTO_IMAGE) {
            listener.onComplicationData(NoDataComplicationData())
            return
        }
        val store = (application as WatchApplication).store
        val map = store.watchFaceMap.value
        val library = store.library.value
        val ride = (if (library.sentAt != Instant.EPOCH) library.ride else null) ?: store.ride.value
        val mapIntent = Intent(this, WatchActivity::class.java).apply {
            putExtra(WatchActivity.EXTRA_VIEW, WatchActivity.VIEW_MAP)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tap = PendingIntent.getActivity(
            this,
            101,
            mapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bitmap = WatchFaceFallbackMapRenderer.rideImage(map, ride)
        if (bitmap == null) {
            listener.onComplicationData(NoDataComplicationData())
            RideTileUpdates.requestPhone(this)
            return
        }

        val data = PhotoImageComplicationData.Builder(
            Icon.createWithBitmap(bitmap),
            PlainComplicationText.Builder("Mitfahrt Live-Buskarte").build()
        ).setTapAction(tap).build()

        listener.onComplicationData(data)
        RideTileUpdates.requestPhone(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData {
        if (type != ComplicationType.PHOTO_IMAGE) return NoDataComplicationData()
        val bitmap = WatchFaceFallbackMapRenderer.renderPreview()
        return PhotoImageComplicationData.Builder(
            Icon.createWithBitmap(bitmap),
            PlainComplicationText.Builder("Mitfahrt Live-Buskarte").build()
        ).build()
    }
}

/**
 * Provides next stop, time to next stop, and arrival ETA for the lower transit bar of the
 * Mitfahrt Live Watch Face (and any other Wear OS watch face supporting LONG_TEXT / SHORT_TEXT).
 */
class RideDetailComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val store = (application as WatchApplication).store
        val map = store.watchFaceMap.value
        val library = store.library.value
        val ride = (if (library.sentAt != Instant.EPOCH) library.ride else null) ?: store.ride.value
        val copy = WatchFaceTransitFormat.format(map, ride, Instant.now())
        val tap = PendingIntent.getActivity(
            this,
            102,
            Intent(this, WatchActivity::class.java).apply {
                putExtra(WatchActivity.EXTRA_VIEW, WatchActivity.VIEW_ACTIVE)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(copy.primaryText).build(),
                PlainComplicationText.Builder(copy.description).build()
            )
                .setTitle(PlainComplicationText.Builder(copy.secondaryTitle).build())
                .setTapAction(tap)
                .build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(copy.shortText).build(),
                PlainComplicationText.Builder(copy.description).build()
            )
                .setTitle(PlainComplicationText.Builder(copy.shortTitle).build())
                .setTapAction(tap)
                .build()

            else -> NoDataComplicationData()
        }
        listener.onComplicationData(data)
        RideTileUpdates.requestPhone(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            PlainComplicationText.Builder("Halt Mitte · in 2 Min").build(),
            PlainComplicationText.Builder("Linie 009, nächster Halt Halt Mitte in 2 Minuten, Ankunft 14:35").build()
        )
            .setTitle(PlainComplicationText.Builder("009 · Ankunft 14:35 · 4 Halte").build())
            .build()

        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("2 Min").build(),
            PlainComplicationText.Builder("Linie 009 in 2 Minuten").build()
        )
            .setTitle(PlainComplicationText.Builder("009").build())
            .build()

        else -> NoDataComplicationData()
    }
}

/**
 * Full-face 450x450 picture for an active ride whose phone has not sent a map tile (demo ride, or a line
 * without verified geometry). It is a route diagram, not a map: no streets are drawn because none are known,
 * and stop ticks and the bus marker appear only when the phone reported stop count and progress.
 * Everything sits in the open band between the clock (to y 148) and the transit slot (from y 284).
 */
internal object WatchFaceFallbackMapRenderer {
    private const val SIZE = 450
    private const val LEFT = 48f
    private const val RIGHT = SIZE - 48f
    private const val TRACK_Y = 222f
    /** More ticks than this blur into a dashed line on a 450 px face, so they are left out. */
    private const val MAX_STOP_TICKS = 12

    // Mitfahrt dark palette: Beere only for the line sign and the travelled part, neutrals for the rest.
    private val berry = Color.rgb(255, 155, 199)
    private val onBerry = Color.rgb(24, 19, 29)
    private val ink = Color.rgb(248, 242, 248)
    private val soft = Color.rgb(203, 192, 206)
    /** About 3:1 against black, so the remaining route stays visible as a graphic. */
    private val track = Color.rgb(102, 92, 108)

    /** The phone's map tile when one arrived, otherwise the route diagram; null without an active ride. */
    fun rideImage(map: WatchFaceMapSnapshot?, ride: WatchRide?): Bitmap? {
        if (map?.active != true && ride?.active != true) return null
        return map?.takeIf { it.active && it.mapPng.isNotEmpty() }?.let { snapshot ->
            runCatching { BitmapFactory.decodeByteArray(snapshot.mapPng, 0, snapshot.mapPng.size) }.getOrNull()
        } ?: renderActive(map, ride)
    }

    fun renderPreview(): Bitmap = render(line = "009", destination = "Halt Süd", boarded = true,
        progress = 0.54f, totalStops = 8, remainingStops = 4)

    private fun renderActive(map: WatchFaceMapSnapshot?, ride: WatchRide?): Bitmap {
        val activeMap = map?.takeIf { it.active }
        val boarded = activeMap?.boarded ?: ride?.boarded ?: false
        return render(
            line = (activeMap?.line?.ifBlank { null } ?: ride?.line).orEmpty().ifBlank { "Bus" },
            destination = (activeMap?.destination?.ifBlank { null } ?: ride?.destination).orEmpty(),
            boarded = boarded,
            // Without a phone snapshot the position is unknown; before boarding the bus is still at the start.
            progress = activeMap?.let { it.progressPercent / 100f } ?: if (!boarded) 0f else null,
            totalStops = activeMap?.totalStops ?: 0,
            remainingStops = activeMap?.remainingStops ?: 0
        )
    }

    private fun render(line: String, destination: String, boarded: Boolean, progress: Float?,
        totalStops: Int, remainingStops: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Line sign as on the bus display: filled Beere, tight 12 px (6 dp) corners, dark number.
        paint.textSize = 22f
        paint.isFakeBoldText = true
        val label = line.take(6)
        val signRight = LEFT + maxOf(56f, paint.measureText(label) + 24f)
        paint.color = berry
        canvas.drawRoundRect(RectF(LEFT, 160f, signRight, 194f), 12f, 12f, paint)
        paint.color = onBerry
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, (LEFT + signRight) / 2f, 185f, paint)

        if (destination.isNotBlank()) {
            paint.color = ink
            paint.textSize = 20f
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(fit(paint, destination, RIGHT - signRight - 14f), signRight + 14f, 184f, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 6f
        paint.color = track
        canvas.drawLine(LEFT, TRACK_Y, RIGHT, TRACK_Y, paint)
        val busX = progress?.let { LEFT + (RIGHT - LEFT) * it.coerceIn(0f, 1f) }
        if (busX != null && busX > LEFT) {
            paint.color = berry
            canvas.drawLine(LEFT, TRACK_Y, busX, TRACK_Y, paint)
        }

        paint.style = Paint.Style.FILL
        if (totalStops in 3..MAX_STOP_TICKS) for (i in 1 until totalStops - 1) {
            val x = LEFT + (RIGHT - LEFT) * i / (totalStops - 1)
            paint.color = Color.BLACK
            canvas.drawCircle(x, TRACK_Y, 6f, paint)
            paint.color = if (busX != null && x <= busX) berry else soft
            canvas.drawCircle(x, TRACK_Y, 3.5f, paint)
        }

        // Start as an open ring, exit as a filled dot.
        paint.color = Color.BLACK
        canvas.drawCircle(LEFT, TRACK_Y, 8f, paint)
        canvas.drawCircle(RIGHT, TRACK_Y, 8f, paint)
        paint.color = ink
        canvas.drawCircle(RIGHT, TRACK_Y, 5.5f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        canvas.drawCircle(LEFT, TRACK_Y, 5f, paint)

        if (busX != null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawCircle(busX, TRACK_Y, 13f, paint)
            paint.color = berry
            canvas.drawCircle(busX, TRACK_Y, 10f, paint)
        }

        paint.style = Paint.Style.FILL
        paint.isFakeBoldText = false
        paint.textSize = 17f
        paint.color = soft
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(when {
            !boarded -> "Wartet auf Einstieg"
            remainingStops == 1 -> "Noch 1 Halt"
            remainingStops > 1 -> "Noch $remainingStops Halte"
            else -> "Unterwegs"
        }, LEFT, 262f, paint)
        // Says plainly that this is a diagram, so nobody reads the straight line as a street.
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Ohne Karte", RIGHT, 262f, paint)
        return bitmap
    }

    private fun fit(paint: Paint, text: String, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val count = paint.breakText(text, true, width - paint.measureText("…"), null)
        return text.take(count).trimEnd() + "…"
    }
}
