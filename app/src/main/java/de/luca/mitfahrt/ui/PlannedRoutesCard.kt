package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.RouteMatch
import de.luca.mitfahrt.data.RoutePlan
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.countdownUntil
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.liveEstimate
import java.time.Instant

@Composable
fun PlannedRoutesCard(plans: List<RoutePlan>, allCount: Int, departures: Map<String, List<Departure>>,
    loading: Boolean, now: Instant, busyKey: String?, onManage: () -> Unit,
    onStart: (RoutePlan, Departure) -> Unit, onOpen: (RoutePlan) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deine Routen", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (allCount > 0) TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Verwalten")
            }
        }
        when {
            allCount == 0 -> OutlinedButton(onClick = onManage,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Strecke hinzufügen")
            }
            plans.isEmpty() -> Text("Zurzeit keine Route für diesen Ort und diese Uhrzeit.",
                color = Soft, fontSize = 14.sp, lineHeight = 20.sp)
            else -> {
                plans.take(2).forEach { plan -> key(plan.id) {
                    PlannedRouteItem(plan, departures[plan.id].orEmpty(), loading, now, busyKey, onStart, onOpen)
                } }
                if (allCount > 2) TextButton(onClick = onManage,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Alle $allCount Routen verwalten")
                }
            }
        }
    }
}

@Composable
private fun PlannedRouteItem(plan: RoutePlan, departures: List<Departure>, loading: Boolean, now: Instant,
    busyKey: String?, onStart: (RoutePlan, Departure) -> Unit, onOpen: (RoutePlan) -> Unit) {
    val next = departures.asSequence().filter { departure ->
        !departure.cancelled && departure.effective >= now &&
            departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
    }.minByOrNull { it.effective }
    val selectedLine = plan.lineLabel().takeIf { it.isNotEmpty() }
    val busy = next != null && next.key == busyKey
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(routeIcon(plan.icon), null, tint = routeColor(plan.color),
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(routeColor(plan.color).copy(alpha = .12f)).padding(7.dp))
                Text(plan.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${shortName(plan.origin.name)} → ${shortName(plan.destination.name)}",
                color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(selectedLine?.let { "Linie $it" }, plan.effectiveWindows().joinToString(" / ") { it.label() }).joinToString(" · "),
                color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
            if (next != null) {
                Text(if (next.liveEstimate(now) != null) "Abfahrt in ${countdownUntil(next.effective, now)}" else "Fahrplan ${clockTime(next.planned)}", color = Lime,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                val forecast = when {
                    next.estimated == null -> "Fahrplan"
                    next.delayMinutes > 0 -> "+${next.delayMinutes} Min"
                    else -> "Live"
                }
                Text("Linie ${next.line} · ${clockTime(next.effective)} · VRR $forecast",
                    color = if (next.delayMinutes > 0) Amber else Soft, fontSize = 12.sp)
                Button(onClick = { onStart(plan, next) }, enabled = busyKey == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    if (busy) {
                        AppCircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp,
                            color = Lime)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "Wird geprüft …" else "Fahrt verfolgen")
                }
                TextButton(onClick = { onOpen(plan) }, enabled = busyKey == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Fahrten anzeigen") }
            } else {
                if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(when {
                    loading -> "Abfahrten werden geladen …"
                    departures.isNotEmpty() -> "Keine aktuellen Abfahrten."
                    else -> "Gerade keine Abfahrt verfügbar."
                }, color = Soft, fontSize = 13.sp)
                OutlinedButton(onClick = { onOpen(plan) }, enabled = busyKey == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Fahrten anzeigen") }
            }
        }
    }
}
