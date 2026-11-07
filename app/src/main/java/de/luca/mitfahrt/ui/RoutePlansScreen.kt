package de.luca.mitfahrt.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlansScreen(
    state: RoutePlansState,
    savedStops: SavedStopsState,
    nearby: List<Stop>,
    onBack: () -> Unit,
    onAutomaticLearning: (Boolean) -> Unit,
    onSave: (RoutePlan) -> Boolean,
    onRemove: (RoutePlan) -> Unit,
    searchStops: suspend (String) -> List<Stop>,
    initialPlan: RoutePlan? = null,
    snackbarHostState: SnackbarHostState? = null,
    onOpen: ((RoutePlan) -> Unit)? = null,
    resolveStop: suspend (Stop) -> Stop = { it },
    startNew: Boolean = false
) {
    var draftJson by rememberSaveable { mutableStateOf(initialPlan?.let { RouteDraft.from(it).encode() } ?: if (startNew) RouteDraft().encode() else null) }
    val localSnackbar = remember { SnackbarHostState() }
    val snackbar = snackbarHostState ?: localSnackbar
    BackHandler(enabled = draftJson == null, onBack = onBack)
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("Deine Strecken") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
        }, actions = {
            IconButton(onClick = { draftJson = RouteDraft().encode() }, enabled = state.routes.size < RoutePlans.MAX_ROUTES) {
                Icon(Icons.Outlined.Add, "Strecke anlegen")
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                RoutePanel {
                    RouteSwitch("Strecken automatisch merken",
                        if (state.automaticLearning) "Häufig gefahrene Strecken zusätzlich vorschlagen." else "Nur eigene Strecken.",
                        state.automaticLearning, onAutomaticLearning)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Eigene Strecken", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${state.routes.size} / ${RoutePlans.MAX_ROUTES}", color = Soft, fontSize = 12.sp)
                }
            }
            if (state.routes.isEmpty()) item {
                RoutePanel {
                    Icon(Icons.Outlined.Route, null, tint = Lime, modifier = Modifier.size(32.dp))
                    Text("Noch keine Strecken", style = MaterialTheme.typography.titleLarge)
                    Text("Start, Ziel und Zeiten festlegen – auch vor deiner ersten Fahrt.", color = Soft)
                    Button(onClick = { draftJson = RouteDraft().encode() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Strecke anlegen")
                    }
                }
            }
            items(state.routes, key = { it.id }) { plan ->
                RoutePlanCard(plan, canAdd = state.routes.size < RoutePlans.MAX_ROUTES,
                    onEdit = { draftJson = RouteDraft.from(plan).encode() }, onEnabled = { enabled ->
                    if (!onSave(plan.copy(enabled = enabled))) scope.launch { snackbar.showSnackbar("Änderung konnte nicht gespeichert werden.") }
                }, onReverse = {
                    draftJson = RouteDraft.from(plan).copy(id = UUID.randomUUID().toString(),
                        name = "${plan.name} zurück".take(RoutePlans.MAX_NAME_LENGTH), origin = plan.destination, destination = plan.origin).encode()
                }, onRemove = { onRemove(plan) }, onOpen = onOpen?.let { open -> { open(plan) } })
            }
            if (state.routes.isNotEmpty() && state.routes.size < RoutePlans.MAX_ROUTES) item {
                OutlinedButton(onClick = { draftJson = RouteDraft().encode() }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Weitere Strecke")
                }
            }
            if (state.routes.size >= RoutePlans.MAX_ROUTES) item {
                Text("Bis zu ${RoutePlans.MAX_ROUTES} Strecken. Lösche eine, um eine weitere anzulegen.", color = Soft, fontSize = 13.sp)
            }
        }
    }
    draftJson?.let { encoded ->
        val draft = remember(encoded) { RouteDraft.decode(encoded) }
        RoutePlanEditor(draft, editing = state.routes.any { it.id == draft.id }, savedStops, nearby,
            onChange = { draftJson = it.encode() }, onDismiss = { draftJson = null }, onSave = onSave, searchStops = searchStops, resolveStop = resolveStop)
    }
}

@Composable
private fun RoutePlanCard(plan: RoutePlan, canAdd: Boolean, onEdit: () -> Unit, onEnabled: (Boolean) -> Unit,
    onReverse: () -> Unit, onRemove: () -> Unit, onOpen: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onOpen ?: onEdit, shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(plan.name, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    if (!plan.enabled) Text("Inaktiv", color = Soft, fontSize = 12.sp)
                }
                Switch(checked = plan.enabled, onCheckedChange = onEnabled,
                    modifier = Modifier.semantics { contentDescription = "${plan.name} aktiv" })
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "${plan.name} verwalten", tint = Soft) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Bearbeiten") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Rückweg anlegen") }, leadingIcon = { Icon(Icons.Outlined.SwapVert, null) },
                            onClick = { menu = false; onReverse() }, enabled = canAdd)
                        DropdownMenuItem(text = { Text("Löschen") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menu = false; onRemove() })
                    }
                }
            }
            RouteStopLine(plan.origin, false)
            RouteStopLine(plan.destination, true)
            HorizontalDivider(color = Soft.copy(alpha = .16f))
            Text("${routeDaysLabel(plan.window.days)} · ${routeTimeLabel(plan.window.startMinute, plan.window.endMinute)}",
                color = Soft, fontSize = 13.sp, lineHeight = 19.sp)
            Text(listOf(if (plan.match == RouteMatch.LINE) "Linie ${plan.line}" else "Alle passenden Linien",
                if (plan.nearbyOnly) "Nur in Startnähe" else "Auch außerhalb der Startnähe").joinToString(" · "),
                color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
            if (onOpen != null) TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Fahrten anzeigen")
            }
        }
    }
}

@Composable
private fun RouteStopLine(stop: Stop, destination: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (destination) Icons.Outlined.LocationOn else Icons.Outlined.TripOrigin, null,
            tint = if (destination) Lime else Soft, modifier = Modifier.size(18.dp))
        Text(shortName(stop.name), fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoutePlanEditor(draft: RouteDraft, editing: Boolean, savedStops: SavedStopsState, nearby: List<Stop>,
    onChange: (RouteDraft) -> Unit, onDismiss: () -> Unit, onSave: (RoutePlan) -> Boolean,
    searchStops: suspend (String) -> List<Stop>, resolveStop: suspend (Stop) -> Stop) {
    var picker by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var timeField by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var discard by rememberSaveable(draft.id) { mutableStateOf(false) }
    val original = rememberSaveable(draft.id) { draft.encode() }
    fun change(value: RouteDraft) { saveError = null; onChange(value) }
    fun requestClose() {
        if (draft.encode() != original) discard = true else onDismiss()
    }
    RouteFullScreenDialog(onDismiss = ::requestClose) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        fun close() { keyboard?.hide(); focus.clearFocus(); requestClose() }
        fun timePicker(start: Boolean) {
            keyboard?.hide(); focus.clearFocus()
            timeField = if (start) "start" else "end"
        }
        Scaffold(Modifier.fillMaxSize().imePadding(), containerColor = Ink, topBar = {
            TopAppBar(title = { Text(if (editing) "Strecke bearbeiten" else "Neue Strecke") }, navigationIcon = {
                IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, "Schließen") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
        }, bottomBar = {
            Surface(color = Panel) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val validation = draft.validationError()
                    (saveError ?: validation)?.let { Text(it, color = if (saveError != null) MaterialTheme.colorScheme.error else Soft,
                        fontSize = 12.sp, lineHeight = 17.sp) }
                    Button(enabled = validation == null, onClick = {
                        keyboard?.hide(); focus.clearFocus()
                        val plan = draft.toPlan() ?: return@Button
                        if (onSave(plan)) onDismiss() else saveError = "Speichern nicht möglich. Bitte prüfe die Angaben und die Anzahl deiner Strecken."
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Strecke speichern") }
                }
            }
        }) { insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    OutlinedTextField(value = draft.name, onValueChange = { change(draft.copy(name = it.take(RoutePlans.MAX_NAME_LENGTH))) },
                        label = { Text("Name für diese Richtung") }, placeholder = { Text("Zum Beispiel Zur Schule") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }))
                }
                item {
                    RoutePanel {
                        RouteStopField("Start", draft.origin, onClick = { picker = "origin" })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { change(draft.copy(origin = draft.destination, destination = draft.origin)) },
                                enabled = draft.origin != null || draft.destination != null) {
                                Icon(Icons.Outlined.SwapVert, "Start und Ziel tauschen", tint = Lime)
                            }
                        }
                        RouteStopField("Ziel", draft.destination, onClick = { picker = "destination" })
                    }
                }
                item {
                    RoutePanel {
                        Text("Passende Busse", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = draft.match == RouteMatch.STOP, onClick = { change(draft.copy(match = RouteMatch.STOP)) }, label = { Text("Haltestelle") })
                            FilterChip(selected = draft.match == RouteMatch.LINE, onClick = { change(draft.copy(match = RouteMatch.LINE)) }, label = { Text("Linie") })
                        }
                        if (draft.match == RouteMatch.LINE) OutlinedTextField(value = draft.line,
                            onValueChange = { change(draft.copy(line = it.take(RoutePlans.MAX_LINE_LENGTH))) }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Linie") }, placeholder = { Text("006 oder SB81") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }))
                        Text(if (draft.match == RouteMatch.STOP) "Alle passenden Linien ab deinem Start." else "Nur diese Linie ab deinem Start.", color = Soft, fontSize = 13.sp)
                    }
                }
                item {
                    RoutePanel {
                        Text("Wann?", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = draft.days == (1..5).toSet(), onClick = { change(draft.copy(days = (1..5).toSet())) }, label = { Text("Mo–Fr") })
                            FilterChip(selected = draft.days == (1..7).toSet(), onClick = { change(draft.copy(days = (1..7).toSet())) }, label = { Text("Täglich") })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            routeDayNames.forEachIndexed { index, day ->
                                val number = index + 1
                                FilterChip(selected = number in draft.days, onClick = {
                                    change(draft.copy(days = if (number in draft.days) draft.days - number else draft.days + number))
                                }, label = { Text(day.first) }, modifier = Modifier.semantics { contentDescription = day.second })
                            }
                        }
                        if (draft.days.isEmpty()) Text("Wähle mindestens einen Tag.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        RouteSwitch("Ganztägig", null, draft.allDay) { allDay ->
                            change(draft.copy(allDay = allDay, endMinute = if (!allDay && draft.startMinute == draft.endMinute)
                                (draft.startMinute + 120) % 1440 else draft.endMinute))
                        }
                        if (!draft.allDay) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                RouteTimeButton("Von", draft.startMinute, Modifier.weight(1f)) { timePicker(true) }
                                RouteTimeButton("Bis", draft.endMinute, Modifier.weight(1f)) { timePicker(false) }
                            }
                            if (draft.endMinute < draft.startMinute) Text("Bis ${routeClock(draft.endMinute)} am Folgetag. Die Tage gelten für den Start.",
                                color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
                item {
                    RoutePanel {
                        RouteSwitch("Strecke aktiv", "Auf der Startseite vorschlagen.", draft.enabled) { change(draft.copy(enabled = it)) }
                        HorizontalDivider(color = Soft.copy(alpha = .16f))
                        RouteSwitch("Nur in Startnähe", "Nur vorschlagen, wenn dein Start in der Nähe ist.", draft.nearbyOnly) { change(draft.copy(nearbyOnly = it)) }
                    }
                }
            }
        }
        timeField?.let { field ->
            val start = field == "start"
            RouteNativeTimePicker(field, if (start) draft.startMinute else draft.endMinute,
                onTime = { value ->
                    change(if (start) draft.copy(startMinute = value, allDay = value == draft.endMinute)
                        else draft.copy(endMinute = value, allDay = value == draft.startMinute))
                    timeField = null
                }, onDismiss = { timeField = null })
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Änderungen verwerfen?") },
        text = { Text("Dein Entwurf wurde noch nicht gespeichert.") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Verwerfen") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Weiter bearbeiten") } })
    picker?.let { field ->
        RouteStopPicker(if (field == "origin") "Start wählen" else "Ziel wählen", field,
            if (field == "origin") draft.origin else draft.destination, savedStops, nearby,
            searchStops, resolveStop, onDismiss = { picker = null }, onSelect = { stop ->
                change(if (field == "origin") draft.copy(origin = stop) else draft.copy(destination = stop))
                picker = null
            })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteStopPicker(title: String, field: String, selected: Stop?, savedStops: SavedStopsState, nearby: List<Stop>,
    searchStops: suspend (String) -> List<Stop>, resolveStop: suspend (Stop) -> Stop,
    onDismiss: () -> Unit, onSelect: (Stop) -> Unit) {
    var query by rememberSaveable(field) { mutableStateOf("") }
    var retry by rememberSaveable(field) { mutableIntStateOf(0) }
    var results by remember { mutableStateOf(emptyList<Stop>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var completedQuery by remember { mutableStateOf<String?>(null) }
    val search by rememberUpdatedState(searchStops)
    val resolve by rememberUpdatedState(resolveStop)
    var selecting by remember { mutableStateOf(false) }
    val resolveScope = rememberCoroutineScope()
    val requester = remember { FocusRequester() }
    val trimmed = query.trim()
    LaunchedEffect(trimmed, retry) {
        results = emptyList(); error = null; loading = false; completedQuery = null
        if (trimmed.length < 2) return@LaunchedEffect
        delay(350)
        loading = true
        try {
            val found = search(trimmed)
            coroutineContext.ensureActive()
            results = found
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            coroutineContext.ensureActive()
            error = "Online-Suche nicht erreichbar. Gespeicherte Haltestellen bleiben verfügbar."
        }
        finally { if (coroutineContext.isActive) loading = false }
        completedQuery = trimmed
    }
    val options = (listOfNotNull(selected).filter { trimmed.isBlank() } + SavedStops.suggestions(savedStops, trimmed) +
        nearby.filter { SavedStops.matches(it, trimmed) } + results).distinctBy { stopKey(it.id) }.take(40)
    RouteFullScreenDialog(onDismiss = onDismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        fun choose(stop: Stop) {
            if (selecting) return
            keyboard?.hide(); focus.clearFocus(); selecting = true
            resolveScope.launch {
                try {
                    val canonical = resolve(stop)
                    coroutineContext.ensureActive()
                    onSelect(canonical)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    coroutineContext.ensureActive()
                    error = "Diese Haltestelle konnte nicht geladen werden. Bitte erneut auswählen."
                } finally { selecting = false }
            }
        }
        fun close() { keyboard?.hide(); focus.clearFocus(); onDismiss() }
        LaunchedEffect(Unit) { requester.requestFocus() }
        Scaffold(Modifier.fillMaxSize().imePadding(), containerColor = Ink, topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück zur Strecke") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().focusRequester(requester),
                    enabled = !selecting, label = { Text("Haltestelle suchen") }, placeholder = { Text("Name oder Ort") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                        if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Suche löschen") }
                    }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide(); focus.clearFocus(); retry++
                    }))
                Spacer(Modifier.height(12.dp))
                if (loading || selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    error?.let { message -> item {
                        Column(Modifier.padding(vertical = 12.dp)) {
                            Text(message, color = Amber, fontSize = 13.sp)
                            TextButton(onClick = { retry++ }) { Text("Erneut versuchen") }
                        }
                    } }
                    items(options, key = { stopKey(it.id) }) { stop ->
                        val nearbyStop = nearby.firstOrNull { stopKey(it.id) == stopKey(stop.id) }
                        val favorite = savedStops.isFavorite(stop)
                        val recent = savedStops.recent.any { stopKey(it.id) == stopKey(stop.id) }
                        val isSelected = selected?.let { stopKey(it.id) == stopKey(stop.id) } == true
                        Surface(shape = RoundedCornerShape(16.dp), color = Panel) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = !selecting, role = Role.Button) { choose(stop) }.heightIn(min = 68.dp).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(when { favorite -> Icons.Outlined.StarOutline; nearbyStop != null -> Icons.Outlined.NearMe
                                    recent -> Icons.Outlined.History; else -> Icons.Outlined.LocationOn }, null, tint = Lime)
                                Column(Modifier.weight(1f)) {
                                    Text(shortName(stop.name), fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(when {
                                        isSelected -> "Ausgewählt"
                                        favorite -> "Favorit"
                                        nearbyStop != null -> "In deiner Nähe" + (nearbyStop.distance?.let { " · $it m" } ?: "")
                                        recent -> "Zuletzt gewählt"
                                        else -> "Suchergebnis"
                                    }, color = Soft, fontSize = 12.sp)
                                }
                                if (isSelected) Icon(Icons.Outlined.Check, "Ausgewählt", tint = Lime)
                            }
                        }
                    }
                    if (options.isEmpty() && !loading && error == null) item {
                        Text(if (trimmed.length < 2) "Suche eine Haltestelle mit mindestens zwei Buchstaben."
                            else if (completedQuery == trimmed) "Keine Haltestelle gefunden. Versuche einen anderen Namen." else "Suche wird vorbereitet …",
                            modifier = Modifier.padding(vertical = 20.dp), color = Soft, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteFullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink, content = content)
    }
}

@Composable
private fun RoutePanel(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun RouteSwitch(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            description?.let { Text(it, color = Soft, fontSize = 12.sp, lineHeight = 18.sp) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RouteStopField(label: String, stop: Stop?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Icon(if (label == "Start") Icons.Outlined.TripOrigin else Icons.Outlined.LocationOn, null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = Soft)
            Text(stop?.let { shortName(it.name) } ?: "Haltestelle wählen", fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Outlined.Search, null, tint = Soft)
    }
}

@Composable
private fun RouteTimeButton(label: String, minute: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 68.dp), shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Soft, fontSize = 12.sp)
            Text(routeClock(minute), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RouteNativeTimePicker(field: String, minute: Int, onTime: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentOnTime by rememberUpdatedState(onTime)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(context, field) {
        val dialog = TimePickerDialog(context, { _, hour, min -> currentOnTime(hour * 60 + min) }, minute / 60, minute % 60, true)
        dialog.setTitle(if (field == "start") "Beginn" else "Ende")
        dialog.setOnDismissListener { currentOnDismiss() }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}

private val routeDayNames = listOf("Mo" to "Montag", "Di" to "Dienstag", "Mi" to "Mittwoch", "Do" to "Donnerstag",
    "Fr" to "Freitag", "Sa" to "Samstag", "So" to "Sonntag")
private fun routeClock(minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
private fun routeDaysLabel(days: Set<Int>): String = when (days) {
    (1..7).toSet() -> "Täglich"
    (1..5).toSet() -> "Mo–Fr"
    setOf(6, 7) -> "Sa–So"
    else -> days.sorted().mapNotNull { routeDayNames.getOrNull(it - 1)?.first }.joinToString(", ")
}
private fun routeTimeLabel(start: Int, end: Int): String = if (start == end) "Ganztägig"
    else "${routeClock(start)}–${routeClock(end)}" + if (end < start) " (+1 Tag)" else ""

/** Save primitive JSON rather than placing non-Parcelable Stop objects in the instance state. */
private data class RouteDraft(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val origin: Stop? = null, val destination: Stop? = null,
    val match: RouteMatch = RouteMatch.STOP, val line: String = "", val days: Set<Int> = (1..5).toSet(),
    val startMinute: Int = 6 * 60, val endMinute: Int = 9 * 60, val allDay: Boolean = false,
    val enabled: Boolean = true, val nearbyOnly: Boolean = true
) {
    fun validationError(): String? = when {
        name.isBlank() -> "Gib dieser Richtung einen Namen."
        origin == null || destination == null -> "Wähle Start und Ziel aus den Haltestellen."
        stopKey(origin.id) == stopKey(destination.id) -> "Start und Ziel müssen verschieden sein."
        days.isEmpty() -> "Wähle mindestens einen Wochentag."
        match == RouteMatch.LINE && line.isBlank() -> "Gib eine Linie ein."
        else -> null
    }
    fun toPlan(): RoutePlan? = if (validationError() != null) null else RoutePlan(id = id, name = name.trim(),
        origin = origin!!, destination = destination!!, match = match, line = line.trim(),
        window = RouteWindow(days = days, startMinute = startMinute, endMinute = if (allDay) startMinute else endMinute),
        enabled = enabled, nearbyOnly = nearbyOnly)
    fun encode(): String = JSONObject().apply {
        put("id", id); put("name", name); put("origin", origin?.toDraftJson()); put("destination", destination?.toDraftJson())
        put("match", match.name); put("line", line); put("days", JSONArray(days.sorted()))
        put("start", startMinute); put("end", endMinute); put("allDay", allDay); put("enabled", enabled); put("nearbyOnly", nearbyOnly)
    }.toString()
    companion object {
        fun from(plan: RoutePlan): RouteDraft = RouteDraft(plan.id, plan.name, plan.origin, plan.destination,
            plan.match, plan.line, plan.window.days, plan.window.startMinute, plan.window.endMinute,
            plan.window.startMinute == plan.window.endMinute, plan.enabled, plan.nearbyOnly)
        fun decode(value: String): RouteDraft = runCatching {
            val obj = JSONObject(value)
            val days = obj.getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) }.filter { it in 1..7 }.toSet() }
            RouteDraft(obj.getString("id"), obj.optString("name"), obj.optJSONObject("origin")?.toDraftStop(),
                obj.optJSONObject("destination")?.toDraftStop(), RouteMatch.valueOf(obj.getString("match")), obj.optString("line"),
                days, obj.getInt("start").coerceIn(0, 1439), obj.getInt("end").coerceIn(0, 1439),
                obj.optBoolean("allDay"), obj.optBoolean("enabled", true), obj.optBoolean("nearbyOnly", true))
        }.getOrElse { RouteDraft() }
    }
}

private fun Stop.toDraftJson(): JSONObject = JSONObject().apply {
    put("id", id); put("name", name)
    point?.let { put("lat", it.lat); put("lon", it.lon) }
}
private fun JSONObject.toDraftStop(): Stop = Stop(getString("id"), getString("name"),
    if (has("lat") && has("lon")) GeoPoint(getDouble("lat"), getDouble("lon")) else null)
