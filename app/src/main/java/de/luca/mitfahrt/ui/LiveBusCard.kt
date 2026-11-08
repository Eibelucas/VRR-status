package de.luca.mitfahrt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.LiveBusState
import de.luca.mitfahrt.data.NewVehicle
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.Stop
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.shortName
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/** A position is shown only for an identified departure or a vehicle the user explicitly chose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveBusCard(ride: Ride, state: LiveBusState, now: Instant, onSelect: (NewVehicle) -> Unit,
    onClear: () -> Unit, onRefresh: () -> Unit) {
    if (ride.demo || ride.boarded || ride.departure.cancelled || state.journeyKey != ride.departure.key) return
    var chooseVehicle by rememberSaveable(ride.departure.key) { mutableStateOf(false) }
    val boarding = ride.stops.getOrNull(ride.boardingIndex)?.let { Stop(it.id, it.name, it.point) }
    val candidates = state.candidates.filter { it.isFresh(now) }.distinctBy { it.id }
    val selected = state.vehicle?.takeIf { state.confirmed || state.selectedVehicleId == it.id }
    val current = selected?.takeIf { it.isFresh(now) }
    val stale = selected?.takeUnless { it.isFresh(now) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Busposition", fontWeight = FontWeight.SemiBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, "Busposition aktualisieren", tint = Lime)
            }
        }
        if (current != null) {
            Text(if (state.confirmed) "Deiner Abfahrt zugeordnet" else "Ausgewähltes Linienfahrzeug",
                color = if (state.confirmed) Lime else Soft, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (!state.confirmed) Text("Zuordnung zu deiner Abfahrt nicht bestätigt.",
                color = Amber, fontSize = 12.sp, lineHeight = 18.sp)
            if (current.destination.isNotBlank()) Text("${if (current.directionReliable) "Richtung" else "Anzeige am Bus"} ${shortName(current.destination)}",
                color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            WaitingBusMap(current.point, boarding, "Linie ${current.line} · Fahrzeug ${current.id}",
                current.observedAt, now)
            Text("Quelle: NEW · ${vehicleAge(current.observedAt, now)}", color = Soft, fontSize = 12.sp)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.DirectionsBus, null, tint = Lime)
                    Text(when {
                        stale != null -> "Positionsdaten veraltet"
                        state.selectedVehicleId != null -> "Keine aktuelle Fahrzeugposition"
                        state.loading -> "Busposition wird gesucht …"
                        candidates.isNotEmpty() -> "Linienfahrzeug auswählen"
                        else -> "Keine Live-Position verfügbar"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    when {
                        stale != null -> Text("Fahrzeug ${stale.id} · letzter Stand ${clockTime(stale.observedAt)}",
                            color = Soft, fontSize = 13.sp)
                        state.selectedVehicleId != null -> Text("Ausgewählt: Fahrzeug ${state.selectedVehicleId}",
                            color = Soft, fontSize = 13.sp)
                        candidates.isNotEmpty() -> Text("Für deine Linie gibt es aktuelle Positionen. Wähle das Fahrzeug nach Linie und Richtung.",
                            color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                        !state.loading && state.error == null -> Text("NEW meldet gerade keine zuordenbare Position.",
                            color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (candidates.isEmpty()) OutlinedButton(onClick = onRefresh, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Erneut laden") }
                }
            }
        }
        state.error?.let { Text(it, color = Amber, fontSize = 13.sp, lineHeight = 19.sp) }
        if (candidates.isNotEmpty() || state.selectedVehicleId != null) {
            if (current == null && state.selectedVehicleId == null) Button(onClick = { chooseVehicle = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Fahrzeug auswählen") }
            else TextButton(onClick = { onClear(); chooseVehicle = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Fahrzeug wechseln")
            }
        }
        if (state.selectedVehicleId != null) TextButton(onClick = onClear,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Auswahl aufheben") }
    }

    if (chooseVehicle) ModalBottomSheet(onDismissRequest = { chooseVehicle = false }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fahrzeug auswählen", style = MaterialTheme.typography.headlineSmall)
            Text("Linie und Richtung vergleichen. Die Abfahrt ist damit nicht bestätigt.",
                color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(candidates, key = { it.id }) { vehicle ->
                    VehicleChoiceRow(vehicle, boarding, now, state.selectedVehicleId == vehicle.id) {
                        chooseVehicle = false
                        onSelect(vehicle)
                    }
                }
                if (candidates.isEmpty()) item {
                    Text("Keine aktuellen Positionsdaten.", color = Soft, modifier = Modifier.padding(vertical = 16.dp))
                }
            }
            TextButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Aktualisieren")
            }
        }
    }
}

@Composable
private fun VehicleChoiceRow(vehicle: NewVehicle, boarding: Stop?, now: Instant, selected: Boolean, onSelect: () -> Unit) {
    ListItem(headlineContent = {
        Text("Linie ${vehicle.line} · Fahrzeug ${vehicle.id}", fontWeight = FontWeight.SemiBold)
    }, supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (vehicle.destination.isBlank()) "Richtung nicht gemeldet" else "${if (vehicle.directionReliable) "Richtung" else "Anzeige am Bus"} ${shortName(vehicle.destination)}",
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val distance = boarding?.point?.distanceTo(vehicle.point)?.takeIf { it.isFinite() }
            Text(listOfNotNull(distance?.let { "ca. ${vehicleDistance(it)} Luftlinie zum Einstieg" },
                "NEW · ${vehicleAge(vehicle.observedAt, now)}").joinToString(" · "), fontSize = 12.sp)
        }
    }, leadingContent = { Icon(Icons.Outlined.DirectionsBus, null, tint = Lime) },
        trailingContent = { if (selected) Icon(Icons.Outlined.Check, "Ausgewählt", tint = Lime) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Panel, headlineColor = Paper, supportingColor = Soft))
}

private fun vehicleAge(observedAt: Instant, now: Instant): String {
    val seconds = Duration.between(observedAt, now).seconds.coerceAtLeast(0)
    return if (seconds < 60) "vor $seconds s" else "vor ${seconds / 60} Min"
}

private fun vehicleDistance(metres: Double): String = if (metres < 1000) "${metres.roundToInt()} m"
    else String.format(Locale.GERMANY, "%.1f km", metres / 1000)
