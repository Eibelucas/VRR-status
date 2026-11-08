package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyPreviewSheet(ride: Ride, now: Instant, onStart: () -> Unit, onDismiss: () -> Unit) {
    val boarding = ride.stops.getOrNull(ride.boardingIndex) ?: return
    val targetIndex = ride.exitTargetIndex() ?: ride.stops.lastIndex
    val target = ride.stops[targetIndex]
    val live = ride.liveBoardingTime(now)
    val freshTrip = !ride.demo && now.epochSecond - ride.fetchedAt.epochSecond in -30..120
    val canStart = !ride.departure.cancelled && !boarding.cancelled && !target.cancelled
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Fahrt ansehen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Vorschau schließen") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(ride.departure.line, color = Lime, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Lime.copy(alpha = .12f))
                                .padding(horizontal = 14.dp, vertical = 12.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Richtung", color = Soft, fontSize = 12.sp)
                            Text(shortName(ride.departure.destination), fontWeight = FontWeight.SemiBold, fontSize = 19.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(previewDate(ride.departure.planned, now), color = Soft, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        PreviewEndpoint("Ab", boarding.name)
                        PreviewEndpoint("Bis", target.name)
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(when {
                            ride.departure.cancelled -> "Fahrt fällt aus"
                            boarding.cancelled -> "Einstiegshaltestelle entfällt"
                            live != null -> "${if (live.isArrival) "Ankunft" else "Abfahrt"} ${clockTime(live.at)} · Live"
                            ride.demo -> "Beispieldaten · ${clockTime(ride.departure.planned)}"
                            else -> "Fahrplan · Abfahrt ${clockTime(ride.departure.planned)}"
                        }, color = if (ride.departure.cancelled || boarding.cancelled) Amber else if (live != null) Lime else Paper,
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (live == null && !ride.demo && !ride.departure.cancelled && !boarding.cancelled)
                            Text("Keine aktuelle Live-Zeit", color = Soft, fontSize = 12.sp)
                    }
                }
                item {
                    HorizontalDivider(color = Soft.copy(alpha = .18f))
                    Text("Fahrtverlauf", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                items((ride.boardingIndex..targetIndex).toList(), key = { it }) { index ->
                    val stop = ride.stops[index]
                    val estimate = if (index == ride.boardingIndex) live?.at
                        else stop.estimated.takeIf { freshTrip && !ride.departure.cancelled && !stop.cancelled }
                    val planned = if (index == ride.boardingIndex) ride.departure.planned else stop.planned
                    PreviewStopRow(stop, estimate, planned,
                        label = when {
                            index == ride.boardingIndex -> "Einstieg"
                            index == targetIndex && ride.exitTargetIndex() != null -> "Dein Ziel"
                            index == targetIndex -> "Endhalt"
                            else -> null
                        }, last = index == targetIndex)
                }
            }
            Surface(color = Panel) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target.cancelled && !ride.departure.cancelled && !boarding.cancelled)
                        Text("Zielhaltestelle entfällt", color = Amber, fontSize = 13.sp)
                    Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Outlined.DirectionsBus, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("Fahrt verfolgen")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewEndpoint(label: String, name: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Soft, fontSize = 14.sp, modifier = Modifier.width(32.dp))
        Text(shortName(name), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewStopRow(stop: RideStop, estimate: Instant?, planned: Instant?, label: String?, last: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(18.dp).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (label != null) 11.dp else 7.dp).clip(CircleShape)
                .background(if (stop.cancelled) Soft else if (label != null) Lime else Soft.copy(alpha = .65f)))
            if (!last) Box(Modifier.padding(top = 5.dp).width(2.dp).height(35.dp).background(Soft.copy(alpha = .18f)))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            label?.let { Text(it, color = Soft, fontSize = 11.sp) }
            Text(shortName(stop.name), fontWeight = if (label != null) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (stop.cancelled) Text("Halt entfällt", color = Amber, fontSize = 12.sp)
        }
        Column(Modifier.padding(top = 6.dp), horizontalAlignment = Alignment.End) {
            Text(if (stop.cancelled) "–" else clockTime(estimate ?: planned),
                color = if (estimate != null && !stop.cancelled) Lime else Soft, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (!stop.cancelled) Text(if (estimate != null) "Live" else "Fahrplan", color = Soft, fontSize = 11.sp)
        }
    }
}

private fun previewDate(at: Instant, now: Instant): String {
    val zone = ZoneId.of("Europe/Berlin")
    val date = at.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (date) {
        today -> "Heute"
        today.plusDays(1) -> "Morgen"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, dd.MM.yyyy", Locale.GERMAN))
    }
}
