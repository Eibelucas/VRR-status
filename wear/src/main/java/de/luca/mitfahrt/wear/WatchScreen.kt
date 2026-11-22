package de.luca.mitfahrt.wear

import androidx.compose.runtime.rememberCoroutineScope

import kotlinx.coroutines.launch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import de.luca.mitfahrt.shared.WatchFaceMapSnapshot
import de.luca.mitfahrt.shared.WatchRide
import de.luca.mitfahrt.shared.WatchCommand
import de.luca.mitfahrt.shared.WatchDeparture
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Lime = Color(0xFFFF9BC7)
private val Ink = Color(0xFF21132A)
private val Panel = Color(0xFF281E48)
private val Soft = Color(0xFFCAC0D0)
private val Amber = Color(0xFFFFCB83)

@Composable
fun WatchScreen(
    store: WatchStore,
    onRefresh: () -> Unit,
    onCommand: (WatchCommand) -> Unit = {},
    requestedView: String? = null,
    requestedViewToken: Int = 0
) {
    val ride by store.ride.collectAsStateWithLifecycle()
    val watchFaceMap by store.watchFaceMap.collectAsStateWithLifecycle()
    val syncMessage by store.syncMessage.collectAsStateWithLifecycle()
    val refreshing by store.refreshing.collectAsStateWithLifecycle()
    val library by store.library.collectAsStateWithLifecycle()
    val commandBusy by store.commandBusy.collectAsStateWithLifecycle()
    val commandFeedback by store.commandFeedback.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf(requestedView ?: WatchActivity.VIEW_ACTIVE) }
    var confirmation by remember { mutableStateOf<WatchDeparture?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmationContext by remember { mutableStateOf("") }
    var confirmationJourney by remember { mutableStateOf("") }
    val journey = library.settings["journey_key"].orEmpty()
    // Journey key at the moment a start was sent; the screen changes only once the phone reports the new ride.
    var pendingStart by remember { mutableStateOf<String?>(null) }
    var direct by remember { mutableStateOf<DirectDeparture?>(null) }
    var directBusy by remember { mutableStateOf(false) }
    var directMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(requestedViewToken) {
        if (requestedViewToken > 0 && !requestedView.isNullOrBlank()) {
            view = requestedView
        }
    }
    LaunchedEffect(journey, commandFeedback, commandBusy) {
        val sent = pendingStart ?: return@LaunchedEffect
        when (WatchStartFlow.resolve(sent, journey, commandFeedback != null && !commandBusy)) {
            WatchStartFlow.Result.DONE -> { pendingStart = null; view = "active" }
            WatchStartFlow.Result.FAILED -> pendingStart = null
            WatchStartFlow.Result.WAITING -> Unit
        }
    }
    LaunchedEffect(journey) { direct = null; directMessage = null }
    LaunchedEffect(library.settings["watch_start"]) {
        if (requestedViewToken == 0) view = library.settings["watch_start"] ?: "active"
    }
    LaunchedEffect(journey, library.selected) { confirmation = null; confirmStop = false }
    val allowedActions = library.settings["watch_actions"].orEmpty().ifEmpty { "board,arrival,stop" }.split(',').toSet()

    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1000) }
        }
    }
    val state = rememberTransformingLazyColumnState()
    // The optional library snapshot and its command key arrive atomically.
    val activeRide = if (library.sentAt != Instant.EPOCH) library.ride else ride
    MaterialTheme(colorScheme = ColorScheme(primary = Lime, onPrimary = Ink, primaryContainer = Panel,
        onPrimaryContainer = Color.White, background = Color.Black, onBackground = Color.White,
        surfaceContainer = Panel, onSurface = Color.White, onSurfaceVariant = Soft)) {
        if (view == WatchActivity.VIEW_MAP) {
            val mapMode = library.settings["watch_map_mode"] ?: "focus"
            WatchLiveMapView(
                map = watchFaceMap,
                ride = activeRide,
                now = now,
                mapMode = mapMode,
                refreshing = refreshing,
                commandBusy = commandBusy,
                canBoard = activeRide?.active == true && activeRide.boarded == false && "board" in allowedActions,
                onBoard = { onCommand(WatchCommand("board", journey = journey)) },
                onToggleMapMode = { onCommand(WatchCommand("map_mode", journey = journey)) },
                onRefresh = onRefresh,
                onBackToRide = { view = WatchActivity.VIEW_ACTIVE }
            )
            return@MaterialTheme
        }
        AppScaffold {
            ScreenScaffold(scrollState = state) { padding ->
                TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = activeRide
                    val leaveLabel = library.settings["leave_label"].orEmpty()
                    val hubPlatform = library.settings["hub_platform"].orEmpty()
                    val missedPrompt = library.settings["missed_prompt"] == "true"
                    val handoffLine = library.settings["handoff_line"].orEmpty()
                    val handoffDest = library.settings["handoff_dest"].orEmpty()
                    val handoffStop = library.settings["handoff_stop"].orEmpty()
                    val handoffPlatform = library.settings["handoff_platform"].orEmpty()
                    val altBusLine = library.settings["alt_bus_line"].orEmpty()
                    val altBusDest = library.settings["alt_bus_dest"].orEmpty()
                    val altBusTarget = library.settings["alt_bus_target"].orEmpty()
                    val quickRouteId = library.settings["quick_route_id"].orEmpty()
                    val quickRouteName = library.settings["quick_route_name"].orEmpty()

                    // With a running ride or transfer handoff the glance starts with the ride itself; switching views comes after it.
                    val rideFirst = view == "active" && (current?.active == true || handoffLine.isNotBlank())
                    val navigation: androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope.() -> Unit = {
                        if (view != "active") item { FilledTonalButton(onClick = { view = "active" }, modifier = Modifier.fillMaxWidth()) { Text("Zur Fahrt") } }
                        if (view != "routes") item { FilledTonalButton(onClick = { view = "routes" }, modifier = Modifier.fillMaxWidth()) { Text("Routen") } }
                        if (view != "favorites") item { FilledTonalButton(onClick = { view = "favorites" }, modifier = Modifier.fillMaxWidth()) { Text("Haltestellen") } }
                    }
                    if (!rideFirst) navigation()
                    if (view == "targets") {
                        item { StatusText("Ausstiegsziel", Lime) }
                        library.targets.forEach { target -> item {
                            FilledTonalButton(onClick = { onCommand(WatchCommand("target", target.index.toString(), journey,
                                library.settings["stops_revision"].orEmpty())); view = "active" },
                                enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) { Text(target.name, maxLines = 2) }
                        } }
                    } else if (view != "active") {
                        val kind = if (view == "routes") "route" else "stop"
                        val entries = library.entries.filter { it.kind == kind }
                        if (entries.isEmpty()) item { StatusText(if (kind == "route") "Am Handy eigene Routen anlegen." else "Am Handy Haltestellen mit Stern merken.", Soft) }
                        entries.forEach { entry -> item {
                            FilledTonalButton(onClick = { confirmation = null; onCommand(WatchCommand("browse", "${entry.kind}/${entry.id}")) },
                                enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Text(entry.title, maxLines = 2)
                                    Text(entry.detail, fontSize = 12.sp, color = Soft, maxLines = 2)
                                }
                            }
                        } }
                        if (library.selected.startsWith("$kind/")) {
                            item { StatusText("Abfahrten", Lime) }
                            val old = now.epochSecond - library.sentAt.epochSecond !in -30..180
                            if (old) item { StatusText("Stand veraltet · aktualisieren", Amber) }
                            library.departures.forEach { departure -> item {
                                FilledTonalButton(onClick = { confirmation = departure; confirmationContext = library.selected; confirmationJourney = journey }, enabled = !commandBusy && !old && !departure.cancelled,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column {
                                        Text("${departure.line} → ${departure.destination}", maxLines = 2)
                                        Text(listOfNotNull(departure.at?.atZone(ZoneId.of("Europe/Berlin"))?.format(DateTimeFormatter.ofPattern("HH:mm")),
                                            if (departure.live && !old) "Live" else "Fahrplan").joinToString(" · "), fontSize = 12.sp, color = Soft)
                                    }
                                }
                            } }
                            if (library.departures.isEmpty()) item { StatusText(library.message.ifEmpty { "Keine Abfahrten" }, Soft) }
                        }
                    } else if (current?.active != true) {
                        if (handoffLine.isNotBlank()) {
                            item {
                                WatchPanel {
                                    Text("Umstieg bereit", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("$handoffLine → $handoffDest", style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                    val detail = listOfNotNull(handoffStop.takeIf { it.isNotBlank() }?.let { "ab $it" }, handoffPlatform.takeIf { it.isNotBlank() }).joinToString(" · ")
                                    if (detail.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(detail, color = Soft, fontSize = 12.sp, maxLines = 2)
                                    }
                                }
                            }
                            item {
                                Button(
                                    onClick = { onCommand(WatchCommand("next_leg", journey = journey)) },
                                    enabled = !commandBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Weiter mit $handoffLine", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        } else if (quickRouteId.isNotBlank() && quickRouteName.isNotBlank()) {
                            val quickDeps = if (library.selected == "route/$quickRouteId") library.departures.filter { !it.cancelled }.take(2) else emptyList()
                            val quickFresh = now.epochSecond - library.sentAt.epochSecond in -30..180
                            item {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(quickRouteName, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Lime, maxLines = 2)
                                    if (quickDeps.isEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text("Schnellstart", textAlign = TextAlign.Center, color = Soft, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (quickDeps.isNotEmpty()) {
                                quickDeps.forEachIndexed { idx, dep ->
                                    item {
                                        val timeLabel = dep.at?.atZone(ZoneId.of("Europe/Berlin"))?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
                                        val onStart = {
                                            store.commandFeedback.value = null
                                            pendingStart = journey
                                            onCommand(WatchCommand("start", dep.id, journey, "route/$quickRouteId"))
                                        }
                                        if (idx == 0) {
                                            Button(onClick = onStart, enabled = !commandBusy && pendingStart == null && quickFresh, modifier = Modifier.fillMaxWidth()) {
                                                Text("${dep.line} um $timeLabel starten", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        } else {
                                            FilledTonalButton(onClick = onStart, enabled = !commandBusy && pendingStart == null && quickFresh, modifier = Modifier.fillMaxWidth()) {
                                                Text("${dep.line} · $timeLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            } else {
                                item {
                                    Button(
                                        onClick = { onCommand(WatchCommand("browse", "route/$quickRouteId")); view = "routes" },
                                        enabled = !commandBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Abfahrten laden") }
                                }
                            }
                        } else {
                            item {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Keine Fahrt", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Lime)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Am Handy eine Fahrt wählen.", textAlign = TextAlign.Center, color = Soft, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        if (current.demo) item { StatusText("Demofahrt", Lime) }
                        val dataState = WatchFreshness.state(current, now)
                        if (dataState != WatchDataState.LIVE) item {
                            StatusText(if (!current.running && dataState == WatchDataState.OFFLINE) "Fahrt pausiert · Stand ${WatchFreshness.clock(current.fetchedAt ?: current.sentAt)}"
                                else WatchFreshness.label(current, now), Amber)
                        }
                        if (dataState != WatchDataState.EXPIRED) item {
                            RideHero(
                                ride = current,
                                now = now,
                                large = library.settings["watch_detail"] != "detail",
                                leaveLabel = leaveLabel.takeIf { !current.boarded },
                                hubPlatform = hubPlatform.takeIf { !current.boarded }
                            )
                        }
                        // Alternate bus suggestion right on the wrist.
                        if (altBusLine.isNotBlank()) {
                            item {
                                WatchPanel {
                                    Text("Im Bus $altBusLine?", color = Amber, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    if (altBusDest.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text("Richtung $altBusDest", color = Color.White, fontSize = 12.sp, maxLines = 2)
                                    }
                                    if (altBusTarget.isNotBlank()) {
                                        Text("Ziel $altBusTarget bleibt", color = Soft, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                            item {
                                Button(onClick = { onCommand(WatchCommand("switch_alternate", journey = journey)) }, enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Zu $altBusLine wechseln")
                                }
                            }
                            item {
                                FilledTonalButton(onClick = { onCommand(WatchCommand("dismiss_alternate", journey = journey)) }, enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Nein, aktueller Bus")
                                }
                            }
                        }
                        // Phase-aware primary one-button action directly below the hero.
                        if (!current.boarded && missedPrompt && !current.demo) {
                            item {
                                Button(onClick = { onCommand(WatchCommand("missed_passed", journey = journey)) }, enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Durchgefahren · Nächster", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            item {
                                FilledTonalButton(onClick = { onCommand(WatchCommand("missed_full", journey = journey)) }, enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("Zu voll · Nächster", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else if (!current.boarded && "board" in allowedActions) {
                            item {
                                Button(onClick = { onCommand(WatchCommand("board", journey = journey)) }, enabled = !commandBusy,
                                    modifier = Modifier.fillMaxWidth()) { Text("Bin im Bus") }
                            }
                        } else if (current.boarded && current.nextStop.isBlank() && "target" in allowedActions) {
                            item {
                                Button(onClick = { view = "targets" }, modifier = Modifier.fillMaxWidth()) { Text("Ziel wählen") }
                            }
                        }
                        item {
                            FilledTonalButton(
                                onClick = { view = WatchActivity.VIEW_MAP },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Vollbild-Karte") }
                        }
                        // Without the phone, the watch may ask VRR itself, but only on request and only for this exact departure.
                        val identity = DepartureIdentity.from(library.settings)
                        if (dataState != WatchDataState.LIVE && !current.boarded && identity != null) {
                            direct?.takeIf { now.epochSecond - it.fetchedAt.epochSecond <= 120 }?.let { result -> item {
                                StatusText(listOf(if (result.cancelled) "Fällt aus laut VRR" else
                                    "${if (result.live) "VRR live" else "Fahrplan"} ${WatchFreshness.clock(result.at)}",
                                    "direkt abgerufen ${WatchFreshness.clock(result.fetchedAt)}").joinToString(" · "), if (result.cancelled) Amber else Lime)
                            } }
                            directMessage?.let { item { StatusText(it, Soft) } }
                            item {
                                FilledTonalButton(onClick = {
                                    directBusy = true; directMessage = null
                                    scope.launch {
                                        val result = runCatching { DirectVrr.fetch(identity) }
                                        direct = result.getOrNull()
                                        directMessage = when {
                                            result.isFailure -> "Uhr hat gerade kein Internet"
                                            direct == null -> "VRR meldet diese Fahrt nicht eindeutig"
                                            else -> null
                                        }
                                        directBusy = false
                                    }
                                }, enabled = !directBusy, modifier = Modifier.fillMaxWidth()) { Text(if (directBusy) "Frage VRR …" else "Beim VRR prüfen") }
                            }
                        }
                        if (current.boarded) item {
                            WatchPanel {
                                Text("Nächster Halt", color = Soft, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(current.nextStop, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (!current.boarded && missedPrompt && "board" in allowedActions) item {
                            FilledTonalButton(onClick = { onCommand(WatchCommand("board", journey = journey)) }, enabled = !commandBusy,
                                modifier = Modifier.fillMaxWidth()) { Text("Bin im Bus") }
                        }
                        if ("arrival" in allowedActions) item {
                            FilledTonalButton(onClick = { onCommand(WatchCommand("arrival", journey = journey)) }, enabled = !commandBusy,
                                modifier = Modifier.fillMaxWidth()) { Text(if (current.boarded) "Ankunft am Ziel merken" else "Ankunft am Einstieg merken") }
                        }
                        if ("target" in allowedActions) item { FilledTonalButton(onClick = { view = "targets" }, modifier = Modifier.fillMaxWidth()) { Text("Ausstiegsziel") } }
                        if ("stop" in allowedActions) item {
                            FilledTonalButton(onClick = { confirmStop = !confirmStop }, modifier = Modifier.fillMaxWidth()) { Text("Fahrt beenden") }
                        }
                        if (confirmStop) item {
                            FilledTonalButton(onClick = { onCommand(WatchCommand("stop", journey = journey)); confirmStop = false },
                                enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) { Text("Beenden bestätigen") }
                        }
                        item {
                            val fetched = current.fetchedAt?.atZone(ZoneId.of("Europe/Berlin"))?.format(DateTimeFormatter.ofPattern("HH:mm"))
                            StatusText(listOfNotNull(current.source.takeIf { it.isNotBlank() }, fetched?.let { "Stand $it" },
                                current.delay.takeIf { it.isNotBlank() }).joinToString(" · "), Soft)
                        }
                    }
                    confirmation?.let { departure ->
                        item { StatusText(if (current?.active == true) "Aktuelle Fahrt ersetzen?" else "${departure.line} verfolgen?", Lime) }
                        item { FilledTonalButton(onClick = {
                                store.commandFeedback.value = null
                                pendingStart = confirmationJourney
                                onCommand(WatchCommand("start", departure.id, confirmationJourney, confirmationContext)); confirmation = null },
                            enabled = !commandBusy && pendingStart == null, modifier = Modifier.fillMaxWidth()) { Text(if (current?.active == true) "Wechseln" else "Fahrt verfolgen") } }
                        item { FilledTonalButton(onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") } }
                    }
                    if (pendingStart != null) item { StatusText("Wird am Handy gestartet …", Soft) }
                    else if (commandBusy) item { StatusText("Wird am Handy ausgeführt …", Soft) }
                    commandFeedback?.let { message -> item { StatusText(message, Amber) } }
                    if (library.settings["watch_show_connection"] != "false") syncMessage?.let { message -> item { StatusText(message, Amber) } }
                    if (rideFirst) navigation()
                    item {
                        FilledTonalButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (refreshing) "Verbinden …" else "Aktualisieren")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchLiveMapView(
    map: WatchFaceMapSnapshot?,
    ride: WatchRide?,
    now: Instant,
    mapMode: String,
    refreshing: Boolean,
    commandBusy: Boolean,
    canBoard: Boolean = false,
    onBoard: () -> Unit = {},
    onToggleMapMode: () -> Unit,
    onRefresh: () -> Unit,
    onBackToRide: () -> Unit
) {
    val active = (map?.active == true) || (ride?.active == true)
    val copy = WatchFaceTransitFormat.format(map, ride, now)
    val bitmap = remember(map?.sentAt, map?.mapPng?.size, ride?.sentAt, ride?.nextStop, ride?.boarded) {
        WatchFaceFallbackMapRenderer.rideImage(map, ride)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showHud by remember { mutableStateOf(true) }
    val isFocusMode = mapMode != "overview"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showHud = !showHud },
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.2f
                        }
                    }
                )
            }
            .pointerInput(scale) {
                if (scale > 1.05f) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val maxShift = (size.width * (scale - 1f) / 2f).coerceAtLeast(0f)
                        offsetX = (offsetX + dragAmount.x).coerceIn(-maxShift, maxShift)
                        offsetY = (offsetY + dragAmount.y).coerceIn(-maxShift, maxShift)
                    }
                }
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Live-Buskarte im Vollbild",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Keine aktive Fahrt", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Am Handy eine Fahrt starten, um die Vollbild-Karte zu sehen.", color = Soft, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        // Minimal edge-to-edge HUD; single tap hides/shows overlays so the street map fills 100% of the dial.
        if (showHud && active) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 14.dp, start = 28.dp, end = 28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC14171D))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(copy.primaryText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(copy.secondaryTitle, color = Color(0xFFD0D5DF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (showHud) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (canBoard) {
                    CompactButton(onClick = onBoard, enabled = !commandBusy) {
                        Text("Bin im Bus", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactButton(onClick = onBackToRide) {
                        Text("Fahrt", fontSize = 11.sp)
                    }
                    if (active) {
                        CompactButton(onClick = onToggleMapMode, enabled = !commandBusy) {
                            Text(if (isFocusMode) "Strecke" else "Bus", fontSize = 11.sp)
                        }
                    }
                    CompactButton(onClick = onRefresh, enabled = !refreshing) {
                        Text(if (refreshing) "…" else "Neu", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RideHero(
    ride: WatchRide,
    now: Instant,
    large: Boolean = true,
    leaveLabel: String? = null,
    hubPlatform: String? = null
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFB40055)).padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val header = listOfNotNull(
            hubPlatform?.takeIf { it.isNotBlank() },
            "${ride.line} → ${ride.destination}"
        ).joinToString(" · ")
        Text(header, color = Color.White, style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        val display = rideTimeDisplay(ride, now)
        val primaryValue = leaveLabel?.takeIf { it.isNotBlank() } ?: display.value
        val secondaryDetail = if (!leaveLabel.isNullOrBlank()) {
            "Abfahrt ${display.value} · ${display.detail}"
        } else {
            display.detail
        }
        Text(primaryValue, color = Color.White, fontSize = if (!large || primaryValue.length > 7) 24.sp else 36.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(secondaryDetail, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WatchPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(16.dp), content = content)
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
}
