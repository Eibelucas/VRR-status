package de.luca.mitfahrt.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.*
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.widgets.WidgetTilePrefetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.time.Instant

class RideService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MitfahrtApplication
    private val store get() = app.store
    private lateinit var notifications: RideNotifications
    private lateinit var wifi: WifiManager
    private lateinit var locations: LocationManager
    private var connectivity: ConnectivityManager? = null
    private var networkCallbackRegistered = false
    private lateinit var alertPreferences: AlertPreferencesStore
    private lateinit var customization: AppSettingsStore
    private var loop: Job? = null
    private var observer: Job? = null
    private var liveLoop: Job? = null
    private val liveWake = Channel<Unit>(Channel.CONFLATED)
    private var lastWifiScan = 0L
    private var lastCandidates = 0L
    private var lastRideRefresh = 0L
    private var refreshFailures = 0
    private var lastCompanionPoll = 0L
    private var companionPollJob: Job? = null
    private var lastKnownPlatform: Pair<String, String>? = null
    private var lastTransferPlatform: Pair<String, String>? = null
    private var corridorPrefetchedKey: String? = null
    private var lastCompletionCheck: Instant? = null
    private var candidateLookup: Job? = null
    /** Recent phone positions for local detection only; never stored or transmitted. */
    private val phoneTrack = PhoneTrack()
    private var shapeJob: Job? = null
    private var shapeCheck: Pair<String, Long>? = null
    private var alternateBusState = AlternateBusTrackerState()
    private var alternateLookup: Job? = null
    private var lastAlternateLookup: Pair<String, Long>? = null
    private var sessionStart = 0L
    private var sensorsRegistered = false
    private var locationSubscribed = false
    private var foregroundType = 0
    private var lastAutoArrival: String? = null
    private var locationProfile: LocationProfile? = null
    private var speech: RideSpeech? = null
    private var announced: Pair<String, Int>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { readWifi() }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                refreshFailures = 0
                lastRideRefresh = 0L
                liveWake.trySend(Unit)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RideNotifications(this)
        alertPreferences = AlertPreferencesStore(this)
        customization = AppSettingsStore(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        locations = getSystemService(LocationManager::class.java)
        connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BOARD || intent?.action == MARK_ARRIVAL) {
            val ride = store.state.value.ride
            if (ride != null && !ride.demo) {
                if (intent.action == BOARD) {
                    store.setRide(ride.board("manual", Instant.now()), preserveStatus = true)
                    store.update { it.copy(missedBusPrompt = false) }
                    noteCorrection()
                }
                else if (!customization.current().bool("arrival_store", true)) {
                    store.update { it.copy(message = "Speichern eigener Ankünfte ist ausgeschaltet.") }
                } else {
                    val stop = if (ride.boarded) ride.exitTargetStop() ?: ride.displayTarget()
                        else ride.stops.getOrNull(ride.boardingIndex)
                    stop?.let { app.arrivals.record(Stop(it.id, it.name, it.point)) }
                    store.update { it.copy(message = "Eigene Ankunft festgehalten.") }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == REFRESH_LIVE) {
            if (store.state.value.running && store.state.value.ride != null) liveWake.trySend(Unit)
            return START_NOT_STICKY
        }
        if (intent?.action == TOGGLE_NOTIFICATION_MAP_MODE) {
            notifications.toggleMapMode()
            val current = store.state.value
            if (current.running && current.ride != null) notifications.post(current)
            return START_NOT_STICKY
        }
        if (intent?.action == UNBOARD || intent?.action == STILL_ON_BOARD) {
            val ride = store.state.value.ride
            if (ride != null && !ride.demo) {
                if (intent.action == UNBOARD) store.setRide(ride.unboard(), preserveStatus = true)
                noteCorrection()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == DISMISS_ALTERNATE) {
            val suggestion = store.state.value.alternateBus
            if (suggestion != null) {
                alternateBusState = alternateBusState.copy(
                    candidateVehicleId = null,
                    confirmations = 0,
                    dismissedVehicleIds = alternateBusState.dismissedVehicleIds + suggestion.vehicle.id
                )
                store.update { it.copy(alternateBus = null) }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == SWITCH_ALTERNATE) {
            val current = store.state.value
            val previous = current.ride
            val suggestion = current.alternateBus
            if (previous != null && suggestion != null && suggestion.journeyKey == previous.departure.key) {
                scope.launch { switchToAlternate(previous, suggestion.departure, autoSwitched = false) }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == MISSED_PASSED || intent?.action == MISSED_FULL) {
            val reason = if (intent.action == MISSED_FULL) {
                "Bus zu voll (Einstieg nicht möglich)"
            } else {
                "Bus durchgefahren (nicht angehalten)"
            }
            scope.launch { handleMissedBus(reason) }
            return START_NOT_STICKY
        }
        if (intent?.action == START_NEXT_LEG) {
            scope.launch { startCompanionNextLeg() }
            return START_NOT_STICKY
        }
        if (intent?.action == START_PLANNED_ROUTE) {
            val planId = intent.getStringExtra(EXTRA_PLAN_ID).orEmpty()
            val plan = app.routePlans.current().routes.firstOrNull { it.id == planId }
            if (plan == null) {
                if (!store.state.value.running) stopSelf()
                return START_NOT_STICKY
            }
            if (!ensureForeground()) return START_NOT_STICKY
            store.update { it.copy(running = true, monitoring = false, transferHandoff = null, message = "Lade ${plan.name}…") }
            ensureLoopsStarted()
            scope.launch { startPlannedRoute(plan) }
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            app.journeyReports.noteEnd(de.luca.mitfahrt.reports.RideEnd.MANUAL)
            store.clear()
            stopSelf()
            return START_NOT_STICKY
        }
        var explicitlyStarted: Ride? = null
        if (intent?.action == START_RIDE) {
            val ride = runCatching { RideCodec.decode(intent.getStringExtra("ride") ?: "") }.getOrNull()
            if (ride == null) { stopSelf(); return START_NOT_STICKY }
            if (store.state.value.ride?.departure?.key != ride.departure.key) explicitlyStarted = ride
            store.setRide(ride)
            store.update { it.copy(monitoring = false, candidates = emptyList(), transferHandoff = null) }
            lastCompletionCheck = null
            lastRideRefresh = SystemClock.elapsedRealtime()
            refreshFailures = 0
            ride.departure.platform.trim().takeIf { it.isNotBlank() }?.let {
                lastKnownPlatform = ride.departure.key to it
            }
        } else if (intent?.action == MONITOR) {
            store.setRide(null)
            store.update { it.copy(monitoring = true, candidates = emptyList(), transferHandoff = null) }
        } else if (store.state.value.ride == null && store.state.value.transferHandoff == null) {
            stopSelf(); return START_NOT_STICKY
        }
        store.update { it.copy(running = true) }
        if (!ensureForeground()) return START_NOT_STICKY
        explicitlyStarted?.takeUnless { it.demo }?.let(app.archive::record)
        ensureLoopsStarted()
        return START_NOT_STICKY
    }

    private fun ensureForeground(): Boolean {
        val type = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return try {
            startForeground(RideNotifications.ID, notifications.build(store.state.value), type)
            foregroundType = type
            true
        } catch (_: SecurityException) {
            store.update { it.copy(running = false, monitoring = false, message = "Bitte Standortzugriff in den App-Einstellungen erlauben und erneut starten.") }
            stopSelf()
            false
        }
    }

    private fun ensureLoopsStarted() {
        if (loop == null) {
            sessionStart = SystemClock.elapsedRealtime()
            startSensors()
            observer = scope.launch { store.state.collect { notifications.post(it) } }
            liveLoop = scope.launch {
                while (isActive) {
                    ensureRouteShape()
                    refreshLiveBus()
                    val settings = customization.current()
                    val interval = powerPlan(settings).refreshMillis
                    withTimeoutOrNull(interval) { liveWake.receive() }
                }
            }
            loop = scope.launch {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime()
                    val settings = customization.current()
                    val now = Instant.now()
                    syncLocationSubscription()
                    if (elapsed - sessionStart > 10_800_000) {
                        store.update { it.copy(message = "Erkennung nach drei Stunden beendet. Zum Fortsetzen erneut starten.") }
                        stopSelf(); break
                    }
                    if (elapsed - lastWifiScan > 120_000) {
                        lastWifiScan = elapsed
                        scanWifi()
                    }
                    readWifi()
                    val ride = store.state.value.ride
                    val handoff = store.state.value.transferHandoff
                    if (ride == null && handoff != null) {
                        if (now.isAfter(handoff.expiresAt)) {
                            store.clear(); stopSelf(); break
                        }
                        notifications.post(store.state.value)
                        delay(10_000)
                        continue
                    }
                    if (ride != null) {
                        val plan = powerPlan(settings)
                        if (plan.saving != store.state.value.powerSaving) store.update { it.copy(powerSaving = plan.saving) }
                        val backoffInterval = when {
                            refreshFailures <= 0 -> plan.refreshMillis
                            refreshFailures == 1 -> maxOf(plan.refreshMillis, 15_000L)
                            refreshFailures == 2 -> maxOf(plan.refreshMillis, 30_000L)
                            else -> maxOf(plan.refreshMillis, 60_000L)
                        }
                        if (RideRefreshPolicy.needsRefresh(ride, elapsed - lastRideRefresh, lastCompletionCheck, now, backoffInterval)) {
                            lastRideRefresh = elapsed
                            refreshRide(ride)
                            store.state.value.ride?.takeIf { it.departure.key == ride.departure.key && it.complete(Instant.now()) }?.let {
                                lastCompletionCheck = it.terminal.effective(it.offsetSeconds)
                            }
                        }
                        val refreshed = store.state.value
                        if (refreshed.ride?.departure?.key == ride.departure.key &&
                            RideRefreshPolicy.canFinish(refreshed, lastCompletionCheck, Instant.now())) {
                            app.journeyReports.noteEnd(de.luca.mitfahrt.reports.RideEnd.SCHEDULE)
                            store.clear(); stopSelf(); break
                        }
                        if (evaluateBoarding()) { stopSelf(); break }
                        evaluateAlternateBus()
                    } else if (store.state.value.monitoring && store.state.value.wifi in setOf(WifiSignal.CONNECTED, WifiSignal.NEARBY)) {
                        findCandidates()
                    }
                    val current = store.state.value
                    val currentRide = current.ride
                    val alertOptions = alertPreferences.current()
                    val runtime = RuntimeAlertOptions.from(settings)
                    val checkNow = Instant.now()
                    if (currentRide != null) {
                        val missed = MissedBusDetector.shouldPrompt(current, checkNow)
                        if (missed != current.missedBusPrompt) {
                            store.update { if (it.ride?.departure?.key == currentRide.departure.key) it.copy(missedBusPrompt = missed) else it }
                        }
                        val lead = runtime.leads.filter { seconds ->
                            ArrivalAlertPolicy.due(current, alertOptions.copy(leadSeconds = seconds), checkNow)
                        }.minOrNull()
                        // If monitoring starts late, deliver only the nearest warning and consume
                        // older lead windows, rather than buzzing several times in one loop.
                        if (lead != null) {
                            runtime.leads.filter { it > lead }.forEach { markEvent(arrivalId(currentRide, it)) }
                            emitAlert(arrivalId(currentRide, lead), "arrival", "Dein Bus kommt",
                                "Linie ${currentRide.departure.line} · ${shortName(currentRide.departure.stopName)}",
                                currentRide.liveBoardingTime(checkNow)?.at ?: checkNow.plusSeconds(60), runtime)
                        }
                        checkWalkingAlert(current, settings, runtime, checkNow)
                        checkDisruptionAlerts(current, settings, runtime, checkNow)
                        pollCompanionTransfer(currentRide, elapsed, runtime, checkNow)
                        if (settings.bool("exit_countdown_haptics", true)) {
                            ExitCountdownHaptics.due(current, checkNow)?.let { signal ->
                                if (!eventSeen(signal.id) && runtime.allowed("exit", checkNow)) {
                                    val connected = app.watch.hasConnectedWatch()
                                    val phone = runtime.phone(connected)
                                    val watch = runtime.watch(connected)
                                    if (phone || watch) {
                                        markEvent(signal.id)
                                        if (phone) de.luca.mitfahrt.alerts.ArrivalHaptics.pulseCountdown(this@RideService, signal.id, signal.stopsRemaining)
                                        if (watch) app.watch.arrivalAlert(signal.id, checkNow.plusSeconds(45))
                                    }
                                }
                            }
                        }
                    }
                    if (ArrivalAlertPolicy.exitDue(current, alertOptions, checkNow) && currentRide != null) {
                        val id = ExitAlarm.id(currentRide)
                        val gpsBased = current.lastRefreshFailed || checkNow.epochSecond - currentRide.fetchedAt.epochSecond !in -30..120
                        val comp = app.companion.state.value?.takeIf { it.trackedKey == currentRide.departure.key }
                        val liveNext = app.companionNext.value
                        val transfer = comp?.let { companionTransferHint(it, liveNext, checkNow) ?: JourneyCompanions.transferHint(it) }
                        val emitted = id != null && emitAlert(id, "exit", if (transfer != null) "Bald umsteigen" else "Bald aussteigen",
                            "Nächster Ausstieg: ${shortName(currentRide.exitTargetStop()?.name.orEmpty())}" +
                                (if (gpsBased) " · per GPS erkannt" else "") + (transfer?.let { " · $it" } ?: ""),
                            currentRide.exitTargetStop()?.estimated?.takeIf { it > checkNow } ?: checkNow.plusSeconds(60), runtime)
                        // Spoken only together with the vibration it accompanies, so it never repeats.
                        val exitRoute = SpeechRoute.of(settings.string("exit_audio", "off"))
                        if (emitted && exitRoute != SpeechRoute.OFF) speechFor().speak(
                            (if (transfer != null) "Bald umsteigen. " else "Bald aussteigen. ") +
                                "Nächster Ausstieg: ${shortName(currentRide.exitTargetStop()?.name.orEmpty())}.", exitRoute) {
                            val active = store.state.value
                            active.running && active.ride?.let(ExitAlarm::id) == id &&
                                SpeechRoute.of(customization.current().string("exit_audio", "off")) == exitRoute
                        }
                    }
                    announceNextStop(settings)
                    notifications.post(store.state.value)
                    delay(10_000)
                }
            }
        } else { readWifi(); liveWake.trySend(Unit) }
    }

    private suspend fun refreshLiveBus() {
        val before = store.state.value
        val ride = before.ride ?: return
        if (!before.running || ride.demo || ride.departure.cancelled || !ride.departure.newOperated) return
        val settings = customization.current()
        if (!settings.bool(if (ride.boarded) "map_onboard" else "map_waiting", true)) return
        val key = ride.departure.key
        val revision = before.liveBus.revision
        val input = before.liveBus.copy(journeyKey = key, loading = true, error = null)
        fun applies(state: TrackingState): Boolean = LiveBusTracker.resultApplies(state, key, ride.boarded, revision)
        store.update { if (applies(it)) it.copy(liveBus = input) else it }
        val options = AutoMatchOptions(enabled = settings.bool("vehicle_auto", true),
            askFirst = settings.string("vehicle_auto_mode", "assign") == "ask")
        // Phone positions stay on the device and are only passed to the local matcher.
        val phone = if (options.enabled && hasLocation()) phoneTrack.recent(Instant.now(), 180) else emptyList()
        try {
            val result = app.liveBusTracker.refresh(ride, input, phone, options,
                before.routeShape?.takeIf { it.journeyKey == key })
            val divergence = if (settings.bool("forecast_fusion", true)) {
                ForecastReconciler.evaluate(ride, result, Instant.now())
            } else null
            store.update { if (applies(it)) it.copy(liveBus = result, forecastDivergence = divergence) else it }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            store.update { if (applies(it)) it.copy(liveBus = input.copy(loading = false,
                error = "NEW-Positionen gerade nicht erreichbar.")) else it }
        }
    }

    /** Loads verified line geometry once per journey; retries are rate-limited by the repository. */
    private fun ensureRouteShape() {
        val state = store.state.value
        val ride = state.ride ?: return
        val key = ride.departure.key
        state.routeShape?.takeIf { it.journeyKey == key }?.let { shape ->
            // A refreshed ride (e.g. diversion stops) must still match; otherwise drop the geometry.
            if (RouteShape.verify(ride, shape.points, shape.source) == null) {
                store.update { if (it.ride?.departure?.key == key) it.copy(routeShape = null) else it }
            } else {
                maybePrefetchCorridor(key, shape)
            }
            return
        }
        if (ride.demo || shapeJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (shapeCheck?.let { it.first == key && now - it.second < 300_000 } == true) return
        shapeCheck = key to now
        shapeJob = scope.launch {
            val shape = try { app.routeShapes.load(ride) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (shape != null) {
                store.update { if (it.ride?.departure?.key == key) it.copy(routeShape = shape) else it }
                maybePrefetchCorridor(key, shape)
            }
        }
    }

    private fun maybePrefetchCorridor(key: String, shape: RouteShape) {
        if (corridorPrefetchedKey == key || !customization.current().bool("offline_prefetch", true)) return
        corridorPrefetchedKey = key
        scope.launch(Dispatchers.IO) {
            runCatching { WidgetTilePrefetcher.prefetchCorridor(this@RideService, shape.points) }
        }
    }

    private fun arrivalId(ride: Ride, lead: Int): String =
        if (lead == 60) ride.departure.key else "arrival/${ride.departure.key}/$lead"

    private fun eventSeen(id: String): Boolean = getSharedPreferences("journey_alert_events", MODE_PRIVATE)
        .getStringSet("seen", emptySet()).orEmpty().contains(id)

    private fun markEvent(id: String) {
        val prefs = getSharedPreferences("journey_alert_events", MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(199) + id).toSet()).apply()
    }

    private fun emitAlert(id: String, kind: String, title: String, text: String, expires: Instant,
        options: RuntimeAlertOptions): Boolean {
        if (eventSeen(id) || !options.allowed(kind, Instant.now())) return false
        val connected = app.watch.hasConnectedWatch()
        val phone = options.phone(connected)
        val watch = options.watch(connected)
        if (!phone && !watch) return false
        markEvent(id)
        if (phone) {
            de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(this, id, kind)
            notifications.hint(id, title, text)
        }
        if (watch) app.watch.arrivalAlert(id, expires)
        return true
    }

    private fun checkWalkingAlert(state: TrackingState, settings: AppSettings, runtime: RuntimeAlertOptions, now: Instant) {
        if (!settings.bool("alert_walking", false) || !state.running) return
        val ride = state.ride?.takeUnless { it.boarded || it.demo } ?: return
        val dataFreshEnough = now.epochSecond - ride.fetchedAt.epochSecond in -30..180
        if (state.lastRefreshFailed && !dataFreshEnough) return
        val at = ride.liveBoardingTime(now)?.at ?: return
        val route = LeaveCountdown.matchingRoute(ride, app.routePlans.current().routes)
        val walk = route?.walkingMinutes ?: settings.int("routes_default_walk_minutes", 5)
        val buffer = route?.bufferMinutes ?: settings.int("routes_default_buffer_minutes", 2)
        val seconds = at.epochSecond - now.epochSecond
        if (seconds in 1..((walk.coerceIn(0, 60) + buffer.coerceIn(0, 30)) * 60L)) {
            val staleNote = if (state.lastRefreshFailed) " · Stand ${clockTime(ride.fetchedAt)}" else ""
            emitAlert("walking/${ride.departure.key}", "walking", "Zeit loszugehen",
                "${walk.coerceIn(0, 60)} Min Fußweg · ${buffer.coerceIn(0, 30)} Min Puffer · Linie ${ride.departure.line}$staleNote",
                at, runtime)
        }
    }

    private fun checkDisruptionAlerts(state: TrackingState, settings: AppSettings, runtime: RuntimeAlertOptions, now: Instant) {
        val ride = state.ride?.takeUnless { it.demo } ?: return
        if (!state.running) return
        if (ride.departure.cancelled) {
            if (settings.bool("alert_cancelled", true) &&
                ride.departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..180 } == true) {
                emitAlert("cancelled/${ride.departure.key}", "cancelled", "Dein Bus fällt aus",
                    "Linie ${ride.departure.line} · ${shortName(ride.departure.stopName)}", now.plusSeconds(60), runtime)
            }
            return
        }
        val dataFreshEnough = now.epochSecond - ride.fetchedAt.epochSecond in -30..180
        if (!settings.bool("alert_delay", false) || !dataFreshEnough) return
        val stop = if (ride.boarded) ride.displayTarget() else ride.stops.getOrNull(ride.boardingIndex) ?: return
        val forecast = if (ride.boarded) stop.estimated else ride.liveBoardingTime(now)?.at
        val planned = if (!ride.boarded && ride.liveBoardingTime(now)?.isArrival == true)
            stop.arrival ?: stop.planned else stop.planned
        if (forecast == null || planned == null) return
        val minutes = ((forecast.epochSecond - planned.epochSecond) / 60).toInt()
        val threshold = settings.int("delay_threshold", 5).coerceIn(1, 60)
        if (minutes < threshold) return
        val prefs = getSharedPreferences("journey_alert_events", MODE_PRIVATE)
        val previous = if (prefs.getString("delay_journey", null) == ride.departure.key) prefs.getInt("delay_minutes", 0) else 0
        if (previous >= threshold && minutes - previous < settings.int("delay_change", 3).coerceIn(1, 30)) return
        val staleNote = if (state.lastRefreshFailed) " · Stand ${clockTime(ride.fetchedAt)}" else ""
        if (emitAlert("delay/${ride.departure.key}/$minutes", "delay", "Verspätung: +$minutes Min",
                "Linie ${ride.departure.line} · ${shortName(stop.name)}$staleNote", now.plusSeconds(60), runtime)) {
            prefs.edit().putString("delay_journey", ride.departure.key).putInt("delay_minutes", minutes).apply()
        }
    }

    private suspend fun refreshRide(ride: Ride) {
        try {
            var departure = ride.departure
            // The trip endpoint may omit real-time estimates even if the departure monitor has them.
            // Legs of a planned journey may be trains or other operators: no NEW filter for those.
            val monitor = runCatching { app.vrr.departures(Stop(departure.stopId, departure.stopName), newOnly = departure.newOperated) }
                .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
                .firstOrNull { it.key == departure.key }
            monitor?.let { departure = it }
            if (departure.cancelled) {
                refreshFailures = 0
                // Cancelled trips may no longer have a TripStopTimes response. Publish the
                // monitor's cancellation immediately without pretending old stop times are fresh.
                store.state.value.ride?.takeIf { it.departure.key == ride.departure.key }?.let { current ->
                    store.setRide(current.copy(departure = departure), preserveStatus = true)
                    store.update { it.copy(message = "Diese Fahrt wird als ausgefallen gemeldet.") }
                }
                return
            }
            val updated = app.vrr.ride(departure)
            refreshFailures = 0
            val current = store.state.value.ride
            if (current?.departure?.key == ride.departure.key) {
                val merged = updated.copy(gpsPassedIndex = maxOf(ride.gpsPassedIndex, current.gpsPassedIndex),
                    boarded = current.boarded, trackingStartedAt = current.trackingStartedAt,
                    boardedAt = current.boardedAt, boardedBy = current.boardedBy).remapExitTargetFrom(current)
                store.setRide(merged)
                val newPlatform = merged.departure.platform.trim()
                if (newPlatform.isNotBlank()) {
                    val prevPlatform = lastKnownPlatform?.takeIf { it.first == merged.departure.key }?.second
                    val change = PlatformHubHelper.detectChange(prevPlatform, newPlatform)
                    lastKnownPlatform = merged.departure.key to newPlatform
                    if (change != null && !merged.boarded) {
                        val runtime = RuntimeAlertOptions.from(customization.current())
                        emitAlert("platform/${merged.departure.key}/$newPlatform", "delay",
                            "Steig geändert: ${change.previous} -> ${change.current}",
                            "Linie ${merged.departure.line} · ${shortName(merged.departure.stopName)}",
                            Instant.now().plusSeconds(120), runtime)
                    }
                }
                if (monitor == null && RideRefreshPolicy.usesMonitorBaseline(merged)) {
                    store.update { it.copy(lastRefreshFailed = true,
                        message = "Fahrtverlauf aktualisiert. Die letzte Abfahrtsprognose konnte nicht bestätigt werden.") }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            refreshFailures = (refreshFailures + 1).coerceAtMost(5)
            if (store.state.value.ride?.departure?.key == ride.departure.key)
                store.update { it.copy(lastRefreshFailed = true, message = "Keine neuen VRR-Daten. Der letzte Stand bleibt sichtbar.") }
        }
    }

    /** Polls live status of the next leg of an accompanied multi-leg journey in the final 20 min before transfer. */
    private fun pollCompanionTransfer(currentRide: Ride, elapsed: Long, runtime: RuntimeAlertOptions, now: Instant) {
        val comp = app.companion.state.value?.takeIf { it.trackedKey == currentRide.departure.key && it.nextLeg != null } ?: return
        val nextLeg = comp.nextLeg ?: return
        val nextDep = nextLeg.asDeparture(comp.journey.fetchedAt) ?: return
        val exitAt = currentRide.exitTargetStop()?.estimated ?: currentRide.exitTargetStop()?.planned
        val withinWindow = exitAt == null || exitAt.epochSecond - now.epochSecond <= 1200L
        if (!withinWindow) return
        if (companionPollJob?.isActive == true || (app.companionNext.value != null && elapsed - lastCompanionPoll < 60_000L)) return
        lastCompanionPoll = elapsed
        companionPollJob = scope.launch {
            val nextRide = runCatching { app.vrr.ride(nextDep) }
                .getOrElse { if (it is CancellationException) throw it; null } ?: return@launch
            val active = app.companion.state.value
            if (active?.journey?.key != comp.journey.key || active.legIndex != comp.legIndex) return@launch
            app.companionNext.value = nextRide
            val checkNow = Instant.now()
            val evaluation = JourneyCompanions.transfer(active, store.state.value.ride, nextRide, checkNow)
            val line = nextLeg.line.ifBlank { nextLeg.lineName }
            if (evaluation != null) {
                when {
                    evaluation.outgoingCancelled -> emitAlert(
                        "transfer_cancel/${nextRide.departure.key}", "cancelled",
                        "Anschluss fällt aus: $line",
                        "${shortName(nextLeg.origin.name)} · Bitte Alternative prüfen",
                        checkNow.plusSeconds(180), runtime
                    )
                    evaluation.risk == TransferRisk.MISSED -> emitAlert(
                        "transfer_missed/${nextRide.departure.key}", "delay",
                        "Anschluss voraussichtlich verpasst",
                        "$line ab ${shortName(nextLeg.origin.name)} nicht mehr erreichbar",
                        checkNow.plusSeconds(180), runtime
                    )
                    evaluation.risk == TransferRisk.TIGHT -> {
                        val mins = (evaluation.slackSeconds / 60).coerceAtLeast(0)
                        emitAlert(
                            "transfer_tight/${nextRide.departure.key}", "delay",
                            "Knapper Umstieg ($mins Min)",
                            "$line ab ${shortName(nextLeg.origin.name)}",
                            checkNow.plusSeconds(180), runtime
                        )
                    }
                }
            }
            val livePlatform = nextRide.departure.platform.trim()
            if (livePlatform.isNotBlank()) {
                val basePlatform = lastTransferPlatform?.takeIf { it.first == nextRide.departure.key }?.second
                    ?: nextLeg.origin.platform.trim()
                val change = PlatformHubHelper.detectChange(basePlatform, livePlatform)
                lastTransferPlatform = nextRide.departure.key to livePlatform
                if (change != null) {
                    emitAlert(
                        "transfer_platform/${nextRide.departure.key}/$livePlatform", "delay",
                        "Anschluss-Steig geändert: ${change.previous} -> ${change.current}",
                        "$line ab ${shortName(nextLeg.origin.name)}",
                        checkNow.plusSeconds(180), runtime
                    )
                }
            }
        }
    }

    private fun companionTransferHint(comp: JourneyCompanion, nextRide: Ride?, now: Instant): String? {
        val nextLeg = comp.nextLeg ?: return null
        val freshNext = nextRide?.takeIf {
            it.departure.key == nextLeg.asDeparture(comp.journey.fetchedAt)?.key &&
                now.epochSecond - it.fetchedAt.epochSecond in -30..180
        } ?: return null
        val line = nextLeg.line.ifBlank { nextLeg.lineName }
        val depTime = freshNext.stops.getOrNull(freshNext.boardingIndex)?.estimatedDeparture ?: freshNext.departure.effective
        val platform = PlatformHubHelper.formatPlatform(freshNext.departure.platform.ifBlank { nextLeg.origin.platform })
        val status = if (freshNext.departure.cancelled) "fällt aus" else "um ${clockTime(depTime)} (Live)"
        return listOfNotNull("Umstieg: $line $status", platform).joinToString(", ")
    }

    /** Records an own observation (ReportSource.OWN) and switches straight to the next bus reaching the same exit target. */
    private suspend fun handleMissedBus(observation: String) {
        val state = store.state.value
        val previous = state.ride ?: return
        if (previous.demo) return
        val now = Instant.now()
        // 1. Immediately preserve user observation in the journey report.
        runCatching {
            val snapshot = de.luca.mitfahrt.reports.ReportSnapshot.capture(
                state, app.companion.state.value, app.companionNext.value, app.arrivals.marks.value, now
            )
            val report = app.journeyReports.ensure(snapshot)
            if (report != null) app.journeyReports.observe(report.id, observation)
        }
        store.update { it.copy(missedBusPrompt = false, message = "$observation. Suche nächsten Bus…") }
        // 2. Find next non-cancelled departure from the same boarding stop serving the same exit target.
        try {
            val stop = Stop(previous.departure.stopId, previous.departure.stopName, previous.stops.getOrNull(previous.boardingIndex)?.point)
            val departures = app.vrr.departures(stop, newOnly = previous.departure.newOperated)
                .filter { !it.cancelled && it.key != previous.departure.key && it.effective.isAfter(now.minusSeconds(60)) }
                .sortedWith(compareByDescending<Departure> { it.line.equals(previous.departure.line, true) }.thenBy { it.effective })
            val exitTarget = previous.exitTargetStop()
            val exitOccurrence = previous.targetOccurrence()
            var replacement: Ride? = null
            for (candidate in departures.take(6)) {
                val loaded = runCatching { app.vrr.ride(candidate) }.getOrNull() ?: continue
                if (exitTarget != null) {
                    val idx = SchoolTrips.targetIndex(loaded, exitTarget.id, exitOccurrence)
                    if (idx != null) {
                        replacement = loaded.copy(boarded = false, trackingStartedAt = Instant.now()).withExitTarget(idx)
                        break
                    }
                } else if (candidate.line.equals(previous.departure.line, true)) {
                    replacement = loaded.copy(boarded = false, trackingStartedAt = Instant.now())
                    break
                }
            }
            if (replacement != null) {
                alternateBusState = AlternateBusTrackerState(journeyKey = replacement.departure.key)
                lastCompletionCheck = null
                lastRideRefresh = SystemClock.elapsedRealtime()
                refreshFailures = 0
                store.setRide(replacement)
                store.update { it.copy(
                    missedBusPrompt = false,
                    message = "Auf nächsten Bus gewechselt: Linie ${replacement.departure.line} (${clockTime(replacement.departure.effective)})."
                ) }
                app.archive.record(replacement)
                liveWake.trySend(Unit)
            } else {
                store.update { it.copy(message = "$observation festgehalten. Kein nächster Bus gefunden.") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.update { it.copy(message = "$observation festgehalten. Nächster Bus gerade nicht erreichbar.") }
        }
    }

    /** 1-tap start of the next leg of an accompanied multi-leg journey (from Now Bar / Watch handoff). */
    private suspend fun startCompanionNextLeg() {
        val current = app.companion.state.value ?: return
        val nextComp = JourneyCompanions.advance(current) ?: return
        val leg = nextComp.leg
        val departure = leg.asDeparture(nextComp.journey.fetchedAt) ?: run {
            store.update { it.copy(message = "Für ${leg.line.ifBlank { "den nächsten Abschnitt" }} fehlt eine VRR-Fahrtkennung.") }
            return
        }
        try {
            val loaded = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = Instant.now())
            val exit = JourneyCompanions.exitIndex(loaded, leg)
            val selected = if (exit != null) loaded.withExitTarget(exit) else loaded
            app.companion.set(JourneyCompanions.tracking(nextComp, selected.departure.key))
            app.companionNext.value = null
            lastCompanionPoll = 0L
            lastCompletionCheck = null
            lastRideRefresh = SystemClock.elapsedRealtime()
            refreshFailures = 0
            store.setRide(selected)
            store.update { it.copy(
                running = true,
                transferHandoff = null,
                message = "Begleite weiter: ${selected.departure.line} -> ${shortName(selected.displayTarget().name)}"
            ) }
            app.archive.record(selected)
            liveWake.trySend(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.update { it.copy(message = "Nächster Abschnitt konnte nicht geladen werden.") }
        }
    }

    /** 1-tap start of a saved [RoutePlan] from the morning check notification or watch. */
    private suspend fun startPlannedRoute(plan: RoutePlan) {
        try {
            val now = Instant.now()
            val loader = PlannedJourneyLoader(app.vrr::departures, app.vrr::ride)
            val loaded = loader.load(listOf(plan), now)
            val next = loaded.departures[plan.id].orEmpty()
                .firstOrNull { !it.cancelled && it.effective.isAfter(now.minusSeconds(60)) }
            if (next == null) {
                store.update { it.copy(message = "Keine anstehende Fahrt für ${plan.name} gefunden.") }
                if (store.state.value.ride == null) stopSelf()
                return
            }
            val ride = app.vrr.ride(next).copy(boarded = false, trackingStartedAt = Instant.now())
            val exitIdx = SchoolTrips.targetIndex(ride, plan.effectiveDestination().id, 0)
            val selected = if (exitIdx != null) ride.withExitTarget(exitIdx) else ride
            lastCompletionCheck = null
            lastRideRefresh = SystemClock.elapsedRealtime()
            refreshFailures = 0
            selected.departure.platform.trim().takeIf { it.isNotBlank() }?.let {
                lastKnownPlatform = selected.departure.key to it
            }
            store.setRide(selected)
            store.update { it.copy(
                running = true,
                monitoring = false,
                transferHandoff = null,
                message = "${plan.name} gestartet."
            ) }
            app.archive.record(selected)
            liveWake.trySend(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.update { it.copy(message = "${plan.name} konnte nicht geladen werden.") }
            if (store.state.value.ride == null) stopSelf()
        }
    }

    private fun findCandidates() {
        val state = store.state.value
        val point = state.location ?: run {
            store.update { it.copy(message = "NEW-Hotspot erkannt. Warte auf Standort – oder Haltestelle manuell wählen.") }
            return
        }
        if (state.locationAt?.let { Instant.now().epochSecond - it.epochSecond > 90 } != false) return
        if (candidateLookup?.isActive == true || SystemClock.elapsedRealtime() - lastCandidates < 60_000) return
        lastCandidates = SystemClock.elapsedRealtime()
        candidateLookup = scope.launch {
            try {
                val nearby = app.vrr.nearby(point).take(3)
                val candidates = nearby.flatMap { app.vrr.departures(it) }
                    .filter { !it.cancelled && it.effective.isAfter(Instant.now().minusSeconds(240)) && it.effective.isBefore(Instant.now().plusSeconds(1200)) }
                    .distinctBy { it.key }.sortedBy { kotlin.math.abs(it.effective.epochSecond - Instant.now().epochSecond) }.take(12)
                if (store.state.value.monitoring) store.update { it.copy(candidates = candidates,
                    message = if (candidates.isEmpty()) "Keine passende NEW-Fahrt gefunden. Haltestelle manuell wählen."
                    else "${candidates.size} mögliche Fahrten gefunden. Bitte deine Linie und Richtung bestätigen.") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                store.update { it.copy(message = "Fahrten konnten nicht geladen werden. Internet prüfen oder manuell suchen.") }
            }
        }
    }

    private fun hasLocation(): Boolean {
        val mode = customization.current().string("location_mode", "active")
        val process = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(process)
        val visible = process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return (mode == "active" || mode == "foreground" && visible) &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun startSensors() {
        if (sensorsRegistered) return
        sensorsRegistered = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply { addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        syncLocationSubscription()
        scanWifi()
        readWifi()
    }

    @SuppressLint("MissingPermission")
    private fun syncLocationSubscription() {
        if (!hasLocation()) {
            if (locationSubscribed) runCatching { locations.removeUpdates(this) }
            locationSubscribed = false
            phoneTrack.clear()
            if (store.state.value.location != null) store.update { it.copy(location = null, locationAt = null) }
            return
        }
        val profile = powerPlan(customization.current()).location
        if (locationSubscribed && profile != locationProfile) {
            // Tighter or looser measuring for the new situation; the foreground type stays.
            runCatching { locations.removeUpdates(this) }
            locationSubscribed = false
        }
        locationProfile = profile
        if (!locationSubscribed) {
            // A session started with location disabled uses the data-sync type. Upgrade
            // its foreground declaration before requesting GPS after an in-app change.
            if (foregroundType != ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) {
                val switched = runCatching {
                    startForeground(RideNotifications.ID, notifications.build(store.state.value),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }.isSuccess
                if (!switched) {
                    store.update { it.copy(message = "Standort konnte nicht aktiviert werden. Öffne Mitfahrt und setze die Fahrt fort.") }
                    return
                }
            }
            locationSubscribed = true
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                runCatching {
                    if (locations.isProviderEnabled(provider)) locations.requestLocationUpdates(provider, profile.intervalMillis, profile.minDistance, this)
                }
            }
        }
    }

    private fun powerPlan(settings: AppSettings): PowerPlan {
        val state = store.state.value
        val boarded = state.ride?.boarded == true
        return PowerPolicy.plan(state.ride, state.boarding?.phase, settings.string("battery_saver", "off"),
            settings.int(if (boarded) "refresh_riding" else "refresh_waiting", if (boarded) 30 else 15), Instant.now())
    }

    private fun speechFor(): RideSpeech = speech ?: RideSpeech(this).also { speech = it }

    /** Announces each next stop once; a correction backwards resets without speaking. */
    private fun announceNextStop(settings: AppSettings) {
        val route = SpeechRoute.of(settings.string("announce_stops", "off"))
        val state = store.state.value
        val key = state.ride?.departure?.key ?: run { announced = null; return }
        if (state.ride?.boarded != true) { announced = null; return }
        val last = announced?.takeIf { it.first == key }?.second
        val info = NextStopResolver.resolve(state, Instant.now(), settings.bool("gps_next_stop", true))
        // A stop index that moved back (manual correction, GPS reset) only resets the memory.
        if (last != null && info?.index != null && info.index < last) { announced = key to info.index; return }
        if (route == SpeechRoute.OFF) { announced = info?.index?.let { key to it } ?: announced; return }
        val next = SpeechPolicy.announcement(state, Instant.now(), last, settings.bool("gps_next_stop", true)) ?: return
        announced = key to next.first
        speechFor().speak(next.second, route) {
            val active = store.state.value
            active.running && !active.announcementsPaused && active.ride?.departure?.key == key &&
                SpeechRoute.of(customization.current().string("announce_stops", "off")) == route
        }
    }

    private fun noteCorrection() {
        announced = null
        val ride = store.state.value.ride ?: return
        val now = Instant.now()
        store.update { state -> if (state.ride?.departure?.key != ride.departure.key) state
            else state.copy(boarding = BoardingDetector.corrected(state.boarding ?: BoardingDetector.initial(ride, now), ride, now)) }
    }

    /**
     * Runs the boarding/exit state machine on the positions held in memory. Returns true when
     * tracking ended because an exit at the chosen stop was confirmed.
     */
    private fun evaluateBoarding(): Boolean {
        val state = store.state.value
        val ride = state.ride ?: return false
        val settings = customization.current()
        if (ride.demo || !settings.bool("boarding_auto", true) || !hasLocation()) return false
        val now = Instant.now()
        val live = state.liveBus
        // Only a vehicle that is assigned to this ride counts; a bus nearby does not.
        val vehicle = live.vehicle?.takeIf { live.journeyKey == ride.departure.key && it.isFresh(now) &&
            (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle) }
        val reference = RouteReference.of(ride, state.routeShape?.takeIf { it.journeyKey == ride.departure.key })
        val next = BoardingDetector.step(state.boarding, BoardingInput(ride, reference,
            phoneTrack.recent(now, BoardingDetector.WINDOW_SECONDS), vehicle, state.wifi, now))
        if (next == state.boarding) return false
        store.update { current -> if (current.ride?.departure?.key == ride.departure.key) current.copy(boarding = next) else current }
        if (next.phase == BoardingPhase.ON_BOARD && !ride.boarded) {
            store.setRide(ride.board("auto", now), preserveStatus = true)
            store.update { it.copy(message = "Einstieg erkannt. Falls das nicht stimmt, tippe auf „Doch nicht im Bus“.") }
            liveWake.trySend(Unit)
        }
        if (next.phase == BoardingPhase.ENDED && state.boarding?.phase != BoardingPhase.ENDED && next.exitAtTarget && ride.boarded) {
            app.journeyReports.noteEnd(de.luca.mitfahrt.reports.RideEnd.GPS_EXIT)
            val comp = app.companion.state.value?.takeIf { it.trackedKey == ride.departure.key && it.nextLeg != null }
            val nextLeg = comp?.nextLeg
            if (comp != null && nextLeg != null) {
                val liveNext = app.companionNext.value?.takeIf {
                    it.departure.key == nextLeg.asDeparture(comp.journey.fetchedAt)?.key
                }
                val handoff = TransferHandoff(
                    nextLine = nextLeg.line.ifBlank { nextLeg.lineName },
                    nextDestination = shortName(nextLeg.destination.name),
                    nextStopName = shortName(nextLeg.origin.name),
                    nextPlatform = liveNext?.departure?.platform?.ifBlank { nextLeg.origin.platform } ?: nextLeg.origin.platform,
                    nextDepartureAt = liveNext?.stops?.getOrNull(liveNext.boardingIndex)?.estimatedDeparture
                        ?: liveNext?.departure?.effective
                        ?: nextLeg.origin.estimatedDeparture
                        ?: nextLeg.origin.plannedDeparture,
                    expiresAt = now.plusSeconds(300)
                )
                store.setRide(null)
                store.update { it.copy(running = true, monitoring = false, transferHandoff = handoff,
                    message = "Ausstieg erkannt. ${handoff.label} ab ${handoff.nextStopName}.") }
                return false
            }
            store.clear()
            return true
        }
        return false
    }

    private suspend fun evaluateAlternateBus() {
        val state = store.state.value
        val ride = state.ride ?: return
        val settings = customization.current()
        val mode = settings.string("auto_switch_bus", "ask")
        if (mode == "off" || ride.demo || !hasLocation()) {
            if (state.alternateBus != null) store.update { it.copy(alternateBus = null) }
            return
        }
        val now = Instant.now()
        val (fleet, tracks) = app.liveBusTracker.fleetSnapshot(now)
        if (fleet.isEmpty()) return
        val live = state.liveBus
        val currentVehicle = live.vehicle?.takeIf { live.journeyKey == ride.departure.key && it.isFresh(now) &&
            (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle) }
        val currentTrack = currentVehicle?.let { tracks[it.id].orEmpty() }.orEmpty()
        val result = AlternateBusDetector.step(
            previous = alternateBusState,
            ride = ride,
            currentVehicle = currentVehicle,
            currentTrack = currentTrack,
            fleet = fleet,
            fleetTracks = tracks,
            phoneFixes = phoneTrack.recent(now, AlternateBusDetector.WINDOW_SECONDS),
            now = now
        )
        alternateBusState = result.state
        val confirmed = result.confirmed ?: return
        if (state.alternateBus?.vehicle?.id == confirmed.vehicle.id) return
        val elapsed = SystemClock.elapsedRealtime()
        if (alternateLookup?.isActive == true) return
        if (lastAlternateLookup?.let { it.first == confirmed.vehicle.id && elapsed - it.second < 30_000L } == true) return
        lastAlternateLookup = confirmed.vehicle.id to elapsed

        val rideKey = ride.departure.key
        alternateLookup = scope.launch {
            try {
                val boardingStop = Stop(ride.departure.stopId, ride.departure.stopName, ride.stops.getOrNull(ride.boardingIndex)?.point)
                var departures = runCatching { app.vrr.departures(boardingStop, newOnly = true) }
                    .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
                var matched = AlternateBusDetector.matchDeparture(confirmed.vehicle, departures, rideKey, Instant.now())
                if (matched == null) {
                    val loc = store.state.value.location
                    if (loc != null) {
                        val nearby = runCatching { app.vrr.nearby(loc).take(2) }
                            .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
                        departures = nearby.flatMap { stop ->
                            runCatching { app.vrr.departures(stop, newOnly = true) }
                                .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
                        }
                        matched = AlternateBusDetector.matchDeparture(confirmed.vehicle, departures, rideKey, Instant.now())
                    }
                }
                val departure = matched ?: return@launch
                val currentRide = store.state.value.ride ?: return@launch
                if (currentRide.departure.key != rideKey) return@launch
                val previewRide = app.vrr.ride(departure)
                val switched = AlternateBusDetector.applySwitchedRide(currentRide, previewRide, Instant.now())
                if (customization.current().string("auto_switch_bus", "ask") == "auto") {
                    applySwitchedRideState(switched, autoSwitched = true)
                } else {
                    val preservedTarget = switched.exitTargetStop()
                    val suggestion = AlternateBusSuggestion(
                        journeyKey = rideKey,
                        vehicle = confirmed.vehicle,
                        departure = departure,
                        preservesExitTarget = preservedTarget != null,
                        exitTargetName = preservedTarget?.name,
                        detectedAt = Instant.now()
                    )
                    store.update { current ->
                        if (current.ride?.departure?.key != rideKey) current
                        else current.copy(alternateBus = suggestion)
                    }
                    notifications.hintAlternateBus(suggestion)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network hiccup: keep current tracking untouched.
            }
        }
    }

    private suspend fun switchToAlternate(previous: Ride, departure: Departure, autoSwitched: Boolean) {
        try {
            val loaded = app.vrr.ride(departure)
            val current = store.state.value.ride ?: return
            if (current.departure.key != previous.departure.key) return
            val switched = AlternateBusDetector.applySwitchedRide(current, loaded, Instant.now())
            applySwitchedRideState(switched, autoSwitched)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            store.update { it.copy(message = "Wechsel zu Linie ${departure.line} fehlgeschlagen. Bitte erneut versuchen.") }
        }
    }

    private fun applySwitchedRideState(switched: Ride, autoSwitched: Boolean) {
        alternateBusState = AlternateBusTrackerState(journeyKey = switched.departure.key)
        lastCompletionCheck = null
        lastRideRefresh = SystemClock.elapsedRealtime()
        store.setRide(switched)
        store.update { it.copy(
            alternateBus = null,
            message = if (autoSwitched) "Automatisch zu Linie ${switched.departure.line} gewechselt (GPS-Erkennung der App)."
            else "Gewechselt zu Linie ${switched.departure.line}."
        ) }
        switched.takeUnless { it.demo }?.let(app.archive::record)
        liveWake.trySend(Unit)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanWifi() {
        if (!customization.current().bool("wifi_detection", true)) return
        runCatching { wifi.startScan() }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifi() {
        val settings = customization.current()
        if (!settings.bool("wifi_detection", true)) {
            if (store.state.value.wifi != WifiSignal.NONE || store.state.value.wifiBusDetected)
                store.update { it.copy(wifi = WifiSignal.NONE, wifiBusDetected = false) }
            return
        }
        val names = settings.string("wifi_names", "NEW-Hotspot").split(',', '\n')
            .map { it.trim().trim('"') }.filter { it.isNotBlank() }.take(20)
        fun matches(name: String): Boolean = if (names.isEmpty()) isNewHotspot(name)
            else names.any { if (it.equals("NEW-Hotspot", true)) isNewHotspot(name) else it.equals(name.trim('"'), true) }
        val signal = runCatching {
            if (!wifi.isWifiEnabled || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                !locations.isLocationEnabled) WifiSignal.UNAVAILABLE
            else if (matches(wifi.connectionInfo.ssid)) WifiSignal.CONNECTED
            else if (wifi.scanResults.any { matches(it.SSID) && SystemClock.elapsedRealtime() - it.timestamp / 1000 in 0..90_000 }) WifiSignal.NEARBY
            else WifiSignal.NONE
        }.getOrDefault(WifiSignal.UNAVAILABLE)
        if (signal != store.state.value.wifi) store.update { it.copy(wifi = signal,
            wifiBusDetected = it.monitoring && signal in setOf(WifiSignal.CONNECTED, WifiSignal.NEARBY)) }
    }

    override fun onLocationChanged(location: Location) {
        if (!hasLocation()) return
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (!location.accuracy.isFinite() || location.accuracy !in 1f..150f || ageNanos !in 0..60_000_000_000L) return
        val point = GeoPoint(location.latitude, location.longitude)
        val now = Instant.now()
        val fix = PhoneFix(point, location.accuracy, now.minusMillis(ageNanos.coerceAtLeast(0) / 1_000_000))
        phoneTrack.add(fix)
        store.update { it.copy(location = point, locationAt = fix.at) }
        val ride = store.state.value.ride ?: return
        detectOwnArrival(ride, point, location.accuracy)
        if (ride.demo || !ride.boarded || !customization.current().bool("gps_next_stop", true)) return
        // Progress along the known stop order (and real line, if verified); never by clock time.
        val state = store.state.value
        val reference = RouteReference.of(ride, state.routeShape?.takeIf { it.journeyKey == ride.departure.key })
        var progress = GpsStopProgress.update(state.gpsProgress, ride, reference, fix)
        val live = state.liveBus
        val reliable = live.vehicle?.takeIf { vehicle ->
            vehicle.isFresh(now) && live.journeyKey == ride.departure.key && (live.confirmed ||
                live.auto.status == AutoMatchStatus.ASSIGNED && live.auto.basis == AutoMatchBasis.CO_MOVEMENT)
        }
        if (reliable != null) progress = GpsStopProgress.update(progress, ride, reference,
            PhoneFix(reliable.point, 25f, reliable.observedAt), fromVehicle = true)
        store.update { current -> if (current.ride?.departure?.key == ride.departure.key) current.copy(gpsProgress = progress) else current }
        if (progress.passedIndex > ride.gpsPassedIndex)
            store.setRide(ride.copy(gpsPassedIndex = progress.passedIndex), preserveStatus = true)
    }

    private fun detectOwnArrival(ride: Ride, point: GeoPoint, accuracy: Float) {
        val settings = customization.current()
        if (!settings.bool("arrival_auto", false) || ride.demo || accuracy > 65) return
        val stop = if (ride.boarded) ride.exitTargetStop() ?: ride.displayTarget()
            else ride.stops.getOrNull(ride.boardingIndex) ?: return
        val id = "${ride.departure.key}/${stopKey(stop.id)}/${ride.boarded}"
        if (id == lastAutoArrival) return
        val radius = settings.int("arrival_radius", 100).coerceIn(25, 500)
        if (stop.point?.distanceTo(point)?.let { it <= radius } != true) return
        lastAutoArrival = id
        val now = Instant.now()
        if (settings.bool("arrival_confirm", true)) {
            store.update { current -> if (current.ride?.departure?.key == ride.departure.key)
                current.copy(pendingArrivalStopId = stop.id, pendingArrivalAt = now) else current }
        } else app.arrivals.recordDetected(Stop(stop.id, stop.name, stop.point), now)
    }

    override fun onProviderDisabled(provider: String) {
        phoneTrack.clear()
        store.update { it.copy(location = null, locationAt = null) }
    }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy platform callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        speech?.shutdown(); speech = null
        customization.close()
        alertPreferences.close()
        if (networkCallbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        if (sensorsRegistered) runCatching { unregisterReceiver(receiver) }
        runCatching { locations.removeUpdates(this) }
        store.update { it.copy(running = false, monitoring = false, transferHandoff = null) }
        notifications.cancelMap()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val MONITOR = "de.luca.mitfahrt.MONITOR"
        const val START_RIDE = "de.luca.mitfahrt.START_RIDE"
        const val START_PLANNED_ROUTE = "de.luca.mitfahrt.START_PLANNED_ROUTE"
        const val EXTRA_PLAN_ID = "plan_id"
        const val STOP = "de.luca.mitfahrt.STOP"
        const val REFRESH_LIVE = "de.luca.mitfahrt.REFRESH_LIVE"
        const val TOGGLE_NOTIFICATION_MAP_MODE = "de.luca.mitfahrt.TOGGLE_NOTIFICATION_MAP_MODE"
        const val BOARD = "de.luca.mitfahrt.BOARD"
        const val MARK_ARRIVAL = "de.luca.mitfahrt.MARK_ARRIVAL"
        const val UNBOARD = "de.luca.mitfahrt.UNBOARD"
        const val STILL_ON_BOARD = "de.luca.mitfahrt.STILL_ON_BOARD"
        const val SWITCH_ALTERNATE = "de.luca.mitfahrt.SWITCH_ALTERNATE"
        const val DISMISS_ALTERNATE = "de.luca.mitfahrt.DISMISS_ALTERNATE"
        const val MISSED_PASSED = "de.luca.mitfahrt.MISSED_PASSED"
        const val MISSED_FULL = "de.luca.mitfahrt.MISSED_FULL"
        const val START_NEXT_LEG = "de.luca.mitfahrt.START_NEXT_LEG"
        fun refreshLive(context: Context) {
            val state = (context.applicationContext as MitfahrtApplication).store.state.value
            if (state.running && state.ride != null) context.startService(Intent(context, RideService::class.java).setAction(REFRESH_LIVE))
        }
        fun start(context: Context, ride: Ride? = null) {
            context.startForegroundService(Intent(context, RideService::class.java).apply {
                action = if (ride == null) MONITOR else START_RIDE
                ride?.let { putExtra("ride", RideCodec.encode(it)) }
            })
        }
        fun startPlannedRoute(context: Context, planId: String) {
            context.startForegroundService(Intent(context, RideService::class.java).apply {
                action = START_PLANNED_ROUTE
                putExtra(EXTRA_PLAN_ID, planId)
            })
        }
        fun correct(context: Context, action: String) {
            context.startService(Intent(context, RideService::class.java).setAction(action))
        }
        fun stop(context: Context) {
            (context.applicationContext as MitfahrtApplication).journeyReports.noteEnd(de.luca.mitfahrt.reports.RideEnd.MANUAL)
            (context.applicationContext as MitfahrtApplication).store.clear()
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}
