package de.luca.mitfahrt.wear

import android.content.Context
import android.os.SystemClock
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.Wearable
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import de.luca.mitfahrt.shared.WatchProtocol
import de.luca.mitfahrt.shared.WatchRide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** No polling or location access: render the latest phone snapshot already saved on the watch. */
class RideTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val snapshot = (application as WatchApplication).store.ride.value
        val now = Instant.now()
        val timeline = TimelineBuilders.Timeline.Builder()
            // Indefinite fallback takes over even if no later process/update request runs.
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                .setLayout(layout(snapshot, now, expired = true)).build())
        for (window in rideTileWindows(snapshot, now)) {
            timeline.addTimelineEntry(TimelineBuilders.TimelineEntry.Builder()
                .setValidity(TimelineBuilders.TimeInterval.Builder().setStartMillis(window.startMillis).setEndMillis(window.endMillis).build())
                .setLayout(layout(snapshot, Instant.ofEpochMilli(window.startMillis), expired = false)).build())
        }
        return immediate(TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setTileTimeline(timeline.build()).setFreshnessIntervalMillis(60_000).build())
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion("1").build())

    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        RideTileUpdates.requestPhone(this)
        RideTileUpdates.request(this, force = true)
    }

    private fun layout(ride: WatchRide?, now: Instant, expired: Boolean): LayoutElementBuilders.Layout {
        val current = ride?.takeIf { it.active }
        val stale = current != null && (expired || current.stale(now))
        val target = current?.let { if (it.boarded) it.terminalAt else it.boardingAt }
        val seconds = target?.let { (it.epochSecond - now.epochSecond).coerceAtLeast(0) }
        val value = when {
            current == null -> "Keine Fahrt"
            current.cancelled -> "Fällt aus"
            stale -> "–"
            seconds == null -> "–"
            !current.boarded && seconds == 0L -> "Erwartet"
            else -> "${ceil(seconds / 60.0).toInt()} Min"
        }
        val status = when {
            current == null -> "Am Handy Fahrt wählen"
            current.demo && stale -> "Demo · Daten veraltet"
            current.demo -> "Demofahrt"
            !current.running -> "Fahrt pausiert"
            stale -> "Daten veraltet"
            current.cancelled -> "Andere Fahrt wählen"
            else -> listOfNotNull(current.source.takeIf { it.isNotBlank() }, current.fetchedAt?.let {
                "Stand ${it.atZone(ZoneId.of("Europe/Berlin")).format(DateTimeFormatter.ofPattern("HH:mm"))}"
            }).joinToString(" · ")
        }
        val detail = when {
            current == null -> "Mitfahrt öffnen"
            current.boarded -> current.nextStop
            current.boardingIsArrival -> "bis dein Bus kommt"
            else -> "bis zur Abfahrt"
        }
        val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
            .setPackageName(packageName).setClassName(WatchActivity::class.java.name).build()).build()
        val clickable = ModifiersBuilders.Clickable.Builder().setId("open-ride").setOnClick(open).build()
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("MITFAHRT", 11f, PINK))
            .addContent(space(8f))
        if (current != null) column.addContent(text("${current.line} → ${current.destination}", 13f, WHITE, lines = 1))
            .addContent(space(6f))
        val card = LayoutElementBuilders.Column.Builder().setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(8f)).build())
                .setBackground(ModifiersBuilders.Background.Builder().setColor(color(BERRY))
                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(22f)).build()).build()).build())
            .addContent(text(value, if (value.length > 7) 23f else 32f, WHITE, bold = true))
            .addContent(space(4f)).addContent(text(detail, 12f, WHITE, lines = 1)).build()
        column.addContent(card).addContent(space(8f))
            .addContent(text(status, 11f, if (stale || current?.cancelled == true) AMBER else SOFT, lines = 1))
        val root = LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(clickable)
                .setPadding(ModifiersBuilders.Padding.Builder().setAll(DimensionBuilders.dp(16f)).build())
                .setBackground(ModifiersBuilders.Background.Builder().setColor(color(BLACK)).build())
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(
                    listOfNotNull("Mitfahrt", current?.line, current?.destination, value, detail, status, "App öffnen").joinToString(". ")).build())
                .build()).addContent(column.build()).build()
        return LayoutElementBuilders.Layout.Builder().setRoot(root).build()
    }

    private fun <T> immediate(value: T): ListenableFuture<T> = CallbackToFutureAdapter.getFuture { completer ->
        completer.set(value)
        "Mitfahrt tile"
    }

    private fun text(value: String, size: Float, argb: Int, lines: Int = 1, bold: Boolean = false): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(lines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(size))
                .setColor(color(argb)).setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build())
            .build()

    private fun space(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(height)).build()
    private fun color(argb: Int) = ColorBuilders.ColorProp.Builder(argb).build()

    companion object {
        private const val BERRY = 0xFFB40055.toInt()
        private const val PINK = 0xFFFF9BC7.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val SOFT = 0xFFCAC0D0.toInt()
        private const val AMBER = 0xFFFFCB83.toInt()
    }
}

/** Change pushes plus a minute timeline; heartbeats do not cause a renderer update every 15 s. */
object RideTileUpdates {
    private var lastSignature: WatchRide? = null
    private var lastUpdate = Long.MIN_VALUE
    private var lastPhoneRequest = Long.MIN_VALUE

    @Synchronized fun request(context: Context, force: Boolean = false) {
        val signature = (context.applicationContext as? WatchApplication)?.store?.ride?.value
            ?.copy(sentAt = Instant.EPOCH, fetchedAt = null)
        val now = SystemClock.elapsedRealtime()
        if (!force && signature == lastSignature && lastUpdate != Long.MIN_VALUE && now - lastUpdate < 30_000) return
        runCatching { TileService.getUpdater(context).requestUpdate(RideTileService::class.java) }
        lastSignature = signature; lastUpdate = now
    }

    @Synchronized fun requestPhone(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastPhoneRequest != Long.MIN_VALUE && now - lastPhoneRequest < 30_000) return
        lastPhoneRequest = now
        runCatching {
            val app = context.applicationContext
            Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
                for (node in nodes) Wearable.getMessageClient(app).sendMessage(node.id, WatchProtocol.REQUEST, byteArrayOf())
                    .addOnFailureListener { /* The cached tile remains explicitly timestamped. */ }
            }.addOnFailureListener { /* No phone connection; the stale fallback remains available. */ }
        }
    }
}
