package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.luca.mitfahrt.RideViewModel
import de.luca.mitfahrt.data.*
import java.time.LocalDate

internal val personalModules = linkedMapOf("exceptions" to "Routenausnahmen", "calendar" to "Schulkalender NRW",
    "archive" to "Fahrtenarchiv", "arrivals" to "Eigene Ankünfte")

@Composable
internal fun PersonalModuleLinks(onOpen: (String) -> Unit, query: String = "") {
    val matches = personalModules.filter { (key, name) -> query.isBlank() ||
        (name + when (key) { "exceptions" -> " heute später andere Uhrzeit"; "calendar" -> " Ferien Feiertage freie Tage";
            "archive" -> " Fahrt Verlauf löschen"; else -> " Ankunft Haltestelle löschen" }).contains(query.trim(), ignoreCase = true) }
    if (matches.isNotEmpty()) AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        matches.forEach { (key, title) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onOpen(key) }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Icon(Icons.Outlined.ChevronRight, "Öffnen", tint = Soft)
            }
        }
    }
}

@Composable
internal fun PersonalModuleScreen(module: String, vm: RideViewModel, today: LocalDate,
    onReport: (de.luca.mitfahrt.data.Ride) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val archived by vm.archive.collectAsState()
    val arrivals by vm.arrivals.collectAsState()
    val routes by vm.routePlans.collectAsState()
    val overrides by vm.dayOverrides.collectAsState()
    val calendar by vm.schoolCalendar.collectAsState()
    var selectedRoute by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text(personalModules[module].orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            when (module) {
                "archive" -> JourneyArchiveScreen(archived, today, vm::removeArchived, vm::restoreArchived, vm::clearArchive,
                    vm::setArchiveEnabled, vm::setArchiveRetention, modifier = Modifier.weight(1f), onReport = onReport)
                "arrivals" -> ArrivalHistoryScreen(arrivals, today, vm::removeArrival, modifier = Modifier.weight(1f))
                "calendar" -> SchoolCalendarScreen(calendar, today, vm::setCalendarEnabled, vm::addFreeDay,
                    vm::removeFreeDay, vm::markSchoolDay, vm::removeSchoolDay, modifier = Modifier.weight(1f))
                "exceptions" -> LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (routes.routes.isEmpty()) item { Text("Lege zuerst eine eigene Route an.", color = Soft) }
                    routes.routes.forEach { route -> item(key = route.id) {
                        val selected = selectedRoute == route.id || selectedRoute == null && routes.routes.size == 1
                        AppCard(onClick = { selectedRoute = if (selected) null else route.id }, shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(route.name, fontWeight = FontWeight.SemiBold)
                                Text("${shortName(route.origin.name)} → ${shortName(route.destination.name)}", color = Soft,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (selected) {
                            val legacy = route.excludedDates.mapNotNull { date -> runCatching {
                                RouteDayOverride("legacy-${route.id}-$date", route.id, LocalDate.parse(date), DayOverrideMode.HIDDEN)
                            }.getOrNull() }
                            RouteDayOverridesEditor(route.id, route.name, (overrides + legacy).distinctBy { it.routeId to it.date }, today,
                                vm::saveDayOverride, vm::removeDayOverride)
                        }
                    } }
                }
            }
        }
    }
}
