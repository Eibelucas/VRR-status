package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() })
    }
}

/** Platforms of MG Hbf or Rheydt Hbf as VRR reports them; positions only where VRR publishes coordinates. */
@Composable
internal fun StationPlatformsScreen(stopId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MitfahrtApplication
    var result by remember { mutableStateOf<StationPlatforms?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var request by remember { mutableIntStateOf(0) }
    var rail by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(stopId, request) {
        loading = true
        try { result = app.vrr.platforms(stopId); error = null
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "Steige gerade nicht abrufbar." }
        finally { loading = false }
    }
    val shown = result?.platforms.orEmpty().filter { (it.kind == PlatformKind.RAIL) == rail }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenHeader("Steige · ${PlatformStations.known[stopKey(stopId)] ?: result?.stopName.orEmpty()}", onBack)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !rail, onClick = { rail = false; selected = null }, label = { Text("Bus") }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(selected = rail, onClick = { rail = true; selected = null }, label = { Text("Bahn") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (loading) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { message -> item {
                    Text(message, color = Amber, fontSize = 14.sp)
                    TextButton(onClick = { request++ }) { Text("Erneut versuchen") }
                } }
                val pins = shown.mapNotNull { platform -> platform.point?.let {
                    MapPin(it, (if (rail) "Gleis " else "Steig ") + platform.name, if (platform.id == selected) PinKind.BOARD else PinKind.STOP, platform.id)
                } }
                if (pins.isNotEmpty()) item {
                    PlainMap(emptyList(), pins, "Karte mit ${pins.size} Steigen", height = 280.dp, onPin = { selected = it.id })
                }
                result?.let { data -> item {
                    Text(listOf("Laut VRR-Abfahrtstafel, Stand ${clockTime(data.fetchedAt)}",
                        "${data.withPosition} von ${data.platforms.size} mit Position").joinToString(" · "), color = Soft, fontSize = 13.sp)
                } }
                if (!loading && error == null && shown.isEmpty()) item {
                    Text("In den nächsten Abfahrten kommt hier kein ${if (rail) "Gleis" else "Bussteig"} vor.", color = Soft)
                }
                items(shown, key = { it.id }) { platform ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { selected = platform.id }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(platform.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 44.dp),
                            color = if (platform.id == selected) Lime else Paper)
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { platform.lines.take(5).forEach { LineSign(it) } }
                            if (platform.directions.isNotEmpty()) Text(platform.directions.joinToString(", "), color = Soft, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (platform.point == null) Text("Ohne Positionsangabe", color = Soft, fontSize = 12.sp)
                        }
                        platform.nextDeparture?.let { Text(clockTime(it), fontSize = 15.sp) }
                    }
                }
            }
        }
    }
}

/** Search NEW lines by number or stop, pick a direction, see its stops and, if verified, its course. */
@Composable
internal fun NetworkPlanScreen(onBack: () -> Unit, onStop: (Stop) -> Unit) {
    BackHandler(onBack = onBack)
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as MitfahrtApplication
    val scope = rememberCoroutineScope()
    val loader = remember { NetworkPlanLoader({ stop -> app.vrr.departures(stop, newOnly = true) }, app.vrr::ride, app.routeShapes::load) }
    var query by rememberSaveable { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<NetworkLine>>(emptyList()) }
    var stopsFound by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var line by remember { mutableStateOf<NetworkLine?>(null) }
    var lineStops by remember { mutableStateOf<List<LineStop>>(emptyList()) }
    var geometry by remember { mutableStateOf<NetworkGeometry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> Unit) = scope.launch {
        busy = true; error = null
        try { block() } catch (e: CancellationException) { throw e
        } catch (_: Exception) { error = "VRR gerade nicht erreichbar." } finally { busy = false }
    }
    fun search() = run {
        line = null; lineStops = emptyList(); geometry = null
        val text = query.trim()
        if (text.isEmpty()) return@run
        if (text.any(Char::isDigit) && text.length <= 4) { lines = app.vrr.linesByNumber(text); stopsFound = emptyList()
            if (lines.isEmpty()) error = "Keine NEW-Linie $text gefunden." }
        else { stopsFound = app.vrr.search(text).take(8); lines = emptyList()
            if (stopsFound.isEmpty()) error = "Keine Haltestelle gefunden." }
    }
    fun open(selected: NetworkLine) = run {
        line = selected; geometry = null
        lineStops = app.vrr.lineStops(selected.id)
        geometry = loader.geometry(selected, lineStops, Instant.now())
    }
    BackHandler(enabled = line != null) { line = null; lineStops = emptyList(); geometry = null }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            ScreenHeader(line?.let { "Linie ${it.number}" } ?: "Liniennetz", onBack = { if (line != null) { line = null; lineStops = emptyList() } else onBack() })
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (line == null) item {
                    OutlinedTextField(value = query, onValueChange = { query = it.take(40) }, singleLine = true,
                        label = { Text("Linie oder Haltestelle") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { search() }, enabled = query.isNotBlank() && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Suchen") }
                }
                if (busy) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { item { Text(it, color = Amber, fontSize = 14.sp) } }
                val current = line
                if (current == null) {
                    items(stopsFound, key = { "s" + it.id }) { stop ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { run { lines = app.vrr.servingLines(stop.id); stopsFound = emptyList()
                            if (lines.isEmpty()) error = "An dieser Haltestelle fährt keine NEW-Linie." } }, verticalAlignment = Alignment.CenterVertically) {
                            Text(shortName(stop.name), fontSize = 16.sp, modifier = Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null, tint = Soft)
                        }
                    }
                    items(lines, key = { "l" + it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { open(entry) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LineSign(entry.number, large = true)
                            Column(Modifier.weight(1f)) {
                                Text("→ ${shortName(entry.direction)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                if (entry.description.isNotBlank()) Text(entry.description, color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    item {
                        Text("→ ${shortName(current.direction)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (current.description.isNotBlank()) Text(current.description, color = Soft, fontSize = 13.sp)
                    }
                    val shape = geometry?.points.orEmpty()
                    val pins = lineStops.mapNotNull { stop -> stop.point?.let { MapPin(it, shortName(stop.name), PinKind.STOP, stop.id) } }
                    if (pins.isNotEmpty() || shape.size > 1) item {
                        PlainMap(if (shape.size > 1) listOf(MapLine(shape, 0xFFB40055)) else emptyList(), pins,
                            "Linie ${current.number} auf der Karte", height = 300.dp)
                    }
                    geometry?.let { result -> item {
                        Text(result.note ?: when (result.source) {
                            RouteShapeSource.TRIP -> "Strecke laut VRR für eine aktuelle Fahrt dieser Linie."
                            else -> "Strecke einer früheren VRR-Fahrt mit gleicher Haltestellenfolge."
                        }, color = if (result.note != null) Amber else Soft, fontSize = 13.sp, lineHeight = 18.sp)
                    } }
                    items(lineStops, key = { "ls" + it.id + lineStops.indexOf(it) }) { stop ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onStop(Stop(stop.id, stop.name, stop.point)) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(shortName(stop.name), fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.ChevronRight, "Abfahrten", tint = Soft)
                        }
                    }
                }
            }
        }
    }
}
