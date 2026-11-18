package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.SearchState
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

@Composable
internal fun FullDepartureBoard(search: SearchState, now: Instant, onBack: () -> Unit, onRefresh: () -> Unit, onChoose: (Departure) -> Unit) {
    val stop = search.selectedStop ?: return
    BoardPage(shortName(stop.name), onBack, trailing = {
        IconButton(onClick = onRefresh, enabled = !search.loading) { Icon(Icons.Outlined.Refresh, "Aktualisieren") }
    }) {
        if (search.loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(listOfNotNull("Abfahrtstafel", search.departures.mapNotNull { it.observedAt }.maxOrNull()?.let { "Stand ${clockTime(it)}" })
                    .joinToString(" · "), color = Soft)
            }
            search.error?.let { item { Text(it, color = Amber) } }
            if (search.departures.isEmpty() && !search.loading && search.error == null) item { Text("Keine Abfahrten", color = Soft) }
            items(search.departures, key = { it.key }) { departure ->
                DepartureRow(departure, now, search.choosing == departure.key, !search.loading) { onChoose(departure) }
            }
        }
    }
}

@Composable
internal fun OfflineDepartureBoard(stop: Stop, now: Instant, store: OfflineBoards, fetch: suspend (Stop, LocalDate) -> OfflineBoard, onBack: () -> Unit) {
    val today = now.atZone(OfflineBoards.zone).toLocalDate()
    var chosenDay by rememberSaveable(stop.id) { mutableStateOf(today.toString()) }
    val day = LocalDate.parse(chosenDay).coerceIn(today, today.plusDays(6))
    var board by remember(stop.id, day) { mutableStateOf(store.get(stop, day)) }
    var error by remember(stop.id, day) { mutableStateOf<String?>(null) }
    var loading by remember(stop.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BoardPage(shortName(stop.name), onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Offline-Fahrplan", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = day > today && !loading, onClick = { chosenDay = day.minusDays(1).toString() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Vorheriger Tag")
                    }
                    Text(day.format(java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM.", java.util.Locale.GERMANY)))
                    IconButton(enabled = day < today.plusDays(6) && !loading, onClick = { chosenDay = day.plusDays(1).toString() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Nächster Tag")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !loading, onClick = {
                        error = null
                        loading = true
                        scope.launch {
                            try {
                                val loaded = fetch(stop, day)
                                store.save(stop, loaded)
                                board = loaded
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                error = "Fahrplan gerade nicht abrufbar."
                            } finally { loading = false }
                        }
                    }) { Text(if (board == null) "Offline speichern" else "Neu laden") }
                    if (board != null) IconButton(enabled = !loading, onClick = { store.remove(stop, day); board = null }) {
                        Icon(Icons.Outlined.DeleteOutline, "Offline-Fahrplan löschen")
                    }
                }
                if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Amber) }
                val saved = board
                Text(if (saved == null) "Für diesen Tag ist kein Fahrplan gespeichert."
                    else "Fahrplan · gespeichert am ${saved.savedAt.atZone(OfflineBoards.zone).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm"))}", color = Soft)
                if (saved?.reachedEnd == false) Text("Der gespeicherte Fahrplan deckt den Tag nur teilweise ab.", color = Amber)
                if (saved?.departures?.isEmpty() == true) Text("Keine gespeicherten Abfahrten", color = Soft)
            }
            items(board?.departures.orEmpty()) { departure ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineSign(departure.line)
                    Column(Modifier.weight(1f)) {
                        Text(shortName(departure.direction), fontWeight = FontWeight.Medium)
                        if (departure.platform.isNotBlank()) Text(departure.platform, color = Soft)
                    }
                    Text(clockTime(departure.time), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BoardPage(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 2)
                trailing()
            }
            content()
        }
    }
}
