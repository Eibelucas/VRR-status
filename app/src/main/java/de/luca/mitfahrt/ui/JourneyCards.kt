package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.AlternativesState
import de.luca.mitfahrt.data.*
import java.time.Instant

@Composable
fun SchoolTripCard(trips: List<SchoolTrip>, now: Instant, onSelect: (SchoolTrip) -> Unit, onRemove: (SchoolDirection) -> Unit) {
    if (trips.isEmpty()) return
    val preferred = SchoolTrips.preferred(now)
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.School, null, tint = Lime)
                Spacer(Modifier.width(10.dp)); Text("Dein Schulweg", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            }
            trips.sortedBy { it.direction != preferred }.forEach { trip ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(trip.direction.label, fontWeight = FontWeight.SemiBold)
                        Text("${trip.line} · ${shortName(trip.boarding.name)} → ${shortName(trip.targetName)}", fontSize = 13.sp, color = Soft)
                    }
                    IconButton(onClick = { onRemove(trip.direction) }) { Icon(Icons.Outlined.Close, "${trip.direction.label} entfernen", tint = Soft) }
                }
                if (trip.direction == preferred) Button(onClick = { onSelect(trip) }, modifier = Modifier.fillMaxWidth()) { Text("Abfahrten ${trip.direction.label.replaceFirstChar { it.lowercase() }}") }
                else OutlinedButton(onClick = { onSelect(trip) }, modifier = Modifier.fillMaxWidth()) { Text("Abfahrten ${trip.direction.label.replaceFirstChar { it.lowercase() }}") }
            }
        }
    }
}

@Composable
fun ExitTargetCard(ride: Ride, onTarget: (Int?) -> Unit, onSave: (SchoolDirection) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    val target = ride.exitTargetStop()
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dein Ausstieg", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(target?.let { shortName(it.name) } ?: "Ausstieg wählen")
            }
            if (target != null) {
                Text(if (target.cancelled) "Dieser Halt entfällt. Bitte ein anderes Ziel wählen."
                    else "Vibration einen Halt vorher · nach Fahrtdaten", fontSize = 12.sp, color = if (target.cancelled) Amber else Soft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SchoolDirection.entries.forEach { direction ->
                        TextButton(onClick = { onSave(direction) }, enabled = !target.cancelled, modifier = Modifier.weight(1f)) {
                            Text(if (direction == SchoolDirection.TO_SCHOOL) "Als Hinweg merken" else "Als Rückweg merken")
                        }
                    }
                }
                TextButton(onClick = { onTarget(null) }) { Text("Ausstiegsalarm ausschalten", color = Soft) }
            }
        }
    }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Wo steigst du aus?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                itemsIndexed(ride.stops.drop(ride.boardingIndex + 1)) { index, stop ->
                    TextButton(onClick = { onTarget(ride.boardingIndex + 1 + index); picker = false }, enabled = !stop.cancelled,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(shortName(stop.name))
                            Text(if (stop.cancelled) "Halt entfällt" else clockTime(stop.effective(ride.offsetSeconds)), fontSize = 12.sp, color = Soft)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = false }) { Text("Abbrechen") } })
}

@Composable
fun AlternativesCard(ride: Ride, state: AlternativesState, now: Instant, onRefresh: () -> Unit, onChoose: (Departure) -> Unit) {
    val options = if (state.forJourney == ride.departure.key) JourneyOptions.alternatives(ride.departure, state.stops, state.departures, now) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Andere Verbindung", fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Alternativen aktualisieren", tint = Lime) }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
