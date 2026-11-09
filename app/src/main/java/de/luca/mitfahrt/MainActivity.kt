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
    private val entryIntent = kotlinx.coroutines.flow.MutableStateFlow<Intent?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        entryIntent.value = intent
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryIntent.value = intent
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val vm: RideViewModel = viewModel()
            val updates: UpdateViewModel = viewModel()
            val updateState by updates.state.collectAsStateWithLifecycle()
            val tracking by vm.tracking.collectAsStateWithLifecycle()
            val search by vm.search.collectAsStateWithLifecycle()
            val context = LocalContext.current
            val launchRequest by entryIntent.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            LaunchedEffect(launchRequest) { launchRequest?.let(vm::handleLaunchIntent) }
            LaunchedEffect(Unit) { updates.checkOnOpen() }
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
                if (vm.settingsStore.current().string("location_mode", "active") == "off") return
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
            var pendingRideJson by rememberSaveable { mutableStateOf<String?>(null) }
            var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
            var switchRideJson by rememberSaveable { mutableStateOf<String?>(null) }
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
                val action = pendingAction
                val selected = pendingRideJson?.let { value -> runCatching { RideCodec.decode(value) }.getOrNull() }
                pendingRideJson = null; pendingAction = null
                val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                when {
                    action == "monitor" && !fine -> notice = "Für die WLAN-Erkennung ist genauer Standort nötig. Du kannst deine Fahrt auch manuell wählen."
                    action == "ride" && selected == null -> notice = "Bitte wähle deine Fahrt erneut aus."
                    action != null -> runCatching { RideService.start(context, selected) }.onFailure {
                        notice = "Die Fahrt konnte nicht gestartet werden. Bitte versuche es erneut."
                    }
                }
            }
            fun begin(ride: Ride?) {
                pendingRideJson = ride?.let(RideCodec::encode)
                pendingAction = if (ride == null) "monitor" else "ride"
                val required = buildList {
                    if (vm.settingsStore.current().string("location_mode", "active") != "off" && !tracking.running && ride?.demo != true && (ride == null ||
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
                    pendingRideJson = null
                    pendingAction = null
                } else permissions.launch(required.toTypedArray())
            }
            fun selectRide(selected: Ride) {
                val current = tracking.ride
                if (current != null && current.departure.key != selected.departure.key) switchRideJson = RideCodec.encode(selected)
                else {
                    val existing = current?.takeIf { it.departure.key == selected.departure.key }
                    val chosen = if (existing != null && selected.exitTargetStop() != null)
                        existing.remapExitTargetFrom(selected) else existing ?: selected
                    if (selected.exitTargetStop() != null && chosen.exitTargetStop() == null)
                        notice = "Der Fahrtverlauf hat sich geändert. Bitte die Fahrt erneut öffnen."
                    else begin(chosen)
                }
            }
            LaunchedEffect(search.pendingRide) {
                search.pendingRide?.let { selected -> if (search.replaceRide) begin(selected) else selectRide(selected); vm.consumeRide() }
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
                onDemo = { selectRide(DemoRide.create()) },
                onResume = { tracking.ride?.let { begin(it) } },
                onStop = { RideService.stop(context) }, onNearbyLocation = ::requestNearbyLocation,
                onIntro = { showIntro = true })
            switchRideJson?.let { value ->
                val selected = runCatching { RideCodec.decode(value) }.getOrNull()
                if (selected != null) de.luca.mitfahrt.ui.MitfahrtTheme {
                    androidx.compose.material3.AlertDialog(onDismissRequest = { switchRideJson = null },
                        title = { androidx.compose.material3.Text(if (selected.demo) "Demo starten?" else "Fahrt wechseln?") },
                        text = { androidx.compose.material3.Text(if (selected.demo) "Die aktuelle Fahrt wird durch die Demofahrt ersetzt." else "Statt der aktuellen Fahrt Linie ${selected.departure.line} Richtung ${shortName(selected.departure.destination)} verfolgen?") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { switchRideJson = null; begin(selected) }) { androidx.compose.material3.Text("Wechseln") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { switchRideJson = null }) { androidx.compose.material3.Text("Abbrechen") } })
                }
            }

        }
    }
}
