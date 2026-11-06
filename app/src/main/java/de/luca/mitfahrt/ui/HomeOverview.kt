package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeOverview(home: HomeState, routes: List<FrequentRoute>, now: Instant, choosing: Boolean, choosingKey: String?,
    onSearch: () -> Unit, onLocation: () -> Unit, onRefresh: () -> Unit, onStop: (Stop) -> Unit,
    onAllStops: () -> Unit, onRoute: (FrequentRoute) -> Unit, onDeparture: (Departure) -> Unit, onClearHistory: () -> Unit) {
    var allRoutes by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val matches = if (home.status == NearbyStatus.READY) RouteHistory.suggestions(routes, home.stops, home.departures, now) else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onSearch).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null, tint = Lime)
            Spacer(Modifier.width(12.dp)); Text("Haltestelle suchen", color = Soft)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("In deiner Nähe", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !home.loading) { Icon(Icons.Outlined.Refresh, "Haltestellen aktualisieren", tint = Lime) }
        }
        if (home.status != NearbyStatus.READY) {
            Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when (home.status) {
                        NearbyStatus.NO_PERMISSION -> "Haltestellen automatisch finden"
                        NearbyStatus.LOCATION_OFF -> "Standort einschalten"
                        NearbyStatus.UNAVAILABLE -> "Standort gerade nicht verfügbar"
                        else -> "Standort wird gesucht …"
                    }, fontWeight = FontWeight.SemiBold)
                    if (home.status == NearbyStatus.LOCATING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Button(onClick = onLocation) { Text(when (home.status) {
                        NearbyStatus.NO_PERMISSION -> "Standort erlauben"
                        NearbyStatus.LOCATION_OFF -> "Standort öffnen"
                        else -> "Erneut versuchen"
                    }) }
                }
            }
        }
        if (home.approximate) Text("Ungefährer Standort", color = Soft, fontSize = 12.sp)
        if (home.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        home.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
        home.stops.take(4).forEach { stop -> key(stop.id) { StopRow(stop) { onStop(stop) } } }
        if (home.status == NearbyStatus.READY && home.stops.isEmpty() && !home.loading && home.error == null)
            Text("Keine Haltestelle im Umkreis von 700 m gefunden.", color = Soft, fontSize = 14.sp)
        if (home.stops.size > 4) TextButton(onClick = onAllStops) { Text("Alle Haltestellen") }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Deine Strecken", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (routes.isNotEmpty()) TextButton(onClick = { allRoutes = true }) { Text("Alle") }
        }
        if (routes.isEmpty()) Text("Deine gewählten Fahrten erscheinen beim nächsten Mal hier.", color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
        else if (home.status == NearbyStatus.READY) {
            if (home.departuresLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            matches.take(4).forEach { match -> key(match.route.key) {
                DepartureRow(match.departure, now, choosingKey == match.departure.key, !choosing,
                    showStop = true, distance = match.stop.distance) { onDeparture(match.departure) }
            } }
            if (matches.isEmpty() && !home.departuresLoading && !home.loading)
                Text(if (home.error != null) "Öffne eine Strecke, um die Abfahrten erneut zu laden."
                    else "Gerade keine passende Abfahrt in deiner Nähe.", color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
        } else routes.take(3).forEach { route -> HistoryRow(route) { onRoute(route) } }
    }
    if (allRoutes) ModalBottomSheet(onDismissRequest = { allRoutes = false }, containerColor = Ink) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Deine Strecken", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(routes, key = { it.key }) { route -> HistoryRow(route) { allRoutes = false; onRoute(route) } }
            }
            TextButton(onClick = { confirmClear = true }) { Text("Streckenverlauf löschen", color = Soft) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Streckenverlauf löschen?") },
        text = { Text("Deine Berichte und Ankünfte bleiben erhalten.") },
        confirmButton = { TextButton(onClick = { onClearHistory(); confirmClear = false; allRoutes = false }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } })
}

@Composable
private fun HistoryRow(route: FrequentRoute, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(route.visit.line, color = Ink, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Lime).padding(horizontal = 10.dp, vertical = 8.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortName(route.visit.destination), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("Ab ${shortName(route.visit.stop.name)}", color = Soft, fontSize = 12.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Soft)
    }
}
