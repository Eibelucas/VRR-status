package de.luca.mitfahrt.ui

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
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.NewVehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** Public NEW positions, without assigning any vehicle to a user's departure. */
@Composable
fun BusMapOverview(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MitfahrtApplication
    val settings by remember(context) { AppSettingsStore(context) }.state.collectAsState()
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
    val current = vehicles.filter { it.isFresh(now) && (line.isBlank() || it.line.contains(line.trim(), ignoreCase = true)) &&
        it.line.lowercase() !in blocked && (allowed.isEmpty() || it.line.lowercase() in allowed) }
        .sortedWith(compareBy<NewVehicle> { it.line.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.line }.thenBy { it.id })
    val selected = current.firstOrNull { it.id == selectedId } ?: current.firstOrNull()
    Dialog(onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text("Buskarte", style = MaterialTheme.typography.titleLarge)
                        Text("NEW · ${current.size} aktuelle Fahrzeuge", style = MaterialTheme.typography.labelSmall, color = Soft)
                    }
                    IconButton(onClick = { request++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Positionen aktualisieren") }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedTextField(value = line, onValueChange = { line = it.take(30) }, label = { Text("Linie filtern") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            trailingIcon = { if (line.isNotEmpty()) IconButton(onClick = { line = "" }) { Icon(Icons.Outlined.Close, "Filter leeren") } })
                    }
                    if (loading) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (error != null) item { Text(error!!, color = Amber, style = MaterialTheme.typography.bodySmall) }
                    if (selected != null) item {
                        WaitingBusMap(selected.point, null, "Linie ${selected.line} · Fahrzeug ${selected.id}", selected.observedAt, now,
                            otherVehicles = current, selectedVehicleId = selected.id, overview = true,
                            routePoints = selected.routeStops.takeIf {
                                selected.routeObservedAt?.let { now.epochSecond - it.epochSecond in -15..90 } == true
                            }.orEmpty().map { it.point },
                            onSelectVehicle = { selectedId = it.id })
                    }
                    item {
                        Text("Gemeldete Linienfahrzeuge", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text("Die Karte ordnet diese Fahrzeuge keiner bestimmten Abfahrt zu.", color = Soft, fontSize = 12.sp)
                    }
                    if (current.isEmpty() && !loading) item {
                        Text(if (line.isBlank()) "Keine aktuellen Fahrzeugpositionen verfügbar." else "Für diese Linie gibt es gerade keine aktuellen Positionen.",
                            color = Soft, modifier = Modifier.padding(vertical = 16.dp))
                    }
                    items(current, key = { it.id }) { vehicle ->
                        Surface(onClick = { selectedId = vehicle.id }, shape = Shape,
                            color = if (selected?.id == vehicle.id) MaterialTheme.colorScheme.secondaryContainer else Panel) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.DirectionsBus, null, tint = Lime)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Linie ${vehicle.line} · ${vehicle.id}", fontWeight = FontWeight.SemiBold)
                                    if (vehicle.destination.isNotBlank()) Text(vehicle.destination, color = Soft, fontSize = 12.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("Stand vor ${(now.epochSecond - vehicle.observedAt.epochSecond).coerceAtLeast(0)} Sek.", color = Soft, fontSize = 11.sp)
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
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Icon(Icons.Outlined.Map, null)
        Spacer(Modifier.width(12.dp))
        Text("Busse auf der Karte")
        Spacer(Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null)
    }
}
