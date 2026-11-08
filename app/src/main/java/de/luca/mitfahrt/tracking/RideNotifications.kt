package de.luca.mitfahrt.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import de.luca.mitfahrt.MainActivity
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.reports.DelayEvidence
import java.time.Instant

class RideNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Aktuelle Busfahrt", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Fahrtstatus auf dem Sperrbildschirm und in der Now Bar"
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun build(state: TrackingState, now: Instant = Instant.now()): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, RideService::class.java).setAction(RideService.STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ride = state.ride
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(if (ride != null) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .setDeleteIntent(stop)
            .setColor(Color.rgb(180, 0, 85))
        if (ride == null) {
            val title = when (state.wifi) {
                WifiSignal.CONNECTED -> "NEW-Hotspot verbunden"
                WifiSignal.NEARBY -> "NEW-Hotspot in der Nähe"
                else -> "Bereit für deine Busfahrt"
            }
            return builder.setContentTitle(title)
                .setContentText(if (state.candidates.isNotEmpty()) "Passende Fahrten gefunden · Zum Auswählen tippen" else "Erkennung läuft · Zum Auswählen einer Fahrt tippen")
                .setStyle(Notification.BigTextStyle().bigText("${state.message ?: "WLAN und Standort werden geprüft."} Mit Beenden stoppst du die Erkennung."))
                .build()
        }
        val waiting = !ride.boarded
        val boardingTime = ride.boardingTime()
        val liveBoarding = if (state.running && !state.lastRefreshFailed) ride.liveBoardingTime(now) else null
        val waitingTime = if (ride.demo) boardingTime else liveBoarding
        val next = ride.nextStopToTarget(now)
        val remaining = if (waiting) waitingTime?.let { minutesUntil(it.at, now) } else ride.minutesToTarget(now)
        val delay = DelayEvidence.timingFor(ride, ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex))
            ?: DelayEvidence.timingFor(ride, ride.boardingIndex)
        val stale = ride.isStale(now) || state.lastRefreshFailed || !state.running
        val timeSource = when {
            ride.demo -> "Demo"
            stale -> "Veraltet"
            waiting -> if (liveBoarding != null) "Live" else "Keine Live-Zeit"
            ride.displayTarget().estimated != null -> "Live"
            ride.departure.estimated != null -> "Geschätzt"
            else -> "Fahrplan"
        }
        val headline = if (waiting) {
            when {
                ride.departure.cancelled -> "Bus fällt aus"
                waitingTime == null -> "Keine Live-Zeit · Fahrplan ${clockTime(ride.stops.getOrNull(ride.boardingIndex)?.planned ?: ride.departure.planned)}"
                !waitingTime.at.isAfter(now) -> "Bus wird erwartet"
                else -> "${if (waitingTime.isArrival) "Bus kommt" else "Abfahrt"} in ${countdownUntil(waitingTime.at, now)}"
            }
        } else if (next == null) "Ziel laut Fahrtverlauf erreicht" else "Nächster Halt: ${shortName(next.name)}"
        val title = "${if (ride.demo) "Demo · " else ""}${ride.departure.line} → ${shortName(ride.displayTarget().name)}"
        val text = listOfNotNull(headline,
            delay?.let { "${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" }).joinToString(" · ")
        builder.setContentTitle(title)
            .setContentText(text)
            .setSubText(if (waiting) "$timeSource · ${shortName(ride.departure.stopName)}" else "$timeSource · ${remaining?.let { "$it Min bis Ziel" } ?: "Ankunft unbekannt"}")
        if (waiting && !ride.departure.cancelled && waitingTime?.at?.isAfter(now) == true) {
            builder.setWhen(waitingTime.at.toEpochMilli()).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(180, 0, 85))))
                .setProgress(if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt())
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_bus)))
            builder.addExtras(NowBarSupport.extras(context, title, "$text · $timeSource",
                "${ride.departure.line} · ${remaining?.let { "$it Min" } ?: "Mitfahrt"}",
                if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt()))
            builder.setShortCriticalText(remaining?.let { "$it Min" } ?: ride.departure.line)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(
                "$headline\n" +
                    (if (waiting) "${shortName(ride.departure.stopName)} · $timeSource" else "${remaining?.let { "$it Min" } ?: "–"} bis ${shortName(ride.displayTarget().name)} · $timeSource") +
                    (delay?.let { "\n${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" } ?: "")))
                .setProgress(100, if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36 && !notification.hasPromotableCharacteristics()) {
            // Initial Android 16 required EXTRA_COLORIZED; later releases changed the
            // promotion contract. Ask the actual framework instead of guessing from SDK.
            val legacy = builder.setColorized(true).build()
            if (legacy.hasPromotableCharacteristics()) return legacy
        }
        return notification
    }

    fun post(state: TrackingState) { manager.notify(ID, build(state)) }

    companion object {
        const val ID = 7
        const val CHANNEL = "ride_status"

        fun openSettings(context: Context) {
            NowBarSupport.openSettings(context)
        }
    }
}
