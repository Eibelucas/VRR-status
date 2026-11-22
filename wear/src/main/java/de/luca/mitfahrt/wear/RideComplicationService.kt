package de.luca.mitfahrt.wear

import androidx.wear.watchface.complications.data.CountDownTimeReference

import androidx.wear.watchface.complications.data.TimeDifferenceStyle

import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText

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
        val store = (application as WatchApplication).store
        val library = store.library.value
        val ride = (if (library.sentAt != Instant.EPOCH) library.ride else null) ?: store.ride.value
        val face = ComplicationFace.of(ride, library.tile, Instant.now())
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = Instant.now()
        // A live countdown is drawn by the watch face itself; no extra updates are needed for it.
        val countdown = face.countdownTo?.takeIf { it.isAfter(now) }?.let {
            TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(it)).build()
        }
        val valid = face.validUntil?.takeIf { it.isAfter(now) }
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(countdown?.takeIf { face.longValue == null } ?: text(face.longValue ?: face.value), text(face.description))
                .setTitle(text(face.title)).setTapAction(tap)
                .apply { if (valid != null) setValidTimeRange(TimeRange.between(now, valid)) }.build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(countdown ?: text(face.value.take(7)), text(face.description))
                .setTitle(text(face.title.take(7))).setTapAction(tap)
                .apply { if (valid != null) setValidTimeRange(TimeRange.between(now, valid)) }.build()
            else -> NoDataComplicationData()
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}

object RideComplicationUpdates {
    fun request(context: Context) {
        listOf(
            RideComplicationService::class.java,
            RideMapComplicationService::class.java,
            RideDetailComplicationService::class.java
        ).forEach { service ->
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context,
                    ComponentName(context, service)
                ).requestUpdateAll()
            }
        }
    }
}
