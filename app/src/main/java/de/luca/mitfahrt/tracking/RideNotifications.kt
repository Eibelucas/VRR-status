package de.luca.mitfahrt.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import de.luca.mitfahrt.MainActivity
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.reports.DelayEvidence
import de.luca.mitfahrt.widgets.MapWidgetMode
import de.luca.mitfahrt.widgets.OsmCachedTileReader
import de.luca.mitfahrt.widgets.RouteSketch
import de.luca.mitfahrt.widgets.TileCoord
import de.luca.mitfahrt.widgets.WidgetTilePrefetcher
import java.time.Instant

class RideNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Aktuelle Busfahrt", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Fahrtstatus auf dem Sperrbildschirm und in der Now Bar"
            setSound(null, null)
            enableVibration(false)
        })
        manager.createNotificationChannel(NotificationChannel(MAP_CHANNEL, "Live-Buskarte (Sperrbildschirm)", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Große Live-Buskarte auf dem Sperrbildschirm und in der Benachrichtigungsleiste"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(HINT_CHANNEL, "Fahrt-Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Busankunft, Ausstieg, Verspätungen und Ausfälle"
            setSound(null, null)
            enableVibration(false)
        })
    }

    fun build(state: TrackingState, now: Instant = Instant.now()): Notification =
        buildWithTiles(state, now).first

    private fun buildWithTiles(state: TrackingState, now: Instant): Pair<Notification, List<TileCoord>> {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, RideService::class.java).setAction(RideService.STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ride = state.ride
        val settings = readSettings()
        val promoted = settings.bool("nowbar_enabled", true)
        val lockscreenDetails = settings.bool("lockscreen_details", true)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(if (lockscreenDetails) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setCategory(if (ride != null) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_SERVICE)
            .setDeleteIntent(stop)
            .setColor(Color.rgb(180, 0, 85))
        if (!lockscreenDetails) builder.setPublicVersion(Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus).setContentTitle("Mitfahrt").setContentText("Aktive Fahrt")
            .setVisibility(Notification.VISIBILITY_PUBLIC).build())
        val app = context.applicationContext as? de.luca.mitfahrt.MitfahrtApplication
        val handoff = state.transferHandoff
        if (ride == null && handoff != null) {
            val nextLegIntent = PendingIntent.getService(context, 7, Intent(context, RideService::class.java)
                .setAction(RideService.START_NEXT_LEG), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val platform = PlatformHubHelper.formatPlatform(handoff.nextPlatform)
            val dep = handoff.nextDepartureAt?.let { "um ${clockTime(it)}" }
            val info = listOfNotNull("ab ${handoff.nextStopName}", dep, platform).joinToString(" · ")
            builder.addAction(Notification.Action.Builder(null, handoff.label, nextLegIntent).build())
            builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            return builder.setContentTitle("Umstieg: ${handoff.nextLine} -> ${handoff.nextDestination}")
                .setContentText(info)
                .setStyle(Notification.BigTextStyle().bigText("$info\nTippe auf „${handoff.label}“, um den nächsten Abschnitt direkt zu begleiten."))
                .build() to emptyList()
        }
        var stopAdded = false
        if (ride != null && !ride.boarded && !ride.demo && !ride.departure.cancelled && state.missedBusPrompt) {
            val passed = PendingIntent.getService(context, 8, Intent(context, RideService::class.java)
                .setAction(RideService.MISSED_PASSED), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val full = PendingIntent.getService(context, 9, Intent(context, RideService::class.java)
                .setAction(RideService.MISSED_FULL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Durchgefahren", passed).build())
            builder.addAction(Notification.Action.Builder(null, "Zu voll", full).build())
            builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            stopAdded = true
        } else {
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
                        "stop" -> {
                            stopAdded = true
                            builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
                        }
                    }
                }
        }
        if (ride == null && !stopAdded) builder.addAction(Notification.Action.Builder(null, "Beenden", stop).build())
        if (ride == null) {
            val title = when (state.wifi) {
                WifiSignal.CONNECTED -> "NEW-Hotspot verbunden"
                WifiSignal.NEARBY -> "NEW-Hotspot in der Nähe"
                else -> "Bereit für deine Busfahrt"
            }
            return builder.setContentTitle(title)
                .setContentText(if (state.candidates.isNotEmpty()) "Passende Fahrten gefunden · Zum Auswählen tippen" else "Erkennung läuft · Zum Auswählen einer Fahrt tippen")
                .setStyle(Notification.BigTextStyle().bigText("${state.message ?: "WLAN und Standort werden geprüft."} Mit Beenden stoppst du die Erkennung."))
                .build() to emptyList()
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
        val matchingPlan = if (waiting) app?.routePlans?.current()?.routes?.let { LeaveCountdown.matchingRoute(ride, it) } else null
        val freshLocation = state.location?.takeIf { state.locationAt?.let { at -> now.epochSecond - at.epochSecond in -30..90 } == true }
        val leaveGuidance = if (waiting && waitingTime != null && !ride.departure.cancelled) {
            LeaveCountdown.compute(
                boardingAt = waitingTime.at,
                cancelled = ride.departure.cancelled,
                now = now,
                plannedWalkMinutes = matchingPlan?.walkingMinutes ?: settings.int("routes_default_walk_minutes", 5),
                bufferMinutes = matchingPlan?.bufferMinutes ?: settings.int("routes_default_buffer_minutes", 2),
                liveLocation = freshLocation,
                stopPoint = ride.stops.getOrNull(ride.boardingIndex)?.point
            )?.takeIf { it.state != ReachabilityState.DEPARTED }
        } else null
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
            val base = when {
                ride.departure.cancelled -> "Bus fällt aus"
                waitingTime == null -> "Keine Live-Zeit · Fahrplan ${clockTime(ride.stops.getOrNull(ride.boardingIndex)?.planned ?: ride.departure.planned)}"
                !waitingTime.at.isAfter(now) -> "Bus wird erwartet"
                else -> "${if (waitingTime.isArrival) "Bus kommt" else "Abfahrt"} in ${countdownUntil(waitingTime.at, now)}"
            }
            val leaveChip = leaveGuidance?.chipLabel()?.takeIf { it.isNotBlank() }
            if (leaveChip != null && waitingTime != null && waitingTime.at.isAfter(now)) "$leaveChip · $base" else base
        } else when {
            nextInfo?.source == NextStopSource.UNCERTAIN -> "Nächster Halt nicht sicher erkannt"
            next == null -> "Ziel laut Fahrtverlauf erreicht"
            nextInfo?.source == NextStopSource.GPS -> "Nächster Halt: ${shortName(next.name)} · per GPS"
            else -> "Nächster Halt: ${shortName(next.name)}"
        }
        val hubPlatform = if (waiting && PlatformHubHelper.isMultiPlatformHub(ride.departure.stopId, ride.departure.stopName)) {
            PlatformHubHelper.formatPlatform(ride.departure.platform)
        } else null
        val fullTitle = listOfNotNull(
            if (ride.demo) "Demo" else null,
            hubPlatform,
            "${ride.departure.line} -> ${shortName(ride.displayTarget().name)}"
        ).joinToString(" · ")
        val mode = settings.string("nowbar_content", "full")
        val builtInTitle = when (mode) {
            "time" -> remaining?.let { "$it Min · Linie ${ride.departure.line}" } ?: "Linie ${ride.departure.line} · Keine Live-Zeit"
            "next" -> if (waiting) fullTitle else "${ride.departure.line} · ${shortName(next?.name.orEmpty())}"
            else -> fullTitle
        }
        // Leg of an accompanied journey: prefer fresh live forecast, fall back to clearly labelled plan time.
        val companion = app?.companion?.state?.value?.takeIf { it.trackedKey == ride.departure.key }
        val transfer = companion?.let { comp ->
            val nextLeg = comp.nextLeg
            val freshNext = app.companionNext.value?.takeIf { next ->
                nextLeg != null && next.departure.key == nextLeg.asDeparture(comp.journey.fetchedAt)?.key &&
                    now.epochSecond - next.fetchedAt.epochSecond in -30..180
            }
            if (freshNext != null && nextLeg != null) {
                val eval = JourneyCompanions.transfer(comp, ride, freshNext, now)
                val depTime = freshNext.stops.getOrNull(freshNext.boardingIndex)?.estimatedDeparture ?: freshNext.departure.effective
                val plat = PlatformHubHelper.formatPlatform(freshNext.departure.platform.ifBlank { nextLeg.origin.platform })
                val line = nextLeg.line.ifBlank { nextLeg.lineName }
                val riskTag = when {
                    freshNext.departure.cancelled || eval?.outgoingCancelled == true -> "fällt aus"
                    eval?.risk == TransferRisk.MISSED -> "verpasst (Live)"
                    eval?.risk == TransferRisk.TIGHT -> "knapp (${(eval.slackSeconds / 60).coerceAtLeast(0)} Min)"
                    else -> "um ${clockTime(depTime)} (Live)"
                }
                listOfNotNull("Umstieg: $line $riskTag", plat).joinToString(", ")
            } else {
                JourneyCompanions.transferHint(comp)?.let { "$it (Plan)" }
            }
        }
        val fullText = listOfNotNull(headline,
            delay?.let { "${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" }, transfer).joinToString(" · ")
        val builtInText = when (mode) {
            "line" -> if (ride.departure.cancelled) "Bus fällt aus" else timeSource
            "time" -> if (waiting) headline else remaining?.let { "$it Min bis Ziel" } ?: "Keine Live-Zeit"
            "next" -> headline
            else -> fullText
        }
        // Own texts from placeholders replace the built-in wording where the user wrote one.
        // The wording already worked out above stays the source for these four, so own texts match the built-in ones.
        val values = TextTemplates.values(state, companion,
            now, settings.bool("gps_next_stop", true) && settings.string("location_mode") != "off", settings.bool("gps_travel_time", true)) + mapOf("status" to headline, "quelle" to timeSource,
            "minuten" to remaining?.toString().orEmpty(), "umstieg" to transfer.orEmpty())
        val customTitle = TextTemplates.custom(settings, if (waiting) "tpl_nowbar_title_wait" else "tpl_nowbar_title_ride", values)
        val customText = TextTemplates.custom(settings, if (waiting) "tpl_nowbar_text_wait" else "tpl_nowbar_text_ride", values)
        val customChip = TextTemplates.custom(settings, "tpl_nowbar_chip", values)
        val usesCalculation = TextTemplates.hasCalculation(settings,
            listOf(if (waiting) "tpl_nowbar_title_wait" else "tpl_nowbar_title_ride",
                if (waiting) "tpl_nowbar_text_wait" else "tpl_nowbar_text_ride", "tpl_nowbar_chip"), values)
        val displaySource = if (usesCalculation) "$timeSource · berechnet" else timeSource
        val title = customTitle ?: builtInTitle
        val text = customText ?: builtInText
        builder.setContentTitle(title)
            .setContentText(text)
            .setSubText(PositionHint.text(state, now)?.takeIf { settings.bool("notification_position", true) }
                ?: if (waiting) "$displaySource · ${shortName(ride.departure.stopName)}" else "$displaySource · ${remaining?.let { "$it Min bis Ziel" } ?: "Ankunft unbekannt"}")
        if (waiting && mode in setOf("full", "time") && !ride.departure.cancelled && waitingTime?.at?.isAfter(now) == true) {
            builder.setWhen(waitingTime.at.toEpochMilli()).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
        }
        // Attach a live bus-focus map thumbnail directly onto the expanded Now Bar notification
        // (setLargeIcon stays 100% compatible with Android 16 ProgressStyle / hasPromotableCharacteristics).
        var missingTiles: List<TileCoord> = emptyList()
        if (!ride.demo && lockscreenDetails && settings.bool("notification_map", true)) {
            val rendered = renderMapBitmap(state, settings, now, width = 256, height = 256, mode = mapMode(context))
            if (rendered != null) {
                builder.setLargeIcon(rendered.first)
                missingTiles = rendered.second
            }
        }
        if (Build.VERSION.SDK_INT >= 36 && promoted) {
            val leavePill = leaveGuidance?.shortChip()?.takeIf { it.isNotBlank() }
            val defaultChip = if (mode == "line") ride.departure.line
                else if (waiting && leavePill != null) "${ride.departure.line} · $leavePill"
                else "${ride.departure.line} · ${remaining?.let { "$it Min" } ?: "Mitfahrt"}"
            val criticalChip = customChip?.takeIf { it.isNotBlank() }
                ?: if (mode == "line") ride.departure.line
                else if (waiting && leavePill != null) leavePill
                else remaining?.let { "$it Min" } ?: ride.departure.line
            builder.setStyle(Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(180, 0, 85))))
                .setProgress(if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt())
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_bus)))
            builder.addExtras(NowBarSupport.extras(context, title, if (mode == "line") "Mitfahrt · $displaySource" else "$text · $displaySource",
                customChip ?: defaultChip,
                if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt()))
            builder.setShortCriticalText(criticalChip)
        } else {
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", false) })
            builder.setStyle(Notification.BigTextStyle().bigText(
                "$headline\n" +
                    (if (waiting) "${shortName(ride.departure.stopName)} · $timeSource" else "${remaining?.let { "$it Min" } ?: "–"} bis ${shortName(ride.displayTarget().name)} · $timeSource") +
                    (delay?.let { "\n${it.shortDelay}${if (it.fromMonitor) " bei Einstieg" else ""}" } ?: "") +
                    (transfer?.let { "\n$it" } ?: "")))
                .setProgress(100, if (waiting) 0 else (ride.progressToTarget(now) * 100).toInt(), false)
        }
        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= 36 && promoted && !notification.hasPromotableCharacteristics()) {
            // Initial Android 16 required EXTRA_COLORIZED; later releases changed the
            // promotion contract. Ask the actual framework instead of guessing from SDK.
            val legacy = builder.setColorized(true).build()
            if (legacy.hasPromotableCharacteristics()) return legacy to missingTiles
        }
        return notification to missingTiles
    }

    /**
     * Full-width live bus map card for the Lockscreen and expanded Notification Shade.
     * Kept separate from the promoted Now Bar notification because Android 16 forbids BigPictureStyle
     * on promoted Live Updates, so this preserves Now Bar promotion while still showing the full street map.
     */
    fun buildMapNotification(state: TrackingState, now: Instant = Instant.now()): Notification? =
        buildMapNotificationWithTiles(state, now)?.first

    private fun buildMapNotificationWithTiles(state: TrackingState, now: Instant): Pair<Notification, List<TileCoord>>? {
        val ride = state.ride?.takeUnless { it.demo } ?: return null
        if (!state.running) return null
        val settings = readSettings()
        if (!settings.bool("notification_map", true) || !settings.bool("lockscreen_details", true)) return null
        val activeMode = mapMode(context)
        val rendered = renderMapBitmap(state, settings, now, width = 900, height = 460, mode = activeMode) ?: return null
        val (bitmap, missing) = rendered

        val openMap = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java).putExtra("open_live_map", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleMode = PendingIntent.getService(
            context,
            6,
            Intent(context, RideService::class.java).setAction(RideService.TOGGLE_NOTIFICATION_MAP_MODE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val modeLabel = if (activeMode == MapWidgetMode.BUS_FOCUS) "Bus-Fokus" else "Gesamtstrecke"
        val toggleLabel = if (activeMode == MapWidgetMode.BUS_FOCUS) "Strecke" else "Bus fokussieren"
        val positionHint = PositionHint.text(state, now)
        val summary = listOfNotNull(modeLabel, positionHint ?: "Live-Buskarte").joinToString(" · ")
        val style = Notification.BigPictureStyle()
            .bigPicture(bitmap)
            .bigLargeIcon(null as Bitmap?)
            .setSummaryText(summary)
        if (Build.VERSION.SDK_INT >= 31) {
            style.showBigPictureWhenCollapsed(true)
        }
        val notification = Notification.Builder(context, MAP_CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("${ride.departure.line} · ${shortName(ride.displayTarget().name)}")
            .setContentText(summary)
            .setContentIntent(openMap)
            .setLargeIcon(bitmap)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Color.rgb(180, 0, 85))
            .addAction(Notification.Action.Builder(null, toggleLabel, toggleMode).build())
            .addAction(Notification.Action.Builder(null, "Vollbild", openMap).build())
            .build()
        return notification to missing
    }

    private fun renderMapBitmap(
        state: TrackingState,
        settings: AppSettings,
        now: Instant,
        width: Int,
        height: Int,
        mode: MapWidgetMode
    ): Pair<Bitmap, List<TileCoord>>? {
        val gps = settings.bool("gps_next_stop", true) && settings.string("location_mode") != "off"
        val model = RouteSketch.model(state, now, gpsEnabled = gps) ?: return null
        val dark = isDarkTheme(settings)
        val result = OsmCachedTileReader.forContext(context).use { reader ->
            RouteSketch.renderDetailed(
                model = model,
                width = width,
                height = height,
                dark = dark,
                tileReader = reader,
                cornerRadiusPx = 0f,
                mode = mode,
                fullBleed = true
            )
        }
        return result.bitmap to result.missingTiles
    }

    private fun isDarkTheme(settings: AppSettings): Boolean {
        val theme = settings.string("map_theme").takeUnless { it == "system" } ?: settings.string("theme")
        return when (theme) {
            "dark" -> true
            "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun post(state: TrackingState) {
        val now = Instant.now()
        val (primary, primaryMissing) = buildWithTiles(state, now)
        manager.notify(ID, primary)
        val mapResult = buildMapNotificationWithTiles(state, now)
        if (mapResult != null) {
            manager.notify(MAP_ID, mapResult.first)
        } else {
            manager.cancel(MAP_ID)
        }
        val missing = (primaryMissing + mapResult?.second.orEmpty()).distinct()
        if (missing.isNotEmpty()) {
            WidgetTilePrefetcher.prefetch(context, missing) {
                val app = context.applicationContext as? de.luca.mitfahrt.MitfahrtApplication ?: return@prefetch
                val latest = app.store.state.value
                if (latest.running && latest.ride != null) post(latest)
            }
        }
    }

    fun cancelMap() {
        manager.cancel(MAP_ID)
    }

    fun toggleMapMode(): MapWidgetMode = toggleMapMode(context)

    fun hint(eventId: String, title: String, text: String) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val details = readSettings().bool("lockscreen_details", true)
        val notification = Notification.Builder(context, HINT_CHANNEL).setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setTimeoutAfter(120_000).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(if (details) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setColor(Color.rgb(180, 0, 85)).build()
        manager.notify(100 + (eventId.hashCode() and 0x7fff), notification)
    }

    fun hintAlternateBus(suggestion: AlternateBusSuggestion) {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val switchIntent = PendingIntent.getService(context, 10, Intent(context, RideService::class.java)
            .setAction(RideService.SWITCH_ALTERNATE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dismissIntent = PendingIntent.getService(context, 11, Intent(context, RideService::class.java)
            .setAction(RideService.DISMISS_ALTERNATE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val details = readSettings().bool("lockscreen_details", true)
        val targetHint = suggestion.exitTargetName?.let { " · Ziel ${shortName(it)} bleibt" } ?: ""
        val notification = Notification.Builder(context, HINT_CHANNEL).setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Anderer Bus erkannt: Linie ${suggestion.departure.line}")
            .setContentText("Richtung ${shortName(suggestion.departure.destination)}$targetHint")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(if (details) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setColor(Color.rgb(180, 0, 85))
            .addAction(Notification.Action.Builder(null, "Wechseln", switchIntent).build())
            .addAction(Notification.Action.Builder(null, "Nein", dismissIntent).build())
            .build()
        manager.notify(100 + ("alt/${suggestion.vehicle.id}".hashCode() and 0x7fff), notification)
    }

    private fun readSettings(): AppSettings {
        val shared = (context.applicationContext as? de.luca.mitfahrt.MitfahrtApplication)?.settingsStore
        if (shared != null) return shared.current()
        val store = AppSettingsStore(context)
        return try { store.current() } finally { store.close() }
    }

    companion object {
        const val ID = 7
        const val MAP_ID = 8
        const val CHANNEL = "ride_status"
        const val MAP_CHANNEL = "ride_map"
        const val HINT_CHANNEL = "journey_hints"
        private const val PREFS = "mitfahrt_notification_map"

        fun mapMode(context: Context): MapWidgetMode {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("mode", null)
            return if (raw == MapWidgetMode.OVERVIEW.name) MapWidgetMode.OVERVIEW else MapWidgetMode.BUS_FOCUS
        }

        fun setMapMode(context: Context, mode: MapWidgetMode) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("mode", mode.name)
                .apply()
        }

        fun toggleMapMode(context: Context): MapWidgetMode {
            val next = if (mapMode(context) == MapWidgetMode.BUS_FOCUS) MapWidgetMode.OVERVIEW else MapWidgetMode.BUS_FOCUS
            setMapMode(context, next)
            return next
        }

        fun openSettings(context: Context) {
            NowBarSupport.openSettings(context)
        }
    }
}
