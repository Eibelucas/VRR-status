package de.luca.mitfahrt.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun WatchScreen(store: WatchStore, onRefresh: () -> Unit, onCommand: (WatchCommand) -> Unit = {}) {
    val ride by store.ride.collectAsStateWithLifecycle()
    val syncMessage by store.syncMessage.collectAsStateWithLifecycle()
    val refreshing by store.refreshing.collectAsStateWithLifecycle()
    val library by store.library.collectAsStateWithLifecycle()
    val commandBusy by store.commandBusy.collectAsStateWithLifecycle()
    val commandFeedback by store.commandFeedback.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf("active") }
    var confirmation by remember { mutableStateOf<WatchDeparture?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmationContext by remember { mutableStateOf("") }
    var confirmationJourney by remember { mutableStateOf("") }
    val journey = library.settings["journey_key"].orEmpty()
    LaunchedEffect(library.settings["watch_start"]) { view = library.settings["watch_start"] ?: "active" }
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
    MaterialTheme(colorScheme = ColorScheme(primary = Lime, onPrimary = Ink, primaryContainer = Panel,
        onPrimaryContainer = Color.White, background = Color.Black, onBackground = Color.White,
        surfaceContainer = Panel, onSurface = Color.White, onSurfaceVariant = Soft)) {
        AppScaffold {
            ScreenScaffold(scrollState = state) { padding ->
                TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The optional library snapshot and its command key arrive atomically.
                    val current = if (library.sentAt != Instant.EPOCH) library.ride else ride
                    // With a running ride the glance starts with the ride itself; switching views comes after it.
                    val rideFirst = view == "active" && current?.active == true
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
                            val old = now.epochSecond - library.sentAt.epochSecond !in -30..120
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
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Keine Fahrt", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Lime)
                                Spacer(Modifier.height(12.dp))
                                Text("Am Handy eine Fahrt wählen.", textAlign = TextAlign.Center, color = Soft, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        if (current.demo) item { StatusText("Demofahrt", Lime) }
                        if (current.stale(now)) item { StatusText(if (!current.running) "Fahrt pausiert" else "Daten veraltet", Amber) }
                        item { RideHero(current, now, library.settings["watch_detail"] != "detail") }
                        if (current.boarded) item {
                            WatchPanel {
                                Text("Nächster Halt", color = Soft, fontSize = 12.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(current.nextStop, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (!current.boarded && "board" in allowedActions) item {
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
                        item { FilledTonalButton(onClick = { onCommand(WatchCommand("start", departure.id, confirmationJourney, confirmationContext)); confirmation = null; view = "active" },
                            enabled = !commandBusy, modifier = Modifier.fillMaxWidth()) { Text("Fahrt verfolgen") } }
                        item { FilledTonalButton(onClick = { confirmation = null }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") } }
                    }
                    if (commandBusy) item { StatusText("Wird am Handy ausgeführt …", Soft) }
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
private fun RideHero(ride: WatchRide, now: Instant, large: Boolean = true) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFB40055)).padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${ride.line} → ${ride.destination}", color = Color.White, style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val display = rideTimeDisplay(ride, now)
        Text(display.value, color = Color.White, fontSize = if (!large || display.value.length > 7) 26.sp else 36.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(display.detail, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
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
