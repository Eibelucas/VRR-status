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
        manager.createNotificationChannel(NotificationChannel(HINT_CHANNEL, "Fahrt-Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Busankunft, Ausstieg, Verspätungen und Ausfälle"
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun build(state: TrackingState, now: Instant = Instant.now()): Notification {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, RideService::class.java).setAction(RideService.STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ride = state.ride
        val settingStore = AppSettingsStore(context)
        val settings = settingStore.current()
        settingStore.close()
        val promoted = settings.bool("nowbar_enabled", true)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(if (settings.bool("lockscreen_details", true)) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setCategory(if (ride != null) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_SERVICE)
            .setDeleteIntent(stop)
            .setColor(Color.rgb(180, 0, 85))
        if (!settings.bool("lockscreen_details", true)) builder.setPublicVersion(Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus).setContentTitle("Mitfahrt").setContentText("Aktive Fahrt")
            .setVisibility(Notification.VISIBILITY_PUBLIC).build())
        settings.string("notification_actions", "board,arrival,stop").split(',').map(String::trim)
            .distinct().filter { it in setOf("board", "arrival", "map", "stop") }.take(3).forEach { action ->
                when (action) {
                    "board" -> if (ride != null && !ride.boarded && !ride.departure.cancelled) {
                        val pending = PendingIntent.getService(context, 2, Intent(context, RideService::class.java)
                            .setAction(RideService.BOARD), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        builder.addAction(Notification.Action.Builder(null, "Bin im Bus", pending).build())
                    }
                    "arrival" -> if (ride != null && !ride.demo) {
                        val pending = PendingIntent.getService(context, 3, Intent(context, RideService::class.java)
                            .setAction(RideService.MARK_ARRIVAL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        builder.addAction(Notification.Action.Builder(null, "Ankunft merken", pending).build())
                    }
                    "map" -> if (ride != null && !ride.demo) {
                        val pending = PendingIntent.getActivity(context, 4, Intent(context, MainActivity::class.java)
                            .putExtra("open_live_map", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        builder.addAction(Notification.Action.Builder(null, "Karte", pending).build())
                    }
                    "stop" -> builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
                }
            }
        if (ride == null) builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
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
        val nextInfo = NextStopResolver.resolve(state, now, settings.bool("gps_next_stop", true))
        val next = nextInfo?.stop
        val remaining = if (waiting) waitingTime?.let { minutesUntil(it.at, now) }
            else if (!ride.isStale(now) && !state.lastRefreshFailed && state.running)
                ride.displayTarget().estimated?.let { minutesUntil(it, now) } else null
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
        } else when {
            nextInfo?.source == NextStopSource.UNCERTAIN -> "Nächster Halt nicht sicher erkannt"
            next == null -> "Ziel laut Fahrtverlauf erreicht"
            nextInfo?.source == NextStopSource.GPS -> "Nächster Halt: ${shortName(next.name)} · per GPS"
            else -> "Nächster Halt: ${shortName(next.name)}"
        }
        val fullTitle = "${if (ride.demo) "Demo · " else ""}${ride.departure.line} → ${shortName(ride.displayTarget().name)}"
        val mode = settings.string("nowbar_content", "full")
        val title = when (mode) {
            "time" -> remaining?.let { "$it Min · Linie ${ride.departure.line}" } ?: "Linie ${ride.departure.line} · Keine Live-Zeit"
            "next" -> if (waiting) fullTitle else "${ride.departure.line} · ${shortName(next?.name.orEmpty())}"
            else -> fullTitle
        }
        val fullText = listOfNotNull(headline,
            delay?.let { "${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" }).joinToString(" · ")
        val text = when (mode) {
            "line" -> if (ride.departure.cancelled) "Bus fällt aus" else timeSource
            "time" -> if (waiting) headline else remaining?.let { "$it Min bis Ziel" } ?: "Keine Live-Zeit"
            "next" -> headline
            else -> fullText
        }
        builder.setContentTitle(title)
            .setContentText(text)
            .setSubText(if (waiting) "$timeSource · ${shortName(ride.departure.stopName)}" else "$timeSource · ${remaining?.let { "$it Min bis Ziel" } ?: "Ankunft unbekannt"}")
        if (waiting && mode in setOf("full", "time") && !ride.departure.cancelled && waitingTime?.at?.isAfter(now) == true) {
            builder.setWhen(waitingTime.at.toEpochMilli()).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
        }
        if (Build.VERSION.SDK_INT >= 36 && promoted) {
            builder.setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(180, 0, 85))))
                .setProgress(if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt())
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_bus)))
            builder.addExtras(NowBarSupport.extras(context, title, if (mode == "line") "Mitfahrt · $timeSource" else "$text · $timeSource",
                if (mode == "line") ride.departure.line else "${ride.departure.line} · ${remaining?.let { "$it Min" } ?: "Mitfahrt"}",
                if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt()))
            builder.setShortCriticalText(if (mode == "line") ride.departure.line else remaining?.let { "$it Min" } ?: ride.departure.line)
        } else {
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", false) })
            builder.setStyle(Notification.BigTextStyle().bigText(
                "$headline\n" +
                    (if (waiting) "${shortName(ride.departure.stopName)} · $timeSource" else "${remaining?.let { "$it Min" } ?: "–"} bis ${shortName(ride.displayTarget().name)} · $timeSource") +
                    (delay?.let { "\n${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" } ?: "")))
                .setProgress(100, if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36 && promoted && !notification.hasPromotableCharacteristics()) {
            // Initial Android 16 required EXTRA_COLORIZED; later releases changed the
            // promotion contract. Ask the actual framework instead of guessing from SDK.
            val legacy = builder.setColorized(true).build()
            if (legacy.hasPromotableCharacteristics()) return legacy
        }
        return notification
    }

    fun post(state: TrackingState) { manager.notify(ID, build(state)) }

    fun hint(eventId: String, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val settingsStore = AppSettingsStore(context)
        val details = settingsStore.current().bool("lockscreen_details", true)
        settingsStore.close()
        val notification = Notification.Builder(context, HINT_CHANNEL).setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(120_000).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(if (details) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setColor(Color.rgb(180, 0, 85)).build()
        manager.notify(100 + (eventId.hashCode() and 0x7fff), notification)
    }

    companion object {
        const val ID = 7
        const val CHANNEL = "ride_status"
        const val HINT_CHANNEL = "journey_hints"

        fun openSettings(context: Context) {
            NowBarSupport.openSettings(context)
        }
    }
}
