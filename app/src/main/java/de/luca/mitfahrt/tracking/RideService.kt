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
import java.time.Instant
import de.luca.mitfahrt.watch.watchSnapshot

class RideService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MitfahrtApplication
    private val store get() = app.store
    private lateinit var notifications: RideNotifications
    private lateinit var wifi: WifiManager
    private lateinit var locations: LocationManager
    private var loop: Job? = null
    private var observer: Job? = null
    private var lastWifiScan = 0L
    private var lastCandidates = 0L
    private var lastRideRefresh = 0L
    private var candidateLookup: Job? = null
    private var approaching: Int? = null
    private var arrivalAlerted: String? = null
    private var sessionStart = 0L
    private var sensorsRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { readWifi() }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RideNotifications(this)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        locations = getSystemService(LocationManager::class.java)
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { store.clear(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == START_RIDE) {
            val ride = runCatching { RideCodec.decode(intent.getStringExtra("ride") ?: "") }.getOrNull()
            if (ride == null) { stopSelf(); return START_NOT_STICKY }
            store.setRide(ride)
            store.update { it.copy(monitoring = false, candidates = emptyList()) }
            approaching = null
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
        } catch (error: SecurityException) {
            store.update { it.copy(running = false, monitoring = false, message = "Bitte Standortzugriff in den App-Einstellungen erlauben und erneut starten.") }
            stopSelf(); return START_NOT_STICKY
        }
        if (intent?.action == START_RIDE) store.state.value.ride?.let { app.routes.record(it) }
        if (loop == null) {
            sessionStart = SystemClock.elapsedRealtime()
            startSensors()
            observer = scope.launch { store.state.collect { notifications.post(it) } }
            loop = scope.launch {
                while (isActive) {
                    val elapsed = SystemClock.elapsedRealtime()
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
                        if (ride.complete(Instant.now()) && (ride.demo || (!ride.isStale(Instant.now()) && !store.state.value.lastRefreshFailed))) {
                            store.clear(); stopSelf(); break
                        }
                        if (!ride.demo && elapsed - lastRideRefresh > 30_000) {
                            lastRideRefresh = elapsed
                            refreshRide(ride)
                        }
                    } else if (store.state.value.monitoring && store.state.value.wifi in setOf(WifiSignal.CONNECTED, WifiSignal.NEARBY)) {
                        findCandidates()
                    }
                    val current = store.state.value
                    val currentRide = current.ride
                    val snapshot = current.watchSnapshot()
                    if (currentRide != null && currentRide.stops.getOrNull(currentRide.boardingIndex)?.cancelled != true &&
                        de.luca.mitfahrt.shared.ArrivalAlert.due(snapshot, Instant.now())) {
                        val id = currentRide.departure.key
                        if (arrivalAlerted != id) {
                            arrivalAlerted = id
                            de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(this@RideService, id)
                            app.watch.arrivalAlert(id, requireNotNull(snapshot.boardingAt))
                        }
                    }
                    if (ExitAlarm.due(current, Instant.now()) && currentRide != null) {
                        val id = ExitAlarm.id(currentRide)
                        val prefs = getSharedPreferences("exit_alerts", MODE_PRIVATE)
                        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
                        if (id != null && id !in seen) {
                            prefs.edit().putStringSet("seen", (seen.toList().takeLast(99) + id).toSet()).apply()
                            de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(this@RideService, id)
                            app.watch.arrivalAlert(id, requireNotNull(currentRide.exitTargetStop()?.effective(currentRide.offsetSeconds)))
                        }
                    }
                    notifications.post(store.state.value)
                    delay(10_000)
                }
            }
        } else { readWifi() }
        return START_NOT_STICKY
    }

    private suspend fun refreshRide(ride: Ride) {
        try {
            var departure = ride.departure
            // The trip endpoint may omit real-time estimates even if the departure monitor has them.
            runCatching { app.vrr.departures(Stop(departure.stopId, departure.stopName)) }.getOrNull()
                ?.firstOrNull { it.key == departure.key }?.let { departure = it }
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
                store.setRide(updated.copy(gpsPassedIndex = maxOf(ride.gpsPassedIndex, current.gpsPassedIndex),
                    boarded = current.boarded, trackingStartedAt = current.trackingStartedAt).remapExitTargetFrom(current))
                if (departure.cancelled) store.update { it.copy(message = "Diese Fahrt wird als ausgefallen gemeldet.") }
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

    private fun hasLocation(): Boolean = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun startSensors() {
        if (sensorsRegistered) return
        sensorsRegistered = true
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply { addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)
        if (hasLocation()) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                runCatching {
                    if (locations.isProviderEnabled(provider)) locations.requestLocationUpdates(provider, 5_000, 12f, this)
                }
            }
        }
        scanWifi()
        readWifi()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanWifi() { runCatching { wifi.startScan() } }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifi() {
        val signal = runCatching {
            if (!wifi.isWifiEnabled || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                !locations.isLocationEnabled) WifiSignal.UNAVAILABLE
            else if (isNewHotspot(wifi.connectionInfo.ssid)) WifiSignal.CONNECTED
            else if (wifi.scanResults.any { isNewHotspot(it.SSID) && SystemClock.elapsedRealtime() - it.timestamp / 1000 in 0..90_000 }) WifiSignal.NEARBY
            else WifiSignal.NONE
        }.getOrDefault(WifiSignal.UNAVAILABLE)
        if (signal != store.state.value.wifi) store.update { it.copy(wifi = signal) }
    }

    override fun onLocationChanged(location: Location) {
        if (location.accuracy > 150 || (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) > 60_000_000_000) return
        val point = GeoPoint(location.latitude, location.longitude)
        store.update { it.copy(location = point, locationAt = Instant.now()) }
        val ride = store.state.value.ride ?: return
        if (ride.demo || location.accuracy > 65) return
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

    override fun onProviderDisabled(provider: String) { store.update { it.copy(location = null, locationAt = null) } }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy platform callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        scope.cancel()
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
