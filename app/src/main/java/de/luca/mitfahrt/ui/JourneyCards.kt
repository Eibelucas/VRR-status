package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.AlternativesState
import de.luca.mitfahrt.data.*
import java.time.Instant

@Composable
fun SchoolTripCard(trips: List<SchoolTrip>, home: HomeState, now: Instant, busy: Boolean,
    onSelect: (SchoolTrip) -> Unit, onRemove: (SchoolDirection) -> Unit, onStart: (SchoolTrip, Departure) -> Unit) {
    if (trips.isEmpty()) return
    val preferred = SchoolTrips.preferred(now)
    var selected by rememberSaveable(preferred) { mutableStateOf(preferred.name) }
    var menu by remember { mutableStateOf(false) }
    val trip = trips.firstOrNull { it.direction.name == selected } ?: trips.first()
    val next = if (home.status == NearbyStatus.READY) RouteHistory.suggestions(listOf(trip.route()), home.stops,
        home.departures.filter { d -> home.stops.firstOrNull { stopKey(it.id) == stopKey(d.stopId) }?.let {
            JourneyOptions.reachability(it, d, now).isPossible } == true }, now).firstOrNull() else null
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.School, null, tint = Lime)
                Spacer(Modifier.width(10.dp)); Text("Dein Schulweg", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Schulweg verwalten", tint = Soft) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("${trip.direction.label} entfernen") }, onClick = { menu = false; onRemove(trip.direction) })
                    }
                }
            }
            if (trips.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trips.sortedBy { it.direction.ordinal }.forEach { saved ->
                    FilterChip(selected = trip.direction == saved.direction, onClick = { selected = saved.direction.name }, label = { Text(saved.direction.label) })
                }
            } else Text(trip.direction.label, color = Lime, fontSize = 13.sp)
            Text("${trip.line} → ${shortName(trip.targetName)}", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            Text("Ab ${shortName(next?.stop?.name ?: trip.boarding.name)}", fontSize = 13.sp, color = Soft)
            if (next != null) {
                Text(if (next.departure.liveEstimate(now) != null) "Abfahrt in ${countdownUntil(next.departure.effective, now)}"
                    else "Fahrplan ${clockTime(next.departure.planned)}", fontWeight = FontWeight.Bold, color = Lime, fontSize = 24.sp)
                JourneyOptions.walking(next.stop)?.let { Text(it.label, color = Soft, fontSize = 12.sp) }
                Button(shape = ControlShape, onClick = { onStart(trip, next.departure) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Text(if (busy) "Fahrt wird geladen …" else "Diesen Bus verfolgen")
                }
                TextButton(onClick = { onSelect(trip) }) { Text("Weitere Abfahrten") }
            } else Button(shape = ControlShape, onClick = { onSelect(trip) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Abfahrten anzeigen") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExitTargetCard(ride: Ride, exitEnabled: Boolean, onTarget: (Int?) -> Unit, onSave: (SchoolDirection) -> Unit) {
    var picker by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val target = ride.exitTargetStop()
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (exitEnabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, null, tint = Lime)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Dein Ausstieg", color = Soft, fontSize = 12.sp)
                Text(target?.let { shortName(it.name) } ?: "Noch kein Ziel gewählt", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                if (target != null) Text(when {
                    target.cancelled -> "Halt entfällt"
                    !exitEnabled -> "Ausstiegshinweis ausgeschaltet"
                    else -> "Hinweis einen Halt vorher"
                }, color = if (target.cancelled) Amber else Soft, fontSize = 12.sp)
            }
            TextButton(onClick = { query = ""; picker = true }) { Text(if (target == null) "Wählen" else "Ändern") }
            if (target != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Ziel und Schulweg verwalten", tint = Soft) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    SchoolDirection.entries.forEach { direction ->
                        DropdownMenuItem(text = { Text(if (direction == SchoolDirection.TO_SCHOOL) "Als Hinweg merken" else "Als Rückweg merken") },
                            onClick = { menu = false; onSave(direction) }, enabled = !target.cancelled)
                    }
                    DropdownMenuItem(text = { Text("Ziel entfernen") }, onClick = { menu = false; onTarget(null) })
                }
            }
        }
    }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().imePadding()) {
            Text("Wo steigst du aus?", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Haltestelle filtern") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Spacer(Modifier.height(8.dp))
            val stops = ride.stops.withIndex().filter { it.index > ride.boardingIndex && SavedStops.matches(Stop(it.value.id, it.value.name), query) }
            LazyColumn(Modifier.heightIn(max = 420.dp).fillMaxWidth()) {
                itemsIndexed(stops, key = { _, entry -> entry.index }) { _, entry ->
                    TextButton(onClick = { onTarget(entry.index); picker = false }, enabled = !entry.value.cancelled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(shortName(entry.value.name), modifier = Modifier.fillMaxWidth())
                            Text(if (entry.value.cancelled) "Halt entfällt" else clockTime(entry.value.effective(ride.offsetSeconds)),
                                fontSize = 12.sp, color = Soft, modifier = Modifier.fillMaxWidth())
                        }
                        if (entry.index == ride.exitTargetIndex()) Icon(Icons.Outlined.Check, "Ausgewählt")
                    }
                }
                if (stops.isEmpty()) item { Text("Kein passender Halt im Fahrtverlauf.", modifier = Modifier.padding(vertical = 20.dp), color = Soft) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun AlternativesCard(ride: Ride, state: AlternativesState, now: Instant, onRefresh: () -> Unit, onChoose: (Departure) -> Unit) {
    val options = if (state.forJourney == ride.departure.key) JourneyOptions.alternatives(ride.departure, state.stops, state.departures, now) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Andere Verbindung", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Alternativen aktualisieren", tint = Lime) }
        }
        if (state.loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
        options.forEach { option ->
            DepartureRow(option.departure, now, false, true, showStop = true, distance = option.stop.distance) { onChoose(option.departure) }
            Text(listOfNotNull(option.reachability.walking?.label, option.reachability.label).joinToString(" · "), fontSize = 12.sp, color = Soft)
        }
        if (options.isEmpty() && !state.loading) Text("Keine passende Direktverbindung gefunden. Du kannst eine andere Haltestelle suchen.", color = Soft, fontSize = 13.sp)
        if (options.isNotEmpty() && ride.exitTargetStop() != null)
            Text("Dein Ausstieg wird beim Auswählen geprüft.", color = Soft, fontSize = 12.sp)
    }
}

@Composable
fun WalkingHint(stop: Stop, departure: Departure, now: Instant) {
    val reachability = JourneyOptions.reachability(stop, departure, now)
    if (reachability.walking != null && !departure.cancelled) Text(reachability.label,
        color = if (reachability.isPossible) Lime else Amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}
