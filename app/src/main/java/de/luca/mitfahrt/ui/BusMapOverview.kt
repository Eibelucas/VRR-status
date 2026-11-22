package de.luca.mitfahrt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import de.luca.mitfahrt.data.LiveFleet
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.FleetAge
import de.luca.mitfahrt.data.VehicleObservation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.NewVehicle
import de.luca.mitfahrt.data.Stop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** Public NEW positions, without assigning any vehicle to a user's departure. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusMapOverview(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MitfahrtApplication
    val settings by rememberAppSettingsStore().state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var vehicles by remember { mutableStateOf<List<NewVehicle>>(emptyList()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var line by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    var official by remember { mutableStateOf(false) }
    LaunchedEffect(lifecycle, request) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { while (true) { now = Instant.now(); delay(1000) } }
            while (true) {
                loading = true
                try {
                    vehicles = app.newVehicles.fetch()
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { error = "NEW-Positionen gerade nicht erreichbar." }
                finally { loading = false }
                delay(when (settings.string("refresh_mode", "balanced")) {
                    "frequent" -> 15_000L
                    "saving" -> 60_000L
                    else -> 30_000L
                })
            }
        }
    }
    val blocked = settings.string("line_block", "").split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val allowed = settings.string("line_allow", "").split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    var direction by rememberSaveable { mutableStateOf<String?>(null) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var history by remember { mutableStateOf<Map<String, List<VehicleObservation>>>(emptyMap()) }
    var fetchedAt by remember { mutableStateOf<Instant?>(null) }
    LaunchedEffect(vehicles) { history = LiveFleet.remember(history, vehicles, Instant.now()); if (vehicles.isNotEmpty()) fetchedAt = Instant.now() }
    val fresh = vehicles.filter { LiveFleet.age(it, now) != FleetAge.GONE }
    val lineChoice = line.takeIf { it.isNotBlank() }
    val current = LiveFleet.filter(vehicles, now, lineChoice, direction, blocked, allowed)
    val selected = current.firstOrNull { it.id == selectedId } ?: current.firstOrNull()
    val headings = LiveFleet.headings(history, current)
    val online = rememberOnline()
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text("Buskarte", style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull("NEW · ${current.size} Busse", fetchedAt?.let { "Abruf vor ${(now.epochSecond - it.epochSecond).coerceAtLeast(0)} s" },
                            "offline".takeIf { !online }).joinToString(" · "), fontSize = 13.sp, color = if (online) Soft else Amber)
                    }
                    IconButton(onClick = { request++ }, enabled = !loading, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Refresh, "Positionen aktualisieren") }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        // Quick filters from the lines that are actually on the road right now.
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = lineChoice == null, onClick = { line = ""; direction = null }, label = { Text("Alle") }, modifier = Modifier.heightIn(min = 48.dp))
                            LiveFleet.lines(fresh).forEach { value ->
                                FilterChip(selected = lineChoice == value, onClick = { line = if (lineChoice == value) "" else value; direction = null },
                                    label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        if (lineChoice != null) {
                            val directions = LiveFleet.directions(fresh, lineChoice)
                            if (directions.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                directions.forEach { value ->
                                    FilterChip(selected = direction == value, onClick = { direction = if (direction == value) null else value },
                                        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !follow, onClick = { follow = false }, label = { Text("Übersicht") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = follow, onClick = { follow = true }, enabled = selected != null,
                                label = { Text(selected?.let { "Bus ${it.id} folgen" } ?: "Bus folgen") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if (loading && vehicles.isEmpty()) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (error != null) item { Text(error!!, color = Amber, style = MaterialTheme.typography.bodySmall) }
                    if (selected != null) item {
                        var mapFullscreen by rememberSaveable { mutableStateOf(false) }
                        val routeStops = selected.routeStops.takeIf {
                            follow && selected.routeObservedAt?.let { now.epochSecond - it.epochSecond in -15..90 } == true
                        }.orEmpty().mapIndexed { index, stop -> Stop("new-path-$index", stop.name.ifBlank { "Halt" }, stop.point) }
                        WaitingBusMap(selected.point, null, "Linie ${selected.line} · Fahrzeug ${selected.id}", selected.observedAt, now,
                            otherVehicles = current, selectedVehicleId = selected.id, overview = !follow,
                            // NEW publishes only stop points for the path; they are shown as stops, not joined into a line.
                            routeStops = routeStops,
                            onSelectVehicle = { selectedId = it.id }, onFullscreen = { mapFullscreen = true },
                            heading = headings[selected.id], headings = headings)
                        if (mapFullscreen) Dialog(
                            onDismissRequest = { mapFullscreen = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
                        ) {
                            Surface(Modifier.fillMaxSize(), color = Ink) {
                                Box(Modifier.fillMaxSize()) {
                                    WaitingBusMap(selected.point, null, "Linie ${selected.line} · Fahrzeug ${selected.id}", selected.observedAt, now,
                                        otherVehicles = current, selectedVehicleId = selected.id, overview = !follow,
                                        routeStops = routeStops, onSelectVehicle = { selectedId = it.id },
                                        edgeToEdge = true, recenterTopPadding = 84.dp, attributionBottomPadding = 8.dp,
                                        heading = headings[selected.id], headings = headings)
                                    Surface(
                                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                                        shape = Shape, color = Panel,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Soft.copy(alpha = 0.35f))
                                    ) {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { mapFullscreen = false }, modifier = Modifier.size(48.dp)) {
                                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Vollbild schließen", tint = Paper)
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text("Linie ${selected.line} · Fahrzeug ${selected.id}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(if (current.size == 1) "1 Bus laut NEW" else "${current.size} Busse laut NEW",
                                                    color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = { request++ }, enabled = !loading, modifier = Modifier.size(48.dp)) {
                                                Icon(Icons.Outlined.Refresh, "Positionen aktualisieren", tint = Paper)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Text("Busse auf der Straße", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("Keine Zuordnung zu einer Abfahrt. Blasse Busse melden seit über ${LiveFleet.OLDER_SECONDS} s nichts Neues.",
                            color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    if (current.isEmpty() && !loading) item {
                        Text(if (lineChoice == null) "Keine aktuellen Fahrzeugpositionen verfügbar." else "Für diese Auswahl gibt es gerade keine aktuellen Positionen.",
                            color = Soft, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(current, key = { it.id }) { vehicle ->
                        val older = LiveFleet.age(vehicle, now) == FleetAge.OLDER
                        Surface(onClick = { selectedId = vehicle.id }, shape = ControlShape,
                            color = if (selected?.id == vehicle.id) MaterialTheme.colorScheme.secondaryContainer else Panel) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineSign(vehicle.line)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (vehicle.destination.isBlank()) "Richtung nicht gemeldet" else if (vehicle.directionReliable) shortName(vehicle.destination)
                                        else "${shortName(vehicle.destination)} (Anzeige, unsicher)", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Fahrzeug ${vehicle.id} · vor ${(now.epochSecond - vehicle.observedAt.epochSecond).coerceAtLeast(0)} s" +
                                        if (older) " · älter" else "", color = if (older) Amber else Soft, fontSize = 13.sp)
                                }
                                if (selected?.id == vehicle.id) Icon(Icons.Outlined.Check, "Ausgewählt", tint = Lime)
                            }
                        }
                    }
                    item {
                        TextButton(onClick = { official = true }, modifier = Modifier.fillMaxWidth()) { Text("Karte von NEW öffnen") }
                    }
                }
            }
        }
    }
    if (official) NewLiveMapScreen(onClose = { official = false })
}

@Composable
fun BusMapShortcut(onOpen: () -> Unit) {
    OutlinedButton(shape = ControlShape, onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Outlined.Map, null)
        Spacer(Modifier.width(12.dp))
        Text("Busse auf der Karte")
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null)
    }
}
