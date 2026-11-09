package de.luca.mitfahrt.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.RoutePlan
import de.luca.mitfahrt.data.SavedStops
import de.luca.mitfahrt.data.Stop
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.stopKey
import de.luca.mitfahrt.ui.MitfahrtTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Lets the user pick content and appearance by readable names; internal IDs are never shown. */
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Leaving with back or cancel must not place a half-configured widget.
        setResult(RESULT_CANCELED)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (widgetId <= 0 || provider != ComponentName(this, MitfahrtWidgetProvider::class.java)) { finish(); return }
        val app = application as MitfahrtApplication
        val store = WidgetStore(this)
        val existing = store.config(widgetId)
        // Read-only snapshots: the configuration never adds favorites, routes or recent stops.
        val saved = app.savedStops.state.value
        val knownStops = (saved.favorites + saved.recent).distinctBy { stopKey(it.id) }
        val routes = app.routePlans.current().routes

        setContent {
            MitfahrtTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WidgetSetup(
                        initial = existing, defaults = WidgetDefaults.resolve(this), knownStops = knownStops, routes = routes,
                        search = { query -> app.vrr.search(query) },
                        onCancel = { finish() },
                        onSave = { config ->
                            if (store.saveConfig(widgetId, config)) {
                                MitfahrtWidgets.configured(applicationContext, widgetId)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }
                        })
                }
            }
        }
    }
}

@Composable
private fun WidgetSetup(
    initial: WidgetConfig?, defaults: WidgetConfig, knownStops: List<Stop>, routes: List<RoutePlan>,
    search: suspend (String) -> List<Stop>, onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit
) {
    val starting = initial ?: defaults
    var useDefaults by remember { mutableStateOf(initial?.useAppDefaults ?: true) }
    var kind by remember { mutableStateOf(starting.kind) }
    var stop by remember {
        mutableStateOf(starting.takeIf { it.kind == WidgetKind.STOP && it.targetId.isNotBlank() }?.let { Stop(it.targetId, it.targetName) })
    }
    var routeId by remember { mutableStateOf(starting.takeIf { it.kind == WidgetKind.ROUTE }?.targetId) }
    var info by remember { mutableStateOf(starting.info) }
    var theme by remember { mutableStateOf(starting.theme) }
    var accent by remember { mutableStateOf(starting.accent) }
    var opacity by remember { mutableStateOf(starting.opacity) }
    var action by remember { mutableStateOf(starting.action) }
    var count by remember { mutableStateOf(starting.rowCount) }

    val route = routes.firstOrNull { it.id == routeId }
    val ownConfig = when (kind) {
        WidgetKind.STOP -> stop?.let { WidgetConfig(kind, it.id, it.name, info, theme, accent, opacity) }
        WidgetKind.ROUTE -> route?.let { WidgetConfig(kind, it.id, it.name, info, theme, accent, opacity) }
        WidgetKind.ACTIVE -> WidgetConfig(kind, info = info, theme = theme, accent = accent, opacity = opacity)
    }?.copy(action = action, rowCount = count, useAppDefaults = false)
    val config = (if (useDefaults) defaults else ownConfig)?.let(WidgetConfigs::clean)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Widget einrichten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App-Vorgaben verwenden", fontWeight = FontWeight.Medium)
                    Hint("Inhalt und Darstellung folgen den Widget-Einstellungen der App.")
                }
                Switch(checked = useDefaults, onCheckedChange = { useDefaults = it })
            }
            if (!useDefaults) {
            Section("Inhalt") {
                ChipRow(WidgetKind.entries, kind, { it.label }) { kind = it }
                Spacer(Modifier.height(4.dp))
                when (kind) {
                    WidgetKind.STOP -> StopPicker(stop, knownStops, search) { stop = it }
                    WidgetKind.ROUTE -> RoutePicker(routes, routeId) { routeId = it }
                    WidgetKind.ACTIVE -> Hint("Zeigt die Fahrt, die du gerade in der App verfolgst.")
                }
            }
            Section("Informationsmenge") {
                ChipRow(InfoLevel.entries, info, { it.label }) { info = it }
                Text("Abfahrten", style = MaterialTheme.typography.labelLarge)
                ChipRow((1..5).toList(), count, Int::toString) { count = it }
            }
            Section("Beim Antippen") {
                val actions = listOf("preview" to "Vorschau", "departures" to "Abfahrten", "ride" to "Aktive Fahrt", "map" to "Karte")
                ChipRow(actions, actions.firstOrNull { it.first == action } ?: actions.first(), { it.second }) { action = it.first }
            }
            Section("Darstellung") {
                ChipRow(ThemeMode.entries, theme, { it.label }) { theme = it }
            }
            Section("Akzentfarbe") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WidgetAccent.entries.forEach { option ->
                        val selected = option == accent
                        Box(
                            Modifier.size(36.dp).background(Color(option.light), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton) { accent = option }
                                .semantics { contentDescription = option.label }
                        )
                    }
                }
            }
            Section("Hintergrund") {
                ChipRow(WidgetConfigs.OPACITIES, opacity, { if (it == 100) "Deckend" else "$it %" }) { opacity = it }
            }
            }
            Section("Vorschau") {
                Preview(config ?: WidgetConfig(kind, info = info, theme = theme, accent = accent, opacity = opacity))
                if (useDefaults && config == null) Hint("Wähle in den App-Einstellungen zuerst eine Haltestelle oder Route oder verwende eigene Einstellungen.")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Abbrechen") }
            Button(onClick = { config?.let(onSave) }, enabled = config != null) {
                Text(if (initial == null) "Widget hinzufügen" else "Übernehmen")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StopPicker(selected: Stop?, known: List<Stop>, search: suspend (String) -> List<Stop>, onSelect: (Stop) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Stop>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        failed = false
        val trimmed = query.trim()
        if (trimmed.length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        results = try { search(trimmed) } catch (error: CancellationException) { throw error } catch (_: Exception) {
            failed = true; emptyList()
        }
        searching = false
    }
    selected?.let { Text("Gewählt: ${shortName(it.name)}", fontWeight = FontWeight.Medium) }
    OutlinedTextField(value = query, onValueChange = { query = it.take(80) }, singleLine = true,
        label = { Text("Haltestelle suchen") }, modifier = Modifier.fillMaxWidth())
    val local = if (query.isBlank()) known else known.filter { SavedStops.matches(it, query) }
    val options = (local + results).distinctBy { stopKey(it.id) }.take(10)
    when {
        searching && options.isEmpty() -> Hint("Suche …")
        failed && options.isEmpty() -> Hint("Suche gerade nicht möglich. Bitte später erneut versuchen.")
        options.isEmpty() && query.isBlank() -> Hint("Gib einen Haltestellennamen ein.")
        options.isEmpty() -> Hint("Keine Haltestelle gefunden.")
    }
    options.forEach { stop ->
        val isSelected = selected?.let { stopKey(it.id) == stopKey(stop.id) } == true
        OptionRow(shortName(stop.name), null, isSelected) { onSelect(Stop(stop.id, stop.name)) }
    }
}

@Composable
private fun RoutePicker(routes: List<RoutePlan>, selected: String?, onSelect: (String) -> Unit) {
    if (routes.isEmpty()) {
        Hint("Noch keine gespeicherten Routen. Lege zuerst in der App eine Route an.")
        return
    }
    routes.forEach { route ->
        OptionRow(route.name, "${shortName(route.origin.name)} → ${shortName(route.destination.name)}",
            route.id == selected) { onSelect(route.id) }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Static sample in the chosen colors; times here are illustrative and labeled like the widget. */
@Composable
private fun Preview(config: WidgetConfig) {
    val dark = when (config.theme) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val palette = WidgetPalette.of(dark, config.accent)
    val background = Color(palette.background).copy(alpha = config.opacity / 100f)
    Box(Modifier.fillMaxWidth().background(Color(0xFF6E7781), RoundedCornerShape(24.dp)).padding(12.dp)) {
        Column(
            Modifier.fillMaxWidth().background(background, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(config.targetName.takeIf { it.isNotBlank() }?.let(::shortName) ?: config.kind.label,
                color = Color(palette.text), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PreviewRow(palette, "017", "Beispielziel", "14:28", "Prognose · Stand 14:24", live = true)
            if (config.info != InfoLevel.MINIMAL) PreviewRow(palette, "023", "Beispielziel", "14:32", "Plan", live = false)
        }
    }
}

@Composable
private fun PreviewRow(palette: WidgetPalette, line: String, title: String, time: String, label: String, live: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(Color(palette.accent), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(line, color = Color(palette.onAccent), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color(palette.text), modifier = Modifier.weight(1f), maxLines = 1)
        Column(horizontalAlignment = Alignment.End) {
            Text(time, color = Color(if (live) palette.accent else palette.text), fontWeight = FontWeight.Medium)
            Text(label, color = Color(if (live) palette.accent else palette.muted), style = MaterialTheme.typography.labelSmall)
        }
    }
}
