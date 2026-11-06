package de.luca.mitfahrt

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.tracking.RideService
import de.luca.mitfahrt.ui.MitfahrtApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val vm: RideViewModel = viewModel()
            val tracking by vm.tracking.collectAsStateWithLifecycle()
            val search by vm.search.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var pending by remember { mutableStateOf<Ride?>(null) }
            var requestMonitor by remember { mutableStateOf(false) }
            var notice by remember { mutableStateOf<String?>(null) }
            val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (requestMonitor && !fine) {
                    notice = "Für die WLAN-Erkennung ist genauer Standort nötig. Du kannst deine Fahrt auch manuell wählen."
                } else {
                    runCatching { RideService.start(context, pending) }.onFailure {
                        notice = "Die Fahrt konnte nicht gestartet werden. Bitte versuche es erneut."
                    }
                }
                pending = null
                requestMonitor = false
            }
            fun begin(ride: Ride?) {
                pending = ride
                requestMonitor = ride == null
                val required = buildList {
                    if (!tracking.running && ride?.demo != true) {
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        if (ride == null) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (required.isEmpty()) {
                    runCatching { RideService.start(context, ride) }.onFailure { notice = "Start fehlgeschlagen. Bitte erneut versuchen." }
                    pending = null
                    requestMonitor = false
                } else permissions.launch(required.toTypedArray())
            }
            LaunchedEffect(search.pendingRide) {
                search.pendingRide?.let { begin(it); vm.consumeRide() }
            }
            MitfahrtApp(tracking, search, vm, notice,
                onDismissNotice = { notice = null }, onMonitor = { begin(null) },
                onDemo = { begin(DemoRide.create()) },
                onResume = { tracking.ride?.let { begin(it) } },
                onStop = { RideService.stop(context) })
        }
    }
}
