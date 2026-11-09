package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/**
 * Saved routes under "Wohin?". The caller shows it only when at least one route is to be shown;
 * there is no empty heading, card or "no routes" text on the start page.
 */
@Composable
fun PlannedRoutesCard(plans: List<RoutePlan>, allCount: Int, departures: Map<String, List<Departure>>,
    loading: Boolean, now: Instant, busyKey: String?, onManage: () -> Unit,
    onStart: (RoutePlan, Departure) -> Unit, onPreview: (RoutePlan, Departure) -> Unit, onOpen: (RoutePlan) -> Unit,
    onConnections: (RoutePlan) -> Unit) {
    if (plans.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deine Routen", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) { Text("Verwalten") }
        }
        plans.forEach { plan -> key(plan.id) {
            PlannedRouteItem(plan, departures[plan.id].orEmpty(), loading, now, busyKey, onStart, onPreview, onOpen, onConnections)
        } }
        if (allCount > plans.size) TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Alle $allCount Routen")
        }
    }
}

@Composable
private fun PlannedRouteItem(plan: RoutePlan, departures: List<Departure>, loading: Boolean, now: Instant,
    busyKey: String?, onStart: (RoutePlan, Departure) -> Unit, onPreview: (RoutePlan, Departure) -> Unit,
    onOpen: (RoutePlan) -> Unit, onConnections: (RoutePlan) -> Unit) {
    val next = departures.asSequence().filter { departure ->
        !departure.cancelled && departure.effective >= now &&
            departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
    }.minByOrNull { it.effective }
    val selectedLine = plan.lineLabel().takeIf { it.isNotEmpty() }
    val busy = next != null && next.key == busyKey
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(routeIcon(plan.icon), null, tint = routeColor(plan.color),
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(routeColor(plan.color).copy(alpha = .12f)).padding(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(plan.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${shortName(plan.origin.name)} → ${shortName(plan.destination.name)}",
                        color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (next != null) {
                val live = next.liveEstimate(now) != null
                Text(if (live) "Abfahrt in ${countdownUntil(next.effective, now)}" else "Fahrplan ${clockTime(next.planned)}", color = Lime,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                val forecast = when {
                    next.estimated == null -> "Fahrplan"
                    next.delayMinutes > 0 -> "live +${next.delayMinutes} Min"
                    else -> "live pünktlich"
                }
                Text(listOfNotNull("Linie ${next.line}", clockTime(next.effective), "VRR $forecast", next.platform.takeIf { it.isNotBlank() })
                    .joinToString(" · "), color = if (next.delayMinutes > 0) Amber else Soft, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPreview(plan, next) }, enabled = busyKey == null,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp)) { Text("Vorschau") }
                    Button(onClick = { onStart(plan, next) }, enabled = busyKey == null, modifier = Modifier.weight(1f).heightIn(min = 50.dp)) {
                        if (busy) {
                            AppCircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Lime)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (busy) "Lädt …" else "Begleiten", maxLines = 1)
                    }
                }
            } else {
                if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(when {
                    loading -> "Abfahrten werden geladen …"
                    departures.isNotEmpty() -> "Keine aktuelle Abfahrt mit Live-Daten."
                    else -> "Gerade keine passende NEW-Abfahrt."
                }, color = Soft, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpen(plan) }, enabled = busyKey == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(listOfNotNull("Abfahrten", selectedLine?.let { "Linie $it" }).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onConnections(plan) }, enabled = busyKey == null, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Verbindungen")
                }
            }
        }
    }
}
