package de.luca.mitfahrt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import de.luca.mitfahrt.data.AutoMatchBasis
import de.luca.mitfahrt.data.AutoMatchStatus
import de.luca.mitfahrt.data.GeoPoint
import de.luca.mitfahrt.data.GpsProgressState
import de.luca.mitfahrt.data.GpsStopProgress
import de.luca.mitfahrt.data.LiveBusState
import de.luca.mitfahrt.data.RouteShape
import de.luca.mitfahrt.data.boardingTime
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.displayTarget
import de.luca.mitfahrt.data.exitTargetIndex
import de.luca.mitfahrt.data.liveBoardingTime
import de.luca.mitfahrt.data.nextStopToTarget
import de.luca.mitfahrt.data.progressToTarget
import de.luca.mitfahrt.data.NewVehicle
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.Stop
import de.luca.mitfahrt.data.shortName
import java.time.Duration
import java.time.Instant
import de.luca.mitfahrt.data.AssignmentStatus
import de.luca.mitfahrt.data.VehicleComparison
import java.util.Locale
import kotlin.math.roundToInt

/** A position is shown only for an identified departure or a vehicle the user explicitly chose. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveBusCard(ride: Ride, state: LiveBusState, now: Instant, onSelect: (NewVehicle) -> Unit,
    onClear: () -> Unit, onRefresh: () -> Unit, shape: RouteShape? = null, progress: GpsProgressState? = null,
    phone: GeoPoint? = null, onReject: (String) -> Unit = {}) {
    val settings by rememberAppSettingsStore().state.collectAsState()
    if (ride.demo || ride.departure.cancelled || state.journeyKey != ride.departure.key ||
        !settings.bool(if (ride.boarded) "map_onboard" else "map_waiting", true)) return
    var chooseVehicle by rememberSaveable(ride.departure.key) { mutableStateOf(false) }
    val boarding = ride.stops.getOrNull(ride.boardingIndex)?.let { Stop(it.id, it.name, it.point) }
    val candidates = state.candidates.filter { it.isFresh(now) }.distinctBy { it.id }
    val auto = state.auto
    val detected = auto.holdsVehicle && state.vehicle?.id == auto.vehicleId && !state.confirmed && state.selectedVehicleId == null
    val selected = state.vehicle?.takeIf { state.confirmed || state.selectedVehicleId == it.id || detected }
    val current = selected?.takeIf { it.isFresh(now) }
    val stale = selected?.takeUnless { it.isFresh(now) }
    val routeShape = shape?.takeIf { it.journeyKey == ride.departure.key }
    val targetIndex = ride.exitTargetIndex() ?: ride.stops.lastIndex
    val travelled = when {
        !ride.boarded -> null
        state.routeOffset != null && (state.confirmed || auto.basis in setOf(AutoMatchBasis.CO_MOVEMENT, AutoMatchBasis.TELEMETRY, AutoMatchBasis.SCHEDULE)) -> state.routeOffset
        progress?.exact == true && GpsStopProgress.confident(progress, now) -> progress.offset
        else -> null
    }
    val mapRoute = MapRoute(shape = routeShape,
        target = ride.stops.getOrNull(targetIndex)?.let { Stop(it.id, it.name, it.point) },
        sectionFrom = routeShape?.offsetOf(ride.boardingIndex), sectionTo = routeShape?.offsetOf(targetIndex),
        passedTo = travelled, projected = state.routePoint, offRoute = state.offRoute, phone = phone)

    var fullscreen by androidx.compose.runtime.saveable.rememberSaveable(ride.departure.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Busposition", fontWeight = FontWeight.SemiBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(Icons.Outlined.Refresh, "Busposition aktualisieren", tint = Lime)
            }
        }
        if (current != null) {
            Text(when {
                state.confirmed || (detected && auto.basis == AutoMatchBasis.SCHEDULE) -> "Dein Bus laut NEW-Fahrplanweg"
                detected && auto.basis == AutoMatchBasis.TELEMETRY -> "Dein Bus laut NEW-Haltestelle und Live-Zeit"
                detected && auto.basis == AutoMatchBasis.CO_MOVEMENT -> "Dein Bus: bewegt sich mit deinem Handy"
                detected -> "Vermutlich dein Bus: Linie, Richtung und Anfahrt passen"
                else -> "Von dir gewählt"
            }, color = Soft, fontSize = 14.sp, lineHeight = 19.sp)
            if (current.destination.isNotBlank()) Text("${if (current.directionReliable || !current.routeLabel.isNullOrBlank()) "Richtung" else "Anzeige am Bus"} ${shortName(current.routeLabel ?: current.destination)}",
                color = Soft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            WaitingBusMap(current.point, boarding, "Linie ${current.line} · Fahrzeug ${current.id}",
                current.observedAt, now, route = mapRoute,
                routeStops = ride.stops.map { Stop(it.id, it.name, it.point) },
                otherVehicles = candidates, selectedVehicleId = current.id,
                onSelectVehicle = onSelect, onFullscreen = { fullscreen = true }, heading = state.heading)
            if (fullscreen) LiveHubFullscreen(
                ride = ride,
                now = now,
                subtitle = "Fahrzeug ${current.id} · NEW ${vehicleAge(current.observedAt, now)}",
                loading = state.loading,
                onRefresh = onRefresh,
                onClose = { fullscreen = false }
            ) { bottomClearance ->
                WaitingBusMap(current.point, boarding, "Linie ${current.line} · Fahrzeug ${current.id}",
                    current.observedAt, now, route = mapRoute,
                    routeStops = ride.stops.map { Stop(it.id, it.name, it.point) },
                    otherVehicles = candidates, selectedVehicleId = current.id, onSelectVehicle = onSelect,
                    edgeToEdge = true, recenterTopPadding = 84.dp, attributionBottomPadding = bottomClearance,
                    heading = state.heading)
            }
            if (state.loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            // No usable bus position: show the phone's own measured progress on the real line, clearly named as GPS.
            val ownPoint = progress?.takeIf { ride.boarded && GpsStopProgress.confident(it, now) }?.confirmedPoint
            val ownAt = progress?.confirmedAt
            if (ownPoint != null && ownAt != null) {
                Text("Keine Busposition von NEW. Die Karte zeigt deine eigene Position.", color = Soft, fontSize = 14.sp, lineHeight = 19.sp)
                WaitingBusMap(ownPoint, boarding, "Per GPS erkannt", ownAt, now, route = mapRoute.copy(phone = null),
                    routeStops = ride.stops.map { Stop(it.id, it.name, it.point) }, gpsSource = true, onFullscreen = { fullscreen = true })
                if (fullscreen) LiveHubFullscreen(
                    ride = ride,
                    now = now,
                    subtitle = "GPS deines Handys · ${vehicleAge(ownAt, now)}",
                    loading = state.loading,
                    onRefresh = onRefresh,
                    onClose = { fullscreen = false }
                ) { bottomClearance ->
                    WaitingBusMap(ownPoint, boarding, "Per GPS erkannt", ownAt, now, route = mapRoute.copy(phone = null),
                        routeStops = ride.stops.map { Stop(it.id, it.name, it.point) }, gpsSource = true,
                        edgeToEdge = true, recenterTopPadding = 84.dp, attributionBottomPadding = bottomClearance)
                }
            } else AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when {
                        stale != null -> "Positionsdaten veraltet"
                        state.selectedVehicleId != null -> "Keine aktuelle Fahrzeugposition"
                        state.loading -> "Busposition wird gesucht …"
                        candidates.isNotEmpty() -> "Bus wird noch zugeordnet"
                        else -> "Keine Live-Position verfügbar"
                    }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    when {
                        stale != null -> Text("Fahrzeug ${stale.id} · letzter Stand ${clockTime(stale.observedAt)}",
                            color = Soft, fontSize = 13.sp)
                        state.selectedVehicleId != null -> Text("Ausgewählt: Fahrzeug ${state.selectedVehicleId}",
                            color = Soft, fontSize = 13.sp)
                        candidates.isNotEmpty() -> Text("Mitfahrt ordnet den Bus zu, sobald er eindeutig ist. Du kannst ihn auch selbst wählen.",
                            color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                        !state.loading && state.error == null -> Text("NEW meldet gerade keine zuordenbare Position.",
                            color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                    if (state.loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    if (candidates.isEmpty()) OutlinedButton(shape = ControlShape, onClick = onRefresh, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Erneut laden") }
                }
            }
        }
        if (auto.status == AutoMatchStatus.RECHECKING && current == null)
            Text("Dein Bus meldet gerade keine Position. Mitfahrt prüft weiter.", color = Amber, fontSize = 13.sp, lineHeight = 19.sp)
        val suggested = auto.vehicleId?.takeIf { auto.status == AutoMatchStatus.SUGGESTED && state.selectedVehicleId == null }
            ?.let { id -> candidates.firstOrNull { it.id == id } }
        if (suggested != null) AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (ride.boarded) "Bist du in diesem Bus?" else "Ist das dein Bus?", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Linie ${suggested.line} · Fahrzeug ${suggested.id} · NEW ${vehicleAge(suggested.observedAt, now)}",
                    color = Soft, fontSize = 13.sp)
                auto.ranking.firstOrNull { it.id == suggested.id }?.reasons?.take(3)?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(", "), color = Soft, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(shape = ControlShape, onClick = { onSelect(suggested) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Das ist mein Bus") }
                    OutlinedButton(shape = ControlShape, onClick = { onReject(suggested.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Anderer Bus") }
                }
            }
        }
        val choices = if (auto.status == AutoMatchStatus.AMBIGUOUS && current == null && state.selectedVehicleId == null)
            auto.choices.mapNotNull { id -> candidates.firstOrNull { it.id == id } } else emptyList()
        if (choices.size >= 2) {
            Text("Welcher Bus ist deiner?", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            choices.forEach { vehicle ->
                VehicleChoiceRow(vehicle, boarding, now, false, VehicleComparison.rows(ride, state, now).firstOrNull { it.vehicle.id == vehicle.id }?.status) { onSelect(vehicle) }
            }
        }
        state.error?.let { Text(it, color = Amber, fontSize = 13.sp, lineHeight = 19.sp) }
        if (candidates.isNotEmpty() || state.selectedVehicleId != null) {
            if (current == null && state.selectedVehicleId == null) OutlinedButton(shape = ControlShape, onClick = { chooseVehicle = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("Bus selbst wählen") }
            else TextButton(onClick = { onClear(); chooseVehicle = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Anderer Bus")
            }
        }
        if (state.selectedVehicleId != null) TextButton(onClick = onClear,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Wieder automatisch zuordnen") }
    }

    if (chooseVehicle) ModalBottomSheet(onDismissRequest = { chooseVehicle = false }, containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fahrzeug auswählen", style = MaterialTheme.typography.headlineSmall)
            Text("Gleiche Liniennummer heißt noch nicht, dass es dein Bus ist. Vergleiche Richtung, Entfernung und Stand. Deine Wahl bleibt, bis du sie aufhebst.",
                color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
            if (state.loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val compared = VehicleComparison.rows(ride, state, now).associateBy { it.vehicle.id }
                items(candidates.sortedBy { compared[it.id]?.status?.ordinal ?: Int.MAX_VALUE }, key = { it.id }) { vehicle ->
                    VehicleChoiceRow(vehicle, boarding, now, state.selectedVehicleId == vehicle.id, compared[vehicle.id]?.status) {
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
private fun VehicleChoiceRow(vehicle: NewVehicle, boarding: Stop?, now: Instant, selected: Boolean, status: AssignmentStatus? = null, onSelect: () -> Unit) {
    ListItem(headlineContent = {
        Text("Linie ${vehicle.line} · Fahrzeug ${vehicle.id}", fontWeight = FontWeight.SemiBold)
    }, supportingContent = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val destLabel = vehicle.routeLabel ?: vehicle.destination
            Text(if (destLabel.isBlank()) "Richtung nicht gemeldet" else "${if (vehicle.directionReliable || !vehicle.routeLabel.isNullOrBlank()) "Richtung" else "Anzeige am Bus:"} ${shortName(destLabel)}",
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val distance = boarding?.point?.distanceTo(vehicle.point)?.takeIf { it.isFinite() }
            val age = Duration.between(vehicle.observedAt, now).seconds
            Text(listOfNotNull(
                vehicle.currentStopName?.takeIf { it.isNotBlank() }?.let { "Halt ${shortName(it)}" },
                distance?.let { "ca. ${vehicleDistance(it)} zum Einstieg" },
                "NEW · ${vehicleAge(vehicle.observedAt, now)}"
            ).joinToString(" · "), fontSize = 12.sp,
                color = if (age >= 60) Amber else Soft)
            status?.let { Text(it.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = if (it == AssignmentStatus.WRONG_DIRECTION || it == AssignmentStatus.REJECTED) Amber else Paper) }
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

/**
 * The map of the running ride on the whole screen. Two solid bars sit over the map: line and source on top,
 * next stop, wait and arrival below. They carry a hairline instead of a shadow so the map stays calm.
 */
@Composable
private fun LiveHubFullscreen(
    ride: Ride,
    now: Instant,
    subtitle: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    map: @Composable (bottomClearance: androidx.compose.ui.unit.Dp) -> Unit
) {
    val targetIdx = ride.exitTargetIndex() ?: ride.stops.lastIndex
    val nextStop = ride.nextStopToTarget(now)
    val nextName = nextStop?.name?.let(::shortName) ?: shortName(ride.displayTarget().name)
    val nextAt = if (ride.boarded) nextStop?.effective(ride.offsetSeconds) else ride.liveBoardingTime(now)?.at ?: ride.boardingTime().at
    val nextMin = nextAt?.let { kotlin.math.ceil((it.epochSecond - now.epochSecond) / 60.0).toInt().coerceAtLeast(0) }
    val arrivalClock = ride.displayTarget().effective(ride.offsetSeconds)?.let(::clockTime)
    val remainingStops = (targetIdx - ride.nextIndex(now).coerceIn(ride.boardingIndex, targetIdx + 1) + 1).coerceAtLeast(0)
    val progress = ride.progressToTarget(now).coerceIn(0f, 1f)
    val delayMinutes = (ride.offsetSeconds / 60).toInt()
    val barBorder = androidx.compose.foundation.BorderStroke(1.dp, Soft.copy(alpha = 0.35f))

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize()) {
                // Bottom bar height at 150 % font size plus its outer padding; the map adds the navigation bar itself.
                map(120.dp)

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = Shape,
                    color = Panel,
                    border = barBorder
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Vollbild schließen", tint = Paper)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Linie ${ride.departure.line} · ${shortName(ride.displayTarget().name)}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Paper,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(subtitle, color = Soft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Refresh, "Busposition aktualisieren", tint = Paper)
                            }
                        }
                        if (loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = Shape,
                    color = Panel,
                    border = barBorder
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (ride.boarded) "Nächster Halt · $nextName" else "Abfahrt · $nextName",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val meta = buildList {
                            nextMin?.let { add(if (it <= 0) "jetzt" else "in $it Min") }
                            arrivalClock?.let { add("Ankunft $it") }
                            if (ride.boarded && remainingStops > 0) add(if (remainingStops == 1) "1 Halt" else "$remainingStops Halte")
                        }.joinToString(" · ")
                        if (meta.isNotEmpty() || delayMinutes != 0) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(meta, color = Paper, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                            // Times stay neutral; only a deviation from the timetable is coloured, always with text.
                            if (delayMinutes != 0) Text(
                                (if (meta.isEmpty()) "" else " · ") + (if (delayMinutes > 0) "+$delayMinutes Min" else "$delayMinutes Min"),
                                color = Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            // Lime is the theme accent; BrandBerry turns dark in dark mode and falls below 3:1 on Panel.
                            color = Lime,
                            trackColor = Soft.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}
