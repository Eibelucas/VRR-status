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
import android.os.Bundle
import android.provider.Settings
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
            .setColor(Color.rgb(196, 245, 139))
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
        val next = ride.nextStop(now)
        val remaining = ride.remainingMinutes(now)
        val delay = DelayEvidence.timingFor(ride, ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex))
            ?: DelayEvidence.timingFor(ride, ride.boardingIndex)
        val stale = ride.isStale(now) || state.lastRefreshFailed || !state.running
        val timeSource = when {
            ride.demo -> "Demo"
            stale -> "Veraltet"
            ride.terminal.estimated != null -> "Live"
            ride.departure.estimated != null -> "Geschätzt"
            else -> "Fahrplan"
        }
        builder.setContentTitle("${if (ride.demo) "Demo · " else ""}${ride.departure.line} → ${shortName(ride.terminal.name)}")
            .setContentText(listOfNotNull(if (next == null) "Endhaltestelle erreicht" else "Nächster Halt: ${shortName(next.name)}",
                delay?.let { "${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" }).joinToString(" · "))
            .setSubText("$timeSource · ${remaining?.let { "$it Min bis Endhalt" } ?: "Ankunft unbekannt"}")
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(196, 245, 139))))
                .setProgress((ride.progress(now) * 100).toInt())
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_bus)))
            // Platform extra works on API 36.0 too; Builder#setRequestPromotedOngoing needs 36.1.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            builder.setShortCriticalText(remaining?.let { "$it Min" } ?: ride.departure.line)
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(
                "${next?.let { "Nächster Halt: ${shortName(it.name)}" } ?: "Endhaltestelle erreicht"}\n" +
                    "${remaining?.let { "$it Min" } ?: "–"} bis ${shortName(ride.terminal.name)} · $timeSource" +
                    (delay?.let { "\n${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" } ?: "")))
                .setProgress(100, (ride.progress(now) * 100).toInt(), false)
        }
        return builder.build()
    }

    fun post(state: TrackingState) { manager.notify(ID, build(state)) }

    companion object {
        const val ID = 7
        const val CHANNEL = "ride_status"

        fun openSettings(context: Context) {
            val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            val intent = if (Build.VERSION.SDK_INT >= 36 && enabled)
                Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            runCatching { context.startActivity(intent) }.onFailure {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
    }
}
