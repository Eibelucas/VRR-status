package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeLayoutDialog(initial: List<String>, onApply: (List<String>) -> Unit, onClose: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(initial.joinToString(",")) }
    val selected = current.split(',').filter(String::isNotBlank)
    val labels = mapOf("planned" to "Eigene Routen", "school" to "Schulwege", "suggestions" to "Streckenvorschläge",
        "last" to "Letzte Fahrt", "map" to "Karte")
    AlertDialog(onDismissRequest = onClose, title = { Text("Startseite anpassen") }, text = {
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            (selected + labels.keys).distinct().forEach { key ->
                val label = labels[key] ?: return@forEach
                val index = selected.indexOf(key)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).heightIn(min = 52.dp).toggleable(index >= 0, role = Role.Checkbox) { active ->
                        current = (if (active) selected + key else selected - key).joinToString(",")
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = index >= 0, onCheckedChange = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (index >= 0) {
                        fun move(delta: Int) {
                            val order = selected.toMutableList()
                            java.util.Collections.swap(order, index, index + delta)
                            current = order.joinToString(",")
                        }
                        IconButton(onClick = { move(-1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "$label nach oben") }
                        IconButton(onClick = { move(1) }, enabled = index < selected.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "$label nach unten") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onApply(selected) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Abbrechen") } })
}
