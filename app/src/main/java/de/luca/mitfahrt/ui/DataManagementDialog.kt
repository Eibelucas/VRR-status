package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.RideViewModel

@Composable
fun DataManagementDialog(vm: RideViewModel, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    val areas = listOf("history" to "Fahrthistorie", "suggestions" to "Vorschlagszähler", "trip_history" to "Suchverlauf der Verbindungssuche",
        "trip_places" to "Gespeicherte Ziele und Suchverlauf", "arrivals" to "Eigene Ankünfte",
        "reports" to "Alle Belege", "map" to "Kartenspeicher")
    AlertDialog(onDismissRequest = onClose, title = { Text("Gespeicherte Daten") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(areas) { area -> OutlinedButton(onClick = { selected = area }, modifier = Modifier.fillMaxWidth()) { Text("${area.second} löschen") } }
            item { Text("Favoriten und Routen werden dabei behalten; gespeicherte Ziele nur, wenn du sie nicht ausdrücklich löschst.", color = Soft) }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } })
    selected?.let { area -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("${area.second} löschen?") },
        text = { Text("Diese Daten werden auf diesem Gerät gelöscht.") },
        confirmButton = { TextButton(onClick = { vm.clearData(area.first); selected = null }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { selected = null }) { Text("Abbrechen") } }) }
}
