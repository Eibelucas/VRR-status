package de.luca.mitfahrt.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Whether the device has a usable internet connection right now; updates while shown. */
@Composable
internal fun rememberOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember { mutableStateOf(isOnline(manager)) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = isOnline(manager) }
            override fun onLost(network: Network) { online = isOnline(manager) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { online = isOnline(manager) }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback) }.isSuccess && manager != null
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    return online
}

private fun isOnline(manager: ConnectivityManager?): Boolean = runCatching {
    val caps = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching manager == null
    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}.getOrDefault(true)

internal const val OFFLINE_MAP_NOTE = "Offline: Kartenbild nur soweit zwischengespeichert. Strecke und Halte kommen aus der App."
