package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.Instant
import java.util.Locale

/**
 * Preview of a connection. Nothing here starts a ride or writes the archive; only
 * "Fahrt begleiten" does, through the caller.
 */
@Composable
fun JourneyDetailsScreen(journey: TripJourney, results: TripResultsState, now: Instant, onBack: () -> Unit,
    onAccompany: () -> Unit, accompanyBusy: Boolean, routeFor: (String) -> RoutePlan?, onSaveRoute: (RoutePlan) -> Unit,
    destinationSaved: Boolean, onSaveDestination: (() -> Unit)?) {
    BackHandler(onBack = onBack)
    val transfers = TripTransfers.assess(journey, now)
    val fresh = now.epochSecond - journey.fetchedAt.epochSecond in -30..120
    var routeName by remember { mutableStateOf<String?>(null) }
    val canSaveRoute = remember(journey) { routeFor("") != null }
    val trackable = journey.transit.firstOrNull()?.asDeparture(journey.fetchedAt) != null
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück zu den Verbindungen") }
                Column(Modifier.weight(1f)) {
                    Text("Verbindung ansehen", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(listOfNotNull("VRR", "Stand ${clockTime(journey.fetchedAt)}",
                        when { !fresh -> "veralteter Datenstand"; journey.realtime -> "mit Live-Daten"; else -> "nur Fahrplan" }).joinToString(" · "), color = Soft, fontSize = 12.sp)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { DetailSummary(journey, fresh) }
                item { JourneyMapCard(journey) }
                journey.legs.forEachIndexed { index, leg ->
                    item(key = "leg/$index") {
                        when (leg.kind) {
                            LegKind.TRANSIT -> TransitLegCard(leg, fresh)
                            LegKind.WALK -> WalkLegRow(leg, isFirst = index == 0, isLast = index == journey.legs.lastIndex)
                            LegKind.OTHER -> if (leg.productName.isNotBlank()) Text(leg.productName, color = Soft, fontSize = 13.sp)
                        }
                    }
                    transfers.firstOrNull { it.fromLeg == index }?.let { transfer ->
                        item(key = "transfer/$index") { TransferNote(transfer, journey.legs[transfer.toLeg]) }
                    }
                }
                if (results.query?.let { now.epochSecond - journey.fetchedAt.epochSecond > 120 } == true) item {
                    Text("Diese Angaben sind älter als zwei Minuten. Aktualisieren in der Liste.", color = Amber, fontSize = 12.sp)
                }
            }
            Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccompany, enabled = !accompanyBusy && trackable && !journey.cancelled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (accompanyBusy) { AppCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (accompanyBusy) "Wird geladen …" else "Fahrt begleiten")
                }
                if (!trackable) Text("Für diese Verbindung fehlt eine VRR-Fahrtkennung; sie kann nur angesehen werden.", color = Soft, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onSaveDestination != null && !destinationSaved) OutlinedButton(onClick = onSaveDestination,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Ziel speichern", maxLines = 1) }
                    if (canSaveRoute) OutlinedButton(onClick = { routeName = "" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Als Route speichern", maxLines = 1)
                    }
                }
            }
        }
    }
    routeName?.let { name ->
        AlertDialog(onDismissRequest = { routeName = null }, title = { Text("Route speichern") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { routeName = it.take(40) }, singleLine = true,
                        label = { Text("Name, z. B. Schule") }, modifier = Modifier.fillMaxWidth())
                    Text("Gespeichert wird die direkte NEW-Fahrt zwischen den beiden Haltestellen. Zeiten und Tage stellst du danach unter Routen ein.",
                        color = Soft, fontSize = 12.sp, lineHeight = 17.sp)
                }
            },
            confirmButton = { TextButton(onClick = { routeFor(name)?.let(onSaveRoute); routeName = null }) { Text("Speichern") } },
            dismissButton = { TextButton(onClick = { routeName = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun DetailSummary(journey: TripJourney, fresh: Boolean) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TimeText(journey.departure, journey.plannedDeparture, fresh && journey.transit.firstOrNull()?.origin?.estimatedDeparture != null, 26)
                Text("  →  ", color = Soft, fontSize = 20.sp)
                TimeText(journey.arrival, journey.plannedArrival, fresh && journey.transit.lastOrNull()?.destination?.estimatedArrival != null, 26)
            }
            Text(listOfNotNull(journey.durationSeconds?.let(::durationLabel),
                if (journey.interchanges == 0) "direkt" else "${journey.interchanges} ${if (journey.interchanges == 1) "Umstieg" else "Umstiege"}",
                journey.walkingSeconds.takeIf { it >= 60 }?.let { "${durationLabel(it)} zu Fuß" }).joinToString(" · "),
                color = Soft, fontSize = 14.sp)
            if (journey.cancelled) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = Amber, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Mindestens eine Fahrt fällt laut VRR aus.", color = Amber, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Only real VRR geometry is drawn; legs without geometry show their stops only. */
@Composable
private fun JourneyMapCard(journey: TripJourney) {
    val accent = 0xFFB40055
    val lines = journey.legs.mapNotNull { leg ->
        when {
            leg.path.size < 2 -> null
            leg.kind == LegKind.WALK -> MapLine(leg.path, 0xFF6B6474, dashed = true, width = 4f)
            leg.kind == LegKind.TRANSIT -> MapLine(leg.path, accent)
            else -> null
        }
    }
    val transit = journey.transit
    val pins = buildList {
        transit.forEachIndexed { index, leg ->
            leg.origin.point?.let { add(MapPin(it, "${if (index == 0) "Einstieg" else "Umstieg"}: ${shortName(leg.origin.name)}",
                if (index == 0) PinKind.BOARD else PinKind.TRANSFER, "o$index")) }
            leg.intermediate.forEachIndexed { stopIndex, stop -> stop.point?.let { add(MapPin(it, shortName(stop.name), PinKind.STOP, "i$index/$stopIndex")) } }
        }
        transit.lastOrNull()?.destination?.point?.let { add(MapPin(it, "Ausstieg: ${shortName(transit.last().destination.name)}", PinKind.EXIT, "exit")) }
    }
    if (pins.isEmpty() && lines.isEmpty()) return
    val missing = transit.count { it.path.size < 2 }
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlainMap(lines, pins, "Karte der Verbindung mit ${pins.size} Haltestellen")
            Text(when {
                missing == 0 -> "Linienverlauf laut VRR. Punkte: Einstieg grün, Umstieg gelb, Ausstieg rot."
                missing == transit.size -> "Der VRR liefert für diese Verbindung keinen Linienverlauf – gezeigt werden nur Haltestellen."
                else -> "Für $missing von ${transit.size} Fahrten liefert der VRR keinen Linienverlauf – dort nur Haltestellen."
            }, color = Soft, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun TransitLegCard(leg: TripLeg, fresh: Boolean) {
    var expanded by rememberSaveable(leg.tripCode, leg.origin.id) { mutableStateOf(false) }
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegBadge(leg)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Richtung ${shortName(leg.direction).ifBlank { "unbekannt" }}", fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(leg.productName.takeIf { it.isNotBlank() }, if (leg.isNew) "NEW" else leg.operatorCode.takeIf { it.isNotBlank() },
                        when { !fresh -> "veralteter Datenstand"; leg.realtime -> "Live"; else -> "Fahrplan" }).joinToString(" · "), color = Soft, fontSize = 12.sp)
                }
            }
            StopTimeRow(leg.origin, departure = true, fresh = fresh)
            if (leg.intermediate.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${leg.intermediate.size} Zwischenhalt${if (leg.intermediate.size == 1) "" else "e"}")
                }
                if (expanded) leg.intermediate.forEach { stop ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Soft))
                        Spacer(Modifier.width(10.dp))
                        Text(shortName(stop.name), fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (stop.cancelled) Amber else Paper)
                        Text(clockTime(stop.departure ?: stop.arrival), fontSize = 13.sp,
                            color = if (fresh && (stop.estimatedDeparture != null || stop.estimatedArrival != null)) Lime else Soft)
                    }
                }
            }
            StopTimeRow(leg.destination, departure = false, fresh = fresh)
            if (leg.cancelled) Text("Fällt laut VRR aus", color = Amber, fontWeight = FontWeight.SemiBold)
            leg.notices.forEach { notice ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, tint = Amber, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(notice, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun StopTimeRow(stop: TripStopTime, departure: Boolean, fresh: Boolean) {
    val planned = if (departure) stop.plannedDeparture else stop.plannedArrival
    val estimated = if (departure) stop.estimatedDeparture else stop.estimatedArrival
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(78.dp)) { TimeText(estimated ?: planned, planned, fresh && estimated != null, 17) }
        Column(Modifier.weight(1f)) {
            Text(shortName(stop.name), fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (stop.cancelled) Amber else Paper)
            if (stop.platform.isNotBlank()) Text(stop.platform, color = Soft, fontSize = 12.sp)
            if (stop.cancelled) Text("Halt entfällt", color = Amber, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WalkLegRow(leg: TripLeg, isFirst: Boolean, isLast: Boolean) {
    val uriHandler = LocalUriHandler.current
    val settings = LocalAppSettings.current
    val from = leg.origin.point
    val to = leg.destination.point
    var failed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, tint = Soft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull("${durationLabel(leg.durationSeconds.coerceAtLeast(60))} zu Fuß",
                leg.distanceMetres?.takeIf { it > 0 }?.let { "$it m" },
                when { isFirst -> "zum Einstieg"; isLast -> "zum Ziel"; else -> "Umstieg" }).joinToString(" · "), fontSize = 14.sp)
            if (leg.footpath.isNotEmpty()) Text("Auf dem Weg: ${leg.footpath.joinToString(", ")}", color = Soft, fontSize = 12.sp)
            if (failed) Text("Keine Karten-App gefunden.", color = Amber, fontSize = 12.sp)
        }
        if (from != null && to != null && Geo.valid(from) && Geo.valid(to) && from.distanceTo(to) > 30) TextButton(onClick = {
            val a = "%.6f,%.6f".format(Locale.ROOT, from.lat, from.lon)
            val b = "%.6f,%.6f".format(Locale.ROOT, to.lat, to.lon)
            failed = runCatching {
                uriHandler.openUri(if (settings.string("map_external", "chooser") == "osm")
                    "https://www.openstreetmap.org/directions?engine=fossgis_osrm_foot&route=$a;$b"
                else "https://www.google.com/maps/dir/?api=1&origin=$a&destination=$b&travelmode=walking")
            }.isFailure
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Weg in Karten-App", fontSize = 13.sp)
        }
    }
}

@Composable
private fun TransferNote(transfer: TransferAssessment, next: TripLeg) {
    val (text, warn) = when (transfer.risk) {
        TransferRisk.MISSED -> (if (transfer.slackSeconds == Long.MIN_VALUE) "Anschluss ${next.line} fällt aus"
            else "Anschluss ${next.line} laut Live-Daten nicht erreichbar") to true
        TransferRisk.TIGHT -> "Anschluss ${next.line} knapp: ${transfer.slackSeconds / 60} Min Puffer laut Live-Daten" to true
        TransferRisk.OK -> "Anschluss ${next.line}: ${transfer.slackSeconds / 60} Min Puffer laut Live-Daten" to false
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (warn) Amber.copy(alpha = .12f) else Lime.copy(alpha = .08f))
        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (warn) Icons.Outlined.WarningAmber else Icons.Outlined.Check, null, tint = if (warn) Amber else Lime, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = if (warn) Amber else Paper, fontWeight = FontWeight.Medium)
    }
}
