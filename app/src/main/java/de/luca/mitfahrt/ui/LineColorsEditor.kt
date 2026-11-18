package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.data.TransitColors
import de.luca.mitfahrt.data.TripResultPreferences

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LineColorsEditor(value: String, onChange: (String) -> Unit) {
    var line by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(TransitColors.palette.first().second) }
    val colors = remember(value) { TransitColors.decode(value) }
    val key = TripResultPreferences.lines(line).singleOrNull()?.let(TripResultPreferences::lineKey)
    val validColor = Regex("#[0-9a-fA-F]{6}").matches(color)
    Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(line, { line = it.take(14) }, label = { Text("Linie") },
            placeholder = { Text("017") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TransitColors.palette.forEach { (name, hex) ->
                FilterChip(color.equals(hex, true), { color = hex }, label = { Text(name) }, leadingIcon = {
                    Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape))
                })
            }
        }
        OutlinedTextField(color, { color = it.take(7) }, label = { Text("Farbcode") },
            isError = !validColor, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = key != null && validColor && (key in colors || colors.size < 40), onClick = {
            if (key != null) {
                onChange(TransitColors.encode(colors + (key to color)))
                line = ""
            }
        }) { Text("Farbe hinzufügen") }
        colors.forEach { (id, hex) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(Color(android.graphics.Color.parseColor(hex)), CircleShape))
                Text("Linie $id", Modifier.weight(1f).padding(start = 10.dp))
                IconButton(onClick = { onChange(TransitColors.encode(colors - id)) }) {
                    Icon(Icons.Outlined.Close, "Farbe für $id entfernen")
                }
            }
        }
    }
}
