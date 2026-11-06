package de.luca.mitfahrt.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

data class HomeLocationEvent(val status: NearbyStatus, val point: GeoPoint? = null, val approximate: Boolean = false)

/** Only collected by the visible home/search screen. Never starts a foreground service. */
class HomeLocation(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun updates() = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var found = false
        fun deliver(location: Location) {
            val point = GeoPoint(location.latitude, location.longitude)
            if (validHomeFix(point, location.accuracy, (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000)) {
                found = true
                trySend(HomeLocationEvent(NearbyStatus.READY, point, location.accuracy > 250))
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { deliver(location) }
            override fun onProviderDisabled(provider: String) {
                if (!manager.isLocationEnabled) { found = false; trySend(HomeLocationEvent(NearbyStatus.LOCATION_OFF)) }
            }
            override fun onProviderEnabled(provider: String) { if (!found) trySend(HomeLocationEvent(NearbyStatus.LOCATING)) }
            @Deprecated("Legacy platform callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val timeout = Runnable { if (!found) trySend(HomeLocationEvent(if (manager.isLocationEnabled) NearbyStatus.UNAVAILABLE else NearbyStatus.LOCATION_OFF)) }
        if (!allowed) trySend(HomeLocationEvent(NearbyStatus.NO_PERMISSION))
        else {
            trySend(HomeLocationEvent(if (manager.isLocationEnabled) NearbyStatus.LOCATING else NearbyStatus.LOCATION_OFF))
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, 15_000, 0f, listener, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let(::deliver)
                }
            }
            handler.postDelayed(timeout, 20_000)
        }
        awaitClose { handler.removeCallbacks(timeout); runCatching { manager.removeUpdates(listener) } }
    }
}
