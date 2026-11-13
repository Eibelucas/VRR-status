package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.Instant

/**
 * "In der Nähe": the existing nearby lookup (same radius, filters and departure loader as
 * before), shown in its own tab. No second stop search.
 */
@Composable
internal fun NearbyOverview(home: HomeState, now: Instant, settings: AppSettings, savedStops: SavedStopsState,
    choosingKey: String?, choosing: Boolean, onLocation: () -> Unit, onRefresh: () -> Unit,
    onSetting: (String, String) -> Unit, onStop: (Stop) -> Unit, onFavorite: (Stop) -> Unit, onDeparture: (Departure) -> Unit) {
    var line by rememberSaveable { mutableStateOf<String?>(null) }
    val locationOff = settings.string("location_mode", "active") == "off"
    val lines = home.departures.map { it.line }.filter(String::isNotBlank).distinct()
        .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
    val shownLine = line?.takeIf { it in lines }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("In deiner Nähe", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            if (home.status == NearbyStatus.READY) IconButton(onClick = onRefresh, enabled = !home.loading, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, "Haltestellen und Abfahrten aktualisieren", tint = Lime)
            }
        }
        if (home.status != NearbyStatus.READY) {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when {
                        locationOff -> "Standort ist in Mitfahrt ausgeschaltet"
                        home.status == NearbyStatus.NO_PERMISSION -> "Standort nicht freigegeben"
                        home.status == NearbyStatus.LOCATION_OFF -> "Standort ist aus"
                        home.status == NearbyStatus.UNAVAILABLE -> "Standort gerade nicht verfügbar"
                        else -> "Standort wird gesucht …"
                    }, fontWeight = FontWeight.SemiBold)
                    Text("Oben kannst du jede Haltestelle auch selbst suchen.", color = Soft, fontSize = 13.sp)
                    when {
                        locationOff -> Unit
                        home.status == NearbyStatus.LOCATING -> AppLinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> Button(shape = ControlShape, onClick = onLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(when (home.status) {
                                NearbyStatus.NO_PERMISSION -> "Standort freigeben"
                                NearbyStatus.LOCATION_OFF -> "Standort einschalten"
                                else -> "Erneut versuchen"
                            })
                        }
                    }
                }
            }
            return@Column
        }
        // Wraps instead of scrolling sideways, so no filter is cut off at the edge.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val radius = settings.int("nearby_radius", 500)
            listOf(300, 500, 1000, 2000).forEach { value ->
                FilterChip(selected = radius == value, onClick = { onSetting("nearby_radius", value.toString()) },
                    label = { Text(if (value < 1000) "$value m" else "${value / 1000} km") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            val map = settings.string("nearby_view", "list") == "map"
            FilterChip(selected = map, onClick = { onSetting("nearby_view", if (map) "list" else "map") },
                label = { Text("Karte") }, leadingIcon = { Icon(Icons.Outlined.Map, null, Modifier.size(16.dp)) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
        if (lines.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = shownLine == null, onClick = { line = null }, label = { Text("Alle Linien") }, modifier = Modifier.heightIn(min = 48.dp))
            lines.forEach { value ->
                FilterChip(selected = shownLine == value, onClick = { line = if (shownLine == value) null else value },
                    label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        val observed = home.departures.mapNotNull { it.observedAt }.maxOrNull()
        Text(listOfNotNull("NEW-Abfahrten laut VRR", (observed ?: home.updatedAt)?.let { "Stand ${clockTime(it)}" },
            if (home.approximate) "ungefährer Standort, Gehzeit geschätzt" else null).joinToString(" · "), color = Soft, fontSize = 12.sp)
        if (home.loading || home.departuresLoading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        home.error?.let { Text(it, color = Amber, fontSize = 13.sp) }
        val stops = home.stops.filter { stop -> shownLine == null || home.departures.any { stopKey(it.stopId) == stopKey(stop.id) && it.line == shownLine } }
        if (settings.string("nearby_view", "list") == "map") {
            val pins = stops.mapNotNull { stop -> stop.point?.let { MapPin(it, shortName(stop.name), PinKind.STOP, stop.id) } } +
                listOfNotNull(home.point?.let { MapPin(it, "Dein Standort", PinKind.ME, "me") })
            PlainMap(emptyList(), pins, "Karte mit ${stops.size} Haltestellen in deiner Nähe", height = 300.dp,
                onPin = { pin -> stops.firstOrNull { it.id == pin.id }?.let(onStop) })
            Text("Haltestelle antippen für alle Abfahrten. Dein Standort wird nur auf dem Gerät gezeigt.", color = Soft, fontSize = 11.sp)
        }
        stops.forEach { stop -> key(stop.id) {
            NearbyStopRow(stop, savedStops.isFavorite(stop), onClick = { onStop(stop) }, onFavorite = { onFavorite(stop) })
            home.departures.filter { stopKey(it.stopId) == stopKey(stop.id) && (shownLine == null || it.line == shownLine) }
                .take(settings.int("departure_count", 5).coerceIn(1, 15)).forEach { departure -> key(departure.key) {
                    DepartureRow(departure, now, choosingKey == departure.key, !choosing) { onDeparture(departure) }
                } }
        } }
        if (stops.isEmpty() && !home.loading && home.error == null) Text(
            if (shownLine != null) "Linie $shownLine fährt gerade keine nahe Haltestelle an." else "Keine Haltestelle im eingestellten Umkreis.",
            color = Soft, fontSize = 14.sp)
    }
}

@Composable
private fun NearbyStopRow(stop: Stop, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    val walk = JourneyOptions.walking(stop)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Abfahrten anzeigen", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = Soft, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(shortName(stop.name), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(stop.distance?.let { "$it m" }, walk?.label).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = Soft)
                }
            }
        }
        IconToggleButton(checked = favorite, onCheckedChange = { onFavorite() }, modifier = Modifier.size(48.dp)) {
            Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (favorite) "${shortName(stop.name)} aus Favoriten entfernen" else "${shortName(stop.name)} merken", tint = if (favorite) Lime else Soft)
        }
    }
}
