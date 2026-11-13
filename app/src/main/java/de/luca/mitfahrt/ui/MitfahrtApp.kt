package de.luca.mitfahrt.ui

import android.app.NotificationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import de.luca.mitfahrt.data.BoardingPhase
import de.luca.mitfahrt.data.BoardingState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Containers that group content. */
internal val Shape = RoundedCornerShape(20.dp)
/** Buttons, fields and rows inside a container: clearly less round than the container. */
internal val ControlShape = RoundedCornerShape(12.dp)

/** Page of the active ride; reached from the ride bar above the navigation, not a tab of its own. */
private const val RIDE_PAGE = 4

@Composable
fun MitfahrtApp(
    tracking: TrackingState, search: SearchState, vm: RideViewModel, notice: String?, updates: UpdateState,
    onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: (File) -> Unit,
    onDismissNotice: () -> Unit, onMonitor: () -> Unit, onDemo: () -> Unit,
    onResume: () -> Unit, onStop: () -> Unit, onNearbyLocation: () -> Unit, onIntro: () -> Unit
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dayOverrides by vm.dayOverrides.collectAsStateWithLifecycle()
    val schoolCalendar by vm.schoolCalendar.collectAsStateWithLifecycle()
    val reports by vm.reports.collectAsStateWithLifecycle()
    val lastRide by vm.lastRide.collectAsStateWithLifecycle()
    val arrivals by vm.arrivals.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val routePlans by vm.routePlans.collectAsStateWithLifecycle()
    val planDepartures by vm.planDepartures.collectAsStateWithLifecycle()
    val schoolTrips by vm.schoolTrips.collectAsStateWithLifecycle()
    val savedStops by vm.savedStops.collectAsStateWithLifecycle()
    val shownSavedStops = if (routePlans.automaticLearning) savedStops else savedStops.copy(recent = emptyList())
    val alertPreferences by vm.alertPreferences.collectAsStateWithLifecycle()
    val alternatives by vm.alternatives.collectAsStateWithLifecycle()
    val hapticTest by vm.hapticTest.collectAsStateWithLifecycle()
    val actionMessage by vm.actionMessage.collectAsStateWithLifecycle()
    val planner: de.luca.mitfahrt.TripPlannerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val plannerForm by planner.form.collectAsStateWithLifecycle()
    val tripPlaces by planner.places.collectAsStateWithLifecycle()
    val companion by vm.companion.collectAsStateWithLifecycle()
    val companionSeen by vm.companionSeen.collectAsStateWithLifecycle()
    val companionNext by vm.companionNext.collectAsStateWithLifecycle()
    val journeyReports by vm.journeyReports.collectAsStateWithLifecycle()
    var openReportId by rememberSaveable { mutableStateOf<String?>(null) }
    var savingReportId by rememberSaveable { mutableStateOf<String?>(null) }
    var savingJourneyReport by rememberSaveable { mutableStateOf(false) }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = savingReportId; savingReportId = null
        if (uri != null && id != null) { if (savingJourneyReport) vm.exportJourneyReport(id, uri) else vm.saveReport(id, uri) }
    }
    val navigation by vm.navigation.collectAsStateWithLifecycle()
    var arrivalRide by remember { mutableStateOf<Ride?>(null) }
    var page by rememberSaveable { mutableIntStateOf(if (settings.string("start_page") == "nearby") 1 else 0) }
    var showPlanner by rememberSaveable { mutableStateOf(false) }
    var showCustomization by rememberSaveable { mutableStateOf(false) }
    var personalModule by rememberSaveable { mutableStateOf<String?>(null) }
    var showDataManagement by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var showLiveMap by rememberSaveable { mutableStateOf(settings.string("start_page") == "map") }
    var showRoutePlans by rememberSaveable { mutableStateOf(settings.string("start_page") == "routes") }
    var newRoutePlan by rememberSaveable { mutableStateOf(false) }
    var dismissedReportOffer by rememberSaveable(tracking.ride?.departure?.key) { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var historyUndoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var planUndoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun selectStop(stop: Stop) { keyboard?.hide(); focus.clearFocus(); vm.selectStop(stop); page = 1 }
    fun toggleFavorite(stop: Stop) {
        val previous = savedStops.favorites.indexOfFirst { stopKey(it.id) == stopKey(stop.id) }
        val pinned = vm.toggleFavorite(stop)
        scope.launch {
            if (previous < 0 && !pinned) snackbar.showSnackbar("Du kannst bis zu 12 Haltestellen merken.")
            else {
                val result = snackbar.showSnackbar(if (pinned) "Haltestelle gemerkt" else "Favorit entfernt",
                    actionLabel = if (previous >= 0) "Rückgängig" else null, withDismissAction = previous >= 0,
                    duration = if (previous >= 0) SnackbarDuration.Long else SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) vm.restoreFavorite(stop, previous)
            }
        }
    }
    fun removeRoute(route: FrequentRoute) {
        val removed = vm.removeRoute(route)
        if (removed.isEmpty()) return
        historyUndoJob?.cancel()
        historyUndoJob = scope.launch {
            if (snackbar.showSnackbar("Strecke aus Verlauf entfernt", actionLabel = "Rückgängig", withDismissAction = true,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) vm.restoreRoutes(removed)
        }
    }
    fun clearRouteHistory() {
        historyUndoJob?.cancel()
        historyUndoJob = null
        vm.clearRoutes()
    }
    fun forgetLastRide(ride: Ride) {
        val removed = vm.forgetLastRide(ride) ?: return
        scope.launch {
            if (snackbar.showSnackbar("Fahrt aus Verlauf entfernt", actionLabel = "Rückgängig", withDismissAction = true,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed &&
                !vm.restoreLastRide(removed)) snackbar.showSnackbar("Die Fahrt konnte nicht wiederhergestellt werden.")
        }
    }
    fun removeRoutePlan(plan: RoutePlan) {
        val removed = vm.removeRoutePlan(plan) ?: return
        planUndoJob?.cancel()
        planUndoJob = scope.launch {
            if (snackbar.showSnackbar("Route entfernt", actionLabel = "Rückgängig", withDismissAction = true,
                    duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed && !vm.restoreRoutePlan(removed))
                snackbar.showSnackbar("Die Route konnte nicht wiederhergestellt werden.")
        }
    }
    val nearbyForPlans = home.stops.takeIf { home.status == NearbyStatus.READY }.orEmpty()
    val visiblePlans = HomeSections.activePlans(routePlans.routes, now, nearbyForPlans, settings, dayOverrides, schoolCalendar)
    val suggestedRoutes = if (routePlans.automaticLearning && settings.bool("learn_routes", true) && !settings.bool("manual_only", false)) routes.filter { it.count >= settings.int("suggestion_threshold", 3) } else emptyList()
    val cardOrder = settings.csv("home_cards").let { if (settings.bool("map_home", false) && "map" !in it) it + "map" else it }
    val locationOff = settings.string("location_mode", "active") == "off"
    val locationUsable = !locationOff && home.status != NearbyStatus.NO_PERMISSION && home.status != NearbyStatus.LOCATION_OFF
    // The start page asks VRR for nearby stops only when one of its sections needs them.
    val homeNeedsNearby = !settings.bool("manual_only", false) &&
        ("planned" in cardOrder && routePlans.routes.any { it.enabled && it.nearbyOnly } ||
            "suggestions" in cardOrder && suggestedRoutes.isNotEmpty() || "school" in cardOrder && schoolTrips.isNotEmpty())
    val homeSections = HomeSections.visible(cardOrder, settings, visiblePlans, now, schoolCalendar, schoolTrips.size,
        suggestedRoutes.size, lastRide != null)
    val companionPhase = companion?.let { JourneyCompanions.phase(it, tracking.ride?.departure?.key, companionSeen) }
    val gap = when (settings.string("density")) { "compact" -> 12.dp; "comfortable" -> 28.dp; else -> 20.dp }
    BackHandler(enabled = page != 0 && !showLiveMap && !showRoutePlans && !showCustomization && personalModule == null && !showPlanner) {
        keyboard?.hide(); focus.clearFocus()
        if (page == 1 && search.selectedStop != null) vm.backToStops() else page = 0
    }
    LaunchedEffect(navigation) {
        when (navigation) {
            "settings" -> page = 3; "map" -> showLiveMap = true; "stops" -> { showPlanner = false; page = 1 }
            "ride" -> { showPlanner = false; page = if (tracking.ride != null) RIDE_PAGE else 0 }
        }
        if (navigation != null) vm.consumeNavigation()
    }
    LaunchedEffect(settings) { vm.applySettings() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page, search.selectedStop?.id, showRoutePlans, showCustomization, personalModule, homeNeedsNearby, lifecycle) {
        if (!showRoutePlans && !showCustomization && personalModule == null && (page == 0 || page == 1)) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.openHome(lookupNearby = if (page == 1) search.selectedStop == null else homeNeedsNearby)
                try { kotlinx.coroutines.awaitCancellation() } finally { vm.closeHome() }
            }
        }
    }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = Instant.now(); vm.settingsStore.refresh(); delay(1000) } } }
    LaunchedEffect(tracking.ride?.departure?.key) {
        val key = tracking.ride?.departure?.key
        if (key != null) {
            if (companion?.trackedKey == key) showPlanner = false
            if (!showPlanner) page = RIDE_PAGE
            showAll = false
        } else if (page == RIDE_PAGE) page = 0
    }
    // Position for "Mein Standort": in memory only, from the same location source as "In der Nähe".
    LaunchedEffect(home.point, home.pointAt, locationUsable) {
        planner.updatePosition(home.point?.takeIf { home.pointAt?.let { Instant.now().epochSecond - it.epochSecond in -10..120 } == true },
            unavailable = !locationUsable)
    }
    LaunchedEffect(locationUsable) { planner.applyDefaultOrigin(locationUsable) }
    // Live data of the next leg while a journey is accompanied and the app is visible.
    LaunchedEffect(companion?.journey?.key, companion?.legIndex, lifecycle) {
        if (companion?.nextLeg != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { vm.refreshCompanionNext(); delay(60_000) }
        }
    }
    LaunchedEffect(companionPhase) {
        if (companionPhase == CompanionPhase.FINISHED) {
            vm.endJourney(); snackbar.showSnackbar("Reise beendet")
        }
    }
    LaunchedEffect(actionMessage) {
        actionMessage?.let { snackbar.showSnackbar(it); vm.clearActionMessage() }
    }
    LaunchedEffect(tracking.ride?.departure?.key, tracking.ride?.departure?.cancelled, tracking.ride?.boarded, page, lifecycle) {
        if (page == RIDE_PAGE && tracking.ride?.departure?.cancelled == true && tracking.ride?.boarded == false) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { vm.refreshAlternatives(); delay(30_000) }
            }
        }
    }
    LaunchedEffect(notice, search.error, reports.error, page) {
        (notice ?: search.error?.takeIf { page != 1 } ?: reports.error)?.let { snackbar.showSnackbar(it); onDismissNotice(); if (page != 1) vm.clearError(); vm.clearReportError() }
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
        if (personalModule != null) PersonalModuleScreen(personalModule!!, vm, now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()) { personalModule = null }
        else if (showCustomization) CustomizationScreen(vm.settingsStore, onBack = { showCustomization = false },
            onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } }, onOpenModule = { personalModule = it })
        else if (showRoutePlans) RoutePlansScreen(routePlans, shownSavedStops, nearbyForPlans,
            onBack = { showRoutePlans = false }, onAutomaticLearning = vm::setAutomaticLearning,
            onSave = vm::saveRoutePlan, onRemove = ::removeRoutePlan, searchStops = vm::findRouteStops,
            snackbarHostState = snackbar, startNew = newRoutePlan,
            resolveStop = vm::resolveRouteStop, onMove = vm::moveRoutePlan, onOpen = { plan ->
                showRoutePlans = false; vm.selectRoutePlan(plan); page = 1
            })
        else Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    tracking.ride?.takeIf { page != RIDE_PAGE }?.let { ride ->
                        ActiveRideBar(ride, tracking, now, companion?.takeIf { companionPhase == CompanionPhase.TRACKING }) { page = RIDE_PAGE }
                    }
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    val tabs = mapOf("home" to Triple(0, "Start", Icons.Outlined.Home), "nearby" to Triple(1, "In der Nähe", Icons.Outlined.NearMe),
                        "reports" to Triple(2, "Berichte", Icons.Outlined.Description), "settings" to Triple(3, "Einstellungen", Icons.Outlined.Tune))
                    (settings.csv("nav_order") + tabs.keys).distinct().mapNotNull(tabs::get).forEach { (index, title, icon) ->
                        NavigationBarItem(selected = page == index, onClick = {
                            if (index == 1 && page == 1 && search.selectedStop != null) vm.backToStops()
                            page = index
                        },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onPrimary, indicatorColor = Lime,
                                selectedTextColor = Paper, unselectedTextColor = Soft, unselectedIconColor = Soft))
                    }
                }
                }
            }
        ) { insets ->
            when (page) {
                RIDE_PAGE -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                    item {
                        if (tracking.ride != null) Header(if (tracking.ride.boarded) "Unterwegs" else "Deine Fahrt", "", Icons.Outlined.Close) { confirmStop = true }
                        else Header("Mitfahrt", "", Icons.Outlined.DirectionsBus)
                    }
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
                            val sourcePlan = routePlans.routes.firstOrNull { plan -> stopKey(plan.origin.id) == stopKey(ride.departure.stopId) &&
                                stopKey(plan.destination.id) == stopKey(ride.displayTarget().id) }
                            val alternative = routePlans.routes.firstOrNull { it.id == sourcePlan?.alternateRouteId && it.enabled }
                            if (alternative != null) OutlinedButton(shape = ControlShape, onClick = { vm.selectRoutePlan(alternative, recordVisit = false); page = 1 }, modifier = Modifier.fillMaxWidth()) {
                                Text("Alternative: ${alternative.name}")
                            }
                        }
                        item { RideHero(ride, now, tracking.running && !tracking.lastRefreshFailed) }
                        companion?.takeIf { companionPhase == CompanionPhase.TRACKING }?.let { current -> item {
                            CompanionCard(current, CompanionPhase.TRACKING, ride, companionNext, now, search.loading,
                                onContinue = vm::continueJourney, onResume = vm::resumeJourney,
                                onAlternatives = { vm.journeyAlternatives()?.let { planner.planQuery(it); showPlanner = true } },
                                onEnd = vm::endJourney)
                        } }
                        val offeredStop = de.luca.mitfahrt.reports.ReportOfferPolicy.candidate(ride, settings, now)
                        if (offeredStop != null && !dismissedReportOffer) item {
                            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Verspätung gemeldet", fontWeight = FontWeight.SemiBold)
                                        TextButton(onClick = { openReportId = vm.currentReportId(); dismissedReportOffer = true }) { Text("Bericht ansehen") }
                                    }
                                    IconButton(onClick = { dismissedReportOffer = true }) { Icon(Icons.Outlined.Close, "Hinweis schließen") }
                                }
                            }
                        }
                        if (!ride.departure.cancelled && !ride.demo) item {
                            BoardingControls(ride, tracking.boarding, settings.bool("boarding_auto", true) && tracking.location != null,
                                onBoard = vm::boardBus, onNotOnBoard = vm::notOnBoard, onStillOnBoard = vm::stillOnBoard, onEnd = { confirmStop = true })
                        }
                        // NEW live positions only for NEW trips; trains and other operators have none.
                        if (!ride.demo && tracking.running && ride.departure.newOperated) item {
                            LiveBusCard(ride, tracking.liveBus, now,
                                onSelect = { vm.selectLiveVehicle(ride.departure.key, it) },
                                onClear = { vm.clearLiveVehicle(ride.departure.key) },
                                onRefresh = { vm.refreshLiveVehicle(ride.departure.key) },
                                shape = tracking.routeShape, progress = tracking.gpsProgress,
                                // Own position is only drawn locally and only while fresh.
                                phone = tracking.location?.takeIf { tracking.locationAt?.let { at -> now.epochSecond - at.epochSecond in -10..60 } == true },
                                onReject = { vm.rejectLiveVehicle(ride.departure.key, it) })
                        }
                        if (ride.boarded) item { NextStopCard(tracking, now, settings.bool("gps_next_stop", true)) }
                        if (!ride.demo && !ride.departure.cancelled) item { ExitTargetCard(ride, alertPreferences.exitEnabled, vm::setExitTarget, vm::saveSchoolTrip) }
                        if (!ride.demo) item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { arrivalRide = ride }, enabled = settings.bool("arrival_store", true), shape = ControlShape, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ankunft merken")
                                }
                                OutlinedButton(onClick = { openReportId = vm.currentReportId() }, shape = ControlShape,
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Bericht")
                                }
                            }
                            ArrivalMark.forRide(arrivals, ride, now).lastOrNull()?.let { mark ->
                                Text("Eigene Ankunft: ${shortName(mark.stopName)} · ${clockTime(mark.at)}", color = Lime, fontSize = 12.sp)
                            }
                        }
                        if (!ride.demo && ride.departure.newOperated) item { TextButton(onClick = { showLiveMap = true }) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(8.dp)); Text("NEW-Livekarte") } }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Fahrtverlauf", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Weniger" else "Alle Halte", color = Lime) }
                            }
                        }
                        val goal = ride.exitTargetIndex() ?: ride.stops.lastIndex
                        val next = if (ride.boarded) ride.nextIndex(now).coerceAtMost(goal) else ride.boardingIndex
                        val indices = if (showAll) (ride.boardingIndex..goal).toList()
                            else (next..minOf(next + 2, goal)).toList().let { list -> if (list.lastOrNull() != goal) list + goal else list }
                        items(indices, key = { it }) { index ->
                            TimelineStop(ride.stops[index], ride, now, index == next,
                                ride.boarded && index < ride.nextIndex(now), index == indices.last(),
                                indices.indexOf(index).let { pos -> pos > 0 && index - indices[pos - 1] > 1 })
                        }
                        item {
                            Text(sourceText(ride, now), color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        item {
                            OutlinedButton(shape = ControlShape, onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text("Fahrt beenden", color = Paper)
                            }
                        }
                    } else item { EmptyState("Keine aktive Fahrt", "Wähle eine Verbindung auf der Startseite.") }
                }
                0 -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(gap)) {
                    item { Header("Mitfahrt", "", Icons.Outlined.DirectionsBus) }
                    companion?.takeIf { companionPhase != null && companionPhase != CompanionPhase.TRACKING }?.let { current -> item {
                        CompanionCard(current, companionPhase!!, tracking.ride, companionNext, now, search.loading,
                            onContinue = vm::continueJourney, onResume = vm::resumeJourney,
                            onAlternatives = { vm.journeyAlternatives()?.let { planner.planQuery(it); showPlanner = true } },
                            onEnd = vm::endJourney)
                    } }
                    item {
                        WhereToCard(plannerForm, tripPlaces.saved,
                            onOrigin = { planner.openPlaceSearch(de.luca.mitfahrt.PlaceField.ORIGIN); showPlanner = true },
                            onDestination = { planner.openPlaceSearch(de.luca.mitfahrt.PlaceField.DESTINATION); showPlanner = true },
                            onSwap = { planner.swap(); if (planner.form.value.complete) showPlanner = true },
                            onSaved = { place -> planner.setPlace(de.luca.mitfahrt.PlaceField.DESTINATION, place); showPlanner = true })
                    }
                    homeSections.forEach { card -> when (card) {
                        // Shown only when at least one saved route is active now: no empty heading or card.
                        "planned" -> {
                            item { PlannedRoutesCard(visiblePlans, routePlans.routes.size, planDepartures.departures,
                                planDepartures.loading, now, search.choosing, onManage = { newRoutePlan = false; showRoutePlans = true },
                                onStart = vm::chooseRoutePlan, onPreview = vm::previewRoutePlan, onOpen = { vm.selectRoutePlan(it); page = 1 },
                                onConnections = { planner.planRoute(it); showPlanner = true }) }
                            planDepartures.error?.let { message -> item { Text(message, color = Amber, fontSize = 13.sp) } }
                        }
                        "school" -> item { SchoolTripCard(schoolTrips, home, now, search.loading, { vm.selectSchoolTrip(it); page = 1 }, vm::removeSchoolTrip, vm::chooseSchoolDeparture) }
                        // Automatic suggestions stay separate from saved routes.
                        "suggestions" -> item {
                            HomeOverview(home, suggestedRoutes, now, search.loading, search.choosing,
                                onSearch = { page = 1 }, onLocation = onNearbyLocation, onRefresh = vm::retryHome, onStop = ::selectStop,
                                onAllStops = { vm.nearby(); page = 1 }, onRoute = { vm.selectRoute(it); page = 1 },
                                onDeparture = vm::chooseHomeDeparture, onClearHistory = ::clearRouteHistory, onRemoveRoute = ::removeRoute,
                                showNearby = false, showRoutes = true, showSearch = false)
                        }
                        "map" -> item { BusMapShortcut { showLiveMap = true } }
                        "last" -> lastRide?.let { previous -> item {
                            AppCard(onClick = { page = 2 }, shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Letzte Fahrt", style = MaterialTheme.typography.titleMedium)
                                    Text("${previous.departure.line} · ${shortName(previous.departure.destination)}")
                                    Text("${clockTime(previous.departure.planned)} · Berichte öffnen", color = Soft, fontSize = 12.sp)
                                }
                            }
                        } }
                    } }
                    if (tracking.ride == null) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showLiveMap = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Livekarte") }
                            TextButton(onClick = if (tracking.monitoring) onStop else onMonitor, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (tracking.monitoring) "Suche stoppen" else "Bus finden") }
                        }
                    }
                    if (tracking.monitoring && settings.bool("wifi_confirm", true)) item { DetectionCard(tracking, onMonitor, onStop) }
                    if (tracking.candidates.isNotEmpty()) {
                        item { Text("Dein Bus?", fontSize = 21.sp, fontWeight = FontWeight.SemiBold) }
                        items(tracking.candidates, key = { it.key }) { departure ->
                            DepartureRow(departure, now, search.choosing == departure.key,
                                enabled = !search.loading, showStop = true) { vm.chooseHomeDeparture(departure) }
                        }
                    }
                }
                1 -> StopSearch(search, tracking, arrivals, now, vm, Modifier.padding(insets), home, routes, onNearbyLocation, shownSavedStops, ::toggleFavorite, settings)
                2 -> ReportsPage(reports, journeyReports, lastRide, Modifier.padding(insets), onOpen = { openReportId = it },
                    onCreate = { openReportId = vm.archivedReportId(it) }, onShare = vm::shareReport,
                    onDeleteReport = vm::deleteReport, onDeleteRide = ::forgetLastRide, onOpenModule = { personalModule = it },
                    onSave = { id -> reports.entries.firstOrNull { it.id == id }?.let { evidence ->
                        savingReportId = id; savingJourneyReport = false
                        savePdf.launch(de.luca.mitfahrt.reports.ReportCustomization.from(settings).exportName(evidence))
                    } })
                else -> SettingsPage(Modifier.padding(insets), onDemo, updates, onCheckUpdate, onDownloadUpdate, onInstallUpdate, onIntro, hapticTest, vm::testVibration, alertPreferences, vm::setArrivalAlert, vm::setExitAlert, vm::setAlertLead,
                    routePlans, vm::setAutomaticLearning, { newRoutePlan = false; showRoutePlans = true },
                    onCustomize = { showCustomization = true }, onManageData = { showDataManagement = true }, onOpenModule = { personalModule = it })
            }
        }
        search.previewRide?.let { preview -> JourneyPreviewSheet(preview, now, vm::confirmPreview, vm::dismissPreview) }
        if (showLiveMap) BusMapOverview(onBack = { showLiveMap = false })
        if (showPlanner) {
            JourneyPlannerScreen(planner, now, locationUsable,
                positionNote = if (home.approximate) "Mein Standort (ungefähr) an VRR übermittelt" else "Mein Standort an VRR übermittelt",
                onRequestLocation = onNearbyLocation, onClose = { showPlanner = false },
                onAccompany = vm::startJourney, accompanyBusy = search.choosing != null,
                onSaveRoute = { plan -> scope.launch {
                    snackbar.showSnackbar(if (vm.saveRoutePlan(plan)) "Route „${plan.name}“ gespeichert" else "Route konnte nicht gespeichert werden.")
                } })
            // Messages (e.g. a leg that cannot be accompanied) must stay visible above the planner.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) }
        }
        if (showDataManagement) DataManagementDialog(vm, onClose = { showDataManagement = false })
        tracking.pendingArrivalStopId?.let { id ->
            val stop = tracking.ride?.stops?.firstOrNull { stopKey(it.id) == stopKey(id) }
            if (stop != null) AlertDialog(onDismissRequest = vm::dismissDetectedArrival,
                title = { Text("Ankunft festhalten?") },
                text = { Text("${shortName(stop.name)} · ${clockTime(tracking.pendingArrivalAt)}\nÜber deinen Standort erkannt.") },
                confirmButton = { TextButton(onClick = vm::confirmDetectedArrival) { Text("Bestätigen") } },
                dismissButton = { TextButton(onClick = vm::dismissDetectedArrival) { Text("Verwerfen") } })
        }
        if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("Fahrt beenden?") },
            text = { Text("Die Fahrtanzeige wird geschlossen.") },
            confirmButton = { TextButton(onClick = { confirmStop = false; onStop() }) { Text("Beenden") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Weiterfahren") } })
        journeyReports.firstOrNull { it.id == openReportId }?.let { report ->
            JourneyReportScreen(report, de.luca.mitfahrt.reports.ReportCustomization.from(settings), now, reports.busy,
                onBack = { openReportId = null }, onShare = { vm.exportJourneyReport(report.id) },
                onSave = { savingReportId = report.id; savingJourneyReport = true; savePdf.launch(vm.exportName(report.id)) },
                onObserve = { vm.addObservation(report.id, it) }, onRemoveObservation = { vm.removeObservation(report.id, it) },
                onShareExport = { vm.shareExport(report.id, it) },
                onDelete = { openReportId = null; vm.deleteJourneyReport(report.id) })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = 84.dp)) }
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
    AppCard(onClick = onClick, shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
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
internal fun Header(title: String, subtitle: String, icon: ImageVector, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The page name alone; [subtitle] is kept for callers but no longer shown as a label above it.
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Paper,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (onAction != null) IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) { Icon(icon, "Fahrt beenden", tint = Paper) }
    }
}

@Composable
private fun DetectionCard(state: TrackingState, onMonitor: () -> Unit, onStop: () -> Unit) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
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
            OutlinedButton(shape = ControlShape, onClick = if (state.monitoring) onStop else onMonitor, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (state.monitoring) "Suche stoppen" else "Erkennung starten")
            }
        }
    }
}

@Composable
internal fun RideHero(ride: Ride, now: Instant, liveAllowed: Boolean) {
    val waiting = !ride.boarded
    val boarding = ride.boardingTime()
    val liveBoarding = if (liveAllowed) ride.liveBoardingTime(now) else null
    val countdownAvailable = ride.demo || liveBoarding != null
    val boardingStop = ride.stops.getOrNull(ride.boardingIndex)
    val plannedBoarding = if (boarding.isArrival) boardingStop?.arrival ?: ride.departure.planned
        else boardingStop?.departure ?: ride.departure.planned
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = BrandBerry, contentColor = Color.White)) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(ride.departure.line, dark = true)
                Spacer(Modifier.width(10.dp))
                Text("Richtung ${shortName(ride.departure.destination)}", fontSize = 13.sp,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            if (waiting) {
                Text(if (!countdownAvailable) "Dein Bus" else if (boarding.isArrival) "Dein Bus kommt in" else "Abfahrt in", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (ride.departure.cancelled) "Fällt aus" else if (!countdownAvailable) "Keine Live-Zeit"
                    else if (!boarding.at.isAfter(now)) "Wird erwartet" else countdownUntil(boarding.at, now),
                    fontSize = if (countdownAvailable && boarding.at.isAfter(now) && !ride.departure.cancelled) 68.sp else 30.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = (-2).sp)
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
                val next = if (waiting) ride.boardingIndex else ride.nextIndex(now).coerceAtMost(ride.stops.lastIndex)
                val evidence = DelayEvidence.timingFor(ride, next) ?: DelayEvidence.timingFor(ride, ride.boardingIndex)
                evidence?.let {
                    Spacer(Modifier.height(10.dp))
                    Text("${it.shortDelay} · ${if (it.fromMonitor || waiting) "am Einstieg" else "am nächsten Halt"}",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!waiting) AppLinearProgressIndicator(progress = { ride.progressToTarget(now) }, modifier = Modifier.fillMaxWidth().height(5.dp),
                color = Color.White, trackColor = Color.White.copy(alpha = 0.16f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (waiting) "${if (boarding.isArrival) "Ankunft" else "Abfahrt"} ${clockTime(if (countdownAvailable) boarding.at else plannedBoarding)}"
                    else "Ankunft ${clockTime(ride.displayTarget().effective(ride.offsetSeconds))}", fontSize = 13.sp)
                Text(when {
                    ride.demo -> "Beispieldaten"
                    waiting -> if (liveBoarding != null) "Live" else "Fahrplan"
                    ride.displayTarget().estimated != null -> "Live"
                    ride.departure.estimated != null -> "Geschätzt"
                    else -> "Laut Fahrplan"
                }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NextStopCard(tracking: TrackingState, now: Instant, gpsEnabled: Boolean) {
    val info = NextStopResolver.resolve(tracking, now, gpsEnabled)
    val next = info?.stop
    val uncertain = info?.source == NextStopSource.UNCERTAIN
    Row(Modifier.fillMaxWidth().clip(Shape).background(Panel).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(Lime.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.NearMe, null, tint = Lime, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (next != null || uncertain) "Nächster Halt" else "Ziel", color = Soft, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text(when {
                uncertain -> "Nicht sicher erkannt"
                next != null -> shortName(next.name)
                else -> "Ziel laut Fahrtverlauf"
            }, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            info?.takeIf { it.stop != null || uncertain }?.let {
                Text(listOfNotNull(it.source.takeUnless { uncertain }?.label, it.note).joinToString(" · "),
                    color = if (uncertain) Amber else Soft, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        when {
            // A remaining time only from a fresh live forecast; GPS detection never yields one.
            info?.minutes != null -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("${info.minutes}", fontWeight = FontWeight.SemiBold, color = Lime, fontSize = 26.sp)
                Text("Min", fontSize = 12.sp, color = Soft)
            }
            info?.source == NextStopSource.SCHEDULE && next?.planned != null ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                    Text(clockTime(next.planned), fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("Plan", fontSize = 12.sp, color = Soft)
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
    home: HomeState, routes: List<FrequentRoute>, onNearbyLocation: () -> Unit, savedStops: SavedStopsState, onFavorite: (Stop) -> Unit,
    settings: AppSettings) {
    var waitingNearby by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun select(stop: Stop) { keyboard?.hide(); focus.clearFocus(); vm.selectStop(stop) }
    LaunchedEffect(home.stops, home.loading, home.status) {
        if (waitingNearby && home.status == NearbyStatus.READY && !home.loading && home.stops.isNotEmpty()) { waitingNearby = false; vm.nearby() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(search.selectedStop?.id, lifecycle) {
        if (search.selectedStop != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refreshSelectedStop()
            while (true) { delay(CustomizationPolicy.refreshMillis(vm.settingsStore.current())); vm.refreshSelectedStop() }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (search.selectedStop == null) {
            item { Header("In der Nähe", "", Icons.Outlined.NearMe) }
            item {
                OutlinedTextField(value = search.query, onValueChange = { waitingNearby = false; vm.query(it) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("Name der Haltestelle") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(); waitingNearby = false; vm.search() }),
                    trailingIcon = { if (search.query.isNotEmpty()) IconButton(onClick = { vm.query("") }) { Icon(Icons.Outlined.Close, "Suche leeren") } })
            }
            if (search.query.isBlank() && !search.searched) item {
                NearbyOverview(home, now, settings, savedStops, search.choosing, search.loading,
                    onLocation = onNearbyLocation, onRefresh = vm::retryHome,
                    onSetting = { key, value -> vm.settingsStore.set(key, value); if (key == "nearby_radius") vm.retryHome() },
                    onStop = ::select, onFavorite = onFavorite, onDeparture = { keyboard?.hide(); vm.chooseHomeDeparture(it) })
            }
            item { SavedStopsSection(savedStops, ::select, onFavorite, vm::removeRecentStop, query = search.query) }
            if (search.stops.isNotEmpty()) item { Text("Suchergebnisse", color = Soft, fontSize = 13.sp) }
            items(search.stops, key = { "result/${it.id}" }) { stop -> StopRow(stop) { select(stop) } }
            if (search.searched && search.stops.isEmpty() && !search.loading && search.error == null) item {
                EmptyState("Keine Haltestelle gefunden", "Versuche einen anderen Namen.")
            }
        } else {
            val stop = search.selectedStop
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::backToStops) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück zur Suche") }
                    Column(Modifier.weight(1f)) {
                        Text(shortName(stop.name), fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                        Text(search.plannedRoute?.let { "${it.name} → ${shortName(it.destination.name)}" }
                            ?: search.routeFilter?.let { "${it.visit.line} → ${shortName(it.visit.destination)}" } ?: "NEW · Abfahrten",
                            color = Soft, fontSize = 13.sp)
                    }
                    IconToggleButton(checked = savedStops.isFavorite(stop), onCheckedChange = { onFavorite(stop) }) {
                        Icon(if (savedStops.isFavorite(stop)) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            if (savedStops.isFavorite(stop)) "Favorit entfernen" else "Haltestelle merken", tint = Lime)
                    }
                    IconButton(onClick = vm::reloadSelectedStop, enabled = !search.loading) { Icon(Icons.Outlined.Refresh, "Abfahrten aktualisieren") }
                }
            }
            if (search.routeFilter != null || search.plannedRoute != null) item { TextButton(onClick = { vm.selectStop(stop, recordVisit = false) }) { Text("Alle Linien anzeigen") } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(JourneyOptions.walking(stop)?.label ?: "Gehzeit nicht verfügbar", color = Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val observed = search.departures.mapNotNull { it.observedAt }.maxOrNull()
                    observed?.let { Text("Stand ${clockTime(it)}", color = Soft, fontSize = 12.sp) }
                }
                if (home.approximate) Text("Ungefährer Standort · Gehzeit ist geschätzt", color = Soft, fontSize = 12.sp)
            }
            item { PersonalArrivalCard(stop, arrivals) { vm.markArrival(stop) } }
            items(search.departures, key = { it.key }) { departure ->
                DepartureRow(departure, now, search.choosing == departure.key, enabled = !search.loading) { keyboard?.hide(); vm.choose(departure) }
                WalkingHint(stop, departure, now)
            }
            if (search.departures.isEmpty() && !search.loading && search.error == null) item {
                EmptyState("Gerade keine NEW-Abfahrten", "Aktualisieren oder eine andere Haltestelle wählen.")
            }
        }
        if (search.loading) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
        search.error?.let { message -> item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp)) {
                    Text(message, color = Amber, fontSize = 14.sp)
                    TextButton(onClick = { if (search.selectedStop != null) vm.reloadSelectedStop() else vm.search() }) { Text("Erneut versuchen") }
                }
            }
        } }
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
    val settings = LocalAppSettings.current
    val live = d.liveEstimate(now)
    val rowPadding = when (settings.string("density")) { "compact" -> 12.dp; "comfortable" -> 22.dp; else -> 16.dp }
    Row(Modifier.fillMaxWidth().clip(ControlShape).background(Panel)
        .clickable(enabled = enabled && !d.cancelled, onClick = onClick).padding(horizontal = 14.dp, vertical = rowPadding - 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LineBadge(d.line)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortName(d.destination), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(if (d.cancelled) "Fällt aus" else if (showStop) shortName(d.stopName) + (distance?.let { " · $it m" } ?: "") else
                listOfNotNull(d.platform.takeIf { it.isNotBlank() }, if (d.estimated != null && d.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } != true) {
                    "Stand ${clockTime(d.observedAt)}"
                } else if (d.estimated != null) {
                    if (settings.string("delay_display") == "times") "Plan ${clockTime(d.planned)} → ${clockTime(live)}"
                    else if (d.delayMinutes > 0) "+${d.delayMinutes} Min" else "Live"
                } else "Fahrplan", if (settings.string("data_detail") == "full") "VRR · Stand ${clockTime(d.observedAt)}" else null).joinToString(" · "),
                color = if (d.cancelled || live != null && d.delayMinutes > 0) Amber else Soft, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (loading) AppCircularProgressIndicator(Modifier.size(22.dp), color = Lime, strokeWidth = 2.dp)
        else Column(horizontalAlignment = Alignment.End) {
            Text(if (d.cancelled) "–" else if (live == null) clockTime(d.planned)
                else if (settings.string("time_display") == "clock") clockTime(live)
                else if (!live.isAfter(now)) "Wird erwartet" else if (settings.string("time_display") == "minutes") "${minutesUntil(live, now)} Min" else countdownUntil(live, now),
                color = if (d.cancelled) Soft else Paper, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text(if (live == null) "Fahrplan" else when (settings.string("time_display")) {
                "clock" -> "live"; else -> "live · ${clockTime(live)}"
            }, color = Soft, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LineBadge(line: String, dark: Boolean = false) {
    LineSign(line, onBrand = dark, large = true)
}

@Composable
private fun SettingsPage(modifier: Modifier, onDemo: () -> Unit, updates: UpdateState,
    onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: (File) -> Unit, onIntro: () -> Unit, hapticTest: de.luca.mitfahrt.alerts.HapticTestState, onHapticTest: () -> Unit,
    alertPreferences: AlertPreferences, onArrival: (Boolean) -> Unit, onExit: (Boolean) -> Unit, onLead: (Int) -> Unit,
    routePlans: RoutePlansState, onAutomaticLearning: (Boolean) -> Unit, onManageRoutes: () -> Unit,
    onCustomize: () -> Unit, onManageData: () -> Unit, onOpenModule: (String) -> Unit) {
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
        item { Header("Einstellungen", "", Icons.Outlined.Tune) }
        item { Button(shape = ControlShape, onClick = onCustomize, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Outlined.Tune, null); Spacer(Modifier.width(10.dp)); Text("Alle Einstellungen & Profile")
        } }
        item { OutlinedButton(shape = ControlShape, onClick = onManageData, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Storage, null); Spacer(Modifier.width(10.dp)); Text("Gespeicherte Daten verwalten")
        } }
        item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Deine Strecken", style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Strecken automatisch merken", fontSize = 15.sp)
                            Text(if (routePlans.automaticLearning) "Häufige Strecken zusätzlich vorschlagen" else "Nur selbst gewählte Routen",
                                color = Soft, fontSize = 12.sp)
                        }
                        Switch(checked = routePlans.automaticLearning, onCheckedChange = onAutomaticLearning)
                    }
                    OutlinedButton(shape = ControlShape, onClick = onManageRoutes, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text("Routen und Zeiten einrichten")
                    }
                }
            }
        }
        item { PersonalModuleLinks(onOpenModule) }
        item { AppearanceCard() }
        item { AlertPreferencesCard(alertPreferences, onArrival, onExit, onLead) }
        item { TextButton(onClick = onIntro) { Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Einführung ansehen") } }
        item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
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
                    updates.changes?.let { Text(it, fontSize = 13.sp, color = Soft, maxLines = 12, overflow = TextOverflow.Ellipsis) }
                    if (updates.phase == UpdatePhase.DOWNLOADING) AppLinearProgressIndicator(progress = { updates.progress }, modifier = Modifier.fillMaxWidth())
                    when (updates.phase) {
                        UpdatePhase.CHECKING -> AppCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        UpdatePhase.DOWNLOADING -> Unit
                        UpdatePhase.AVAILABLE -> Button(shape = ControlShape, onClick = onDownloadUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Herunterladen") }
                        UpdatePhase.READY -> Button(shape = ControlShape, onClick = { updates.file?.let(onInstallUpdate) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Installieren") }
                        else -> OutlinedButton(shape = ControlShape, onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Nach Updates suchen") }
                    }
                    if (updates.phase == UpdatePhase.ERROR && updates.info != null)
                        TextButton(onClick = onDownloadUpdate) { Text("Download erneut versuchen") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateInfo.REPOSITORY))) }) { Text("Projekt auf GitHub") }
                }
            }
        }
        item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
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
                    OutlinedButton(shape = ControlShape, onClick = { RideNotifications.openSettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Benachrichtigungen öffnen") }
                }
            }
        }
        item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
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
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Watch, null, tint = Lime)
                    Text("Auf deiner Uhr", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Text("Linie, nächster Halt und Restzeit auf Wear OS. Die Fahrt wählst du hier am Handy.", color = Soft, fontSize = 14.sp, lineHeight = 21.sp)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${UpdateInfo.REPOSITORY}/blob/main/WATCH.md")))
                    }) { Text("Watch-App & Installation") }
                    Text("Für die Kachel auf der Uhr: Kachel hinzufügen → Fahrtstatus.", color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                    OutlinedButton(shape = ControlShape, onClick = onHapticTest, enabled = !hapticTest.running, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hapticTest.running) "Uhr wird geprüft …" else "Vibration auf Handy & Uhr testen")
                    }
                    hapticTest.message?.let { Text(it, color = Soft, fontSize = 13.sp) }
                    Text("Nicht stören und Vibrationsstärke gelten auf beiden Geräten.", color = Soft, fontSize = 12.sp)
                }
            }
        }
        item {
            OutlinedButton(shape = ControlShape, onClick = {
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
            Text("Daten vom VRR. Standort wird für Haltestellenabfragen und – nur wenn du „Mein Standort“ wählst – für die Verbindungssuche an den VRR übermittelt, aber nicht gespeichert. Kein Konto, keine Werbung, keine Analyse-Tracker.",
                color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(8.dp))
            Text("Letzte Fahrt, selbst erfasste Ankünfte und Berichte werden auf deinem Gerät gespeichert.", color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            Text("Mitfahrt ${BuildConfig.VERSION_NAME} · Inoffizielle App", color = Soft, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReportsPage(state: ReportsState, journeys: List<de.luca.mitfahrt.reports.JourneyReport>, lastRide: Ride?, modifier: Modifier,
    onOpen: (String) -> Unit, onCreate: (Ride) -> Unit, onShare: (String) -> Unit, onDeleteReport: (String) -> Unit,
    onDeleteRide: (Ride) -> Unit, onSave: (String) -> Unit, onOpenModule: (String) -> Unit) {
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
        item { Header("Berichte", "", Icons.Outlined.Description) }
        if (journeys.isEmpty() && state.entries.isEmpty()) item {
            EmptyState("Noch keine Berichte", "Wenn du eine Verbindung begleitest, entsteht hier ihr Bericht.")
        }
        items(journeys, key = { "j" + it.id }) { report ->
            JourneyReportRow(report) { onOpen(report.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
        }
        // Offered only while the last ride has no report yet, e.g. with automatic reports switched off.
        if (lastRide != null && journeys.none { report -> report.legs.any { it.key == lastRide.departure.key } }) item {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Letzte Fahrt: Linie ${lastRide.departure.line}", fontWeight = FontWeight.SemiBold)
                    Text("${shortName(lastRide.departure.destination)} · ${reportDate(lastRide.departure.planned)}", color = Soft, fontSize = 13.sp)
                }
                TextButton(onClick = { onCreate(lastRide) }) { Text("Bericht") }
                IconButton(onClick = { onDeleteRide(lastRide) }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "Gespeicherte letzte Fahrt löschen", tint = Soft)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenModule("archive") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fahrtenarchiv") }
                TextButton(onClick = { onOpenModule("arrivals") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Eigene Ankünfte") }
            }
        }
        if (state.entries.isNotEmpty()) item {
            Text("Ältere Einzelbelege", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        }
        items(state.entries, key = { it.id }) { report ->
            AppCard(modifier = Modifier.padding(bottom = 10.dp), shape = ControlShape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${report.line} · ${shortName(report.stopName)}", fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { confirmDeleteId = report.id }, enabled = !state.busy) {
                            if (state.deletingId == report.id) AppCircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.DeleteOutline, "Bericht für Linie ${report.line} löschen", tint = Soft)
                        }
                    }
                    Text("${reportDate(report.planned)} · ${report.event}", color = Soft, fontSize = 12.sp)
                    Text(report.delayText, color = if (report.delaySeconds > 0) Amber else Paper, fontSize = 14.sp)
                    if (report.personalArrivals.isNotEmpty()) Text("Eigene Ankunft im Bericht enthalten", color = Soft, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Privater VRR-Datenbericht", color = Soft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onSave(report.id) }, enabled = !state.busy) {
                            Icon(Icons.Outlined.SaveAlt, "PDF speichern")
                        }
                        TextButton(onClick = { onShare(report.id) }, enabled = !state.busy) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Teilen")
                        }
                    }
                }
            }
        }
    }
    state.entries.firstOrNull { it.id == confirmDeleteId }?.let { report ->
        AlertDialog(onDismissRequest = { confirmDeleteId = null }, title = { Text("Bericht löschen?") },
            text = { Text("${report.line} · ${shortName(report.stopName)}\n${reportDate(report.planned)}\n\nDer PDF-Bericht wird dauerhaft gelöscht.") },
            confirmButton = { TextButton(onClick = { confirmDeleteId = null; onDeleteReport(report.id) }, enabled = !state.busy) {
                Text("Löschen", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun PersonalArrivalCard(stop: Stop, arrivals: List<ArrivalMark>, onRecord: () -> Unit) {
    val last = arrivals.filter { stopKey(it.stopId) == stopKey(stop.id) }.maxByOrNull { it.at }
    AppCard(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Meine Ankunft", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (last != null) Text("Erfasst: ${reportDate(last.at)}", color = Lime, fontSize = 13.sp)
            else Text("Uhrzeit per Tastendruck festhalten", color = Soft, fontSize = 13.sp)
            OutlinedButton(shape = ControlShape, onClick = onRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(if (last == null) Icons.Outlined.LocationOn else Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (last == null) "Bin angekommen" else "Erneut festhalten")
            }
        }
    }
}

/** Boarding is detected by the app; these controls are the manual correction in both directions. */
@Composable
internal fun BoardingControls(ride: Ride, boarding: BoardingState?, detecting: Boolean,
    onBoard: () -> Unit, onNotOnBoard: () -> Unit, onStillOnBoard: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!ride.boarded) {
            val label = if (ride.departure.newOperated) "Bin im Bus" else "Bin eingestiegen"
            if (detecting) OutlinedButton(onClick = onBoard, shape = ControlShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
            else Button(onClick = onBoard, shape = ControlShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
            if (detecting) Text(if (boarding?.phase == BoardingPhase.POSSIBLE_BOARDING) "Einstieg wird geprüft."
                else "Mitfahrt erkennt den Einstieg selbst. Du kannst ihn auch hier bestätigen.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        } else if (boarding?.phase == BoardingPhase.ENDED) {
            Text("Ausgestiegen?", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text("Dein Handy bewegt sich nicht mehr mit dem Bus.", color = Soft, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onStillOnBoard, shape = ControlShape, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Noch im Bus") }
                Button(onClick = onEnd, shape = ControlShape, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Fahrt beenden") }
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull("Im Bus", ride.boardedAt?.let { "seit ${clockTime(it)}" },
                "erkannt".takeIf { ride.boardedBy == "auto" }).joinToString(" · "), color = Soft, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onNotOnBoard) { Text("Doch nicht im Bus") }
        }
    }
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
    AppCard(shape = Shape) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Soft, fontSize = 13.sp)
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
internal fun EmptyState(title: String, text: String) {
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
    ride.departure.estimated != null -> "Ankunft anhand der letzten Verspätung geschätzt · Stand ${clockTime(ride.departure.observedAt)}"
    else -> "Zeiten laut Fahrplan · Stand ${clockTime(ride.fetchedAt)}"
}

/** Compact access to the active ride from every tab, without covering the journey search. */
@Composable
private fun ActiveRideBar(ride: Ride, tracking: TrackingState, now: Instant, companion: JourneyCompanion?, onOpen: () -> Unit) {
    val live = if (tracking.running && !tracking.lastRefreshFailed) ride.liveBoardingTime(now) else null
    val status = when {
        ride.departure.cancelled -> "Fällt aus"
        !ride.boarded -> live?.at?.takeIf { it.isAfter(now) }?.let { "${if (live.isArrival) "kommt" else "fährt"} in ${countdownUntil(it, now)}" }
            ?: "Fahrplan ${clockTime(ride.stops.getOrNull(ride.boardingIndex)?.planned ?: ride.departure.planned)}"
        else -> "bis ${shortName(ride.displayTarget().name)}" +
            (ride.displayTarget().estimated?.takeIf { !ride.isStale(now) }?.let { " · ${minutesUntil(it, now)} Min" } ?: "")
    }
    val prefix = companion?.let { "Abschnitt ${it.position}/${it.count} · " }.orEmpty()
    Surface(onClick = onOpen, color = BrandBerry, contentColor = Color.White,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Aktive Fahrt öffnen: Linie ${ride.departure.line}, $status" }) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(ride.departure.line, dark = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$prefix${shortName(ride.departure.destination)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
