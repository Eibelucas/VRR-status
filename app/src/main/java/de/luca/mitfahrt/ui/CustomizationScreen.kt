package de.luca.mitfahrt.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Search, profiles and backup are available without knowing any internal setting keys. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomizationScreen(store: AppSettingsStore, onBack: () -> Unit, onNotice: (String) -> Unit = {}, onOpenModule: (String) -> Unit = {}) {
    val context = LocalContext.current
    val widgetsAvailable = remember(context) { runCatching {
        context.packageManager.getReceiverInfo(android.content.ComponentName(context.packageName,
            "de.luca.mitfahrt.widgets.MitfahrtWidgetProvider"), 0)
    }.isSuccess }
    val settings by store.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SettingDefinition?>(null) }
    var editingProfile by remember { mutableStateOf<SettingsProfile?>(null) }
    var addingProfile by remember { mutableStateOf(false) }
    var resetGroup by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notice(message: String) { onNotice(message); scope.launch { snackbar.showSnackbar(message) } }

    // Automatic profile windows also switch while the screen stays open.
    LaunchedEffect(store) { while (isActive) { store.refresh(); delay(15_000) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val success = withContext(Dispatchers.IO) { runCatching {
                val data = SettingsTemplate.export(context, store)
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(data.toByteArray(Charsets.UTF_8)) }
            }.isSuccess }
            busy = false
            notice(if (success) "Einstellungen und Routen gesichert" else "Die Sicherung konnte nicht geschrieben werden.")
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val data = withContext(Dispatchers.IO) { runCatching {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(output.size() + read <= AppSettingsCodec.MAX_BYTES)
                        output.write(buffer, 0, read)
                    }
                    output.toString("UTF-8").also { SettingsTemplate.validate(it) }
                }
            }.getOrNull() }
            busy = false
            if (data == null) notice("Diese Datei ist keine gültige Mitfahrt-Sicherung.") else importDraft = data
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("Anpassen") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                OutlinedTextField(value = query, onValueChange = { query = it; recentOnly = false },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Einstellungen suchen") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Suche löschen") }
                    })
            }
            item { PersonalModuleLinks(onOpenModule, query) }
            item {
                AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Profil", style = MaterialTheme.typography.titleMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = settings.manualProfileId == "base", onClick = { store.selectProfile("base") }, label = { Text("Standard") })
                            FilterChip(selected = settings.manualProfileId == "auto", onClick = { store.selectProfile("auto") }, label = { Text("Nach Zeit") })
                            settings.profiles.forEach { profile ->
                                FilterChip(selected = settings.manualProfileId == profile.id, onClick = { store.selectProfile(profile.id) },
                                    label = { Text(profile.name, maxLines = 1) })
                            }
                        }
                        if (settings.manualProfileId == "auto") Text("Gerade aktiv: ${settings.activeProfileName}", style = MaterialTheme.typography.bodySmall, color = Soft)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = ControlShape, onClick = { addingProfile = true }, enabled = settings.profiles.size < AppSettingsCodec.MAX_PROFILES) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Profil")
                            }
                            settings.profiles.firstOrNull { it.id == settings.activeProfileId }?.let { profile ->
                                TextButton(onClick = { editingProfile = profile }) { Text("Bearbeiten") }
                            }
                        }
                        Text(if (settings.activeProfileId == null) "Änderungen gelten für den Standard." else "Änderungen gelten für ${settings.activeProfileName}. Andere Werte werden vom Standard übernommen.",
                            style = MaterialTheme.typography.bodySmall, color = Soft)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = recentOnly, onClick = { recentOnly = !recentOnly; query = "" },
                        label = { Text("Zuletzt geändert") }, leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)) })
                    Spacer(Modifier.weight(1f))
                    Text("Erweitert", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = settings.advanced, onCheckedChange = store::setAdvanced)
                }
            }
            val filtered = SettingsCatalog.definitions.filter { definition ->
                definition.key != "transport_filter" &&
                (widgetsAvailable || !definition.key.startsWith("widget_")) &&
                (!recentOnly || definition.key in settings.recentKeys) &&
                    (settings.advanced || !definition.advanced || query.isNotBlank() || recentOnly) &&
                    (query.isBlank() || normalizeSettingsSearch("${definition.title} ${SettingsCatalog.groups[definition.group]} ${definition.hint}")
                        .contains(normalizeSettingsSearch(query)))
            }.let { definitions -> if (recentOnly) definitions.sortedBy { settings.recentKeys.indexOf(it.key) } else definitions }
            if (filtered.isEmpty()) item { Text(if (recentOnly) "Noch keine Einstellungen geändert." else "Keine passende Einstellung gefunden.", color = Soft) }
            if (recentOnly) {
                items(filtered, key = { it.key }) { definition ->
                    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                        SettingControl(definition, settings, store) { editing = it }
                    }
                }
            } else SettingsCatalog.groups.forEach { (group, title) ->
                val definitions = filtered.filter { it.group == group }
                if (definitions.isNotEmpty()) item(key = "group_$group") {
                    val open = query.isNotBlank() || expanded == group
                    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column {
                            Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == group) null else group }
                                .padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("${definitions.size}", color = Soft, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(8.dp))
                                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (open) "Zuklappen" else "Öffnen", tint = Soft)
                            }
                            if (open) {
                                if (group in listOf("appearance", "nowbar")) CustomizationPreview(settings, group)
                                definitions.forEach { definition ->
                                    HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = Soft.copy(alpha = 0.15f))
                                    SettingControl(definition, settings, store) { editing = it }
                                }
                                TextButton(onClick = { resetGroup = group }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp)) {
                                    Text("Bereich zurücksetzen")
                                }
                            }
                        }
                    }
                }
            }
            item {
                AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sicherung & Vorlagen", style = MaterialTheme.typography.titleMedium)
                        Text("Einstellungen und eigene Routen als Datei sichern oder mit anderen teilen. Fahrthistorie, Belege und erfasste Standorte werden nicht übernommen.",
                            color = Soft, style = MaterialTheme.typography.bodySmall)
                        if (settings.bool("export_personal")) Text("Name, Klasse und Zusatznotiz sind in der Sicherung enthalten.", color = Amber, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(shape = ControlShape, onClick = { exportLauncher.launch("Mitfahrt-Einstellungen.json") }, enabled = !busy) { Text("Sichern / Teilen") }
                            OutlinedButton(shape = ControlShape, onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("Importieren") }
                        }
                        if (busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    editing?.let { definition -> SettingEditor(definition, settings, context, onSave = { value ->
        if (store.set(definition.key, value)) editing = null else notice("Bitte einen gültigen Wert eingeben.")
    }, onDismiss = { editing = null }) }
    if (addingProfile) AddProfileDialog(onDismiss = { addingProfile = false }, onSave = { name ->
        val id = store.addProfile(name)
        if (id != null) { addingProfile = false; editingProfile = store.current().profiles.firstOrNull { it.id == id } }
    })
    editingProfile?.let { profile -> ProfileDialog(profile, onDismiss = { editingProfile = null }, onSave = {
        if (store.updateProfile(it)) editingProfile = null else notice("Wähle einen Namen und mindestens einen Wochentag.")
    }, onDelete = { store.deleteProfile(profile.id); editingProfile = null }) }
    resetGroup?.let { group -> AlertDialog(onDismissRequest = { resetGroup = null }, title = { Text("${SettingsCatalog.groups[group]} zurücksetzen?") },
        text = { Text(if (settings.activeProfileId == null) "Die Standardwerte dieses Bereichs werden wiederhergestellt." else "Dieses Profil übernimmt wieder die Standardwerte des Bereichs.") },
        confirmButton = { TextButton(onClick = { store.resetGroup(group); resetGroup = null }) { Text("Zurücksetzen") } },
        dismissButton = { TextButton(onClick = { resetGroup = null }) { Text("Abbrechen") } }) }
    importDraft?.let { data -> AlertDialog(onDismissRequest = { importDraft = null }, title = { Text("Sicherung übernehmen?") },
        text = { Text("Deine Einstellungen und Profile werden ersetzt. Routen aus der Datei werden ergänzt; vorhandene Routen mit gleicher Kennung werden aktualisiert. Fahrten und Belege bleiben erhalten.") },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                busy = true
                val success = withContext(Dispatchers.IO) { SettingsTemplate.importConfiguration(context, store, data) }
                busy = false; importDraft = null
                notice(if (success) "Sicherung übernommen" else "Sicherung konnte nicht übernommen werden. Prüfe die Anzahl eigener Routen.")
            }
        }, enabled = !busy) { Text("Übernehmen") } }, dismissButton = { TextButton(onClick = { importDraft = null }, enabled = !busy) { Text("Abbrechen") } }) }
}

private fun normalizeSettingsSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.GERMAN).replace("ß", "ss")

@Composable
private fun SettingControl(definition: SettingDefinition, settings: AppSettings, store: AppSettingsStore, onEdit: (SettingDefinition) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable {
        if (definition.kind == SettingKind.BOOLEAN) store.set(definition.key, (!settings.bool(definition.key)).toString()) else onEdit(definition)
    }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(definition.title, style = MaterialTheme.typography.bodyLarge)
            if (definition.kind != SettingKind.BOOLEAN) Text(settingSummary(definition, settings), color = Soft,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else if (definition.hint.isNotBlank()) Text(definition.hint, color = Soft, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (definition.kind == SettingKind.BOOLEAN) Switch(checked = settings.bool(definition.key), onCheckedChange = { store.set(definition.key, it.toString()) })
        else Icon(Icons.Outlined.ChevronRight, null, tint = Soft)
    }
}

private fun settingSummary(definition: SettingDefinition, settings: AppSettings): String {
    val value = settings.string(definition.key)
    definition.choices.firstOrNull { it.value == value }?.let { return it.label }
    if (definition.key in listOf("home_cards", "nav_order", "report_fields", "notification_actions", "watch_actions", "alert_leads", "trip_modes")) {
        val choices = listChoices(definition.key)
        return value.split(',').mapNotNull { item -> choices.firstOrNull { it.value == item }?.label }.joinToString(" · ").ifEmpty { "Keine ausgewählt" }
    }
    if (definition.key in listOf("widget_route_id", "widget_stop_id", "watch_tile_id", "excluded_stops", "preferred_stops"))
        return if (value.isBlank()) "Nicht festgelegt" else "${value.split(',').size} ausgewählt"
    if (value.isBlank()) return "Nicht festgelegt"
    return if (definition.key in listOf("report_name", "report_class", "report_note")) "Hinterlegt" else value
}

@Composable
private fun CustomizationPreview(settings: AppSettings, group: String) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(settings.string("accent_hex", "#B40055"))) }.getOrDefault(BrandBerry)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text("Vorschau", color = Soft, style = MaterialTheme.typography.labelSmall)
        Surface(shape = RoundedCornerShape(18.dp), color = if (group == "nowbar") Paper.copy(alpha = settings.int("widget_opacity", 100) / 100f * 0.08f) else Ink,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = if (settings.string("accent") == "custom") accent else Lime) {
                    Text("007", color = if (settings.string("accent") == "custom") Color.White else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("Hauptbahnhof", style = MaterialTheme.typography.titleSmall); Text("Beispielhaltestelle", color = Soft, style = MaterialTheme.typography.bodySmall) }
                Text("3 Min", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun listChoices(key: String): List<SettingChoice> = when (key) {
    "home_cards" -> listOf("planned" to "Eigene Routen", "school" to "Schulwege", "suggestions" to "Automatische Vorschläge", "last" to "Letzte Fahrt", "map" to "Karte")
    "nav_order" -> listOf("home" to "Start", "nearby" to "In der Nähe", "reports" to "Berichte", "settings" to "Einstellungen")
    "trip_modes" -> listOf("train" to "Bahn", "light_rail" to "U-/Stadtbahn & Tram", "bus" to "Bus", "on_demand" to "Rufbus & AST", "other" to "Sonstige")
    "notification_actions" -> listOf("board" to "Bin im Bus", "arrival" to "Ankunft merken", "map" to "Karte öffnen", "stop" to "Fahrt beenden")
    "watch_actions" -> listOf("board" to "Bin im Bus", "arrival" to "Ankunft merken", "target" to "Ziel wählen", "stop" to "Fahrt beenden")
    "alert_leads" -> listOf("300" to "5 Minuten vorher", "180" to "3 Minuten vorher", "120" to "2 Minuten vorher", "60" to "1 Minute vorher")
    "report_fields" -> listOf("line" to "Linie", "route" to "Strecke", "date" to "Datum", "operator_times" to "Gemeldete Zeiten", "own_arrival" to "Eigene Ankunft", "sources" to "Quellen")
    else -> emptyList()
}.map { SettingChoice(it.first, it.second) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingEditor(definition: SettingDefinition, settings: AppSettings, context: Context, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(definition.key) { mutableStateOf(settings.string(definition.key)) }
    val listChoices = remember(definition.key) { listChoices(definition.key) }
    val app = context.applicationContext as? MitfahrtApplication
    val routes = app?.routePlans?.state?.collectAsStateWithLifecycle()?.value?.routes.orEmpty()
    val saved = app?.savedStops?.state?.collectAsStateWithLifecycle()?.value ?: SavedStopsState()
    val stops = (saved.favorites + saved.recent + routes.flatMap { listOf(it.origin, it.destination) }).distinctBy { stopKey(it.id) }
    val stationPicker = definition.key in listOf("widget_stop_id", "excluded_stops", "preferred_stops") || definition.key == "watch_tile_id" && settings.string("watch_tile") == "stop"
    val routePicker = definition.key == "widget_route_id" || definition.key == "watch_tile_id" && settings.string("watch_tile") == "route"
    val namedChoices = if (routePicker) routes.map { SettingChoice(it.id, it.name) }
        else if (stationPicker) stops.map { SettingChoice(stopKey(it.id), shortName(it.name)) } else emptyList()
    val multiPicker = definition.key in listOf("excluded_stops", "preferred_stops")
    if (definition.key in listOf("quiet_start", "quiet_end")) {
        DisposableEffect(definition.key) {
            val time = runCatching { LocalTime.parse(text) }.getOrDefault(LocalTime.of(22, 0))
            val dialog = TimePickerDialog(context, { _, hour, minute -> onSave("%02d:%02d".format(Locale.ROOT, hour, minute)) }, time.hour, time.minute, true)
            dialog.setOnDismissListener { onDismiss() }; dialog.show()
            onDispose { dialog.dismiss() }
        }
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(definition.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (listChoices.isNotEmpty()) {
                val selected = text.split(',').filter(String::isNotEmpty).distinct()
                val ordered = selected.mapNotNull { id -> listChoices.firstOrNull { it.value == id } } + listChoices.filterNot { it.value in selected }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ordered, key = { it.value }) { choice ->
                        val index = selected.indexOf(choice.value)
                        val required = definition.key == "nav_order" || definition.key == "report_fields" && choice.value == "sources"
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = choice.value in selected || required, enabled = !required, onCheckedChange = { checked ->
                                val updated = if (checked) selected + choice.value else selected - choice.value
                                if (definition.key !in listOf("alert_leads", "trip_modes") || updated.isNotEmpty()) text = updated
                                    .let { if (definition.key == "notification_actions") it.takeLast(3) else it }.joinToString(",")
                            })
                            Text(choice.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (index >= 0 && definition.key !in listOf("alert_leads", "report_fields", "trip_modes")) {
                                IconButton(onClick = { val updated = selected.toMutableList(); java.util.Collections.swap(updated, index, index - 1); text = updated.joinToString(",") },
                                    enabled = index > 0, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.KeyboardArrowUp, "${choice.label} nach oben") }
                                IconButton(onClick = { val updated = selected.toMutableList(); java.util.Collections.swap(updated, index, index + 1); text = updated.joinToString(",") },
                                    enabled = index < selected.lastIndex, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, "${choice.label} nach unten") }
                            }
                        }
                    }
                }
                if (definition.key == "report_fields") Text("Quellen und Kennzeichnung eigener Angaben bleiben enthalten.", style = MaterialTheme.typography.bodySmall, color = Soft)
            } else if (stationPicker || routePicker) {
                if (namedChoices.isEmpty()) Text(if (routePicker) "Lege zuerst eine eigene Route an." else "Merke zuerst eine Haltestelle als Favorit.")
                else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable { text = "" }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = text.isBlank(), onClick = { text = "" }); Text(if (multiPicker) "Keine" else "Automatisch")
                        }
                    }
                    items(namedChoices, key = { it.value }) { choice ->
                        val selected = text.split(',').filter(String::isNotEmpty)
                        fun choose() { text = if (!multiPicker) choice.value else (if (choice.value in selected) selected - choice.value else selected + choice.value).joinToString(",") }
                        Row(Modifier.fillMaxWidth().clickable { choose() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (multiPicker) Checkbox(checked = choice.value in selected, onCheckedChange = { choose() })
                            else RadioButton(selected = text == choice.value, onClick = { choose() })
                            Text(choice.label, modifier = Modifier.weight(1f))
                            if (definition.key == "preferred_stops" && choice.value in selected) Text("${selected.indexOf(choice.value) + 1}", color = Soft)
                        }
                    }
                }
            } else if (definition.kind == SettingKind.CHOICE) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(definition.choices, key = { it.value }) { choice ->
                        Row(Modifier.fillMaxWidth().clickable { text = choice.value }.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = text == choice.value, onClick = { text = choice.value }); Text(choice.label)
                        }
                    }
                }
            } else if (definition.key == "routes_free_dates") {
                val dates = text.split(',').filter(String::isNotBlank).sorted()
                OutlinedButton(shape = ControlShape, onClick = {
                    val today = LocalDate.now()
                    DatePickerDialog(context, { _, year, month, day -> text = (dates + LocalDate.of(year, month + 1, day).toString()).distinct().sorted().joinToString(",") }, today.year, today.monthValue - 1, today.dayOfMonth).show()
                }) { Text("Freien Tag hinzufügen") }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(dates, key = { it }) { date ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(runCatching { LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) }.getOrDefault(date), Modifier.weight(1f))
                            IconButton(onClick = { text = (dates - date).joinToString(",") }) { Icon(Icons.Outlined.Close, "Tag entfernen") }
                        }
                    }
                }
                if (dates.isEmpty()) Text("Keine zusätzlichen freien Tage.", color = Soft)
            } else {
                if (definition.key == "accent_hex") FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("#B40055", "#006B5F", "#2F5DA8", "#7C428A", "#B65A00", "#333333").forEach { color ->
                        FilterChip(selected = text.equals(color, true), onClick = { text = color }, label = {
                            Box(Modifier.size(22.dp).background(Color(android.graphics.Color.parseColor(color)), RoundedCornerShape(6.dp)))
                        })
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = definition.key != "report_note", label = { Text(if (definition.kind == SettingKind.NUMBER) "Wert" else definition.title) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (definition.kind == SettingKind.NUMBER) KeyboardType.Number else KeyboardType.Text),
                    isError = definition.clean(text) == null, supportingText = {
                        Text(if (definition.kind == SettingKind.NUMBER) "${definition.range.first}–${definition.range.last}${if (definition.hint.isNotBlank()) " · ${definition.hint}" else ""}" else definition.hint)
                    })
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(text) }, enabled = definition.clean(text) != null) { Text("Übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@Composable
private fun AddProfileDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Neues Profil") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name, z. B. Schule") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Anlegen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDialog(profile: SettingsProfile, onDismiss: () -> Unit, onSave: (SettingsProfile) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var scheduled by remember(profile.id) { mutableStateOf(profile.scheduled) }
    var days by remember(profile.id) { mutableStateOf(profile.days) }
    var start by remember(profile.id) { mutableIntStateOf(profile.startMinute) }
    var end by remember(profile.id) { mutableIntStateOf(profile.endMinute) }
    var deleting by remember { mutableStateOf(false) }
    fun time(value: Int) = "%02d:%02d".format(Locale.ROOT, value / 60, value % 60)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Profil bearbeiten") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Zeitplan aktivieren", Modifier.weight(1f)); Switch(scheduled, { scheduled = it }) }
            if (scheduled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEachIndexed { i, day ->
                        FilterChip(selected = i + 1 in days, onClick = { days = if (i + 1 in days) days - (i + 1) else days + (i + 1) }, label = { Text(day) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(shape = ControlShape, onClick = { TimePickerDialog(context, { _, hour, minute -> start = hour * 60 + minute }, start / 60, start % 60, true).show() }) { Text("Von ${time(start)}") }
                    OutlinedButton(shape = ControlShape, onClick = { TimePickerDialog(context, { _, hour, minute -> end = hour * 60 + minute }, end / 60, end % 60, true).show() }) { Text("Bis ${time(end)}") }
                }
                Text("Bei gleicher Start- und Endzeit gilt das Profil ganztägig. Überlappen Zeitpläne, gilt das zuerst angelegte passende Profil.", color = Soft, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { deleting = true }) { Text("Profil löschen", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(profile.copy(name = name.trim(), scheduled = scheduled, days = days, startMinute = start, endMinute = end)) },
        enabled = name.isNotBlank() && days.isNotEmpty()) { Text("Speichern") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Profil löschen?") }, text = { Text("Die Einstellungen dieses Profils werden entfernt. Deine Routen bleiben erhalten.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Löschen") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Abbrechen") } })
}

/** Only configuration and deliberate route templates enter this format, never ride records. */
object SettingsTemplate {
    private const val KIND = "mitfahrt-configuration"
    fun export(context: Context, store: AppSettingsStore): String {
        val app = context.applicationContext as? MitfahrtApplication
        val routes = app?.routePlans?.current() ?: RoutePlansStore(context).current()
        return JSONObject().apply {
            put("kind", KIND); put("version", 1)
            put("settings", JSONObject(store.exportSettings(store.current().bool("export_personal"))))
            put("routes", JSONObject(RoutePlans.encode(routes.copy(automaticLearning = false))))
        }.toString(2)
    }
    fun validate(value: String): Pair<String, List<RoutePlan>> {
        require(value.toByteArray(Charsets.UTF_8).size <= AppSettingsCodec.MAX_BYTES)
        val json = JSONObject(value)
        require(json.optString("kind") == KIND && json.optInt("version") == 1)
        val settings = json.getJSONObject("settings").toString()
        AppSettingsCodec.decode(settings)
        val routeJson = json.getJSONObject("routes")
        val routes = RoutePlans.decode(routeJson.toString()).routes
        require(routeJson.getJSONArray("routes").length() == routes.size)
        return settings to routes
    }
    fun importConfiguration(context: Context, store: AppSettingsStore, value: String): Boolean = runCatching {
        val (settings, routes) = validate(value)
        val routeStore = (context.applicationContext as? MitfahrtApplication)?.routePlans ?: RoutePlansStore(context)
        if (!routeStore.importMerge(routes)) return false
        store.importSettings(settings)
    }.getOrDefault(false)
}
