package de.luca.mitfahrt.ui

import android.app.NotificationManager
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.luca.mitfahrt.RideViewModel
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.updates.UpdateState
import de.luca.mitfahrt.updates.UpdatePhase
import de.luca.mitfahrt.updates.UpdateInfo
import java.io.File
import de.luca.mitfahrt.SearchState
import de.luca.mitfahrt.ReportsState
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.tracking.RideNotifications
import de.luca.mitfahrt.tracking.NowBarSupport
import de.luca.mitfahrt.reports.DelayEvidence
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val Ink = Color(0xFFF2F2F3)
internal val Panel = Color(0xFFFFFFFF)
internal val Soft = Color(0xFF62636D)
internal val Lime = Color(0xFFB40055)
internal val Paper = Color(0xFF22262F)
internal val Amber = Color(0xFF925000)
internal val Shape = RoundedCornerShape(24.dp)

@Composable
internal fun MitfahrtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Lime, onPrimary = Color.White, background = Ink, onBackground = Paper,
        surface = Panel, onSurface = Paper, surfaceVariant = Panel, onSurfaceVariant = Soft,
        outline = Color(0xFFD9D5DF), secondary = Lime
    ), content = content)
}

@Composable
fun MitfahrtApp(
    tracking: TrackingState, search: SearchState, vm: RideViewModel, notice: String?, updates: UpdateState,
    onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: (File) -> Unit,
    onDismissNotice: () -> Unit, onMonitor: () -> Unit, onDemo: () -> Unit,
    onResume: () -> Unit, onStop: () -> Unit, onNearbyLocation: () -> Unit, onIntro: () -> Unit
) {
    val context = LocalContext.current
    val reports by vm.reports.collectAsStateWithLifecycle()
    val lastRide by vm.lastRide.collectAsStateWithLifecycle()
    val arrivals by vm.arrivals.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val schoolTrips by vm.schoolTrips.collectAsStateWithLifecycle()
    val alternatives by vm.alternatives.collectAsStateWithLifecycle()
    val hapticTest by vm.hapticTest.collectAsStateWithLifecycle()
    val actionMessage by vm.actionMessage.collectAsStateWithLifecycle()
    var reportRide by remember { mutableStateOf<Ride?>(null) }
    var arrivalRide by remember { mutableStateOf<Ride?>(null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var showLiveMap by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page, tracking.ride?.departure?.key, search.selectedStop?.id, lifecycle) {
        if (page == 0 && tracking.ride == null || page == 1 && search.selectedStop == null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.openHome()
                try { kotlinx.coroutines.awaitCancellation() } finally { vm.closeHome() }
            }
        }
    }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1000) } }
    LaunchedEffect(tracking.ride?.departure?.key) { if (tracking.ride != null) { page = 0; showAll = false } }
    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbar.showSnackbar(it); vm.clearActionMessage() }
    }
    LaunchedEffect(tracking.ride?.departure?.key, tracking.ride?.departure?.cancelled, tracking.ride?.boarded, page, lifecycle) {
        if (page == 0 && tracking.ride?.departure?.cancelled == true && tracking.ride?.boarded == false) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { vm.refreshAlternatives(); delay(30_000) }
            }
        }
    }
    LaunchedEffect(notice, search.error, reports.error) {
        (notice ?: search.error ?: reports.error)?.let { snackbar.showSnackbar(it); onDismissNotice(); vm.clearError(); vm.clearReportError() }
    }
    LaunchedEffect(reports.share) {
        reports.share?.let { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Verspätungsbericht · Mitfahrt")
                    clipData = ClipData.newRawUri("Verspätungsbericht", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Bericht teilen"))
            }.onFailure { snackbar.showSnackbar("Der Bericht wurde gespeichert. Teilen ist gerade nicht möglich.") }
            vm.consumeShare()
        }
    }
    MitfahrtTheme {
        Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    listOf("Fahrt" to Icons.Outlined.DirectionsBus, "Haltestellen" to Icons.Outlined.Search,
                        "Berichte" to Icons.Outlined.Description, "Einstellungen" to Icons.Outlined.Tune).forEachIndexed { index, (title, icon) ->
                        NavigationBarItem(selected = page == index, onClick = { page = index },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, indicatorColor = Lime,
                                selectedTextColor = Paper, unselectedTextColor = Soft, unselectedIconColor = Soft))
                    }
                }
            }
        ) { insets ->
            when (page) {
                0 -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item { Header("Mitfahrt", "MÖNCHENGLADBACH", Icons.Outlined.DirectionsBus) }
                    val ride = tracking.ride
                    if (ride != null) {
                        if (ride.demo) item { SmallNotice("Demofahrt", "Beispieldaten", Icons.Outlined.PlayCircleOutline) }
                        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) item {
                            NoticeCard("Auch auf dem Sperrbildschirm", "Erlaube Mitfahrt, Benachrichtigungen anzuzeigen.", "Aktivieren") {
                                RideNotifications.openSettings(context)
                            }
                        }
                        if (!tracking.running) item { NoticeCard("Fahrt pausiert", "Zum Weiterverfolgen fortsetzen.", "Fortsetzen", onResume) }
                        else if (!ride.demo && !ride.departure.cancelled && (ride.isStale(now) || tracking.lastRefreshFailed)) item {
                            SmallNotice("Verbindung unterbrochen", "Stand ${clockTime(ride.fetchedAt)}", Icons.Outlined.CloudOff, Amber)
                        }
                        if (ride.departure.cancelled) item { SmallNotice("Fahrt fällt aus", "Bitte eine andere Fahrt wählen", Icons.Outlined.WarningAmber, Amber) }
                        if (ride.departure.cancelled && !ride.boarded) item {
                            AlternativesCard(ride, alternatives, now, vm::refreshAlternatives, vm::chooseAlternative)
                        }
                        item { RideHero(ride, now) }
                        if (!ride.boarded && !ride.departure.cancelled) item {
                            Button(onClick = vm::boardBus, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp)); Text("Bin im Bus")
                            }
                        }
                        if (ride.boarded) item { NextStopCard(ride, now) }
                        if (!ride.demo && !ride.departure.cancelled) item { ExitTargetCard(ride, vm::setExitTarget, vm::saveSchoolTrip) }
                        if (!ride.demo) item { LiveMapCard { showLiveMap = true } }
                        if (!ride.demo) item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { arrivalRide = ride }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp)); Text("Bin angekommen")
                                }
                                ArrivalMark.forRide(arrivals, ride, now).lastOrNull()?.let { mark ->
                                    Text("Eigene Ankunft: ${shortName(mark.stopName)} · ${clockTime(mark.at)}", color = Lime, fontSize = 12.sp)
                                }
                            }
                        }
                        if (!ride.demo) item {
                            OutlinedButton(onClick = { reportRide = ride }, enabled = !reports.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text(if (reports.busy) "Wird erstellt …" else "Verspätung belegen")
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Fahrtverlauf", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Weniger" else "Alle Halte", color = Lime) }
                            }
                        }
                        val next = ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex)
                        val indices = if (showAll) (ride.boardingIndex..ride.stops.lastIndex).toList()
                            else (next..minOf(next + 3, ride.stops.lastIndex)).toList().let { list ->
                                if (list.lastOrNull() != ride.stops.lastIndex) list + ride.stops.lastIndex else list
                            }
                        items(indices, key = { it }) { index ->
                            TimelineStop(ride.stops[index], ride, now, index == next,
                                index < ride.nextIndex(now), index == indices.last(),
                                indices.indexOf(index).let { pos -> pos > 0 && index - indices[pos - 1] > 1 })
                        }
                        item {
                            Text(sourceText(ride, now), color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        item {
                            OutlinedButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text("Fahrt beenden", color = Paper)
                            }
                        }
                    } else {
                        item { SchoolTripCard(schoolTrips, now, vm::selectSchoolTrip, vm::removeSchoolTrip) }
                        item {
                            HomeOverview(home, routes, now, search.loading, search.choosing,
                                onSearch = { page = 1 }, onLocation = onNearbyLocation, onRefresh = vm::retryHome,
                                onStop = { vm.selectStop(it); page = 1 },
                                onAllStops = { vm.nearby(); page = 1 }, onRoute = { vm.selectRoute(it); page = 1 },
                                onDeparture = vm::choose, onClearHistory = vm::clearRoutes)
                        }
                        item { LiveMapCard { showLiveMap = true } }
                        item { DetectionCard(tracking, onMonitor, onStop) }
                        if (tracking.candidates.isNotEmpty()) {
                            item { Text("Dein Bus?", fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }
                            items(tracking.candidates, key = { it.key }) { departure ->
                                DepartureRow(departure, now, search.choosing == departure.key,
                                    enabled = !search.loading, showStop = true) { vm.choose(departure) }
                            }
                        }
                    }
                }
                1 -> StopSearch(search, tracking, arrivals, now, vm, Modifier.padding(insets), home, routes, onNearbyLocation)
                2 -> ReportsPage(reports, lastRide, Modifier.padding(insets), onCreate = { reportRide = it }, onShare = vm::shareReport)
                else -> SettingsPage(Modifier.padding(insets), onDemo, updates, onCheckUpdate, onDownloadUpdate, onInstallUpdate, onIntro, hapticTest, vm::testVibration)
            }
        }
        if (showLiveMap) NewLiveMapScreen(onClose = { showLiveMap = false })
        if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("Fahrt beenden?") },
            text = { Text("Die Fahrtanzeige wird geschlossen.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text("Beenden") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Weiterfahren") } })
        reportRide?.let { selected ->
            ReportStopPicker(selected, onDismiss = { reportRide = null }) { index ->
                vm.createReport(selected, index); reportRide = null
            }
        }
        arrivalRide?.let { selected ->
            AlertDialog(onDismissRequest = { arrivalRide = null },
                title = { Text("Wo bist du angekommen?") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(selected.stops.drop(selected.boardingIndex).distinctBy { stopKey(it.id) }, key = { stopKey(it.id) }) { stop ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                vm.markArrival(Stop(stop.id, stop.name)); arrivalRide = null
                            }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, null, tint = Lime, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp)); Text(shortName(stop.name))
                            }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { arrivalRide = null }) { Text("Abbrechen") } })
        }
    }
}

@Composable
private fun LiveMapCard(onClick: () -> Unit) {
    Card(onClick = onClick, shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Map, null, tint = Lime)
            Column(Modifier.weight(1f)) {
                Text("Busse auf der Karte", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Livepositionen von NEW", color = Soft, fontSize = 13.sp)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Soft, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(subtitle, color = Soft, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Lime, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
private fun DetectionCard(state: TrackingState, onMonitor: () -> Unit, onStop: () -> Unit) {
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Wifi, null, tint = Lime)
                Column(Modifier.weight(1f)) {
                    Text(when {
                        !state.monitoring -> "Bus automatisch finden"
                        state.wifi == WifiSignal.CONNECTED -> "Im NEW-Hotspot"
                        state.wifi == WifiSignal.NEARBY -> "NEW-Hotspot in der Nähe"
                        else -> "Suche nach deinem Bus"
                    }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(when {
                        !state.monitoring -> "Über WLAN und deinen Standort"
                        state.wifi == WifiSignal.UNAVAILABLE -> "WLAN und genauen Standort einschalten"
                        state.candidates.isNotEmpty() -> "Wähle unten deine Fahrt."
                        else -> "Lass WLAN und Standort eingeschaltet."
                    }, fontSize = 13.sp, color = Soft, lineHeight = 19.sp)
                }
            }
            if (state.monitoring && state.message != null && state.candidates.isEmpty())
                Text(state.message, color = Soft, fontSize = 13.sp, lineHeight = 19.sp)
            OutlinedButton(onClick = if (state.monitoring) onStop else onMonitor, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.monitoring) "Suche stoppen" else "Erkennung starten")
            }
        }
    }
}

@Composable
private fun RideHero(ride: Ride, now: Instant) {
    val waiting = !ride.boarded
    val boarding = ride.boardingTime()
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Lime, contentColor = Color.White)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(ride.departure.line, dark = true)
                Spacer(Modifier.width(10.dp))
                Text("Richtung ${shortName(ride.departure.destination)}", fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            if (waiting) {
                Text(if (boarding.isArrival) "DEIN BUS KOMMT IN" else "BIS ZUR ABFAHRT", fontSize = 11.sp, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (ride.departure.cancelled) "Fällt aus" else if (!boarding.at.isAfter(now)) "Wird erwartet"
                    else countdownUntil(boarding.at, now), fontSize = if (boarding.at.isAfter(now) && !ride.departure.cancelled) 68.sp else 30.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = (-2).sp)
                if (boarding.at.isAfter(now) && !ride.departure.cancelled) Text("Min : Sek", fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text(shortName(ride.departure.stopName), fontSize = 19.sp, fontWeight = FontWeight.Medium)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(ride.minutesToTarget(now)?.toString() ?: "–", fontSize = 88.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = (-6).sp, lineHeight = 88.sp)
                    Text(" Min", fontSize = 23.sp, modifier = Modifier.padding(bottom = 10.dp), fontWeight = FontWeight.Medium)
                }
                Text("bis ${shortName(ride.displayTarget().name)}", fontSize = 19.sp, fontWeight = FontWeight.Medium)
            }
            if (!ride.demo) {
                val next = ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex)
                val evidence = DelayEvidence.timingFor(ride, next) ?: DelayEvidence.timingFor(ride, ride.boardingIndex)
                evidence?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("${it.shortDelay} · ${if (it.fromMonitor) "bei Einstieg" else "${it.event.lowercase()} am nächsten Halt"}",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!waiting) LinearProgressIndicator(progress = { ride.progressToTarget(now) }, modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.16f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (waiting) "${if (boarding.isArrival) "Ankunft" else "Abfahrt"} ${clockTime(boarding.at)}"
                    else "Ankunft ${clockTime(ride.displayTarget().effective(ride.offsetSeconds))}", fontSize = 13.sp)
                Text(when {
                    ride.demo -> "Beispieldaten"
                    waiting -> if (boarding.live) "Live" else "Laut Fahrplan"
                    ride.displayTarget().estimated != null -> "Live"
                    ride.departure.estimated != null -> "Geschätzt"
                    else -> "Laut Fahrplan"
                }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NextStopCard(ride: Ride, now: Instant) {
    val next = ride.nextStopToTarget(now)
    Row(Modifier.fillMaxWidth().clip(Shape).background(Panel).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Lime.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.NearMe, null, tint = Lime, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (next != null) "NÄCHSTER HALT" else "ZIEL", color = Soft, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(5.dp))
            Text(next?.let { shortName(it.name) } ?: "Ziel laut Fahrtverlauf", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        next?.effective(ride.offsetSeconds)?.let {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("${minutesUntil(it, now)}", fontWeight = FontWeight.SemiBold, color = Lime, fontSize = 26.sp)
                Text("Min", fontSize = 12.sp, color = Soft)
            }
        }
    }
}

@Composable
private fun TimelineStop(stop: RideStop, ride: Ride, now: Instant, next: Boolean, passed: Boolean, last: Boolean, skipped: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 6.dp).size(if (next) 12.dp else 8.dp).clip(CircleShape).background(if (next) Lime else Color(0xFFAAA3B5)))
            if (!last) Box(Modifier.width(2.dp).height(32.dp).background(Color(0xFFD9D5DF)))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            if (skipped) Text("Weitere Halte", color = Soft, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Text(shortName(stop.name), color = if (passed) Soft else Paper, fontSize = 15.sp,
                fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal)
            if (stop.cancelled) Text("Halt entfällt", color = Amber, fontSize = 11.sp)
        }
        Text(clockTime(stop.effective(ride.offsetSeconds)), color = if (next) Lime else Soft, fontSize = 14.sp,
            modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun StopSearch(search: SearchState, tracking: TrackingState, arrivals: List<ArrivalMark>, now: Instant, vm: RideViewModel, modifier: Modifier,
    home: HomeState, routes: List<FrequentRoute>, onNearbyLocation: () -> Unit) {
    var waitingNearby by remember { mutableStateOf(false) }
    LaunchedEffect(home.stops, home.loading, home.status) {
        if (waitingNearby && home.status == NearbyStatus.READY && !home.loading && home.stops.isNotEmpty()) {
            waitingNearby = false; vm.nearby()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(search.selectedStop?.id, lifecycle) {
        if (search.selectedStop != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(30_000); vm.refreshSelectedStop() }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Haltestellen", "DEINE FAHRT FINDEN", Icons.Outlined.Search) }
        if (search.selectedStop == null) {
            item {
                OutlinedTextField(value = search.query, onValueChange = { waitingNearby = false; vm.query(it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Haltestelle suchen") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { waitingNearby = false; vm.search() }),
                    trailingIcon = { if (search.query.isNotEmpty()) IconButton(onClick = { vm.query("") }) { Icon(Icons.Outlined.Close, "Suche leeren") } })
            }
            item {
                Button(onClick = { waitingNearby = false; vm.search() }, enabled = search.query.trim().length >= 2 && !search.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Suchen") }
            }
            item {
                TextButton(onClick = {
                    if (home.status == NearbyStatus.READY || tracking.location != null) vm.nearby()
                    else { waitingNearby = true; onNearbyLocation() }
                }, enabled = !search.loading) {
                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("In meiner Nähe")
                }
            }
            if (!search.searched && !search.loading) {
                val suggestions = home.stops.ifEmpty { routes.map { it.visit.stop }.distinctBy { stopKey(it.id) } }
                if (suggestions.isNotEmpty()) item { Text(if (home.stops.isNotEmpty()) "In deiner Nähe" else "Zuletzt gewählt", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)) }
                items(suggestions, key = { it.id }) { stop ->
                    StopRow(stop) { vm.selectStop(stop) }
                }
            }
            items(search.stops, key = { it.id }) { stop -> StopRow(stop) { vm.selectStop(stop) } }
            if (search.searched && search.stops.isEmpty() && !search.loading) item {
                EmptyState("Keine Haltestelle gefunden", "Versuche einen anderen Namen.")
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::backToStops) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text(shortName(search.selectedStop.name), fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        Text(search.routeFilter?.let { "${it.visit.line} → ${shortName(it.visit.destination)}" } ?: "NEW · Abfahrten", color = Soft, fontSize = 13.sp)
                    }
                    IconButton(onClick = { vm.selectStop(search.selectedStop, search.routeFilter, search.schoolTrip) }, enabled = !search.loading) { Icon(Icons.Outlined.Refresh, "Aktualisieren") }
                }
            }
            if (search.routeFilter != null) item { TextButton(onClick = { vm.selectStop(search.selectedStop) }) { Text("Alle Linien anzeigen") } }
            JourneyOptions.walking(search.selectedStop)?.let { walk -> item {
                Text("${walk.label} · geschätzter Weg zur Haltestelle", color = Soft, fontSize = 13.sp)
            } }
            item { PersonalArrivalCard(search.selectedStop, arrivals) { vm.markArrival(search.selectedStop) } }
            items(search.departures, key = { it.key }) { departure ->
                DepartureRow(departure, now, search.choosing == departure.key, enabled = !search.loading) { vm.choose(departure) }
                WalkingHint(search.selectedStop, departure, now)
            }
            if (search.departures.isEmpty() && !search.loading) item { EmptyState("Gerade keine NEW-Abfahrten", "Wähle eine andere Haltestelle oder aktualisiere die Liste.") }
        }
        if (search.loading) item {
            Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), color = Lime, strokeWidth = 2.dp) }
        }
    }
}

@Composable
internal fun StopRow(stop: Stop, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocationOn, null, tint = Soft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortName(stop.name), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            stop.distance?.let { Text("$it m entfernt", fontSize = 12.sp, color = Soft) }
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Soft)
    }
}

@Composable
internal fun DepartureRow(d: Departure, now: Instant, loading: Boolean, enabled: Boolean, showStop: Boolean = false, distance: Int? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)
        .clickable(enabled = enabled && !d.cancelled, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LineBadge(d.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortName(d.destination), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(if (d.cancelled) "Fällt aus" else if (showStop) shortName(d.stopName) + (distance?.let { " · $it m" } ?: "") else
                listOfNotNull(d.platform.takeIf { it.isNotBlank() }, if (d.observedAt?.let { now.epochSecond - it.epochSecond > 120 } == true) {
                    "Stand ${clockTime(d.observedAt)}"
                } else if (d.estimated != null) {
                    if (d.delayMinutes > 0) "+${d.delayMinutes} Min" else "Live"
                } else "Fahrplan").joinToString(" · "),
                color = if (d.cancelled) Amber else Soft, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Lime, strokeWidth = 2.dp)
        else Column(horizontalAlignment = Alignment.End) {
            Text(if (d.cancelled) "–" else if (!d.effective.isAfter(now)) "Jetzt" else countdownUntil(d.effective, now),
                color = if (d.cancelled) Soft else Lime, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(if (d.effective.isAfter(now)) "Min : Sek" else clockTime(d.effective), color = Soft, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LineBadge(line: String, dark: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (dark) Ink else Lime.copy(alpha = .12f)).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(line, color = Lime, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, onDemo: () -> Unit, updates: UpdateState,
    onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: (File) -> Unit, onIntro: () -> Unit, hapticTest: de.luca.mitfahrt.alerts.HapticTestState, onHapticTest: () -> Unit) {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    var nowBarStatus by remember { mutableStateOf(NowBarSupport.status(context)) }
    var samsungCompat by remember { mutableStateOf(NowBarSupport.samsungEnabled(context)) }
    var ownTitle by remember { mutableStateOf(NowBarSupport.ownTitleEnabled(context)) }
    fun refreshNowBar() {
        val state = (context.applicationContext as de.luca.mitfahrt.MitfahrtApplication).store.state.value
        if (state.running && state.ride != null) RideNotifications(context).post(state)
    }
    LaunchedEffect(Unit) {
        while (true) { nowBarStatus = NowBarSupport.status(context); delay(2000) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Header("Einstellungen", "MITFAHRT", Icons.Outlined.Tune) }
        item { TextButton(onClick = onIntro) { Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Einführung ansehen") } }
        item {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("App-Updates", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("Installiert: ${BuildConfig.VERSION_NAME}", color = Soft, fontSize = 13.sp)
                    Text(when (updates.phase) {
                        UpdatePhase.IDLE -> "Updates über GitHub"
                        UpdatePhase.CHECKING -> "Suche nach Updates …"
                        UpdatePhase.CURRENT -> "Du bist auf dem neuesten Stand."
                        UpdatePhase.EMPTY -> "Noch keine Update-Version veröffentlicht."
                        UpdatePhase.AVAILABLE -> "Version ${updates.info?.versionName} ist verfügbar."
                        UpdatePhase.DOWNLOADING -> "Wird heruntergeladen · ${(updates.progress * 100).toInt()} %"
                        UpdatePhase.READY -> "Version ${updates.info?.versionName} ist bereit."
                        UpdatePhase.ERROR -> updates.message ?: "Das Update konnte nicht geladen werden."
                    }, color = if (updates.phase == UpdatePhase.ERROR) Amber else Paper, fontSize = 14.sp, lineHeight = 20.sp)
                    if (updates.phase == UpdatePhase.DOWNLOADING) LinearProgressIndicator(progress = { updates.progress }, modifier = Modifier.fillMaxWidth())
                    when (updates.phase) {
                        UpdatePhase.CHECKING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        UpdatePhase.DOWNLOADING -> Unit
                        UpdatePhase.AVAILABLE -> Button(onClick = onDownloadUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Herunterladen") }
                        UpdatePhase.READY -> Button(onClick = { updates.file?.let(onInstallUpdate) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Installieren") }
                        else -> OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Nach Updates suchen") }
                    }
                    if (updates.phase == UpdatePhase.ERROR && updates.info != null)
                        TextButton(onClick = onDownloadUpdate) { Text("Download erneut versuchen") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateInfo.REPOSITORY))) }) { Text("Projekt auf GitHub") }
                }
            }
        }
        item {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = Lime)
                    Text("Auf dem Sperrbildschirm", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text(if (Build.VERSION.SDK_INT >= 36) "Bus auswählen, Live-Benachrichtigungen erlauben und die App verlassen. Die Now Bar prüfst du anschließend auf dem Sperrbildschirm."
                    else "Deine Fahrt erscheint als laufende Benachrichtigung. Für die Now Bar ist eine unterstützte Samsung-Version mit Android 16 nötig.", color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
                    Text(nowBarStatus, color = Paper, fontSize = 13.sp, lineHeight = 20.sp)
                    if (Build.MANUFACTURER.equals("samsung", true) && Build.VERSION.SDK_INT >= 36) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Samsung-Kompatibilität", fontSize = 14.sp)
                                Text("Experimentelle Now-Bar-Anpassung", color = Soft, fontSize = 12.sp)
                            }
                            Switch(checked = samsungCompat, onCheckedChange = { enabled ->
                                samsungCompat = enabled
                                NowBarSupport.setSamsungEnabled(context, enabled)
                                refreshNowBar()
                            })
                        }
                        if (samsungCompat && Build.VERSION.SDK_INT >= 37) Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Eigenen Titel verwenden", fontSize = 14.sp)
                                Text("Bei Darstellungsproblemen ausschalten", color = Soft, fontSize = 12.sp)
                            }
                            Switch(checked = ownTitle, onCheckedChange = { enabled ->
                                ownTitle = enabled
                                NowBarSupport.setOwnTitleEnabled(context, enabled)
                                refreshNowBar()
                            })
                        }
                    }
                    OutlinedButton(onClick = { RideNotifications.openSettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Benachrichtigungen öffnen") }
                }
            }
        }
        item {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Wifi, null, tint = Lime)
                    Text("Buserkennung", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("NEW-Hotspot und dein Standort helfen beim Finden. Deine Linie bestätigst du selbst.", color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
                    Text("Die Erkennung läuft nur, wenn du sie startest. Android kann WLAN-Scans im Hintergrund einschränken.", color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Berechtigungen öffnen") }
                }
            }
        }
        item {
            TextButton(onClick = onDemo) {
                Icon(Icons.Outlined.PlayCircleOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Demofahrt ansehen")
            }
        }
        item {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Watch, null, tint = Lime)
                    Text("Auf deiner Uhr", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("Linie, nächster Halt und Restzeit auf Wear OS. Die Fahrt wählst du hier am Handy.", color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${UpdateInfo.REPOSITORY}/blob/main/WATCH.md")))
                    }) { Text("Watch-App & Installation") }
                    Text("Für die Kachel auf der Uhr: Kachel hinzufügen → Fahrtstatus.", color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                    OutlinedButton(onClick = onHapticTest, enabled = !hapticTest.running, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hapticTest.running) "Uhr wird geprüft …" else "Vibration auf Handy & Uhr testen")
                    }
                    hapticTest.message?.let { Text(it, color = Soft, fontSize = 13.sp) }
                    Text("Nicht stören und Vibrationsstärke gelten auf beiden Geräten.", color = Soft, fontSize = 12.sp)
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Mitfahrt · Busstatus für Mönchengladbach\n${UpdateInfo.REPOSITORY}/releases/latest")
                }, "App teilen"))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("App teilen")
            }
        }
        item {
            Text("Daten vom VRR. Standort wird für Haltestellenabfragen an den VRR übermittelt. Kein Konto, keine Werbung, keine Analyse-Tracker.",
                color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("Letzte Fahrt, selbst erfasste Ankünfte und Berichte werden auf deinem Gerät gespeichert.", color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            Text("Mitfahrt ${BuildConfig.VERSION_NAME} · Inoffizielle App", color = Soft, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReportsPage(state: ReportsState, lastRide: Ride?, modifier: Modifier,
    onCreate: (Ride) -> Unit, onShare: (String) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Header("Berichte", "FÜR DEINE UNTERLAGEN", Icons.Outlined.Description) }
        if (lastRide != null) item {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Letzte Fahrt", color = Soft, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(lastRide.departure.line)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shortName(lastRide.departure.destination), fontWeight = FontWeight.SemiBold)
                            Text(reportDate(lastRide.departure.planned), color = Soft, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { onCreate(lastRide) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (state.busy) "Wird erstellt …" else "Bericht erstellen")
                    }
                }
            }
        }
        if (state.entries.isEmpty()) item {
            EmptyState("Noch keine Berichte", "Erstelle einen Bericht während oder nach deiner Fahrt.")
        }
        items(state.entries, key = { it.id }) { report ->
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${report.line} · ${shortName(report.stopName)}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("${reportDate(report.planned)} · ${report.event}", color = Soft, fontSize = 12.sp)
                    Text(report.delayText, color = if (report.delaySeconds > 0) Amber else Lime, fontSize = 14.sp)
                    if (report.personalArrivals.isNotEmpty()) Text("Eigene Ankunft im Bericht enthalten", color = Soft, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Privater VRR-Datenbericht", color = Soft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onShare(report.id) }) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Teilen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalArrivalCard(stop: Stop, arrivals: List<ArrivalMark>, onRecord: () -> Unit) {
    val last = arrivals.filter { stopKey(it.stopId) == stopKey(stop.id) }.maxByOrNull { it.at }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Meine Ankunft", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (last != null) Text("Erfasst: ${reportDate(last.at)}", color = Lime, fontSize = 13.sp)
            else Text("Uhrzeit per Tastendruck festhalten", color = Soft, fontSize = 13.sp)
            OutlinedButton(onClick = onRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(if (last == null) Icons.Outlined.LocationOn else Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (last == null) "Bin angekommen" else "Erneut festhalten")
            }
        }
    }
}

@Composable
private fun ReportStopPicker(ride: Ride, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val choices = remember(ride) { (ride.boardingIndex..ride.stops.lastIndex).map { index ->
        Triple(index, ride.stops[index], DelayEvidence.timingFor(ride, index))
    } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Haltestelle wählen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nur Halte mit gemeldeten Zeiten sind auswählbar.", color = Soft, fontSize = 13.sp)
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(choices, key = { it.first }) { (index, stop, timing) ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = timing != null) { onSelect(index) }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                            Text(shortName(stop.name), color = if (timing == null) Soft else Paper, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(timing?.let { "${it.event} ${clockTime(it.estimated)} · ${it.shortDelay}" }
                                ?: "Keine gemeldete Prognose", color = if (timing == null) Soft else Lime, fontSize = 12.sp)
                        }
                    }
                }
                Text("Privater Bericht aus VRR-Daten. Keine offizielle Bescheinigung.", color = Soft, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } })
}

private fun reportDate(time: Instant): String = time.atZone(ZoneId.of("Europe/Berlin"))
    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm"))

@Composable
private fun SmallNotice(title: String, subtitle: String, icon: ImageVector, color: Color = Lime) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(subtitle, color = Soft, fontSize = 11.sp)
    }
}

@Composable
private fun NoticeCard(title: String, subtitle: String, button: String, onClick: () -> Unit) {
    Card(shape = Shape) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Soft, fontSize = 13.sp)
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.DirectionsBus, null, tint = Soft, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

private fun sourceText(ride: Ride, now: Instant): String = when {
    ride.demo -> "Demofahrt · Keine echten Fahrtdaten"
    ride.isStale(now) -> "Letzter Stand ${clockTime(ride.fetchedAt)} · Zeiten können abweichen"
    ride.displayTarget().estimated != null -> "VRR-Echtzeitdaten · Stand ${clockTime(ride.fetchedAt)}"
    ride.departure.estimated != null -> "Ankunft anhand der letzten Verspätung geschätzt · Stand ${clockTime(ride.fetchedAt)}"
    else -> "Zeiten laut Fahrplan · Stand ${clockTime(ride.fetchedAt)}"
}
