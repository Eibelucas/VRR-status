package de.luca.mitfahrt.ui

import android.app.DatePickerDialog
import android.app.Dialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import de.luca.mitfahrt.data.NrwSchoolHolidays
import de.luca.mitfahrt.data.SchoolCalendar
import de.luca.mitfahrt.data.SchoolCalendarSettings
import java.time.LocalDate
import java.time.ZoneId

/** School calendar for NRW. Data from `SchoolCalendarStore.state`, actions via callbacks. */
@Composable
fun SchoolCalendarScreen(
    settings: SchoolCalendarSettings,
    today: LocalDate,
    onEnabled: (Boolean) -> Unit,
    onAddFreeDay: (LocalDate, String) -> Boolean,
    onRemoveFreeDay: (LocalDate) -> Unit,
    onMarkSchoolDay: (LocalDate) -> Boolean,
    onRemoveSchoolDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf<LocalDate?>(null) }
    var addingFree by remember { mutableStateOf<LocalDate?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    val dialogs = remember(context) { mutableListOf<Dialog>() }
    DisposableEffect(context) {
        onDispose { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }
    }

    fun pickDate(onPicked: (LocalDate) -> Unit) {
        val dialog = DatePickerDialog(context, { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            today.year, today.monthValue - 1, today.dayOfMonth).apply {
            datePicker.minDate = LocalDate.of(2000, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate = LocalDate.of(2200, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Schulkalender", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = settings.enabled, onCheckedChange = onEnabled,
                    modifier = Modifier.semantics { contentDescription = "Schulkalender verwenden" })
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = SchoolCalendar.status(today, settings)
                Text("Heute", style = MaterialTheme.typography.titleMedium)
                Text(status.text())
                OutlinedButton(onClick = { pickDate { checked = it } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Datum prüfen")
                }
                checked?.let { date ->
                    val result = SchoolCalendar.status(date, settings)
                    Text(SchoolCalendar.dateLabel(date), style = MaterialTheme.typography.bodyLarge)
                    Text((if (result.free) "Frei · " else "") + result.text(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (settings.enabled) AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nächste freie Tage", style = MaterialTheme.typography.titleMedium)
                val periods = SchoolCalendar.freePeriods(today, 120, settings).take(8)
                if (periods.isEmpty()) Text("Keine in den nächsten Monaten", color = MaterialTheme.colorScheme.onSurfaceVariant)
                periods.forEach { period ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(period.label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(SchoolCalendar.periodLabel(period), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (today.plusDays(120).isAfter(NrwSchoolHolidays.coverageEnd))
                    Text("Ferientermine nach ${SchoolCalendar.dateLabel(NrwSchoolHolidays.coverageEnd)} fehlen noch",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Eigene freie Tage", style = MaterialTheme.typography.titleMedium)
                settings.freeDays.entries.filter { !it.key.isBefore(today) }.sortedBy { it.key }.forEach { (date, label) ->
                    DayRow(SchoolCalendar.dateLabel(date), label.ifBlank { null }) { onRemoveFreeDay(date) }
                }
                OutlinedButton(onClick = { pickDate { addingFree = it } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Freien Tag hinzufügen")
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Trotzdem Schultag", style = MaterialTheme.typography.titleMedium)
                settings.schoolDays.filter { !it.isBefore(today) }.sorted().forEach { date ->
                    DayRow(SchoolCalendar.dateLabel(date), null) { onRemoveSchoolDay(date) }
                }
                OutlinedButton(onClick = { pickDate { actionFailed = !onMarkSchoolDay(it) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Schultag markieren")
                }
                if (actionFailed) Text("Der Schultag konnte nicht gespeichert werden.", color = MaterialTheme.colorScheme.error)
            }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (showAdvanced) "Datenquelle ausblenden" else "Erweitert: Datenquelle")
        }
        if (showAdvanced) AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(NrwSchoolHolidays.SOURCE_TITLE, style = MaterialTheme.typography.bodyMedium)
                Text(NrwSchoolHolidays.SOURCE_URL, style = MaterialTheme.typography.bodySmall)
                Text("Stand der Regelung: ${SchoolCalendar.dateLabel(NrwSchoolHolidays.SOURCE_AMENDED)}", style = MaterialTheme.typography.bodySmall)
                Text("Abgerufen: ${SchoolCalendar.dateLabel(NrwSchoolHolidays.RETRIEVED)}", style = MaterialTheme.typography.bodySmall)
                Text("Ferien bekannt: ${SchoolCalendar.dateLabel(NrwSchoolHolidays.coverageStart)} bis " +
                    SchoolCalendar.dateLabel(NrwSchoolHolidays.coverageEnd), style = MaterialTheme.typography.bodySmall)
                Text("Bewegliche Ferientage legt jede Schule selbst fest – bitte als eigene freie Tage eintragen.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Feiertage werden berechnet und funktionieren ohne Internet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    addingFree?.let { date ->
        var label by remember(date) { mutableStateOf("") }
        var failed by remember(date) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { addingFree = null },
            title = { Text(SchoolCalendar.dateLabel(date)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = label, onValueChange = { label = it.take(SchoolCalendar.MAX_LABEL) },
                        singleLine = true, label = { Text("Bezeichnung (optional)") }, modifier = Modifier.fillMaxWidth())
                    if (failed) Text("Konnte nicht gespeichert werden.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { if (onAddFreeDay(date, label)) addingFree = null else failed = true },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Hinzufügen") }
            },
            dismissButton = { TextButton(onClick = { addingFree = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun DayRow(title: String, subtitle: String?, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "$title entfernen" }) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
        }
    }
}
