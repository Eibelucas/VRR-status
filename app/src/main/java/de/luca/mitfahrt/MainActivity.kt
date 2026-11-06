package de.luca.mitfahrt

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.app.NotificationManager
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.tracking.RideService
import de.luca.mitfahrt.ui.MitfahrtApp
import de.luca.mitfahrt.ui.FirstTimeSetup
import de.luca.mitfahrt.tracking.RideNotifications
import de.luca.mitfahrt.updates.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val vm: RideViewModel = viewModel()
            val updates: UpdateViewModel = viewModel()
            val updateState by updates.state.collectAsStateWithLifecycle()
            val tracking by vm.tracking.collectAsStateWithLifecycle()
            val search by vm.search.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val setup = remember { context.getSharedPreferences("first_time_setup", MODE_PRIVATE) }
            var showIntro by rememberSaveable { mutableStateOf(!setup.getBoolean("completed", false)) }
            var locationAllowed by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
            var notificationsAllowed by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
                    locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                } }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            val nearbyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!showIntro) vm.retryHome()
            }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                notificationsAllowed = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            }
            fun requestNearbyLocation() {
                when {
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED -> {
                        if (setup.getBoolean("location_requested", false) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION))
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        else {
                            setup.edit().putBoolean("location_requested", true).apply()
                            nearbyPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }
                    !context.getSystemService(LocationManager::class.java).isLocationEnabled ->
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    !showIntro -> vm.retryHome()
                }
            }
            var pending by remember { mutableStateOf<Ride?>(null) }
            var requestMonitor by remember { mutableStateOf(false) }
            var notice by remember { mutableStateOf<String?>(null) }
            var pendingInstallPath by rememberSaveable { mutableStateOf<String?>(null) }
            fun openInstaller(file: File) {
                runCatching {
                    check(file.isFile)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        clipData = ClipData.newRawUri("Mitfahrt-Update", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { notice = "Der Android-Installer konnte nicht geöffnet werden. Bitte erneut herunterladen." }
            }
            val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val file = pendingInstallPath?.let(::File)
                pendingInstallPath = null
                if (file != null) {
                    if (context.packageManager.canRequestPackageInstalls()) openInstaller(file)
                    else notice = "Die Installation wurde nicht freigegeben. Du kannst es über Installieren erneut versuchen."
                }
            }
            fun installUpdate(file: File) {
                if (context.packageManager.canRequestPackageInstalls()) openInstaller(file)
                else {
                    pendingInstallPath = file.absolutePath
                    runCatching { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"))) }.onFailure {
                        pendingInstallPath = null
                        notice = "Bitte erlaube die Installation aus Mitfahrt in den Android-Einstellungen."
                    }
                }
            }
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
                    if (!tracking.running && ride?.demo != true && (ride == null ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
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
            if (showIntro) FirstTimeSetup(locationAllowed, notificationsAllowed,
                onLocation = ::requestNearbyLocation,
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33 && !(setup.getBoolean("notifications_requested", false) &&
                            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
                        setup.edit().putBoolean("notifications_requested", true).apply()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else RideNotifications.openSettings(context)
                },
                onFinish = { setup.edit().putBoolean("completed", true).apply(); showIntro = false })
            else MitfahrtApp(tracking, search, vm, notice, updateState,
                onCheckUpdate = updates::check, onDownloadUpdate = updates::download, onInstallUpdate = ::installUpdate,
                onDismissNotice = { notice = null }, onMonitor = { begin(null) },
                onDemo = { begin(DemoRide.create()) },
                onResume = { tracking.ride?.let { begin(it) } },
                onStop = { RideService.stop(context) }, onNearbyLocation = ::requestNearbyLocation,
                onIntro = { showIntro = true })
        }
    }
}
