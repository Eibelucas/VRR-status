package de.luca.mitfahrt.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.data.ArchivedJourney
import de.luca.mitfahrt.data.JourneyArchive
import de.luca.mitfahrt.data.JourneyArchiveFilter
import de.luca.mitfahrt.data.JourneyArchiveState
import de.luca.mitfahrt.data.JourneyArchiveUndo
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.exitTargetIndex
import de.luca.mitfahrt.data.shortName
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Archive of explicitly started rides. Data from `JourneyArchiveStore.state`; actions via
 * callbacks (`remove`, `restore`, `clear`, `setEnabled`, `setRetentionDays`).
 * Historic rides are shown with clock times only and cannot be started from here.
 */
@Composable
fun JourneyArchiveScreen(
    state: JourneyArchiveState,
    today: LocalDate,
    onRemove: (Set<String>) -> JourneyArchiveUndo?,
    onUndo: (JourneyArchiveUndo) -> Int,
    onClear: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRetentionDays: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var line by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var detail by remember { mutableStateOf<ArchivedJourney?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    val visible = JourneyArchive.filter(state.entries, JourneyArchiveFilter(query, line, day, day))
    val groups = JourneyArchive.byDay(visible)

    fun delete(ids: Set<String>) {
        val undo = onRemove(ids) ?: return
        selected = selected - ids
        scope.launch {
            val text = if (undo.entries.size == 1) "Fahrt gelöscht" else "${undo.entries.size} Fahrten gelöscht"
            if (snackbar.showSnackbar(text, actionLabel = "Rückgängig", withDismissAction = true) == SnackbarResult.ActionPerformed &&
                onUndo(undo) == 0) snackbar.showSnackbar("Nicht wiederhergestellt: Eintrag wurde inzwischen geändert")
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gefahrene Fahrten", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                        label = { Text("Suchen") }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = day == null, onClick = { day = null }, label = { Text("Alle Tage") },
                            modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(selected = day != null, onClick = {
                            val start = day ?: today
                            DatePickerDialog(context, { _, y, m, d -> day = LocalDate.of(y, m + 1, d) },
                                start.year, start.monthValue - 1, start.dayOfMonth).show()
                        }, label = { Text(day?.let(::dayLabelShort) ?: "Datum wählen") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    val lines = JourneyArchive.lines(state.entries)
                    if (lines.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = line == null, onClick = { line = null }, label = { Text("Alle Linien") },
                            modifier = Modifier.heightIn(min = 48.dp))
                        lines.forEach { option ->
                            FilterChip(selected = line == option, onClick = { line = if (line == option) null else option },
                                label = { Text(option) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    if (state.entries.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selecting = !selecting; selected = emptySet() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (selecting) "Fertig" else "Auswählen")
                        }
                        if (selecting && selected.isNotEmpty()) Button(onClick = { delete(selected) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("${selected.size} löschen")
                        }
                    }
                    if (!state.settings.enabled) Text("Speicherung ist ausgeschaltet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (groups.isEmpty()) item {
                Text(if (state.entries.isEmpty()) "Noch keine gefahrenen Fahrten" else "Keine Treffer",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            groups.forEach { (date, entries) ->
                item(key = "day-$date") {
                    Text(dayLabel(date, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(entries, key = { it.id }) { entry ->
                    ArchiveRow(entry, selecting, entry.id in selected,
                        onToggle = { selected = if (entry.id in selected) selected - entry.id else selected + entry.id },
                        onOpen = { detail = entry }, onDelete = { delete(setOf(entry.id)) })
                }
            }
            item {
                Column(Modifier.padding(vertical = 16.dp)) {
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (advanced) "Erweitert ausblenden" else "Erweitert")
                    }
                    if (advanced) AppCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Fahrten speichern", modifier = Modifier.weight(1f))
                                Switch(checked = state.settings.enabled, onCheckedChange = onEnabled,
                                    modifier = Modifier.semantics { contentDescription = "Fahrten speichern" })
                            }
                            Text("Automatisch löschen nach", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                JourneyArchive.RETENTION_OPTIONS.forEach { days ->
                                    FilterChip(selected = state.settings.retentionDays == days, onClick = { onRetentionDays(days) },
                                        label = { Text(if (days == 0) "Nie" else "$days Tagen") }, modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                            TextButton(onClick = { confirmClear = true }, enabled = state.entries.isNotEmpty(),
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Alle Fahrten löschen", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    detail?.let { entry -> ArchivedJourneyDialog(entry, today) { detail = null } }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Alle Fahrten löschen?") },
        text = { Text("${state.entries.size} Einträge werden endgültig gelöscht.") },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; selected = emptySet(); onClear() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Endgültig löschen", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") } }
    )
}

@Composable
private fun ArchiveRow(entry: ArchivedJourney, selecting: Boolean, selected: Boolean,
                       onToggle: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    val title = "${shortName(entry.boardingName)} → ${shortName(entry.targetName)}"
    AppCard(onClick = if (selecting) onToggle else onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() })
            LineBadge(entry.line)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Abfahrt laut Plan ${clockTime(entry.plannedBoarding)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!selecting) IconButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "Fahrt $title löschen" }) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
            }
        }
    }
}

@Composable
private fun LineBadge(line: String) {
    Box(Modifier.widthIn(min = 40.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(line.ifBlank { "?" }, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1)
    }
}

/** Historic view: planned times of that day, no countdown and no start action. */
@Composable
private fun ArchivedJourneyDialog(entry: ArchivedJourney, today: LocalDate, onDismiss: () -> Unit) {
    val ride = entry.ride
    val last = ride.exitTargetIndex() ?: ride.stops.lastIndex
    val stops = ride.stops.subList(ride.boardingIndex.coerceIn(0, ride.stops.lastIndex), (last + 1).coerceAtMost(ride.stops.size))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Linie ${entry.line} · ${dayLabel(entry.day, today)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Gestartet ${clockTime(entry.startedAt)} · Zeiten laut Fahrplan",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ride.departure.cancelled) Text("Damals als ausgefallen gemeldet", color = MaterialTheme.colorScheme.error)
                stops.forEach { stop ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(shortName(stop.name), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (stop.cancelled) "entfiel" else clockTime(stop.planned), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Schließen") } }
    )
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private val shortDayFormat = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN)
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Heute"
    today.minusDays(1) -> "Gestern"
    else -> dayFormat.format(date)
}
private fun dayLabelShort(date: LocalDate): String = shortDayFormat.format(date)
