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
import androidx.compose.foundation.selection.toggleable
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
    val dynamicSuggestions by vm.dynamicRouteSuggestions.collectAsStateWithLifecycle()
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
    var editLayouts by rememberSaveable { mutableStateOf(false) }
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
    var departureBoard by rememberSaveable { mutableStateOf<String?>(null) }
    var editHomeLayout by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(enabled = page != 0 && !showLiveMap && !showRoutePlans && !showCustomization && personalModule == null && !showPlanner && departureBoard == null) {
        keyboard?.hide(); focus.clearFocus()
        if (page == 1 && search.selectedStop != null) vm.backToStops() else page = 0
    }
    LaunchedEffect(navigation) {
        when (navigation) {
            "settings" -> page = 3; "map" -> showLiveMap = true; "stops" -> {
                departureBoard = null
                showPlanner = false
                showCustomization = false
                showRoutePlans = false
                showLiveMap = false
                personalModule = null
                page = 1
            }
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
        if (editHomeLayout) HomeLayoutDialog(cardOrder, { selected ->
            vm.settingsStore.set("home_cards", selected.joinToString(","))
            vm.settingsStore.set("map_home", ("map" in selected).toString())
            editHomeLayout = false
        }) { editHomeLayout = false }
        if (personalModule == "templates") TemplateEditorScreen(settings, { key, value -> vm.settingsStore.set(key, value) }) { personalModule = null }
        else if (personalModule == "network") NetworkPlanScreen(onBack = { personalModule = null }, onStop = { stop -> personalModule = null; selectStop(stop) })
        else if (personalModule?.startsWith("platforms:") == true) StationPlatformsScreen(personalModule!!.removePrefix("platforms:")) { personalModule = null }
        else if (personalModule != null) PersonalModuleScreen(personalModule!!, vm, now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate(),
            onReport = { openReportId = vm.archivedReportId(it) }) { personalModule = null }
        else if (showCustomization) CustomizationScreen(vm.settingsStore, onBack = { showCustomization = false },
            onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } }, onOpenModule = { personalModule = it })
        else if (showRoutePlans) RoutePlansScreen(routePlans, shownSavedStops, nearbyForPlans,
            onBack = { showRoutePlans = false }, onAutomaticLearning = vm::setAutomaticLearning,
            onSave = vm::saveRoutePlan, onRemove = ::removeRoutePlan, searchStops = vm::findRouteStops,
            snackbarHostState = snackbar, startNew = newRoutePlan,
            resolveStop = vm::resolveRouteStop, onMove = vm::moveRoutePlan, onOpen = { plan ->
                showRoutePlans = false; vm.selectRoutePlan(plan); page = 1
            })
        else if (departureBoard == "live" && search.selectedStop != null) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(search.selectedStop.id, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        delay(CustomizationPolicy.refreshMillis(vm.settingsStore.current()))
                        vm.refreshSelectedStop()
                    }
                }
            }
            FullDepartureBoard(search, now, { departureBoard = null }, vm::reloadSelectedStop) {
                departureBoard = null
                vm.choose(it)
            }
        } else if (departureBoard == "offline" && search.selectedStop != null) {
            OfflineDepartureBoard(search.selectedStop, now, vm.offlineBoards, vm::fetchOfflineBoard) { departureBoard = null }
        }
        else Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    tracking.ride?.takeIf { page != RIDE_PAGE }?.let { ride ->
                        ActiveRideBar(ride, tracking, now, companion?.takeIf { companionPhase == CompanionPhase.TRACKING }) { page = RIDE_PAGE }
                    }
                // Three places for three questions: what now, what leaves here, what happened. Settings sit behind the gear.
                HorizontalDivider(color = Hairline)
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    val tabs = mapOf("home" to Triple(0, "Start", Icons.Outlined.Home), "nearby" to Triple(1, "Abfahrten", Icons.Outlined.DepartureBoard),
                        "reports" to Triple(2, "Fahrten", Icons.Outlined.History))
                    (settings.csv("nav_order") + tabs.keys).distinct().mapNotNull(tabs::get).forEach { (index, title, icon) ->
                        NavigationBarItem(selected = page == index, onClick = {
                            if (index == 1 && page == 1 && search.selectedStop != null) vm.backToStops()
                            page = index
                        },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(title) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedTextColor = Paper, unselectedTextColor = Soft, unselectedIconColor = Soft))
                    }
                }
                }
            }
        ) { insets ->
            CompositionLocalProvider(LocalOpenSettings provides if (page == 3) null else ({ page = 3 })) {
            when (page) {
                RIDE_PAGE -> LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                    item {
                        if (tracking.ride != null) Header(if (tracking.ride.boarded) "Unterwegs" else "Deine Fahrt", "", Icons.Outlined.Close, onAction = { confirmStop = true })
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
                        tracking.alternateBus?.takeIf { it.journeyKey == ride.departure.key }?.let { alt ->
                            item {
                                AlternateBusCard(
                                    suggestion = alt,
                                    onSwitch = { de.luca.mitfahrt.tracking.RideService.correct(context, de.luca.mitfahrt.tracking.RideService.SWITCH_ALTERNATE) },
                                    onDismiss = { de.luca.mitfahrt.tracking.RideService.correct(context, de.luca.mitfahrt.tracking.RideService.DISMISS_ALTERNATE) }
                                )
                            }
                        }
                        item { RideHero(ride, now, tracking.running && !tracking.lastRefreshFailed,
                            TravelEstimator.estimate(tracking, now, settings.bool("gps_next_stop", true)).takeIf { settings.bool("gps_travel_time", true) }) }
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
                        if (tracking.powerSaving) item {
                            Text(if (ride.isStale(now) || tracking.lastRefreshFailed) "Akku sparen · Daten länger nicht aktualisiert"
                                else "Akku sparen · seltener aktualisiert, kurz vor Ein- und Ausstieg wieder normal",
                                color = if (ride.isStale(now) || tracking.lastRefreshFailed) Amber else Soft, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        if (ride.boarded && settings.string("announce_stops", "off") != "off") item {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (tracking.announcementsPaused) "Ansagen pausiert" else "Nächster Halt wird angesagt",
                                    color = Soft, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = vm::toggleAnnouncements) { Text(if (tracking.announcementsPaused) "Fortsetzen" else "Pausieren") }
                            }
                        }
                        if (ride.boarded) item { NextStopCard(tracking, now, settings.bool("gps_next_stop", true), settings.bool("gps_travel_time", true)) }
                        if (!ride.demo && !ride.departure.cancelled) item { ExitTargetCard(ride, alertPreferences.exitEnabled, vm::setExitTarget, vm::saveSchoolTrip) }
                        if (!ride.demo) item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { arrivalRide = ride }, enabled = settings.bool("arrival_store", true), shape = ControlShape, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ankunft merken")
                                }
                                OutlinedButton(onClick = { openReportId = vm.currentReportId() }, shape = ControlShape,
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fahrtbericht")
                                }
                            }
                            ArrivalMark.forRide(arrivals, ride, now).lastOrNull()?.let { mark ->
                                Text("Eigene Ankunft: ${shortName(mark.stopName)} · ${clockTime(mark.at)}", color = Lime, fontSize = 12.sp)
                            }
                        }
                        if (!ride.demo && ride.departure.newOperated) item { TextButton(onClick = { showLiveMap = true }) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(8.dp)); Text("Livekarte") } }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Halte", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
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
                    item {
                        // The start page answers "what is next today", so it is named after the day, not the app.
                        val today = now.atZone(ZoneId.of("Europe/Berlin"))
                        Header("Heute", today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", java.util.Locale.GERMAN)),
                            Icons.Outlined.DirectionsBus, extra = {
                                IconButton(onClick = { editHomeLayout = true }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Outlined.Tune, "Startseite anpassen", tint = Paper)
                                }
                            })
                    }
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
                                onConnections = { planner.planRoute(it); showPlanner = true },
                                suggestions = dynamicSuggestions,
                                onApplyTemporary = vm::applyTemporaryDestination,
                                onClearTemporary = vm::clearTemporaryDestination,
                                onDismissSuggestion = vm::dismissDestinationSuggestion) }
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
                                    Text("${clockTime(previous.departure.planned)} · Fahrten öffnen", color = Soft, fontSize = 12.sp)
                                }
                            }
                        } }
                    } }
                    if (tracking.ride == null) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showLiveMap = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Icon(Icons.Outlined.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Livekarte") }
                            TextButton(onClick = { personalModule = "network" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Liniennetz") }
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
                1 -> StopSearch(search, tracking, arrivals, now, vm, Modifier.padding(insets), home, routes, onNearbyLocation, shownSavedStops, ::toggleFavorite, settings,
                    onOpenBoard = { departureBoard = it }, onOpenModule = { personalModule = it })
                2 -> ReportsPage(reports, journeyReports, lastRide, Modifier.padding(insets), onOpen = { openReportId = it },
                    onCreate = { openReportId = vm.archivedReportId(it) }, onShare = vm::shareReport,
                    onDeleteReport = vm::deleteReport, onDeleteRide = ::forgetLastRide, onOpenModule = { personalModule = it },
                    onSave = { id -> reports.entries.firstOrNull { it.id == id }?.let { evidence ->
                        savingReportId = id; savingJourneyReport = false
                        savePdf.launch(de.luca.mitfahrt.reports.ReportCustomization.from(settings).exportName(evidence))
                    } })
                else -> SettingsPage(Modifier.padding(insets), onDemo, updates, onCheckUpdate, onDownloadUpdate, onInstallUpdate, onIntro, hapticTest, vm::testVibration, alertPreferences, vm::setArrivalAlert, vm::setExitAlert, vm::setAlertLead,
                    routePlans, vm::setAutomaticLearning, { newRoutePlan = false; showRoutePlans = true },
                    onCustomize = { showCustomization = true }, onManageData = { showDataManagement = true }, onOpenModule = { personalModule = it },
                    onBack = { page = 0 })
            }
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
            val layoutState by vm.reportLayouts.collectAsStateWithLifecycle()
            val layout = layoutState.first.firstOrNull { it.id == layoutState.second }
            JourneyReportScreen(report, de.luca.mitfahrt.reports.ReportLayouts.apply(de.luca.mitfahrt.reports.ReportCustomization.from(settings), layout), now, reports.busy,
                onBack = { openReportId = null }, onShare = { vm.exportJourneyReport(report.id) },
                onSave = { savingReportId = report.id; savingJourneyReport = true; savePdf.launch(vm.exportName(report.id)) },
                onObserve = { vm.addObservation(report.id, it) }, onRemoveObservation = { vm.removeObservation(report.id, it) },
                onShareExport = { vm.shareExport(report.id, it) },
                onDelete = { openReportId = null; vm.deleteJourneyReport(report.id) },
                layouts = layoutState.first, selectedLayout = layoutState.second, onSelectLayout = vm::selectReportLayout,
                onEditLayouts = { editLayouts = true })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.navigationBarsPadding().padding(bottom = 84.dp)) }
        }
        if (editLayouts) {
            val layoutState by vm.reportLayouts.collectAsStateWithLifecycle()
            ReportLayoutsScreen(layoutState.first, layoutState.second, vm::newReportLayout, vm::saveReportLayout, vm::removeReportLayout,
                vm::selectReportLayout) { editLayouts = false }
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
internal fun Header(title: String, subtitle: String, icon: ImageVector, actionLabel: String = "Fahrt beenden",
    extra: (@Composable RowScope.() -> Unit)? = null, onAction: (() -> Unit)? = null) {
    val openSettings = LocalOpenSettings.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Paper, modifier = Modifier.semantics { heading() })
            if (subtitle.isNotBlank()) Text(subtitle, color = Soft, fontSize = 14.sp)
        }
        extra?.invoke(this)
        if (onAction != null) IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) { Icon(icon, actionLabel, tint = Paper) }
        // Settings left the tab bar; every page header carries the way there instead.
        else if (openSettings != null) IconButton(onClick = openSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Settings, "Einstellungen", tint = Paper)
        }
    }
}

/** Opens the settings page; null on the settings page itself. */
internal val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

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
internal fun RideHero(ride: Ride, now: Instant, liveAllowed: Boolean, estimate: TravelEstimate? = null) {
    val positionEstimate = TravelEstimator.fallback(ride, now, liveAllowed, estimate)
    val dataFresh = liveAllowed && now.epochSecond - ride.fetchedAt.epochSecond in -30..120
    val remaining = if (dataFresh || ride.demo) ride.minutesToTarget(now) else ride.displayTarget().planned?.let { minutesUntil(it, now) }
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
                // Without a live time, the chosen bus' position gives a calculated time to the stop.
                val calculated = positionEstimate?.takeIf { !countdownAvailable && !ride.departure.cancelled }
                // Whole minutes like the display at the stop; seconds suggested a precision the data does not have.
                val minutes = if (countdownAvailable && boarding.at.isAfter(now)) minutesLabel(boarding.at, now) else null
                val soon = minutes == "jetzt"
                Text(if (calculated != null) "Dein Bus kommt in etwa" else if (!countdownAvailable) "Dein Bus"
                    else if (soon) (if (boarding.isArrival) "Dein Bus kommt" else "Abfahrt")
                    else if (boarding.isArrival) "Dein Bus kommt in" else "Abfahrt in", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (ride.departure.cancelled) "Fällt aus" else if (calculated != null) "${calculated.minutes} Min" else if (!countdownAvailable) "Keine Live-Zeit"
                    else minutes ?: "Wird erwartet",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = if (calculated != null || minutes != null && !ride.departure.cancelled) 68.sp else 30.sp,
                        lineHeight = if (calculated != null || minutes != null && !ride.departure.cancelled) 72.sp else 36.sp))
                Spacer(Modifier.height(12.dp))
                Text(shortName(ride.departure.stopName), fontSize = 19.sp, fontWeight = FontWeight.Medium)
                if (calculated != null) Text(calculated.label.replaceFirstChar { it.uppercase() } + ", keine VRR-Angabe", fontSize = 13.sp)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text((positionEstimate?.minutes ?: remaining)?.toString() ?: "–", fontSize = 88.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = (-6).sp, lineHeight = 88.sp)
                    Text(" Min", fontSize = 23.sp, modifier = Modifier.padding(bottom = 10.dp), fontWeight = FontWeight.Medium)
                }
                Text("bis ${shortName(ride.displayTarget().name)}", fontSize = 19.sp, fontWeight = FontWeight.Medium)
                if (positionEstimate != null) Text(positionEstimate.label.replaceFirstChar { it.uppercase() } + ", keine VRR-Angabe", fontSize = 13.sp)
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
                Text(if (waiting && positionEstimate != null) "Ankunft etwa ${clockTime(now.plusSeconds(positionEstimate.seconds))}"
                    else if (waiting) "${if (boarding.isArrival) "Ankunft" else "Abfahrt"} ${clockTime(if (countdownAvailable) boarding.at else plannedBoarding)}"
                    else if (positionEstimate != null) "Ankunft etwa ${clockTime(now.plusSeconds(positionEstimate.seconds))}"
                    else "Ankunft ${clockTime(if (dataFresh || ride.demo) ride.displayTarget().effective(ride.offsetSeconds) else ride.displayTarget().planned)}", fontSize = 13.sp)
                Text(when {
                    ride.demo -> "Beispieldaten"
                    positionEstimate != null && (!waiting || liveBoarding == null) -> "Berechnet"
                    waiting -> if (liveBoarding != null) "Live" else "Fahrplan"
                    dataFresh && ride.displayTarget().estimated != null -> "Live"
                    dataFresh && ride.departure.estimated != null -> "Geschätzt"
                    else -> "Laut Fahrplan"
                }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NextStopCard(tracking: TrackingState, now: Instant, gpsEnabled: Boolean, travelTime: Boolean = true) {
    val info = NextStopResolver.resolve(tracking, now, gpsEnabled)
    val calculated = if (travelTime) TravelEstimator.estimate(tracking, now, gpsEnabled)?.takeIf { it.nextStopIndex == info?.index }?.nextStopMinutes else null
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
            // A live forecast comes first; otherwise the app's own calculation, named as such.
            info?.minutes != null -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("${info.minutes}", fontWeight = FontWeight.SemiBold, color = Lime, fontSize = 26.sp)
                Text("Min", fontSize = 12.sp, color = Soft)
            }
            calculated != null && next != null && info?.source != NextStopSource.SCHEDULE ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                    Text("ca. $calculated", fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
                    Text("Min, berechnet", fontSize = 12.sp, color = Soft)
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
            // Stop dots carry meaning, so they use the 3:1 outline; the connecting line is decoration.
            Box(Modifier.padding(top = 6.dp).size(if (next) 12.dp else 8.dp).clip(CircleShape)
                .background(if (next) Lime else MaterialTheme.colorScheme.outline))
            if (!last) Box(Modifier.width(2.dp).height(32.dp).background(Hairline))
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
    settings: AppSettings, onOpenBoard: (String) -> Unit, onOpenModule: (String) -> Unit) {
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
            item { Header("Abfahrten", "", Icons.Outlined.NearMe) }
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onOpenBoard("live") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Vollbild")
                    }
                    if (savedStops.isFavorite(stop)) TextButton(onClick = { onOpenBoard("offline") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Offline-Fahrplan") }
                }
            }
            if (search.routeFilter != null || search.plannedRoute != null) item { TextButton(onClick = { vm.selectStop(stop, recordVisit = false) }) { Text("Alle Linien anzeigen") } }
            if (PlatformStations.isKnown(stop.id)) item {
                OutlinedButton(shape = ControlShape, onClick = { onOpenModule("platforms:${stopKey(stop.id)}") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Steige am ${PlatformStations.known[stopKey(stop.id)]}")
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(JourneyOptions.walking(stop, TripSettings.walkingSpeed(settings))?.label ?: "Gehzeit nicht verfügbar", color = Soft, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val observed = search.departures.mapNotNull { it.observedAt }.maxOrNull()
                    observed?.let { Text("Stand ${clockTime(it)}", color = Soft, fontSize = 12.sp) }
                }
                if (home.approximate) Text("Ungefährer Standort · Gehzeit ist geschätzt", color = Soft, fontSize = 12.sp)
            }
            item { PersonalArrivalCard(stop, arrivals) { vm.markArrival(stop) } }
            if (search.departures.isNotEmpty()) item(key = "board/${stop.id}") {
                DepartureBoard(search.departures) { departure -> key(departure.key) {
                    DepartureRow(departure, now, search.choosing == departure.key, enabled = !search.loading, onBoard = true) {
                        keyboard?.hide(); vm.choose(departure)
                    }
                    Box(Modifier.padding(horizontal = 16.dp)) { WalkingHint(stop, departure, now) }
                } }
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
internal fun DepartureRow(d: Departure, now: Instant, loading: Boolean, enabled: Boolean, showStop: Boolean = false, distance: Int? = null,
    onBoard: Boolean = false, onClick: () -> Unit) {
    val settings = LocalAppSettings.current
    val live = d.liveEstimate(now)
    val stale = d.estimated != null && live == null
    val rowPadding = when (settings.string("density")) { "compact" -> 10.dp; "comfortable" -> 18.dp; else -> 14.dp }
    val surface = if (onBoard) Modifier else Modifier.clip(ControlShape).background(Panel)
    Row(Modifier.fillMaxWidth().then(surface)
        .clickable(enabled = enabled && !d.cancelled, onClickLabel = "Mitfahren", onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = rowPadding), verticalAlignment = Alignment.CenterVertically) {
        LineBadge(d.line, cancelled = d.cancelled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortName(d.destination), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (d.cancelled) Soft else Paper)
            val detail = if (d.cancelled) "Fällt aus" else listOfNotNull(
                if (showStop) shortName(d.stopName) + (distance?.let { " · $it m" } ?: "") else null,
                d.platform.takeIf { it.isNotBlank() }?.let { "Steig $it" },
                when {
                    live == null -> null
                    settings.string("delay_display") == "times" && d.delayMinutes != 0 -> "Plan ${clockTime(d.planned)}"
                    d.delayMinutes > 0 -> "+${d.delayMinutes} Min"
                    else -> null
                },
                if (settings.string("data_detail") == "full") "VRR · Stand ${clockTime(d.observedAt)}" else null).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, color = if (d.cancelled || live != null && d.delayMinutes > 0) Amber else Soft, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        if (loading) AppCircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        // Time column of the board: whole minutes only with fresh live data, otherwise the clock time and its source.
        else Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 64.dp)) {
            Text(when {
                d.cancelled -> clockTime(d.planned)
                live == null -> clockTime(d.planned)
                settings.string("time_display") == "clock" -> clockTime(live)
                else -> minutesLabel(live, now)
            }, style = BoardTime, color = if (d.cancelled) Soft else Paper,
                textDecoration = if (d.cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
            if (!d.cancelled) when {
                live != null -> SourceMark(TimeSource.LIVE)
                stale -> Text("Stand ${clockTime(d.observedAt)}", color = Soft, fontSize = 12.sp, lineHeight = 16.sp)
                else -> SourceMark(TimeSource.PLAN)
            }
        }
    }
}

/**
 * Tafel: departures of one stop on one surface, separated by hairlines, read like the display at the stop.
 */
@Composable
internal fun <T> DepartureBoard(rows: List<T>, header: (@Composable () -> Unit)? = null, row: @Composable (T) -> Unit) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        header?.invoke()
        rows.forEachIndexed { index, item ->
            if (index > 0 || header != null) HorizontalDivider(color = Hairline, modifier = Modifier.padding(start = if (index > 0) 16.dp else 0.dp))
            row(item)
        }
    }
}

@Composable
private fun LineBadge(line: String, dark: Boolean = false, cancelled: Boolean = false) {
    LineSign(line, onBrand = dark, large = true, cancelled = cancelled)
}

@Composable
internal fun SettingsPage(modifier: Modifier, onDemo: () -> Unit, updates: UpdateState,
    onCheckUpdate: () -> Unit, onDownloadUpdate: () -> Unit, onInstallUpdate: (File) -> Unit, onIntro: () -> Unit, hapticTest: de.luca.mitfahrt.alerts.HapticTestState, onHapticTest: () -> Unit,
    alertPreferences: AlertPreferences, onArrival: (Boolean) -> Unit, onExit: (Boolean) -> Unit, onLead: (Int) -> Unit,
    routePlans: RoutePlansState, onAutomaticLearning: (Boolean) -> Unit, onManageRoutes: () -> Unit,
    onCustomize: () -> Unit, onManageData: () -> Unit, onOpenModule: (String) -> Unit,
    onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
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
    val quickStore = rememberAppSettingsStore()
    val quick by quickStore.state.collectAsStateWithLifecycle()
    val activeRoutes = routePlans.routes.count { it.enabled }
    val updateBadge = when (updates.phase) {
        UpdatePhase.AVAILABLE, UpdatePhase.READY -> "Update"
        UpdatePhase.DOWNLOADING -> "${(updates.progress * 100).toInt()} %"
        UpdatePhase.CURRENT -> "Aktuell"
        else -> "v${BuildConfig.VERSION_NAME}"
    }
    val updateHighlight = updates.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = Paper)
                    }
                }
                Text("Einstellungen", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                Surface(shape = RoundedCornerShape(10.dp), color = Well) {
                    Text("v${BuildConfig.VERSION_NAME}", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        item { AppearanceCard() }
        item {
            SettingsGroup("Automatik während der Fahrt") {
                SettingsSwitch(
                    icon = Icons.Outlined.DirectionsBus,
                    title = "Einstieg und Ausstieg erkennen",
                    hint = "Wechselt selbst von Warten zu Im Bus. Korrigieren geht immer.",
                    checked = quick.bool("boarding_auto", true)
                ) { quickStore.set("boarding_auto", it.toString()) }
                SettingsDivider()
                SettingsSwitch(
                    icon = Icons.Outlined.MyLocation,
                    title = "Bus automatisch zuordnen",
                    hint = "Ordnet deinen Bus über NEW-Haltestelle, Live-Zeit und optional GPS zu.",
                    checked = quick.bool("vehicle_auto", true)
                ) { quickStore.set("vehicle_auto", it.toString()) }
                SettingsDivider()
                SettingsSwitch(
                    icon = Icons.Outlined.Description,
                    title = "Fahrtbericht vorbereiten",
                    hint = "Zu jeder begleiteten Verbindung. Geteilt wird nur auf deinen Wunsch.",
                    checked = quick.bool("report_auto", true)
                ) { quickStore.set("report_auto", it.toString()) }
            }
        }
        item { AlertPreferencesCard(alertPreferences, onArrival, onExit, onLead, hapticTest, onHapticTest) }
        item {
            SettingsGroup("Routen & Schulalltag") {
                SettingsLink(
                    icon = Icons.Outlined.AltRoute,
                    title = "Routen und Zeiten",
                    hint = "Namen, Richtung, Wochentage und Zeitfenster",
                    badge = if (activeRoutes > 0) "$activeRoutes aktiv" else null,
                    onClick = onManageRoutes
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.CalendarMonth,
                    title = "Schulkalender NRW",
                    hint = "Ferien, Feiertage und freie Tage",
                    onClick = { onOpenModule("calendar") }
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.EventBusy,
                    title = "Routenausnahmen",
                    hint = "Einzelne Tage später starten oder ausblenden",
                    onClick = { onOpenModule("exceptions") }
                )
                SettingsDivider()
                SettingsSwitch(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Häufige Strecken vorschlagen",
                    hint = "Schlägt oft genutzte Verbindungen lokal vor",
                    checked = routePlans.automaticLearning,
                    onChange = onAutomaticLearning
                )
            }
        }
        item {
            SettingsGroup("Geräte & Integrationen") {
                SettingsExpander(
                    icon = Icons.Outlined.Smartphone,
                    title = "Sperrbildschirm und Now Bar",
                    badge = if (Build.VERSION.SDK_INT >= 36) "Android 16+" else null
                ) {
                    Text(if (Build.VERSION.SDK_INT >= 36) "Bus auswählen, Live-Benachrichtigungen erlauben und die App verlassen. Die Now Bar prüfst du anschließend auf dem Sperrbildschirm."
                    else "Deine Fahrt erscheint als laufende Benachrichtigung. Für die Now Bar ist eine unterstützte Samsung-Version mit Android 16 nötig.", color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
                    Surface(shape = ControlShape, color = Well, modifier = Modifier.fillMaxWidth()) {
                        Text(nowBarStatus, color = Paper, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Buskarte auf Sperrbildschirm & Now Bar", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Live-Karte in der erweiterten Now Bar und als große Karte auf dem Sperrbildschirm", color = Soft, fontSize = 12.sp)
                        }
                        Switch(
                            checked = quick.bool("notification_map", true),
                            onCheckedChange = { enabled ->
                                quickStore.set("notification_map", enabled.toString())
                                refreshNowBar()
                            }
                        )
                    }
                    if (Build.MANUFACTURER.equals("samsung", true) && Build.VERSION.SDK_INT >= 36) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Samsung-Kompatibilität", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                                Text("Eigenen Titel verwenden", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("Bei Darstellungsproblemen ausschalten", color = Soft, fontSize = 12.sp)
                            }
                            Switch(checked = ownTitle, onCheckedChange = { enabled ->
                                ownTitle = enabled
                                NowBarSupport.setOwnTitleEnabled(context, enabled)
                                refreshNowBar()
                            })
                        }
                    }
                    OutlinedButton(shape = ControlShape, onClick = { RideNotifications.openSettings(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("Benachrichtigungen öffnen") }
                }
                SettingsDivider()
                SettingsExpander(
                    icon = Icons.Outlined.Watch,
                    title = "Wear OS Uhr",
                    badge = "Kachel & Zifferblatt"
                ) {
                    Text("Linie, nächster Halt und Restzeit auf Wear OS. Die Fahrt wählst du am Handy.", color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
                    Text("Für die Kachel auf der Uhr: Kachel hinzufügen → Fahrtstatus.", color = Soft, fontSize = 13.sp, lineHeight = 19.sp)
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${UpdateInfo.REPOSITORY}/blob/main/WATCH.md")))
                    }) { Text("Watch-App & Installation") }
                    WatchUpdateSettings()
                    Text("Das Zifferblatt „Mitfahrt Live“ zeigt während einer Fahrt die Karte, den nächsten Halt und die Ankunft. Das untere Feld belegst du auf der Uhr selbst, etwa mit Musik: Zifferblatt lang drücken, dann „Anpassen“.", color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                }
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.EditNote,
                    title = "Eigene Texte",
                    hint = "Now Bar, Benachrichtigung und Widget selbst formulieren",
                    onClick = { onOpenModule("templates") }
                )
                SettingsDivider()
                SettingsExpander(
                    icon = Icons.Outlined.Wifi,
                    title = "Bus per WLAN finden"
                ) {
                    Text("Der NEW-Hotspot und dein Standort schlagen Fahrten vor. Welche es ist, bestätigst du selbst. Die Suche läuft nur, wenn du sie startest.", color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
                    OutlinedButton(shape = ControlShape, onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("Berechtigungen öffnen") }
                }
            }
        }
        item {
            SettingsGroup("Verlauf, Daten & Sicherung") {
                SettingsLink(
                    icon = Icons.Outlined.History,
                    title = "Fahrtenarchiv",
                    hint = "Vergangene Fahrten ansehen oder Berichte öffnen",
                    onClick = { onOpenModule("archive") }
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.Flag,
                    title = "Eigene Ankünfte",
                    hint = "Selbst festgehaltene Ankunftszeiten",
                    onClick = { onOpenModule("arrivals") }
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.Insights,
                    title = "Pünktlichkeit",
                    hint = "Auswertung deiner gespeicherten Fahrten",
                    onClick = { onOpenModule("punctuality") }
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Sicherung",
                    hint = "Routen und Einstellungen sichern oder importieren",
                    onClick = { onOpenModule("backup") }
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.Storage,
                    title = "Gespeicherte Daten",
                    hint = "Ansehen, sichern und löschen",
                    onClick = onManageData
                )
            }
        }
        item {
            SettingsGroup("App & Erweitert") {
                SettingsLink(
                    icon = Icons.Outlined.Tune,
                    title = "Alle Einstellungen und Profile",
                    hint = "Zeitpläne, Karte, Hinweise, Berichte, Datenschutz",
                    badge = "Alle",
                    onClick = onCustomize
                )
                SettingsDivider()
                SettingsExpander(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "App-Update",
                    badge = updateBadge,
                    highlightBadge = updateHighlight,
                    open = updates.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY, UpdatePhase.DOWNLOADING, UpdatePhase.ERROR)
                ) {
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
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.HelpOutline,
                    title = "Einführung ansehen",
                    hint = null,
                    onClick = onIntro
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.PlayCircleOutline,
                    title = "Demofahrt ansehen",
                    hint = "Beispieldaten, ohne echte Fahrt",
                    onClick = onDemo
                )
                SettingsDivider()
                SettingsLink(
                    icon = Icons.Outlined.Share,
                    title = "App teilen",
                    hint = null
                ) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Mitfahrt · Busstatus für Mönchengladbach\n${UpdateInfo.REPOSITORY}/releases/latest")
                    }, "App teilen"))
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Daten vom VRR, NEW und OpenStreetMap (Photon). Standort wird nur für Haltestellenabfragen und bei „Mein Standort“ verwendet, nicht gespeichert. Kein Konto, keine Werbung, keine Tracker.",
                    color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                Text("Letzte Fahrt, selbst erfasste Ankünfte und Berichte bleiben auf deinem Gerät.", color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Mitfahrt ${BuildConfig.VERSION_NAME} · Inoffizielle App", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternateBusCard(
    suggestion: AlternateBusSuggestion,
    onSwitch: () -> Unit,
    onDismiss: () -> Unit
) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineSign(suggestion.departure.line)
                Column(Modifier.weight(1f)) {
                    Text("In Linie ${suggestion.departure.line} unterwegs?", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Richtung ${shortName(suggestion.departure.destination)} · GPS-Erkennung der App",
                        color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            val targetNote = if (suggestion.preservesExitTarget && suggestion.exitTargetName != null)
                "Dein Ausstieg ${shortName(suggestion.exitTargetName)} bleibt erhalten."
            else "Anderer Streckenverlauf als deine bisherige Fahrt."
            Text(targetNote, color = if (suggestion.preservesExitTarget) Soft else Amber, fontSize = 12.sp, lineHeight = 17.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onSwitch, shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Zu Linie ${suggestion.departure.line} wechseln")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Hier bleiben")
                }
            }
        }
    }
}

/** Grouped card of settings rows with leading icons and subtle inset hairlines. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Soft,
            letterSpacing = 0.3.sp,
            modifier = Modifier.padding(horizontal = 6.dp).semantics { heading() }
        )
        AppCard(Modifier.fillMaxWidth(), shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), content = content)
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = Hairline, modifier = Modifier.padding(start = 66.dp, end = 18.dp))
}

@Composable
private fun SettingsIconBadge(icon: ImageVector, active: Boolean = false) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Well),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active) Lime else Paper, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsBadge(text: String, highlight: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) Lime else Well
    ) {
        Text(
            text,
            color = if (highlight) MaterialTheme.colorScheme.onPrimary else Soft,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SettingsSwitch(icon: ImageVector, title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIconBadge(icon, active = checked)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, hint: String?, badge: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (badge != null) SettingsBadge(badge)
        Icon(Icons.Outlined.ChevronRight, null, tint = Soft)
    }
}

/** Rare, longer content stays folded until it is needed. */
@Composable
private fun SettingsExpander(icon: ImageVector, title: String, badge: String? = null, highlightBadge: Boolean = false, open: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (expanded) "Einklappen" else "Ausklappen") { expanded = !expanded }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsIconBadge(icon, active = expanded || highlightBadge)
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (badge != null) SettingsBadge(badge, highlight = highlightBadge)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Soft)
    }
    if (expanded) Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
internal fun ReportsPage(state: ReportsState, journeys: List<de.luca.mitfahrt.reports.JourneyReport>, lastRide: Ride?, modifier: Modifier,
    onOpen: (String) -> Unit, onCreate: (Ride) -> Unit, onShare: (String) -> Unit, onDeleteReport: (String) -> Unit,
    onDeleteRide: (Ride) -> Unit, onSave: (String) -> Unit, onOpenModule: (String) -> Unit) {
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
        item { Header("Fahrten", "", Icons.Outlined.Description) }
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
                TextButton(onClick = { onCreate(lastRide) }) { Text("Fahrtbericht") }
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
        !ride.boarded -> live?.at?.takeIf { it.isAfter(now) }?.let { at ->
            val minutes = minutesLabel(at, now)
            "${if (live.isArrival) "kommt" else "fährt"} ${if (minutes == "jetzt") minutes else "in $minutes"}" }
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
