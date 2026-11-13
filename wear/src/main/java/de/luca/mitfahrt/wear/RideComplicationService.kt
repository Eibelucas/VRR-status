package de.luca.mitfahrt.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The face opens the companion; old phone snapshots never become a live countdown. */
class RideComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(content(request.complicationType))
        RideTileUpdates.requestPhone(this)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("608 · 08:35"), text("Mitfahrt, Linie 608 um 08:35"))
            .setTitle(text("Busstatus")).build()
        else -> ShortTextComplicationData.Builder(text("08:35"), text("Linie 608 um 08:35"))
            .setTitle(text("608")).build()
    }

    private fun content(type: ComplicationType): ComplicationData {
        val ride = (application as WatchApplication).store.ride.value
        val now = Instant.now()
        val current = ride?.takeIf { it.active }
        val fresh = current != null && !current.stale(now) && !current.cancelled
        val at = if (current?.boarded == true) current.terminalAt else current?.boardingAt
        val clock = at?.atZone(ZoneId.of("Europe/Berlin"))?.format(DateTimeFormatter.ofPattern("HH:mm"))
        val value = when {
            current == null -> "–"
            current.cancelled -> "Ausfall"
            !fresh -> "Alt"
            clock == null -> "–"
            else -> clock
        }
        val title = current?.line?.take(7) ?: "Mitfahrt"
        val description = listOfNotNull("Mitfahrt", current?.line, current?.destination, value,
            if (current != null && !fresh) "Daten veraltet" else null).joinToString(" · ")
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val expires = current?.let { listOfNotNull(it.sentAt, it.fetchedAt).minOrNull()?.plusSeconds(120) }
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("$title · $value"), text(description))
                .setTitle(text(if (current?.boarded == true) "${current.destination.take(24)}" else "Nächster Bus"))
                .setTapAction(tap).apply { if (fresh && expires != null && expires > now) setValidTimeRange(TimeRange.between(now, expires)) }.build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(value.take(7)), text(description))
                .setTitle(text(title)).setTapAction(tap)
                .apply { if (fresh && expires != null && expires > now) setValidTimeRange(TimeRange.between(now, expires)) }.build()
            else -> NoDataComplicationData()
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}

object RideComplicationUpdates {
    fun request(context: Context) {
        runCatching { ComplicationDataSourceUpdateRequester.create(context,
            ComponentName(context, RideComplicationService::class.java)).requestUpdateAll() }
    }
}
