package de.luca.mitfahrt.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.PlaceField
import de.luca.mitfahrt.PlaceSearchState
import de.luca.mitfahrt.PlannerForm
import de.luca.mitfahrt.TripPlannerViewModel
import de.luca.mitfahrt.data.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone = ZoneId.of("Europe/Berlin")

/** "Wohin?" at the top of the start page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhereToCard(form: PlannerForm, saved: List<SavedPlace>, onOrigin: () -> Unit, onDestination: () -> Unit,
    onSwap: () -> Unit, onSaved: (TripPlace) -> Unit) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceLine("Von", form.origin, "Start wählen", Icons.Outlined.TripOrigin, onOrigin)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(Ink)
                        .clickable(role = Role.Button, onClickLabel = "Ziel suchen", onClick = onDestination).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = Lime)
                        Spacer(Modifier.width(12.dp))
                        Text(form.destination?.let(::placeTitle) ?: "Wohin?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            color = if (form.destination == null) Soft else Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onSwap, enabled = form.origin != null || form.destination != null, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.SwapVert, "Start und Ziel tauschen", tint = Lime)
                }
            }
            if (saved.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                saved.take(6).forEach { place ->
                    AssistChip(onClick = { onSaved(place.place) }, label = { Text(place.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(16.dp), tint = Lime) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaceLine(label: String, place: TripPlace?, empty: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.Button, onClickLabel = "$label ändern", onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (place?.isMyLocation == true) Icons.Outlined.MyLocation else icon, null, tint = Soft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Soft, fontSize = 13.sp, modifier = Modifier.width(44.dp))
        Text(place?.let(::placeTitle) ?: empty, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (place == null) Soft else Paper,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

internal fun placeTitle(place: TripPlace): String = if (place.isMyLocation) "Mein Standort" else shortName(place.name)

/** Full-screen journey search: form, time, options, results; details and place search on top. */
@Composable
fun JourneyPlannerScreen(planner: TripPlannerViewModel, now: Instant, locationUsable: Boolean, positionNote: String?,
    onRequestLocation: () -> Unit, onClose: () -> Unit, onAccompany: (TripJourney) -> Unit,
    onSaveRoute: (RoutePlan) -> Unit, accompanyBusy: Boolean) {
    val form by planner.form.collectAsState()
    val results by planner.results.collectAsState()
    val places by planner.places.collectAsState()
    val placeSearch by planner.placeSearch.collectAsState()
    val selected by planner.selected.collectAsState()
    val waiting by planner.waitingForPosition.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    val detail = if (selected != null) planner.detailJourney(results) else null
    BackHandler(enabled = placeSearch.field == null && detail == null) { onClose() }
    // Live data is refreshed only while results or details are visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val interval = CustomizationPolicy.refreshMillis(LocalAppSettings.current).coerceAtLeast(30_000L)
    LaunchedEffect(results.query, interval, lifecycle) {
        if (results.query != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(interval); planner.refresh() }
        }
    }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text("Verbindung", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).semantics { heading() })
                val options = planner.options()
                TextButton(onClick = { showOptions = true }, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = if (options.changedFromDefault) "Suchoptionen, eigene Einstellungen aktiv" else "Suchoptionen" }) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Optionen")
                    if (options.changedFromDefault) Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(Lime))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { PlannerFormCard(form, onField = planner::openPlaceSearch, onSwap = planner::swap,
                    onRemoveVia = { planner.setPlace(PlaceField.VIA, null) }) }
                item { TimeRow(form, now, planner::setTime) }
                if (!form.complete) {
                    if (form.origin != null && form.destination != null) item {
                        Text("Start und Ziel sind gleich.", color = Amber, fontSize = 14.sp)
                    }
                    return@LazyColumn
                }
                if (waiting) item {
                    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (locationUsable) "Standort wird bestimmt …" else "Standort nicht verfügbar", fontWeight = FontWeight.SemiBold)
                            if (locationUsable) AppLinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!locationUsable) Button(onClick = onRequestLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text("Standort freigeben") }
                                OutlinedButton(onClick = { planner.openPlaceSearch(if (form.origin?.isMyLocation == true) PlaceField.ORIGIN else PlaceField.DESTINATION) },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Selbst wählen") }
                            }
                        }
                    }
                }
                resultItems(results, now, positionNote.takeIf { form.origin?.isMyLocation == true || form.destination?.isMyLocation == true },
                    onOpen = planner::openDetails, onEarlier = planner::earlier, onLater = planner::later,
                    onRefresh = planner::refresh, onRetry = { planner.search(force = true) })
            }
        }
    }
    if (showOptions) TripOptionsSheet(planner.options(), onApply = { planner.setOptions(it); showOptions = false }, onDismiss = { showOptions = false })
    if (detail != null) JourneyDetailsScreen(detail, results, now, onBack = planner::closeDetails, onAccompany = { onAccompany(detail) },
        accompanyBusy = accompanyBusy, routeFor = { name -> planner.routeFrom(detail, name) }, onSaveRoute = onSaveRoute,
        destinationSaved = form.destination?.let { places.isSaved(it) } == true,
        onSaveDestination = form.destination?.takeUnless { it.isMyLocation }?.let { place -> { planner.savePlace(place) } })
    if (placeSearch.field != null) PlacePicker(placeSearch, places, locationUsable, onQuery = planner::placeQuery,
        onPlace = planner::choosePlace, onRecent = planner::chooseRecent, onRemoveRecent = planner::removeRecent,
        onToggleSaved = { place -> if (places.isSaved(place)) planner.unsavePlace(place) else planner.savePlace(place) },
        onClose = planner::closePlaceSearch)
}

@Composable
private fun PlannerFormCard(form: PlannerForm, onField: (PlaceField) -> Unit, onSwap: () -> Unit, onRemoveVia: () -> Unit) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PlaceLine("Von", form.origin, "Start wählen", Icons.Outlined.TripOrigin) { onField(PlaceField.ORIGIN) }
                if (form.via != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PlaceLine("Über", form.via, "", Icons.Outlined.Route) { onField(PlaceField.VIA) } }
                    IconButton(onClick = onRemoveVia, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Zwischenhalt entfernen", tint = Soft) }
                }
                PlaceLine("Nach", form.destination, "Wohin?", Icons.Outlined.LocationOn) { onField(PlaceField.DESTINATION) }
                if (form.via == null) TextButton(onClick = { onField(PlaceField.VIA) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Zwischenhalt")
                }
            }
            IconButton(onClick = onSwap, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.SwapVert, "Start und Ziel tauschen", tint = Lime) }
        }
    }
}

@Composable
private fun TimeRow(form: PlannerForm, now: Instant, onTime: (TripTimeMode, Instant?) -> Unit) {
    val context = LocalContext.current
    fun pick(mode: TripTimeMode) {
        val start = (form.at ?: now).atZone(zone)
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                onTime(mode, LocalDate.of(year, month + 1, day).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant())
            }, start.hour, start.minute, true).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).apply { datePicker.minDate = now.minusSeconds(86_400L * 30).toEpochMilli() }.show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = form.mode == TripTimeMode.NOW, onClick = { onTime(TripTimeMode.NOW, null) }, label = { Text("Jetzt") },
                modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = form.mode == TripTimeMode.DEPART, onClick = { pick(TripTimeMode.DEPART) }, label = { Text("Abfahrt") },
                modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = form.mode == TripTimeMode.ARRIVE, onClick = { pick(TripTimeMode.ARRIVE) }, label = { Text("Ankunft") },
                modifier = Modifier.heightIn(min = 48.dp))
        }
        if (form.mode != TripTimeMode.NOW && form.at != null) TextButton(onClick = { pick(form.mode) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("${if (form.mode == TripTimeMode.ARRIVE) "Ankunft bis" else "Abfahrt ab"} ${dateTimeLabel(form.at, now)}")
        }
    }
}

internal fun dateTimeLabel(at: Instant, now: Instant): String {
    val local = at.atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), local.toLocalDate())
    val day = when (days) {
        0L -> "heute"
        1L -> "morgen"
        -1L -> "gestern"
        else -> local.format(DateTimeFormatter.ofPattern("EE dd.MM.", Locale.GERMANY))
    }
    return "$day, ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

internal fun durationLabel(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return if (minutes < 60) "$minutes Min" else "${minutes / 60} Std ${minutes % 60} Min"
}

private fun androidx.compose.foundation.lazy.LazyListScope.resultItems(state: TripResultsState, now: Instant, positionNote: String?,
    onOpen: (TripJourney) -> Unit, onEarlier: () -> Unit, onLater: () -> Unit, onRefresh: () -> Unit, onRetry: () -> Unit) {
    if (state.query == null && !state.loading) return
    item {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull("VRR", state.fetchedAt?.let { "Stand ${clockTime(it)}" }, positionNote).joinToString(" · "),
                color = Soft, fontSize = 12.sp, modifier = Modifier.weight(1f), lineHeight = 17.sp)
            IconButton(onClick = onRefresh, enabled = !state.loading && !state.refreshing, modifier = Modifier.size(48.dp)) {
                if (state.refreshing) AppCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, "Verbindungen aktualisieren", tint = Lime)
            }
        }
    }
    if (state.loading) { item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }; return }
    state.error?.let { message ->
        item {
            AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(18.dp)) {
                    Text(message, color = Amber, fontSize = 14.sp, lineHeight = 20.sp)
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Erneut versuchen") }
                }
            }
        }
        return
    }
    if (state.empty) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Der VRR hat keine passende Verbindung geliefert.", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Andere Zeit, weniger Einschränkungen oder einen anderen Start probieren.", color = Soft, fontSize = 13.sp)
            }
        }
        return
    }
    item {
        PagingButton(if (state.noEarlier) "Keine früheren Verbindungen geliefert" else "Frühere Verbindungen",
            state.paging == TripPaging.EARLIER, enabled = !state.noEarlier && state.paging == TripPaging.NONE, onEarlier)
    }
    items(state.journeys, key = { it.key }) { journey -> JourneyRow(journey, now) { onOpen(journey) } }
    item {
        PagingButton(if (state.noLater) "Keine späteren Verbindungen geliefert" else "Spätere Verbindungen",
            state.paging == TripPaging.LATER, enabled = !state.noLater && state.paging == TripPaging.NONE, onLater)
    }
    state.pageError?.let { item { Text(it, color = Amber, fontSize = 13.sp) } }
    item {
        Text("Zeiten in Farbe sind Live-Prognosen des VRR, sonst Fahrplan. Anschlüsse werden nur mit Live-Daten beider Fahrten bewertet.",
            color = Soft, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun PagingButton(label: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (loading) { AppCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(label)
    }
}

/** One connection: expected times, plan where different, duration, transfers, lines, warnings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JourneyRow(journey: TripJourney, now: Instant, onClick: () -> Unit) {
    val transfers = TripTransfers.assess(journey, now)
    val risk = transfers.minByOrNull { it.slackSeconds }?.risk
    val departure = journey.departure
    val arrival = journey.arrival
    val liveDeparture = now.epochSecond - journey.fetchedAt.epochSecond in -30..120 && journey.transit.firstOrNull()?.origin?.estimatedDeparture != null
    val liveArrival = now.epochSecond - journey.fetchedAt.epochSecond in -30..120 && journey.transit.lastOrNull()?.destination?.estimatedArrival != null
    val summary = buildString {
        append("Ab ${clockTime(departure)} an ${clockTime(arrival)}, ")
        journey.durationSeconds?.let { append("${durationLabel(it)}, ") }
        append(if (journey.interchanges == 0) "direkt" else "${journey.interchanges} Umstiege")
        append(". Linien ${journey.transit.joinToString(", ") { it.line.ifBlank { it.lineName } }}.")
        if (journey.cancelled) append(" Fahrt fällt aus.")
        if (risk == TransferRisk.MISSED) append(" Anschluss laut Live-Daten nicht erreichbar.")
        if (risk == TransferRisk.TIGHT) append(" Anschluss knapp.")
    }
    AppCard(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = summary }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TimeText(departure, journey.plannedDeparture, liveDeparture, 22)
                Text("  →  ", color = Soft, fontSize = 18.sp)
                TimeText(arrival, journey.plannedArrival, liveArrival, 22)
                Spacer(Modifier.weight(1f))
                journey.durationSeconds?.let { Text(durationLabel(it), fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                journey.legs.forEach { leg ->
                    when (leg.kind) {
                        LegKind.TRANSIT -> LegBadge(leg)
                        LegKind.WALK -> if (leg.durationSeconds >= 60) Text("${(leg.durationSeconds + 59) / 60}′ zu Fuß", color = Soft, fontSize = 12.sp)
                        LegKind.OTHER -> Unit
                    }
                }
            }
            Text(listOfNotNull(if (journey.interchanges == 0) "Direkt" else "${journey.interchanges} ${if (journey.interchanges == 1) "Umstieg" else "Umstiege"}",
                journey.walkingSeconds.takeIf { it >= 60 }?.let { "${durationLabel(it)} Fußweg" },
                journey.transit.firstOrNull()?.let { "ab ${shortName(it.origin.name)}" }).joinToString(" · "),
                color = Soft, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val warnings = buildList {
                if (journey.cancelled) add("Fahrt fällt aus")
                if (risk == TransferRisk.MISSED) add("Anschluss nicht erreichbar")
                else if (risk == TransferRisk.TIGHT) add("Anschluss knapp")
                journey.transit.mapNotNull { it.delaySeconds }.maxOrNull()?.takeIf { it >= 120 }?.let { add("+${it / 60} Min") }
                if (journey.legs.any { it.notices.isNotEmpty() }) add("Hinweise")
            }
            if (warnings.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, null, tint = Amber, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(warnings.joinToString(" · "), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (now.epochSecond - journey.fetchedAt.epochSecond !in -30..120)
                Text("Veralteter Datenstand · ${clockTime(journey.fetchedAt)}", color = Soft, fontSize = 11.sp)
        }
    }
}

/** Expected time large; the planned time only next to it when it differs. Live times use the accent colour. */
@Composable
internal fun TimeText(expected: Instant?, planned: Instant?, live: Boolean, size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(clockTime(expected), fontSize = size.sp, fontWeight = FontWeight.Bold, color = if (live) Lime else Paper)
        if (planned != null && expected != null && planned.epochSecond / 60 != expected.epochSecond / 60)
            Text(" ${clockTime(planned)}", fontSize = (size * .6).sp, color = Soft, textDecoration = TextDecoration.LineThrough,
                modifier = Modifier.semantics { contentDescription = "geplant ${clockTime(planned)}" })
    }
}

@Composable
internal fun LegBadge(leg: TripLeg) {
    val icon = when (ProductGroup.of(leg.productClass)) {
        ProductGroup.TRAIN -> Icons.Outlined.Train
        ProductGroup.LIGHT_RAIL -> Icons.Outlined.Tram
        else -> Icons.Outlined.DirectionsBus
    }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (leg.cancelled) Amber.copy(alpha = .15f) else Lime.copy(alpha = .12f))
        .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (leg.cancelled) Amber else Lime, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(leg.line.ifBlank { leg.lineName }.ifBlank { leg.productName }, color = if (leg.cancelled) Amber else Lime,
            fontWeight = FontWeight.Bold, fontSize = 14.sp, textDecoration = if (leg.cancelled) TextDecoration.LineThrough else null)
    }
}

/** Search for stops, addresses and places; saved destinations and recent searches first. */
@Composable
private fun PlacePicker(state: PlaceSearchState, places: TripPlacesState, locationUsable: Boolean,
    onQuery: (String) -> Unit, onPlace: (TripPlace) -> Unit, onRecent: (RecentTrip) -> Unit, onRemoveRecent: (RecentTrip) -> Unit,
    onToggleSaved: (TripPlace) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BackHandler { keyboard?.hide(); onClose() }
    LaunchedEffect(state.field) { runCatching { focus.requestFocus() } }
    val title = when (state.field) { PlaceField.ORIGIN -> "Start"; PlaceField.VIA -> "Zwischenhalt"; else -> "Ziel" }
    val suggestions = TripPlaces.suggestions(places, state.text)
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { keyboard?.hide(); onClose() }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                OutlinedTextField(value = state.text, onValueChange = onQuery, singleLine = true, shape = RoundedCornerShape(18.dp),
                    placeholder = { Text("$title: Haltestelle, Adresse, Ort") }, modifier = Modifier.weight(1f).focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
                    trailingIcon = { if (state.text.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Eingabe leeren") } })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (state.text.isBlank() && state.field != PlaceField.VIA && locationUsable) item {
                    PlaceRow(TripPlace.MY_LOCATION, saved = null, onToggleSaved = {}) { onPlace(TripPlace.MY_LOCATION) }
                }
                if (suggestions.isNotEmpty()) {
                    item { SectionLabel(if (state.text.isBlank()) "Gespeichert & zuletzt" else "Passend aus deinem Verlauf") }
                    items(suggestions, key = { "s/${it.key}" }) { place ->
                        PlaceRow(place, places.isSaved(place), { onToggleSaved(place) }) { onPlace(place) }
                    }
                }
                if (state.text.isBlank() && places.recent.isNotEmpty() && state.field != PlaceField.VIA) {
                    item { SectionLabel("Letzte Suchen") }
                    items(places.recent, key = { "r/${it.key}" }) { trip ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable(onClickLabel = "Suche übernehmen") { onRecent(trip) }.padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.History, null, tint = Soft, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("${placeTitle(trip.origin)} → ${placeTitle(trip.destination)}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onRemoveRecent(trip) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Close, "Suche ${placeTitle(trip.origin)} nach ${placeTitle(trip.destination)} löschen", tint = Soft)
                            }
                        }
                    }
                }
                if (state.loading) item { AppLinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
                state.error?.let { item { Text(it, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) } }
                if (state.results.isNotEmpty()) {
                    item { SectionLabel("VRR-Suche") }
                    items(state.results, key = { "v/${it.key}" }) { place ->
                        PlaceRow(place, places.isSaved(place), { onToggleSaved(place) }) { onPlace(place) }
                    }
                }
                if (state.searched && state.results.isEmpty() && !state.loading && state.error == null) item {
                    Text("Nichts gefunden. Schreibweise oder Ort prüfen.", color = Soft, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Soft, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun PlaceRow(place: TripPlace, saved: Boolean?, onToggleSaved: () -> Unit, onClick: () -> Unit) {
    val icon = when (place.kind) {
        PlaceKind.MY_LOCATION -> Icons.Outlined.MyLocation
        PlaceKind.STOP -> Icons.Outlined.DirectionsBus
        PlaceKind.ADDRESS, PlaceKind.STREET -> Icons.Outlined.Home
        PlaceKind.POI -> Icons.Outlined.Place
        else -> Icons.Outlined.LocationOn
    }
    val kind = when (place.kind) {
        PlaceKind.MY_LOCATION -> "Aktueller Standort, wird nur für diese Suche gesendet"
        PlaceKind.STOP -> "Haltestelle"
        PlaceKind.ADDRESS -> "Adresse"
        PlaceKind.STREET -> "Straße"
        PlaceKind.POI -> "Ort von Interesse"
        PlaceKind.LOCALITY -> "Ort"
        PlaceKind.OTHER -> "Ort"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (place.isMyLocation) Lime else Soft, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(placeTitle(place), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(kind, place.locality).filter(String::isNotBlank).joinToString(" · "), color = Soft, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (saved != null) IconToggleButton(checked = saved, onCheckedChange = { onToggleSaved() }, modifier = Modifier.size(48.dp)) {
            Icon(if (saved) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (saved) "${placeTitle(place)} nicht mehr speichern" else "${placeTitle(place)} als Ziel speichern", tint = if (saved) Lime else Soft)
        }
    }
}

/** Only options that the VRR interface documents; nothing else gets a switch. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TripOptionsSheet(initial: TripOptions, onApply: (TripOptions) -> Unit, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Panel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Suchoptionen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
            item { OptionLabel("Verkehrsmittel") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductGroup.entries.forEach { group ->
                        val on = group !in options.excluded
                        FilterChip(selected = on, onClick = {
                            val excluded = if (on) options.excluded + group else options.excluded - group
                            if (excluded.size < ProductGroup.entries.size) options = options.copy(excluded = excluded)
                        }, label = { Text(group.label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            item { OptionLabel("Bevorzugen") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RoutePreference.entries.forEach { preference ->
                        FilterChip(selected = options.preference == preference, onClick = { options = options.copy(preference = preference) },
                            label = { Text(preference.label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            item { OptionLabel("Umstiege höchstens") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = options.maxChanges == null, onClick = { options = options.copy(maxChanges = null) },
                        label = { Text("Beliebig") }, modifier = Modifier.heightIn(min = 48.dp))
                    TripOptions.MAX_CHANGES.forEach { count ->
                        FilterChip(selected = options.maxChanges == count, onClick = { options = options.copy(maxChanges = count) },
                            label = { Text(if (count == 0) "Nur direkt" else "$count") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            item { OptionLabel("Gehgeschwindigkeit") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WalkSpeed.entries.forEach { speed ->
                        FilterChip(selected = options.walkSpeed == speed, onClick = { options = options.copy(walkSpeed = speed) },
                            label = { Text(speed.label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            item { OptionLabel("Fußweg zur Haltestelle höchstens") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = options.maxWalkMinutes == null, onClick = { options = options.copy(maxWalkMinutes = null) },
                        label = { Text("Wie VRR") }, modifier = Modifier.heightIn(min = 48.dp))
                    TripOptions.MAX_WALK.forEach { minutes ->
                        FilterChip(selected = options.maxWalkMinutes == minutes, onClick = { options = options.copy(maxWalkMinutes = minutes) },
                            label = { Text("$minutes Min") }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            item { OptionLabel("Barrierearm") }
            item { OptionSwitch("Ohne Treppen", options.noSolidStairs) { options = options.copy(noSolidStairs = it) } }
            item { OptionSwitch("Ohne Rolltreppen", options.noEscalators) { options = options.copy(noEscalators = it) } }
            item { OptionSwitch("Niederflurfahrzeuge", options.lowFloor) { options = options.copy(lowFloor = it) } }
            item { OptionSwitch("Für Rollstuhl geeignet", options.wheelchair) { options = options.copy(wheelchair = it) } }
            item {
                Text("Der VRR berücksichtigt diese Wünsche nach seinen Daten. Einen eigenen Umstiegspuffer bietet die Schnittstelle nicht an.",
                    color = Soft, fontSize = 12.sp, lineHeight = 17.sp)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { options = TripOptions() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Zurücksetzen") }
                    Button(onClick = { onApply(options) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Übernehmen") }
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Switch) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
