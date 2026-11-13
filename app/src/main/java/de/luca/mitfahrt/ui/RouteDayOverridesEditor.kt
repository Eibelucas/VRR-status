package de.luca.mitfahrt.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.data.DayOverrideMode
import de.luca.mitfahrt.data.DayTimeWindow
import de.luca.mitfahrt.data.RouteDayOverride
import de.luca.mitfahrt.data.RouteDayOverrides
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exceptions for one saved route. Data and callbacks come from the caller, typically
 * `RouteDayOverridesStore.state`, `save` and `remove` (undo via `restore`).
 */
@Composable
fun RouteDayOverridesEditor(
    routeId: String,
    routeName: String,
    overrides: List<RouteDayOverride>,
    today: LocalDate,
    onSave: (RouteDayOverride) -> Boolean,
    onRemove: (RouteDayOverride) -> Unit,
    modifier: Modifier = Modifier
) {
    val upcoming = RouteDayOverrides.upcoming(overrides, routeId, today)
    var editing by remember(routeId) { mutableStateOf<RouteDayOverride?>(null) }
    var actionFailed by remember(routeId) { mutableStateOf(false) }
    val todayOverride = upcoming.firstOrNull { it.date == today }

    AppCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ausnahmen", style = MaterialTheme.typography.titleMedium)
            Text(routeName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actionFailed = !onSave(RouteDayOverride(routeId = routeId, date = today, mode = DayOverrideMode.HIDDEN)) },
                    enabled = todayOverride?.mode != DayOverrideMode.HIDDEN,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.EventBusy, contentDescription = null)
                    Text("Heute ausblenden", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = { editing = RouteDayOverride(routeId = routeId, date = today.plusDays(1), mode = DayOverrideMode.TIMES,
                        windows = listOf(DayTimeWindow(9 * 60, 10 * 60))) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Ausnahme", Modifier.padding(start = 6.dp))
                }
            }
            if (actionFailed) Text("Die Ausnahme konnte nicht gespeichert werden.", color = MaterialTheme.colorScheme.error)
            if (upcoming.isEmpty()) {
                Text("Keine Ausnahmen geplant", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            upcoming.forEach { item ->
                val dateLabel = RouteDayOverrides.dateLabel(item.date, today)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { editing = item }, modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(dateLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(RouteDayOverrides.summary(item), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    IconButton(onClick = { onRemove(item) },
                        modifier = Modifier.semantics { contentDescription = "Ausnahme $dateLabel entfernen" }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        OverrideDialog(draft, today, onDismiss = { editing = null }, onConfirm = { result ->
            if (onSave(result)) { editing = null; true } else false
        })
    }
}

@Composable
private fun OverrideDialog(
    initial: RouteDayOverride, today: LocalDate,
    onDismiss: () -> Unit, onConfirm: (RouteDayOverride) -> Boolean
) {
    val context = LocalContext.current
    var date by remember(initial) { mutableStateOf(initial.date) }
    var mode by remember(initial) { mutableStateOf(initial.mode) }
    var windows by remember(initial) { mutableStateOf(initial.windows.ifEmpty { listOf(DayTimeWindow(9 * 60, 10 * 60)) }) }
    var failed by remember(initial) { mutableStateOf(false) }
    val dialogs = remember(context) { mutableListOf<Dialog>() }
    DisposableEffect(context) {
        onDispose { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    }
    fun show(dialog: Dialog) {
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    fun pickTime(minute: Int, onPicked: (Int) -> Unit) {
        show(TimePickerDialog(context, { _, hour, min -> onPicked(hour * 60 + min) }, minute / 60, minute % 60, true))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ausnahme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(shape = ControlShape, onClick = {
                    show(DatePickerDialog(context, { _, year, month, day -> date = LocalDate.of(year, month + 1, day) },
                        date.year, date.monthValue - 1, date.dayOfMonth).apply {
                        datePicker.minDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        datePicker.maxDate = today.plusDays(RouteDayOverrides.MAX_DAYS_AHEAD)
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    })
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(RouteDayOverrides.dateLabel(date, today))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == DayOverrideMode.TIMES, onClick = { mode = DayOverrideMode.TIMES },
                        label = { Text("Andere Zeiten") }, modifier = Modifier.heightIn(min = 48.dp))
                    FilterChip(selected = mode == DayOverrideMode.HIDDEN, onClick = { mode = DayOverrideMode.HIDDEN },
                        label = { Text("Ausblenden") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                if (mode == DayOverrideMode.TIMES) {
                    windows.forEachIndexed { index, window ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(shape = ControlShape, onClick = { pickTime(window.startMinute) { start ->
                                windows = windows.toMutableList().also { it[index] = window.copy(startMinute = start) } } },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Von ${RouteDayOverrides.time(window.startMinute)}")
                            }
                            OutlinedButton(shape = ControlShape, onClick = { pickTime(window.endMinute) { end ->
                                windows = windows.toMutableList().also { it[index] = window.copy(endMinute = end) } } },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Bis ${RouteDayOverrides.time(window.endMinute)}")
                            }
                            if (windows.size > 1) IconButton(onClick = { windows = windows.filterIndexed { i, _ -> i != index } },
                                modifier = Modifier.semantics { contentDescription = "Zeitfenster entfernen" }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                            }
                        }
                        when {
                            window.wholeDay -> Text("Ganztägig", style = MaterialTheme.typography.bodySmall)
                            window.overnight -> Text("Endet am Folgetag", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (windows.size < RouteDayOverrides.MAX_WINDOWS) {
                        TextButton(onClick = {
                            val last = windows.lastOrNull()?.endMinute ?: (9 * 60)
                            windows = windows + DayTimeWindow(last, (last + 60).coerceAtMost(23 * 60 + 59))
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Zeitfenster hinzufügen") }
                    }
                }
                if (failed) Text("Speichern nicht möglich. Prüfe Datum, Zeiten und die Anzahl deiner Ausnahmen.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                failed = !onConfirm(initial.copy(date = date, mode = mode,
                    windows = if (mode == DayOverrideMode.TIMES) windows else emptyList()))
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") } }
    )
}
