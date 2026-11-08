package de.luca.mitfahrt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.data.ArrivalHistory
import de.luca.mitfahrt.data.ArrivalMark
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.shortName
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Overview of the user's own arrival marks (e.g. `ArrivalStore.marks`). Times are shown as
 * recorded and cannot be edited here. [originLabel] can be replaced by the caller; the
 * default uses the stored method. Deletion is confirmed because ArrivalStore offers no restore.
 */
@Composable
fun ArrivalHistoryScreen(
    marks: List<ArrivalMark>,
    today: LocalDate,
    onDelete: (ArrivalMark) -> Unit,
    modifier: Modifier = Modifier,
    originLabel: (ArrivalMark) -> String? = ArrivalHistory::originLabel
) {
    var stop by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ArrivalMark?>(null) }
    val stops = ArrivalHistory.stops(marks)
    val activeStop = stop?.takeIf { key -> stops.any { it.key == key } }
    val days = ArrivalHistory.byDay(marks, activeStop)

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Eigene Ankünfte", style = MaterialTheme.typography.titleLarge)
                if (stops.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = activeStop == null, onClick = { stop = null }, label = { Text("Alle") },
                        modifier = Modifier.heightIn(min = 48.dp))
                    stops.forEach { option ->
                        FilterChip(selected = activeStop == option.key, onClick = { stop = option.key },
                            label = { Text(shortName(option.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        if (days.isEmpty()) item {
            Text("Noch keine eigenen Ankünfte", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp))
        }
        days.forEach { (day, entries) ->
            item(key = "day-$day") {
                Text(dayLabel(day, today), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(entries, key = { it.id }) { mark ->
                val name = shortName(mark.stopName)
                val origin = originLabel(mark)
                AppCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull("${clockTime(mark.at)} Uhr", origin).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { pendingDelete = mark },
                            modifier = Modifier.semantics { contentDescription = "Eintrag $name ${clockTime(mark.at)} löschen" }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        }
                    }
                }
            }
        }
        if (days.isNotEmpty()) item {
            Text("Persönliche Aufzeichnung, keine Bestätigung durch NEW", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    pendingDelete?.let { mark ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Eintrag löschen?") },
            text = { Text("${shortName(mark.stopName)}, ${dayLabel(mark.at.atZone(ArrivalHistory.zone).toLocalDate(), today)} ${clockTime(mark.at)} Uhr") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(mark) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") } }
        )
    }
}

private val arrivalDayFormat = DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Heute"
    today.minusDays(1) -> "Gestern"
    else -> arrivalDayFormat.format(date)
}
