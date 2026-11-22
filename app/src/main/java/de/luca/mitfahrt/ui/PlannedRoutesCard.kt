package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import de.luca.mitfahrt.data.DynamicDestinationSuggestion
import de.luca.mitfahrt.data.DynamicRouteLearner
import de.luca.mitfahrt.data.RouteMatch
import de.luca.mitfahrt.data.RoutePlan
import de.luca.mitfahrt.data.TemporaryDestination
import de.luca.mitfahrt.data.TemporaryDestinationPresets
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.minutesLabel
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.liveEstimate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Day and month of a temporary destination, shared with the route editor so both read the same. */
internal val TempDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)

/**
 * Saved routes under "Wohin?". The caller shows it only when at least one route is to be shown;
 * there is no empty heading, card or "no routes" text on the start page.
 */
@Composable
fun PlannedRoutesCard(plans: List<RoutePlan>, allCount: Int, departures: Map<String, List<Departure>>,
    loading: Boolean, now: Instant, busyKey: String?, onManage: () -> Unit,
    onStart: (RoutePlan, Departure) -> Unit, onPreview: (RoutePlan, Departure) -> Unit, onOpen: (RoutePlan) -> Unit,
    onConnections: (RoutePlan) -> Unit,
    suggestions: List<DynamicDestinationSuggestion> = emptyList(),
    onApplyTemporary: (String, TemporaryDestination) -> Unit = { _, _ -> },
    onClearTemporary: (String) -> Unit = {},
    onDismissSuggestion: (String, String) -> Unit = { _, _ -> }) {
    if (plans.isEmpty()) return
    val suggestionByRoute = suggestions.associateBy { it.routeId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Routen", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) { Text("Verwalten") }
        }
        plans.forEach { plan -> key(plan.id) {
            PlannedRouteItem(plan, departures[plan.id].orEmpty(), loading, now, busyKey, onStart, onPreview, onOpen, onConnections,
                suggestionByRoute[plan.id], onApplyTemporary, onClearTemporary, onDismissSuggestion)
        } }
        if (allCount > plans.size) TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Alle $allCount Routen")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannedRouteItem(plan: RoutePlan, departures: List<Departure>, loading: Boolean, now: Instant,
    busyKey: String?, onStart: (RoutePlan, Departure) -> Unit, onPreview: (RoutePlan, Departure) -> Unit,
    onOpen: (RoutePlan) -> Unit, onConnections: (RoutePlan) -> Unit,
    suggestion: DynamicDestinationSuggestion?,
    onApplyTemporary: (String, TemporaryDestination) -> Unit,
    onClearTemporary: (String) -> Unit,
    onDismissSuggestion: (String, String) -> Unit) {
    val today = now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate()
    val activeTemp = plan.activeTemporaryDestination(today)
    val effectiveDest = plan.effectiveDestination(today)
    val next = departures.asSequence().filter { departure ->
        !departure.cancelled && departure.effective >= now &&
            departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
    }.minByOrNull { it.effective }
    val selectedLine = plan.lineLabel().takeIf { it.isNotEmpty() }
    val busy = next != null && next.key == busyKey
    var menuOpen by remember { mutableStateOf(false) }
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Route plaque: the chosen colour fills a small sign, the symbol on it picks its own readable ink.
                val plaque = routeColor(plan.color)
                Box(Modifier.size(36.dp).clip(SignShape).background(plaque), contentAlignment = Alignment.Center) {
                    Icon(routeIcon(plan.icon), null, tint = onColorFor(plaque), modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(plan.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${shortName(plan.origin.name)} → ${shortName(effectiveDest.name)}",
                        color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = busyKey == null) {
                        Icon(Icons.Outlined.MoreVert, "Mehr zu ${plan.name}")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (next != null) DropdownMenuItem(text = { Text("Vorschau") },
                            onClick = { menuOpen = false; onPreview(plan, next) })
                        DropdownMenuItem(text = { Text(listOfNotNull("Abfahrten", selectedLine?.let { "Linie $it" }).joinToString(" · ")) },
                            onClick = { menuOpen = false; onOpen(plan) })
                        DropdownMenuItem(text = { Text("Verbindungen") }, onClick = { menuOpen = false; onConnections(plan) })
                    }
                }
            }
            if (activeTemp != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val labelPart = activeTemp.label.takeIf { it.isNotEmpty() }?.let { "$it · " }.orEmpty()
                    val range = if (activeTemp.startDate == activeTemp.endDate) "heute"
                        else "bis ${TempDateFormat.format(activeTemp.endDate)}"
                    Text("${labelPart}Zeitweises Ziel $range · sonst ${shortName(plan.destination.name)}",
                        color = Amber, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onClearTemporary(plan.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Normales Ziel")
                    }
                }
            }
            if (next != null) Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val live = next.liveEstimate(now) != null
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    LineSign(next.line, large = true)
                    // Minutes only with fresh live data; otherwise the plan time, never a running counter.
                    Text(if (live) minutesLabel(next.effective, now) else clockTime(next.planned),
                        style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f, fill = false))
                    SourceMark(if (live) TimeSource.LIVE else TimeSource.PLAN)
                }
                val forecast = when {
                    next.estimated == null -> null
                    next.delayMinutes > 0 -> "+${next.delayMinutes} Min"
                    else -> "pünktlich"
                }
                Text(listOfNotNull("Abfahrt ${clockTime(next.effective)}", forecast, next.platform.takeIf { it.isNotBlank() }?.let { "Steig $it" })
                    .joinToString(" · "), color = if (next.delayMinutes > 0) Amber else Soft, fontSize = 13.sp)
                Button(onClick = { onStart(plan, next) }, enabled = busyKey == null, shape = ControlShape,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 52.dp)) {
                    if (busy) {
                        AppCircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "Lädt …" else "Mitfahren", maxLines = 1)
                }
            } else Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(when {
                    loading -> "Abfahrten werden geladen …"
                    departures.isNotEmpty() -> "Keine aktuelle Abfahrt mit Live-Daten."
                    else -> "Gerade keine passende NEW-Abfahrt."
                }, color = Soft, fontSize = 13.sp)
            }
            if (suggestion != null) Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(top = 4.dp))
                Text("In den letzten ${DynamicRouteLearner.LOOKBACK_DAYS} Tagen ${suggestion.count}-mal bis " +
                    "${shortName(suggestion.suggestedStop.name)} gefahren. Als zeitweises Ziel nehmen?",
                    color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { onApplyTemporary(plan.id, TemporaryDestinationPresets.thisWeek(suggestion.suggestedStop, today)) },
                        shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Diese Woche", maxLines = 1) }
                    OutlinedButton(
                        onClick = { onApplyTemporary(plan.id, TemporaryDestinationPresets.twoWeeks(suggestion.suggestedStop, today)) },
                        shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("2 Wochen", maxLines = 1) }
                    TextButton(
                        onClick = { onDismissSuggestion(plan.id, suggestion.suggestedStop.id) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Ausblenden") }
                }
            }
        }
    }
}
