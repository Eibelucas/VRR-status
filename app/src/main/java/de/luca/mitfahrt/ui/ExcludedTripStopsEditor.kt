package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.data.Stop
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.stopKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
internal fun ExcludedTripStopsEditor(stops: Map<String, String>, find: suspend (String) -> List<Stop>,
    onChange: (Map<String, String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<Stop>()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        matches = emptyList()
        message = null
        val term = query.trim()
        if (term.length < 2) return@LaunchedEffect
        delay(300)
        try {
            matches = find(term).take(5)
            if (matches.isEmpty()) message = "Keine Haltestelle gefunden"
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            message = "Haltestellen gerade nicht verfügbar"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query, { query = it.take(80) }, label = { Text("Haltestelle meiden") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = stops.size < 20)
        message?.let { Text(it, color = Soft, style = MaterialTheme.typography.bodySmall) }
        matches.filter { stopKey(it.id) !in stops }.forEach { stop ->
            TextButton(onClick = {
                onChange(stops + (stopKey(stop.id) to stop.name))
                query = ""
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(shortName(stop.name)) }
        }
        stops.forEach { (id, name) ->
            InputChip(selected = true, onClick = { onChange(stops - id) },
                label = { Text(shortName(name)) },
                trailingIcon = { Icon(Icons.Outlined.Close, "Aus Ausschlüssen entfernen") },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}
