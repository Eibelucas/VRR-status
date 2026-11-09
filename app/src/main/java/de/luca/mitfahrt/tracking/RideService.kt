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
import android.net.wifi.WifiManager
import android.os.*
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*
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
    private lateinit var alertPreferences: AlertPreferencesStore
    private lateinit var customization: AppSettingsStore
    private var loop: Job? = null
    private var observer: Job? = null
    private var liveLoop: Job? = null
    private val liveWake = Channel<Unit>(Channel.CONFLATED)
    private var lastWifiScan = 0L
    private var lastCandidates = 0L
    private var lastRideRefresh = 0L
    private var lastCompletionCheck: Instant? = null
    private var candidateLookup: Job? = null
    private var approaching: Int? = null
    private var sessionStart = 0L
    private var sensorsRegistered = false
    private var locationSubscribed = false
    private var foregroundType = 0
    private var lastAutoArrival: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { readWifi() }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RideNotifications(this)
        alertPreferences = AlertPreferencesStore(this)
        customization = AppSettingsStore(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        locations = getSystemService(LocationManager::class.java)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == BOARD || intent?.action == MARK_ARRIVAL) {
            val ride = store.state.value.ride
            if (ride != null && !ride.demo) {
                if (intent.action == BOARD) store.setRide(ride.copy(boarded = true), preserveStatus = true)
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
        if (intent?.action == STOP) { store.clear(); stopSelf(); return START_NOT_STICKY }
        var explicitlyStarted: Ride? = null
        if (intent?.action == START_RIDE) {
            val ride = runCatching { RideCodec.decode(intent.getStringExtra("ride") ?: "") }.getOrNull()
            if (ride == null) { stopSelf(); return START_NOT_STICKY }
            if (store.state.value.ride?.departure?.key != ride.departure.key) explicitlyStarted = ride
            store.setRide(ride)
            store.update { it.copy(monitoring = false, candidates = emptyList()) }
            approaching = null
            lastCompletionCheck = null
            lastRideRefresh = SystemClock.elapsedRealtime()
        } else if (intent?.action == MONITOR) {
            store.setRide(null)
            store.update { it.copy(monitoring = true, candidates = emptyList()) }
        } else if (store.state.value.ride == null) {
            stopSelf(); return START_NOT_STICKY
        }
        store.update { it.copy(running = true) }
        val type = if (hasLocation()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            startForeground(RideNotifications.ID, notifications.build(store.state.value), type)
            foregroundType = type
        } catch (error: SecurityException) {
            store.update { it.copy(running = false, monitoring = false, message = "Bitte Standortzugriff in den App-Einstellungen erlauben und erneut starten.") }
            stopSelf(); return START_NOT_STICKY
        }
        explicitlyStarted?.takeUnless { it.demo }?.let(app.archive::record)
        if (loop == null) {
            sessionStart = SystemClock.elapsedRealtime()
            startSensors()
            observer = scope.launch { store.state.collect { notifications.post(it) } }
            liveLoop = scope.launch {
                while (isActive) {
                    refreshLiveBus()
                    val settings = customization.current()
                    val interval = settings.int(if (store.state.value.ride?.boarded == true) "refresh_riding" else "refresh_waiting",
                        if (store.state.value.ride?.boarded == true) 30 else 15).coerceIn(15, 120) * 1000L
                    withTimeoutOrNull(interval) { liveWake.receive() }
                }
            }
            loop = scope.launch {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime()
                    val settings = customization.current()
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
                    if (ride != null) {
                        val interval = settings.int(if (ride.boarded) "refresh_riding" else "refresh_waiting", if (ride.boarded) 30 else 15)
                            .coerceIn(15, 120) * 1000L
                        if (RideRefreshPolicy.needsRefresh(ride, elapsed - lastRideRefresh, lastCompletionCheck, Instant.now(), interval)) {
                            lastRideRefresh = elapsed
                            refreshRide(ride)
                            store.state.value.ride?.takeIf { it.departure.key == ride.departure.key && it.complete(Instant.now()) }?.let {
                                lastCompletionCheck = it.terminal.effective(it.offsetSeconds)
                            }
                        }
                        val refreshed = store.state.value
                        if (refreshed.ride?.departure?.key == ride.departure.key &&
                            RideRefreshPolicy.canFinish(refreshed, lastCompletionCheck, Instant.now())) {
                            store.clear(); stopSelf(); break
                        }
                    } else if (store.state.value.monitoring && store.state.value.wifi in setOf(WifiSignal.CONNECTED, WifiSignal.NEARBY)) {
                        findCandidates()
                    }
                    val current = store.state.value
                    val currentRide = current.ride
                    val alertOptions = alertPreferences.current()
                    val runtime = RuntimeAlertOptions.from(settings)
                    val now = Instant.now()
                    if (currentRide != null) {
                        val lead = runtime.leads.filter { seconds ->
                            ArrivalAlertPolicy.due(current, alertOptions.copy(leadSeconds = seconds), now)
                        }.minOrNull()
                        // If monitoring starts late, deliver only the nearest warning and consume
                        // older lead windows, rather than buzzing several times in one loop.
                        if (lead != null) {
                            runtime.leads.filter { it > lead }.forEach { markEvent(arrivalId(currentRide, it)) }
                            emitAlert(arrivalId(currentRide, lead), "arrival", "Dein Bus kommt",
                                "Linie ${currentRide.departure.line} · ${shortName(currentRide.departure.stopName)}",
                                currentRide.liveBoardingTime(now)?.at ?: now.plusSeconds(60), runtime)
                        }
                        checkWalkingAlert(current, settings, runtime, now)
                        checkDisruptionAlerts(current, settings, runtime, now)
                    }
                    if (ArrivalAlertPolicy.exitDue(current, alertOptions, now) && currentRide != null) {
                        val id = ExitAlarm.id(currentRide)
                        if (id != null) emitAlert(id, "exit", "Bald aussteigen",
                            "Nächster Ausstieg: ${shortName(currentRide.exitTargetStop()?.name.orEmpty())}",
                            currentRide.exitTargetStop()?.estimated?.takeIf { it > now } ?: now.plusSeconds(60), runtime)
                    }
                    notifications.post(store.state.value)
                    delay(10_000)
                }
            }
        } else { readWifi(); liveWake.trySend(Unit) }
        return START_NOT_STICKY
    }

    private suspend fun refreshLiveBus() {
        val before = store.state.value
        val ride = before.ride ?: return
        if (!before.running || ride.demo || ride.departure.cancelled) return
        val settings = customization.current()
        if (!settings.bool(if (ride.boarded) "map_riding" else "map_waiting", true)) return
        val key = ride.departure.key
        val revision = before.liveBus.revision
        val input = before.liveBus.copy(journeyKey = key, loading = true, error = null)
        fun applies(state: TrackingState): Boolean = state.running && state.ride?.departure?.key == key &&
            state.ride?.boarded == ride.boarded && state.liveBus.revision == revision
        store.update { if (applies(it)) it.copy(liveBus = input) else it }
        try {
            val result = app.liveBusTracker.refresh(ride, input)
            store.update { if (applies(it)) it.copy(liveBus = result) else it }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            store.update { if (applies(it)) it.copy(liveBus = input.copy(loading = false,
                error = "NEW-Positionen gerade nicht erreichbar.")) else it }
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
        if (!settings.bool("alert_walking", false) || state.lastRefreshFailed || !state.running) return
        val ride = state.ride?.takeUnless { it.boarded || it.demo } ?: return
        val at = ride.liveBoardingTime(now)?.at ?: return
        val origin = stopKey(ride.departure.stopId)
        val target = ride.exitTargetStop()?.id?.let(::stopKey)
        val route = app.routePlans.current().routes.firstOrNull { plan ->
            plan.enabled && stopKey(plan.origin.id) == origin &&
                (target == null || stopKey(plan.destination.id) == target) &&
                ride.stops.drop(ride.boardingIndex + 1).any { stopKey(it.id) == stopKey(plan.destination.id) } &&
                (plan.match == RouteMatch.STOP || ride.departure.line.equals(plan.line, true) ||
                    plan.allowedLines.any { it.equals(ride.departure.line, true) })
        }
        val walk = route?.walkingMinutes ?: settings.int("routes_default_walk_minutes", 5)
        val buffer = route?.bufferMinutes ?: settings.int("routes_default_buffer_minutes", 2)
        val seconds = at.epochSecond - now.epochSecond
        if (seconds in 1..((walk.coerceIn(0, 60) + buffer.coerceIn(0, 30)) * 60L)) {
            emitAlert("walking/${ride.departure.key}", "walking", "Zeit loszugehen",
                "${walk.coerceIn(0, 60)} Min Fußweg · ${buffer.coerceIn(0, 30)} Min Puffer · Linie ${ride.departure.line}",
                at, runtime)
        }
    }

    private fun checkDisruptionAlerts(state: TrackingState, settings: AppSettings, runtime: RuntimeAlertOptions, now: Instant) {
        val ride = state.ride?.takeUnless { it.demo } ?: return
        if (!state.running) return
        if (ride.departure.cancelled) {
            if (settings.bool("alert_cancelled", true) &&
                ride.departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true) {
                emitAlert("cancelled/${ride.departure.key}", "cancelled", "Dein Bus fällt aus",
                    "Linie ${ride.departure.line} · ${shortName(ride.departure.stopName)}", now.plusSeconds(60), runtime)
            }
            return
        }
        if (!settings.bool("alert_delay", false) || state.lastRefreshFailed || ride.isStale(now)) return
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
        if (emitAlert("delay/${ride.departure.key}/$minutes", "delay", "Verspätung: +$minutes Min",
                "Linie ${ride.departure.line} · ${shortName(stop.name)}", now.plusSeconds(60), runtime)) {
            prefs.edit().putString("delay_journey", ride.departure.key).putInt("delay_minutes", minutes).apply()
        }
    }

    private suspend fun refreshRide(ride: Ride) {
        try {
            var departure = ride.departure
            // The trip endpoint may omit real-time estimates even if the departure monitor has them.
            val monitor = runCatching { app.vrr.departures(Stop(departure.stopId, departure.stopName)) }
                .getOrElse { error -> if (error is CancellationException) throw error else emptyList() }
                .firstOrNull { it.key == departure.key }
            monitor?.let { departure = it }
            if (departure.cancelled) {
                // Cancelled trips may no longer have a TripStopTimes response. Publish the
                // monitor's cancellation immediately without pretending old stop times are fresh.
                store.state.value.ride?.takeIf { it.departure.key == ride.departure.key }?.let { current ->
                    store.setRide(current.copy(departure = departure), preserveStatus = true)
                    store.update { it.copy(message = "Diese Fahrt wird als ausgefallen gemeldet.") }
                }
                return
            }
            val updated = app.vrr.ride(departure)
            val current = store.state.value.ride
            if (current?.departure?.key == ride.departure.key) {
                val merged = updated.copy(gpsPassedIndex = maxOf(ride.gpsPassedIndex, current.gpsPassedIndex),
                    boarded = current.boarded, trackingStartedAt = current.trackingStartedAt).remapExitTargetFrom(current)
                store.setRide(merged)
                if (monitor == null && RideRefreshPolicy.usesMonitorBaseline(merged)) {
                    store.update { it.copy(lastRefreshFailed = true,
                        message = "Fahrtverlauf aktualisiert. Die letzte Abfahrtsprognose konnte nicht bestätigt werden.") }
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            if (store.state.value.ride?.departure?.key == ride.departure.key)
                store.update { it.copy(lastRefreshFailed = true, message = "Keine neuen VRR-Daten. Der letzte Stand bleibt sichtbar.") }
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

    private fun hasLocation(): Boolean = customization.current().string("location_mode", "active") == "active" &&
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            if (store.state.value.location != null) store.update { it.copy(location = null, locationAt = null) }
            return
        }
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
                    if (locations.isProviderEnabled(provider)) locations.requestLocationUpdates(provider, 5_000, 12f, this)
                }
            }
        }
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
        if (location.accuracy > 150 || (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) > 60_000_000_000) return
        val point = GeoPoint(location.latitude, location.longitude)
        store.update { it.copy(location = point, locationAt = Instant.now()) }
        val ride = store.state.value.ride ?: return
        detectOwnArrival(ride, point, location.accuracy)
        if (ride.demo || !ride.boarded || location.accuracy > 65) return
        // Confirm passing a stop only after approaching and leaving it; never jump to a parallel street.
        val upcoming = ride.stops.indices.filter { it >= ride.boardingIndex && it > ride.gpsPassedIndex }
            .take(4).minByOrNull { ride.stops[it].point?.distanceTo(point) ?: Double.MAX_VALUE }
        val close = upcoming?.takeIf { index ->
            ride.stops[index].point?.distanceTo(point)?.let { it < 70 } == true &&
                ride.stops[index].effective(ride.offsetSeconds)?.let { kotlin.math.abs(it.epochSecond - Instant.now().epochSecond) < 360 } == true
        }
        if (close != null) approaching = close
        val seen = approaching
        if (seen != null && (ride.stops.getOrNull(seen)?.point?.distanceTo(point) ?: 0.0) > 130) {
            store.setRide(ride.copy(gpsPassedIndex = maxOf(ride.gpsPassedIndex, seen)), preserveStatus = true)
            approaching = null
        }
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

    override fun onProviderDisabled(provider: String) { store.update { it.copy(location = null, locationAt = null) } }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy platform callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
        customization.close()
        alertPreferences.close()
        if (sensorsRegistered) runCatching { unregisterReceiver(receiver) }
        runCatching { locations.removeUpdates(this) }
        store.update { it.copy(running = false, monitoring = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val MONITOR = "de.luca.mitfahrt.MONITOR"
        const val START_RIDE = "de.luca.mitfahrt.START_RIDE"
        const val STOP = "de.luca.mitfahrt.STOP"
        const val REFRESH_LIVE = "de.luca.mitfahrt.REFRESH_LIVE"
        const val BOARD = "de.luca.mitfahrt.BOARD"
        const val MARK_ARRIVAL = "de.luca.mitfahrt.MARK_ARRIVAL"
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
        fun stop(context: Context) {
            (context.applicationContext as MitfahrtApplication).store.clear()
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}
