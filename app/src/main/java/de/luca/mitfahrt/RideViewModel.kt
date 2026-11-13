package de.luca.mitfahrt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.reports.DelayEvidence
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID
import android.os.SystemClock
import de.luca.mitfahrt.tracking.HomeLocation
import kotlinx.coroutines.flow.drop

data class SearchState(
    val query: String = "", val stops: List<Stop> = emptyList(),
    val selectedStop: Stop? = null, val departures: List<Departure> = emptyList(),
    val loading: Boolean = false, val error: String? = null,
    val pendingRide: Ride? = null, val choosing: String? = null,
    val searched: Boolean = false, val routeFilter: FrequentRoute? = null, val schoolTrip: SchoolTrip? = null,
    val plannedRoute: RoutePlan? = null, val previewRide: Ride? = null,
    /** A journey leg replaces the current ride without the "Fahrt wechseln?" question. */
    val replaceRide: Boolean = false
)
data class AlternativesState(val forJourney: String? = null, val stops: List<Stop> = emptyList(),
    val departures: List<Departure> = emptyList(), val loading: Boolean = false, val error: String? = null)

data class PlanDeparturesState(val departures: Map<String, List<Departure>> = emptyMap(),
    val loading: Boolean = false, val error: String? = null)

data class ReportsState(
    val entries: List<DelayEvidence> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val share: File? = null, val deletingId: String? = null
)

class RideViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MitfahrtApplication
    val settingsStore = AppSettingsStore(app)
    val settings = settingsStore.state
    private val navigationState = MutableStateFlow<String?>(null)
    val navigation = navigationState.asStateFlow()
    fun consumeNavigation() { navigationState.value = null }
    fun handleLaunchIntent(intent: android.content.Intent) {
        if (intent.getBooleanExtra("open_settings", false)) { navigationState.value = "settings"; return }
        if (intent.getBooleanExtra("open_live_map", false) || intent.getStringExtra("widget_action") == "map") { navigationState.value = "map"; return }
        if (intent.getStringExtra("widget_kind") == "active" || intent.getStringExtra("widget_action") == "ride") {
            navigationState.value = "ride"; return
        }
        val id = intent.getStringExtra("widget_id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return
        when (intent.getStringExtra("widget_kind")) {
            "route" -> app.routePlans.current().routes.firstOrNull { it.id == id }?.let { selectRoutePlan(it, recordVisit = false); navigationState.value = "stops" }
            "stop" -> {
                val saved = app.savedStops.state.value
                val widgetStore = de.luca.mitfahrt.widgets.WidgetStore(app)
                val fromWidget = de.luca.mitfahrt.widgets.MitfahrtWidgets.ids(app).asSequence().mapNotNull(widgetStore::config)
                    .firstOrNull { it.kind == de.luca.mitfahrt.widgets.WidgetKind.STOP && stopKey(it.targetId) == stopKey(id) }
                    ?.let { Stop(it.targetId, it.targetName) }
                val stop = (saved.favorites + saved.recent).firstOrNull { stopKey(it.id) == stopKey(id) } ?: fromWidget
                if (stop != null) { selectStop(stop, recordVisit = false); navigationState.value = "stops" }
                else actionState.value = "Diese Haltestelle ist nicht mehr im Widget gespeichert. Bitte das Widget neu einrichten."
            }
            "active" -> navigationState.value = "ride"
        }
    }
    val tracking = app.store.state
    val lastRide = app.store.lastRide
    val arrivals = app.arrivals.marks
    val routes = app.routes.routes
    val routePlans = app.routePlans.state
    val dayOverrides = app.dayOverrides.state
    val schoolCalendar = app.schoolCalendar.state
    val archive = app.archive.state
    private val archivedLastRideUndo = mutableMapOf<Long, Ride>()
    private val lastRideArchiveUndo = mutableMapOf<String, JourneyArchiveUndo>()
    fun removeArchived(ids: Set<String>): JourneyArchiveUndo? {
        val undo = app.archive.remove(ids) ?: return null
        app.store.lastRide.value?.takeIf { JourneyArchive.id(it) in ids }?.let { ride ->
            app.store.forgetLastRide(ride.departure.key)?.let { archivedLastRideUndo[undo.sequence] = it }
        }
        return undo
    }
    fun restoreArchived(undo: JourneyArchiveUndo): Int {
        val restored = app.archive.restore(undo)
        archivedLastRideUndo.remove(undo.sequence)?.takeIf { ride -> archive.value.entries.any { it.id == JourneyArchive.id(ride) } }?.let(app.store::restoreLastRide)
        return restored
    }
    fun clearArchive() {
        app.archive.clear()
        if (archive.value.entries.isNotEmpty()) return
        archivedLastRideUndo.clear(); lastRideArchiveUndo.clear()
        app.store.lastRide.value?.let { app.store.forgetLastRide(it.departure.key) }
    }
    fun setArchiveEnabled(enabled: Boolean) { settingsStore.set("history_enabled", enabled.toString()) }
    fun setArchiveRetention(days: Int) { settingsStore.set("history_days", days.toString()) }
    fun removeArrival(mark: ArrivalMark) { app.arrivals.remove(mark.id) }
    fun saveDayOverride(item: RouteDayOverride): Boolean {
        if (routePlans.value.routes.none { it.id == item.routeId }) return false
        val saved = app.dayOverrides.save(item)
        if (saved && item.mode == DayOverrideMode.TIMES) routePlans.value.routes.firstOrNull { it.id == item.routeId }?.let { plan ->
            if (item.date.toString() in plan.excludedDates) app.routePlans.save(plan.copy(excludedDates = plan.excludedDates - item.date.toString()))
        }
        return saved
    }
    fun removeDayOverride(item: RouteDayOverride) {
        app.dayOverrides.remove(item.id)
        routePlans.value.routes.firstOrNull { it.id == item.routeId }?.let { plan ->
            if (item.date.toString() in plan.excludedDates) app.routePlans.save(plan.copy(excludedDates = plan.excludedDates - item.date.toString()))
        }
    }
    fun setCalendarEnabled(enabled: Boolean) { settingsStore.set("calendar_enabled", enabled.toString()) }
    fun addFreeDay(date: java.time.LocalDate, label: String): Boolean = app.schoolCalendar.addFreeDay(date, label)
    fun removeFreeDay(date: java.time.LocalDate) = app.schoolCalendar.removeFreeDay(date)
    fun markSchoolDay(date: java.time.LocalDate): Boolean = app.schoolCalendar.markSchoolDay(date)
    fun removeSchoolDay(date: java.time.LocalDate) = app.schoolCalendar.removeSchoolDay(date)
    private val planDepartureState = MutableStateFlow(PlanDeparturesState())
    val planDepartures = planDepartureState.asStateFlow()
    private val plannedLoader = PlannedJourneyLoader(app.vrr::departures, app.vrr::ride)
    private var plannedJob: Job? = null
    private var plannedGeneration = 0L
    private var lastPlannedPlans: List<RoutePlan> = emptyList()
    private var lastPlannedRefresh = 0L
    fun setAutomaticLearning(enabled: Boolean) { app.routePlans.setAutomaticLearning(enabled) }
    fun saveRoutePlan(plan: RoutePlan): Boolean {
        val saved = app.routePlans.save(plan)
        if (saved && mutable.value.plannedRoute?.id == plan.id) selectRoutePlan(plan, recordVisit = false)
        return saved
    }
    fun removeRoutePlan(plan: RoutePlan): RoutePlan? {
        val removed = app.routePlans.remove(plan.id)
        if (removed != null && mutable.value.plannedRoute?.id == plan.id) backToStops()
        return removed
    }
    fun restoreRoutePlan(plan: RoutePlan): Boolean = app.routePlans.restore(plan)
    fun moveRoutePlan(id: String, delta: Int) = app.routePlans.move(id, delta)
    suspend fun findRouteStops(query: String): List<Stop> = app.vrr.search(query)
    suspend fun resolveRouteStop(stop: Stop): Stop = app.vrr.resolveStop(stop)
    val schoolTrips = app.schoolTrips.trips
    val savedStops = app.savedStops.state
    private val alertStore = AlertPreferencesStore(app)
    val alertPreferences = alertStore.state
    fun setArrivalAlert(enabled: Boolean) = alertStore.setArrivalEnabled(enabled)
    fun setExitAlert(enabled: Boolean) = alertStore.setExitEnabled(enabled)
    fun setAlertLead(seconds: Int) = alertStore.setLeadSeconds(seconds)
    fun toggleFavorite(stop: Stop): Boolean = app.savedStops.toggleFavorite(stop)
    fun restoreFavorite(stop: Stop, index: Int) = app.savedStops.restoreFavorite(stop, index)
    fun removeRecentStop(stop: Stop) = app.savedStops.removeRecent(stop)
    private val hapticTestClient = de.luca.mitfahrt.alerts.HapticTestClient(app)
    val hapticTest = hapticTestClient.state
    fun testVibration() { viewModelScope.launch { hapticTestClient.run() } }
    fun selectLiveVehicle(journeyKey: String, vehicle: NewVehicle) {
        val state = tracking.value
        val ride = state.ride?.takeIf { it.departure.key == journeyKey } ?: return
        if (!state.running) return
        val selected = LiveBusTracker.select(ride, state.liveBus, vehicle, Instant.now()) ?: run {
            actionState.value = "Diese Position ist nicht mehr aktuell. Bitte erneut laden."; return
        }
        app.store.update { current ->
            if (current.ride?.departure?.key == journeyKey && current.liveBus.revision == state.liveBus.revision)
                current.copy(liveBus = selected.copy(revision = state.liveBus.revision + 1)) else current
        }
        de.luca.mitfahrt.tracking.RideService.refreshLive(app)
    }
    fun clearLiveVehicle(journeyKey: String) {
        app.store.update { state ->
            if (state.ride?.departure?.key == journeyKey) {
                // Clearing an automatically detected vehicle must not let it be re-assigned at once.
                val auto = state.liveBus.auto.vehicleId?.takeIf { state.liveBus.auto.holdsVehicle }
                    ?.let { de.luca.mitfahrt.data.AutoMatchEngine.reject(state.liveBus.auto, it) } ?: state.liveBus.auto
                state.copy(liveBus = state.liveBus.copy(
                    vehicle = null, selectedVehicleId = null, confirmed = false, error = null, auto = auto,
                    routePoint = null, routeOffset = null, offRoute = false,
                    loading = state.running, revision = state.liveBus.revision + 1))
            } else state
        }
        de.luca.mitfahrt.tracking.RideService.refreshLive(app)
    }
    /** "Nein" on "Bist du in diesem Bus?": this vehicle is not proposed again for this journey. */
    fun rejectLiveVehicle(journeyKey: String, vehicleId: String) {
        app.store.update { state ->
            if (state.ride?.departure?.key == journeyKey) state.copy(liveBus = state.liveBus.copy(
                auto = de.luca.mitfahrt.data.AutoMatchEngine.reject(state.liveBus.auto, vehicleId),
                vehicle = state.liveBus.vehicle?.takeUnless { it.id == vehicleId && state.liveBus.selectedVehicleId == null && !state.liveBus.confirmed },
                revision = state.liveBus.revision + 1))
            else state
        }
        de.luca.mitfahrt.tracking.RideService.refreshLive(app)
    }
    fun refreshLiveVehicle(journeyKey: String) {
        if (tracking.value.ride?.departure?.key == journeyKey) de.luca.mitfahrt.tracking.RideService.refreshLive(app)
    }
    private val alternativeState = MutableStateFlow(AlternativesState())
    val alternatives = alternativeState.asStateFlow()
    private var alternativeJob: Job? = null
    private val actionState = MutableStateFlow<String?>(null)
    val actionMessage = actionState.asStateFlow()
    private val homeState = MutableStateFlow(HomeState())
    val home = homeState.asStateFlow()
    private val homeLoader = NearbyHomeLoader({ point -> configuredNearby(point) }, app.vrr::departures)
    private var homeJob: Job? = null
    private var nearbyJob: Job? = null
    private var boardsJob: Job? = null
    private var lastPoint: GeoPoint? = null
    private var queryPoint: GeoPoint? = null
    private var lastQuery = 0L
    private var lastFix = 0L
    private val reportState = MutableStateFlow(ReportsState())
    val reports = reportState.asStateFlow()
    private val mutable = MutableStateFlow(SearchState())
    val search = mutable.asStateFlow()
    private var job: Job? = null
    private var queryJob: Job? = null
    private var nearbyQueries = true

    init {
        viewModelScope.launch { app.journeyReports.error.collect { error ->
            if (error != null) reportState.value = reportState.value.copy(error = error)
        } }
        viewModelScope.launch { app.archive.error.collect { message ->
            if (message != null) { actionState.value = message; app.archive.clearError() }
        } }
        viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { app.reports.list() }
        if (!reportState.value.busy && reportState.value.entries.isEmpty()) reportState.value = reportState.value.copy(entries = saved)
    } }

    fun openHome(lookupNearby: Boolean = true) {
        if (homeJob?.isActive == true) return
        if (settingsStore.current().string("location_mode", "active") == "off") {
            homeState.value = HomeState(status = NearbyStatus.NO_PERMISSION); refreshPlanDepartures(); return
        }
        nearbyQueries = lookupNearby
        homeState.value = HomeState()
        homeJob = viewModelScope.launch {
            launch {
                HomeLocation(app).updates().collect { event ->
                    if (event.status in setOf(NearbyStatus.NO_PERMISSION, NearbyStatus.LOCATION_OFF)) {
                        nearbyJob?.cancel(); boardsJob?.cancel(); queryPoint = null; lastPoint = null
                        homeState.value = HomeState(status = event.status)
                        mutable.value = mutable.value.copy(selectedStop = mutable.value.selectedStop?.copy(distance = null))
                    } else homeState.value = homeState.value.copy(status = event.status, approximate = event.approximate)
                    refreshPlanDepartures()
                    event.point?.let { point ->
                        lastPoint = point; lastFix = SystemClock.elapsedRealtime()
                        homeState.value = homeState.value.copy(point = point, pointAt = Instant.now())
                        mutable.value.selectedStop?.let { stop ->
                            mutable.value = mutable.value.copy(selectedStop = stop.copy(distance = stop.point?.distanceTo(point)?.toInt()))
                        }
                        if (nearbyQueries && (queryPoint?.distanceTo(point)?.let { it >= 100 } != false || lastFix - lastQuery >= 120_000))
                            loadNearby(point)
                    }
                }
            }
            launch { routes.drop(1).collect { if (nearbyQueries) refreshHomeDepartures() } }
            launch { schoolTrips.drop(1).collect { if (nearbyQueries) refreshHomeDepartures() } }
            launch { dayOverrides.drop(1).collect { if (nearbyQueries) refreshPlanDepartures(force = true) } }
            launch { schoolCalendar.drop(1).collect { if (nearbyQueries) refreshPlanDepartures(force = true) } }
            launch { routePlans.drop(1).collect { if (nearbyQueries) { refreshHomeDepartures(); refreshPlanDepartures(force = true) } } }
            refreshPlanDepartures()
            launch {
                while (isActive) {
                    delay(CustomizationPolicy.refreshMillis(settingsStore.current()))
                    if (lastPoint != null && SystemClock.elapsedRealtime() - lastFix > 90_000) {
                        nearbyJob?.cancel(); boardsJob?.cancel()
                        homeState.value = HomeState(status = NearbyStatus.UNAVAILABLE)
                        queryPoint = null
                        mutable.value = mutable.value.copy(selectedStop = mutable.value.selectedStop?.copy(distance = null))
                    } else if (nearbyQueries) refreshHomeDepartures()
                    if (nearbyQueries) refreshPlanDepartures()
                }
            }
        }
    }

    fun closeHome() {
        homeJob?.cancel(); nearbyJob?.cancel(); boardsJob?.cancel(); plannedJob?.cancel(); queryPoint = null
        plannedGeneration++; lastPlannedRefresh = 0L
        planDepartureState.value = planDepartureState.value.copy(loading = false)
        homeState.value = homeState.value.copy(loading = false, departuresLoading = false)
    }
    fun retryHome() { closeHome(); queryPoint = null; openHome() }
    fun clearRoutes() { app.routes.clear(); homeState.value = homeState.value.copy(departures = emptyList()) }
    fun removeRoute(route: FrequentRoute): List<RouteVisit> = app.routes.remove(route.key)
    fun restoreRoutes(visits: List<RouteVisit>) = app.routes.restore(visits)
    fun forgetLastRide(ride: Ride): Ride? {
        val removed = app.store.forgetLastRide(ride.departure.key) ?: return null
        app.archive.remove(setOf(JourneyArchive.id(ride)))?.let { lastRideArchiveUndo[ride.departure.key] = it }
        return removed
    }
    fun restoreLastRide(ride: Ride): Boolean {
        val restored = app.store.restoreLastRide(ride)
        lastRideArchiveUndo.remove(ride.departure.key)?.takeIf { restored }?.let(app.archive::restore)
        return restored
    }
    fun clearActionMessage() { actionState.value = null }
    fun setExitTarget(index: Int?) {
        tracking.value.ride?.let { app.store.setRide(it.withExitTarget(index), preserveStatus = true) }
    }
    fun saveSchoolTrip(direction: SchoolDirection) {
        val trip = tracking.value.ride?.let { SchoolTrips.fromRide(direction, it) }
        if (trip == null) actionState.value = "Wähle zuerst deine Ausstiegshaltestelle."
        else { app.schoolTrips.save(trip); actionState.value = "${direction.label} gespeichert" }
    }
    fun removeSchoolTrip(direction: SchoolDirection) { app.schoolTrips.remove(direction) }
    fun selectSchoolTrip(trip: SchoolTrip) { selectStop(trip.boarding, trip.route(), trip) }

    fun refreshAlternatives() {
        val ride = tracking.value.ride ?: return
        if (ride.demo || ride.boarded || !ride.departure.cancelled || alternativeJob?.isActive == true) return
        alternativeState.value = AlternativesState(forJourney = ride.departure.key, loading = true)
        alternativeJob = viewModelScope.launch {
            try {
                val current = tracking.value
                val point = if (lastPoint != null && SystemClock.elapsedRealtime() - lastFix <= 90_000) lastPoint
                    else current.location?.takeIf { current.locationAt?.let { Instant.now().epochSecond - it.epochSecond in 0..90 } == true }
                val boarding = ride.stops.getOrNull(ride.boardingIndex)
                val original = Stop(stopKey(ride.departure.stopId), ride.departure.stopName, boarding?.point,
                    if (point != null) boarding?.point?.distanceTo(point)?.toInt() else null)
                val nearby = if (point == null) emptyList() else runCatching { homeLoader.stops(point) }.getOrElse {
                    if (it is CancellationException) throw it
                    emptyList()
                }
                val stops = (nearby + original).distinctBy { stopKey(it.id) }
                val result = homeLoader.departures(stops)
                if (tracking.value.ride?.departure?.key == ride.departure.key)
                    alternativeState.value = AlternativesState(ride.departure.key, stops, result.departures,
                        error = if (result.failed > 0) "Einige Abfahrten sind gerade nicht erreichbar." else null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                alternativeState.value = AlternativesState(ride.departure.key, error = "Alternativen konnten nicht geladen werden.")
            }
        }
    }

    private fun loadNearby(point: GeoPoint) {
        val moved = queryPoint?.distanceTo(point)?.let { it >= 100 } != false
        queryPoint = point; lastQuery = SystemClock.elapsedRealtime()
        nearbyJob?.cancel(); boardsJob?.cancel()
        homeState.value = homeState.value.copy(loading = true, error = null,
            stops = if (moved) emptyList() else homeState.value.stops,
            departures = if (moved) emptyList() else homeState.value.departures)
        nearbyJob = viewModelScope.launch {
            try {
                val stops = CustomizationPolicy.stops(homeLoader.stops(point), settingsStore.current())
                homeState.value = homeState.value.copy(stops = stops, loading = false, updatedAt = Instant.now())
                refreshHomeDepartures()
                refreshPlanDepartures(force = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                homeState.value = homeState.value.copy(loading = false, error = "Haltestellen konnten nicht geladen werden.")
            }
        }
    }

    private fun refreshHomeDepartures() {
        if (homeState.value.status != NearbyStatus.READY || homeState.value.stops.isEmpty() ||
            nearbyJob?.isActive == true && homeState.value.loading || boardsJob?.isActive == true) return
        val stops = homeState.value.stops
        boardsJob = viewModelScope.launch {
            homeState.value = homeState.value.copy(departuresLoading = true)
            try {
                val result = homeLoader.departures(stops)
                homeState.value = homeState.value.copy(departures = CustomizationPolicy.departures(result.departures, settingsStore.current()),
                    stops = CustomizationPolicy.stops(homeState.value.stops, settingsStore.current(), result.departures), departuresLoading = false,
                    error = if (result.failed > 0) "Einige Abfahrten konnten nicht geladen werden." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    private fun refreshPlanDepartures(force: Boolean = false) {
        if (!nearbyQueries) return
        val now = Instant.now()
        val nearby = homeState.value.stops.takeIf { homeState.value.status == NearbyStatus.READY }.orEmpty()
        val plans = routePlans.value.routes.filter { CustomizationPolicy.active(it, now, nearby, settingsStore.current(), dayOverrides.value, schoolCalendar.value) }.take(settingsStore.current().int("routes_visible_count", 3))
        val changed = plans != lastPlannedPlans
        if (!force && !changed && (plannedJob?.isActive == true ||
                SystemClock.elapsedRealtime() - lastPlannedRefresh < 30_000)) return
        plannedJob?.cancel()
        val generation = ++plannedGeneration
        lastPlannedPlans = plans
        lastPlannedRefresh = SystemClock.elapsedRealtime()
        if (plans.isEmpty()) { planDepartureState.value = PlanDeparturesState(); return }
        planDepartureState.value = if (changed || force) PlanDeparturesState(loading = true)
            else planDepartureState.value.copy(loading = true, error = null)
        plannedJob = viewModelScope.launch {
            try {
                val result = plannedLoader.load(plans, now)
                if (generation == plannedGeneration) planDepartureState.value = PlanDeparturesState(result.departures,
                    error = if (result.failed > 0) "Einige Routen konnten nicht vollständig geladen werden." else null)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (generation == plannedGeneration) planDepartureState.value = planDepartureState.value.copy(
                    loading = false, error = "Routen gerade nicht erreichbar. Bitte erneut versuchen.")
            }
        }
    }

    fun selectRoutePlan(plan: RoutePlan, recordVisit: Boolean = true) {
        queryJob?.cancel()
        val visitId = UUID.randomUUID().toString()
        load {
            val point = lastPoint?.takeIf { SystemClock.elapsedRealtime() - lastFix <= 90_000 }
            val selected = plan.origin.copy(distance = point?.let { plan.origin.point?.distanceTo(it)?.toInt() })
            mutable.value = mutable.value.copy(selectedStop = selected, departures = emptyList(), routeFilter = null,
                schoolTrip = null, plannedRoute = plan, previewRide = null)
            val result = plannedLoader.load(listOf(plan), Instant.now())
            mutable.value = mutable.value.copy(departures = result.departures[plan.id].orEmpty(),
                error = if (result.failed > 0) "Einige Fahrten konnten nicht geprüft werden. Bitte erneut versuchen." else null)
            if (recordVisit && (result.failed == 0 || result.departures[plan.id].orEmpty().isNotEmpty()))
                app.savedStops.observe(selected, visitId)
        }
    }
    fun chooseRoutePlan(plan: RoutePlan, departure: Departure) { chooseWithTarget(departure, plan.destination.id, 0, preview = false) }
    /** Preview only: shows the trip, starts nothing and records nothing. */
    fun previewRoutePlan(plan: RoutePlan, departure: Departure) { chooseWithTarget(departure, plan.destination.id, 0, preview = true) }

    fun createReport(ride: Ride, stopIndex: Int) {
        if (reportState.value.busy) return
        // Freeze the selected ride before any suspension or service update.
        val snapshot = runCatching { DelayEvidence.fromRide(ride, stopIndex, personalArrivals = arrivals.value) }.getOrElse {
            reportState.value = reportState.value.copy(error = it.message); return
        }
        reportState.value = reportState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val (file, entries) = withContext(Dispatchers.IO) {
                    app.reports.create(snapshot)
                    app.reports.exportFile(snapshot) to app.reports.list()
                }
                reportState.value = ReportsState(entries = entries, share = file)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                reportState.value = reportState.value.copy(busy = false, error = "Der Bericht konnte nicht gespeichert werden.")
            }
        }
    }

    // --- journey reports: one per accompanied connection, exported only on request ---
    val journeyReports = app.journeyReports.state
    private fun reportSnapshot(): de.luca.mitfahrt.reports.ReportSnapshot {
        return de.luca.mitfahrt.reports.ReportSnapshot.capture(tracking.value, companion.value,
            app.companionNext.value, arrivals.value, Instant.now())
    }
    /** Report of the running accompaniment; created now if automatic preparation is off. */
    fun currentReportId(): String? = app.journeyReports.ensure(reportSnapshot())?.id
        ?: null.also { reportState.value = reportState.value.copy(error = "Für diese Fahrt kann kein Bericht angelegt werden.") }
    fun archivedReportId(ride: Ride): String? = app.journeyReports.forArchived(ride, arrivals.value)?.id
        ?: null.also { reportState.value = reportState.value.copy(error = "Der Bericht konnte nicht gespeichert werden.") }
    fun addObservation(id: String, text: String) {
        if (text.isNotBlank() && !app.journeyReports.observe(id, text))
            reportState.value = reportState.value.copy(error = "Die Angabe konnte nicht gespeichert werden.")
    }
    fun removeObservation(id: String, eventId: String) { app.journeyReports.removeObservation(id, eventId) }
    fun deleteJourneyReport(id: String) {
        actionState.value = if (runCatching { app.journeyReports.delete(id) }.getOrDefault(false)) "Bericht gelöscht"
            else "Der Bericht konnte nicht gelöscht werden. Bitte erneut versuchen."
    }
    /** Writes a new PDF on request and hands it to the share sheet or to a chosen file. */
    fun exportJourneyReport(id: String, target: android.net.Uri? = null) {
        if (reportState.value.busy) return
        reportState.value = reportState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    app.journeyReports.export(id).also { pdf ->
                        if (target != null) (app.contentResolver.openOutputStream(target, "wt") ?: error("Speicherort nicht verfügbar"))
                            .use { output -> pdf.inputStream().use { it.copyTo(output) } }
                    }
                }
                reportState.value = reportState.value.copy(busy = false, share = file.takeIf { target == null })
                if (target != null) actionState.value = "PDF gespeichert"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                reportState.value = reportState.value.copy(busy = false, error = "Die PDF konnte nicht erstellt werden.")
            }
        }
    }
    fun shareExport(id: String, export: de.luca.mitfahrt.reports.ReportExport) {
        val file = app.journeyReports.exportFile(id, export)
        reportState.value = reportState.value.copy(share = file, error = if (file == null) "Diese PDF ist nicht mehr vorhanden." else null)
    }
    fun exportName(id: String): String = journeyReports.value.firstOrNull { it.id == id }?.let { report ->
        de.luca.mitfahrt.reports.ReportCustomization.from(settingsStore.current()).exportName(report, (report.exports.maxOfOrNull { it.number } ?: 0) + 1)
    } ?: "Mitfahrt-Fahrtbericht.pdf"

    fun deleteReport(id: String) {
        val state = reportState.value
        if (state.busy || state.entries.none { it.id == id }) return
        reportState.value = state.copy(busy = true, deletingId = id, error = null, share = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { check(app.reports.delete(id)) }
                reportState.value = reportState.value.copy(entries = reportState.value.entries.filterNot { it.id == id },
                    busy = false, deletingId = null)
                actionState.value = "Bericht gelöscht"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                reportState.value = reportState.value.copy(busy = false, deletingId = null,
                    error = "Der Bericht konnte nicht gelöscht werden. Bitte erneut versuchen.")
            }
        }
    }

    fun saveReport(id: String, uri: android.net.Uri) { viewModelScope.launch {
        try {
            val evidence = reportState.value.entries.firstOrNull { it.id == id } ?: return@launch
            withContext(Dispatchers.IO) { app.reports.exportTo(evidence, uri) }
            actionState.value = "PDF gespeichert"
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { actionState.value = "Die PDF konnte nicht gespeichert werden." }
    } }
    fun shareReport(id: String) {
        val file = runCatching { reportState.value.entries.firstOrNull { it.id == id }?.let(app.reports::exportFile) }.getOrNull()
        reportState.value = reportState.value.copy(share = file,
            error = if (file == null) "Der Bericht ist nicht mehr vorhanden." else null)
    }
    fun consumeShare() { reportState.value = reportState.value.copy(share = null) }
    fun clearReportError() { reportState.value = reportState.value.copy(error = null); app.journeyReports.clearError() }
    fun markArrival(stop: Stop) { if (settingsStore.current().bool("arrival_store", true)) app.arrivals.record(stop) else actionState.value = "Speichern eigener Ankünfte ist ausgeschaltet." }
    fun confirmDetectedArrival() {
        val state = tracking.value
        val stop = state.ride?.stops?.firstOrNull { stopKey(it.id) == stopKey(state.pendingArrivalStopId.orEmpty()) }
        if (stop != null && state.pendingArrivalAt != null) app.arrivals.recordDetected(Stop(stop.id, stop.name, stop.point), state.pendingArrivalAt)
        dismissDetectedArrival()
    }
    fun dismissDetectedArrival() { app.store.update { it.copy(pendingArrivalStopId = null, pendingArrivalAt = null) } }
    fun clearData(area: String) { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { when (area) {
                "history" -> { clearArchive(); check(archive.value.entries.isEmpty()); app.routes.clear(); de.luca.mitfahrt.widgets.MitfahrtWidgets.clearCache(app) }
                "suggestions" -> { app.routes.clear(); app.savedStops.clearRecent(); app.savedStops.clearMuted() }
                "arrivals" -> app.arrivals.clear()
                "trip_history" -> app.tripPlaces.clearRecent()
                "trip_places" -> app.tripPlaces.clearAll()
                "reports" -> { check(app.journeyReports.clear()); check(app.reports.clear()) }
                "map" -> { val path = File(app.cacheDir, "waiting-map"); check(!path.exists() || path.deleteRecursively()) }
            } }
            if (area == "reports") { reportState.value = ReportsState(); app.journeyReports.reload() }
            actionState.value = "Daten gelöscht"
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { actionState.value = "Löschen fehlgeschlagen. Bitte erneut versuchen." }
    } }
    fun applySettings() {
        app.store.applyPrivacy()
        val config = settingsStore.current()
        if (!config.bool("arrival_store", true) || !config.bool("arrival_auto", false)) dismissDetectedArrival()
        app.archive.setEnabled(config.bool("history_enabled", true)); app.archive.setRetentionDays(config.int("history_days", 0))
        app.schoolCalendar.setEnabled(config.bool("calendar_enabled", true))
        app.dayOverrides.prune()
        app.savedStops.refresh(); app.routes.refresh(); app.arrivals.prune(); app.tripPlaces.refresh()
        de.luca.mitfahrt.updates.UpdateScheduler.schedule(app)
        de.luca.mitfahrt.widgets.MitfahrtWidgets.settingsChanged(app)
        if (homeJob?.isActive == true) retryHome()
        if (mutable.value.selectedStop != null) reloadSelectedStop()
    }
    private suspend fun configuredNearby(point: GeoPoint): List<Stop> {
        val selected = settingsStore.current()
        val radius = selected.int("nearby_radius", 500)
        val found = app.vrr.nearbyWithin(point, radius)
        return if (found.isEmpty() && selected.bool("nearby_expand", true)) app.vrr.nearbyWithin(point, 3000) else found
    }
    fun boardBus() { correctBoarding(de.luca.mitfahrt.tracking.RideService.BOARD) { it.board("manual", Instant.now()) } }
    /** "Doch nicht im Bus": back to waiting; the detection pauses for a few minutes. */
    fun notOnBoard() { correctBoarding(de.luca.mitfahrt.tracking.RideService.UNBOARD) { it.unboard() } }
    fun stillOnBoard() { correctBoarding(de.luca.mitfahrt.tracking.RideService.STILL_ON_BOARD) { it } }
    private fun correctBoarding(action: String, change: (Ride) -> Ride) {
        val state = tracking.value
        val ride = state.ride ?: return
        // The running service owns the detection state; without it the ride is changed directly.
        if (state.running && !ride.demo) runCatching { de.luca.mitfahrt.tracking.RideService.correct(app, action) }
            .onFailure { app.store.setRide(change(ride), preserveStatus = true) }
        else app.store.setRide(change(ride), preserveStatus = true)
    }

    fun query(value: String) {
        queryJob?.cancel(); job?.cancel()
        mutable.value = mutable.value.copy(query = value, stops = emptyList(), loading = false, searched = false, error = null)
        if (value.trim().length >= 2) queryJob = viewModelScope.launch { delay(350); search() }
    }
    fun search() {
        queryJob?.cancel()
        val query = mutable.value.query.trim()
        if (query.length < 2) return
        load {
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), routeFilter = null, schoolTrip = null, plannedRoute = null)
            val stops = app.vrr.search(query)
            mutable.value = mutable.value.copy(stops = stops, searched = true)
        }
    }
    fun nearby() {
        queryJob?.cancel()
        if (homeState.value.stops.isNotEmpty() && homeState.value.status == NearbyStatus.READY) {
            job?.cancel()
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(),
                stops = homeState.value.stops, searched = true, loading = false, error = null, routeFilter = null, schoolTrip = null, plannedRoute = null)
            return
        }
        val state = tracking.value
        val point = lastPoint ?: state.location
        val fresh = if (lastPoint != null) SystemClock.elapsedRealtime() - lastFix <= 90_000
            else state.locationAt?.let { Instant.now().epochSecond - it.epochSecond <= 90 } == true
        if (point == null || !fresh) {
            mutable.value = mutable.value.copy(error = "Standort wird gesucht. Du kannst die Haltestelle auch eintippen.")
            return
        }
        load {
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), routeFilter = null, schoolTrip = null, plannedRoute = null)
            mutable.value = mutable.value.copy(stops = CustomizationPolicy.stops(configuredNearby(point), settingsStore.current()), searched = true)
        }
    }
    fun selectRoute(route: FrequentRoute) { selectStop(route.visit.stop, route) }
    fun selectStop(stop: Stop, route: FrequentRoute? = null, schoolTrip: SchoolTrip? = null, recordVisit: Boolean = true) {
        queryJob?.cancel()
        val visitId = UUID.randomUUID().toString()
        load {
            val canonical = if (stop.id.startsWith("de:")) stop else app.vrr.resolveStop(stop)
            if (stopKey(canonical.id) != stopKey(stop.id)) {
                val previousIndex = savedStops.value.favorites.indexOfFirst { stopKey(it.id) == stopKey(stop.id) }
                if (previousIndex >= 0) {
                    app.savedStops.toggleFavorite(stop)
                    app.savedStops.restoreFavorite(canonical, previousIndex)
                }
            }
            val nearby = homeState.value.stops.firstOrNull { stopKey(it.id) == stopKey(canonical.id) }
            val point = lastPoint?.takeIf { SystemClock.elapsedRealtime() - lastFix <= 90_000 }
                ?: tracking.value.location?.takeIf { tracking.value.locationAt?.let { at -> Instant.now().epochSecond - at.epochSecond in 0..90 } == true }
            val selected = nearby ?: canonical.copy(distance = if (point != null) canonical.point?.distanceTo(point)?.toInt() else null)
            mutable.value = mutable.value.copy(selectedStop = selected, departures = emptyList(), routeFilter = route, schoolTrip = schoolTrip,
                plannedRoute = null, previewRide = null)
            mutable.value = mutable.value.copy(departures = filterDepartures(app.vrr.departures(canonical), route))
            if (recordVisit) app.savedStops.observe(selected, visitId)
        }
    }
    fun reloadSelectedStop() {
        val state = mutable.value
        state.plannedRoute?.let { selectRoutePlan(it, recordVisit = false); return }
        state.selectedStop?.let { selectStop(it, state.routeFilter, state.schoolTrip, recordVisit = false) }
    }
    fun backToStops() { job?.cancel(); mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), loading = false, error = null, routeFilter = null, schoolTrip = null, plannedRoute = null, previewRide = null) }
    suspend fun refreshSelectedStop() {
        val previous = mutable.value
        val stop = previous.selectedStop ?: return
        if (previous.loading) return
        try {
            val planned = previous.plannedRoute
            val checked = planned?.let { plannedLoader.load(listOf(it), Instant.now()) }
            val departures = if (planned != null) checked!!.departures[planned.id].orEmpty()
                else filterDepartures(app.vrr.departures(stop), previous.routeFilter)
            if (mutable.value.selectedStop?.id == stop.id && mutable.value.routeFilter?.key == previous.routeFilter?.key &&
                mutable.value.plannedRoute == planned && !mutable.value.loading) {
                if (checked?.failed != null && checked.failed > 0 && departures.isEmpty())
                    mutable.value = mutable.value.copy(error = "Neue Fahrtdaten gerade nicht erreichbar. Der letzte Stand bleibt sichtbar.")
                else mutable.value = mutable.value.copy(departures = departures, error = if (checked?.failed != null && checked.failed > 0)
                    "Einige Fahrten konnten nicht geprüft werden." else null)
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { /* Keep the timestamped last data; the manual refresh remains available. */ }
    }
    fun chooseHomeDeparture(departure: Departure) { chooseWithTarget(departure, null, 0) }
    fun chooseSchoolDeparture(trip: SchoolTrip, departure: Departure) { chooseWithTarget(departure, trip.targetId, trip.targetOccurrence, preview = false) }
    fun choose(departure: Departure) {
        val plan = mutable.value.plannedRoute
        if (plan != null) chooseWithTarget(departure, plan.destination.id, 0)
        else chooseWithTarget(departure, mutable.value.schoolTrip?.targetId, mutable.value.schoolTrip?.targetOccurrence ?: 0)
    }
    fun chooseAlternative(departure: Departure) { chooseWithTarget(departure, tracking.value.ride?.exitTargetStop()?.id, tracking.value.ride?.targetOccurrence() ?: 0) }
    private fun chooseWithTarget(departure: Departure, targetId: String?, occurrence: Int, preview: Boolean = true) {
        if (departure.cancelled) return
        val visitId = UUID.randomUUID().toString()
        load {
            mutable.value = mutable.value.copy(choosing = departure.key)
            val ride = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = Instant.now())
            val selected = if (targetId == null) ride else ride.withExitTarget(SchoolTrips.targetIndex(ride, targetId, occurrence))
            if (targetId != null && selected.exitTargetStop() == null) {
                actionState.value = "Diese Fahrt bedient deinen Ausstieg nicht. Bitte eine andere Fahrt wählen."
                mutable.value = mutable.value.copy(choosing = null)
                return@load
            }
            // A preview is no ride: route suggestions learn only from rides that are actually started.
            if (!preview) app.routes.observe(selected, visitId)
            mutable.value = if (preview) mutable.value.copy(previewRide = selected, choosing = null)
                else mutable.value.copy(pendingRide = selected, previewRide = null, choosing = null)
        }
    }
    fun previewLastRide(ride: Ride) { mutable.value = mutable.value.copy(previewRide = ride) }
    fun dismissPreview() { mutable.value = mutable.value.copy(previewRide = null) }
    fun confirmPreview() {
        val ride = mutable.value.previewRide ?: return
        app.routes.observe(ride, UUID.randomUUID().toString())
        mutable.value = mutable.value.copy(previewRide = null, pendingRide = ride)
    }
    fun consumeRide() { mutable.value = mutable.value.copy(pendingRide = null, replaceRide = false) }

    // --- accompaniment of a planned journey (several legs) ---
    val companion = app.companion.state
    val companionSeen = app.companion.seen
    private val companionNextState = app.companionNext
    /** Live trip of the next leg, for the transfer; null when not loaded or not available. */
    val companionNext = companionNextState.asStateFlow()
    private var companionNextJob: Job? = null

    /** Explicit "Fahrt begleiten": tracks the first vehicle leg with the existing ride tracking. */
    fun startJourney(journey: TripJourney) {
        val started = JourneyCompanions.start(journey, Instant.now())
        if (started == null) {
            actionState.value = "Diese Verbindung kann nicht begleitet werden: Für die erste Fahrt fehlt eine VRR-Fahrtkennung."
            return
        }
        companionNextState.value = null
        startCompanionLeg(started, replace = false)
    }

    /** Explicit transfer to the next leg; never triggered by time alone. */
    fun continueJourney() {
        val next = companion.value?.let(JourneyCompanions::advance) ?: return
        companionNextState.value = null
        startCompanionLeg(next, replace = true)
    }

    fun endJourney() { companionNextJob?.cancel(); companionNextState.value = null; app.companion.clear() }

    /** Starts the current leg again, e.g. after the permission dialog was declined. */
    fun resumeJourney() {
        val current = companion.value ?: return
        startCompanionLeg(current.copy(trackedKey = null), replace = false)
    }

    /** Search query for alternatives from the transfer stop; the screen opens the planner with it. */
    fun journeyAlternatives(): TripQuery? = companion.value?.let {
        JourneyCompanions.alternatives(it, Instant.now(), TripSettings.options(settingsStore.current()))
    }

    private fun startCompanionLeg(companion: JourneyCompanion, replace: Boolean) {
        val leg = companion.leg
        val departure = leg.asDeparture(companion.journey.fetchedAt) ?: run {
            actionState.value = "Für ${leg.line.ifBlank { "diesen Abschnitt" }} fehlt eine VRR-Fahrtkennung. Bitte neu suchen."
            return
        }
        load {
            mutable.value = mutable.value.copy(choosing = departure.key)
            val ride = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = Instant.now())
            val exit = JourneyCompanions.exitIndex(ride, leg)
            if (exit == null) {
                actionState.value = "Der Fahrtverlauf enthält ${shortName(leg.destination.name)} nicht mehr. Bitte neu suchen."
                mutable.value = mutable.value.copy(choosing = null)
                return@load
            }
            val selected = ride.withExitTarget(exit)
            app.companion.set(JourneyCompanions.tracking(companion, selected.departure.key))
            mutable.value = mutable.value.copy(pendingRide = selected, replaceRide = replace, previewRide = null, choosing = null)
        }
    }

    /** Loads the next leg's trip (any operator) for a live transfer check. */
    fun refreshCompanionNext() {
        val current = companion.value ?: return
        val departure = current.nextLeg?.asDeparture(current.journey.fetchedAt) ?: run { companionNextState.value = null; return }
        if (companionNextJob?.isActive == true) return
        companionNextJob = viewModelScope.launch {
            val ride = runCatching { app.vrr.ride(departure) }.getOrElse { if (it is CancellationException) throw it; null }
            // Only keep it while the same journey and leg are still accompanied.
            val active = companion.value
            if (active?.journey?.key == current.journey.key && active.startedAt == current.startedAt &&
                active.legIndex == current.legIndex && active.nextLeg?.asDeparture(active.journey.fetchedAt)?.key == departure.key) {
                companionNextState.value = ride
            }
        }
    }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    private fun filterDepartures(departures: List<Departure>, route: FrequentRoute?): List<Departure> = CustomizationPolicy.departures(departures, settingsStore.current()).filter {
        it.effective.isAfter(Instant.now().minusSeconds(180)) && (route == null ||
            it.lineId == route.visit.lineId && it.destination.trim().equals(route.visit.destination.trim(), true))
    }

    private fun load(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = when (e) {
                is java.io.IOException -> "Keine Verbindung. Bitte versuche es noch mal."
                else -> e.message ?: "Das hat nicht geklappt. Bitte noch mal versuchen."
            }, choosing = null) }
            finally { if (currentCoroutineContext().isActive) mutable.value = mutable.value.copy(loading = false) }
        }
    }
}
