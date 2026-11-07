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
import android.os.SystemClock
import de.luca.mitfahrt.tracking.HomeLocation
import kotlinx.coroutines.flow.drop

data class SearchState(
    val query: String = "", val stops: List<Stop> = emptyList(),
    val selectedStop: Stop? = null, val departures: List<Departure> = emptyList(),
    val loading: Boolean = false, val error: String? = null,
    val pendingRide: Ride? = null, val choosing: String? = null,
    val searched: Boolean = false, val routeFilter: FrequentRoute? = null, val schoolTrip: SchoolTrip? = null,
    val plannedRoute: RoutePlan? = null
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
    val tracking = app.store.state
    val lastRide = app.store.lastRide
    val arrivals = app.arrivals.marks
    val routes = app.routes.routes
    val routePlans = app.routePlans.state
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
        if (saved && mutable.value.plannedRoute?.id == plan.id) selectRoutePlan(plan)
        return saved
    }
    fun removeRoutePlan(plan: RoutePlan): RoutePlan? {
        val removed = app.routePlans.remove(plan.id)
        if (removed != null && mutable.value.plannedRoute?.id == plan.id) backToStops()
        return removed
    }
    fun restoreRoutePlan(plan: RoutePlan): Boolean = app.routePlans.restore(plan)
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
    private val alternativeState = MutableStateFlow(AlternativesState())
    val alternatives = alternativeState.asStateFlow()
    private var alternativeJob: Job? = null
    private val actionState = MutableStateFlow<String?>(null)
    val actionMessage = actionState.asStateFlow()
    private val homeState = MutableStateFlow(HomeState())
    val home = homeState.asStateFlow()
    private val homeLoader = NearbyHomeLoader(app.vrr::nearby, app.vrr::departures)
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

    init { viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { app.reports.list() }
        if (!reportState.value.busy && reportState.value.entries.isEmpty()) reportState.value = reportState.value.copy(entries = saved)
    } }

    fun openHome(lookupNearby: Boolean = true) {
        if (homeJob?.isActive == true) return
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
            launch { routePlans.drop(1).collect { if (nearbyQueries) { refreshHomeDepartures(); refreshPlanDepartures(force = true) } } }
            refreshPlanDepartures()
            launch {
                while (isActive) {
                    delay(30_000)
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
    fun forgetLastRide(ride: Ride): Ride? = app.store.forgetLastRide(ride.departure.key)
    fun restoreLastRide(ride: Ride): Boolean = app.store.restoreLastRide(ride)
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
                val stops = homeLoader.stops(point)
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
        if (homeState.value.status != NearbyStatus.READY || homeState.value.stops.isEmpty() || ((routes.value.isEmpty() || !routePlans.value.automaticLearning) && schoolTrips.value.isEmpty()) ||
            nearbyJob?.isActive == true && homeState.value.loading || boardsJob?.isActive == true) return
        val stops = homeState.value.stops
        boardsJob = viewModelScope.launch {
            homeState.value = homeState.value.copy(departuresLoading = true)
            try {
                val result = homeLoader.departures(stops)
                homeState.value = homeState.value.copy(departures = result.departures, departuresLoading = false,
                    error = if (result.failed > 0) "Einige Abfahrten konnten nicht geladen werden." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    private fun refreshPlanDepartures(force: Boolean = false) {
        if (!nearbyQueries) return
        val now = Instant.now()
        val nearby = homeState.value.stops.takeIf { homeState.value.status == NearbyStatus.READY }.orEmpty()
        val plans = routePlans.value.routes.filter { RoutePlans.active(it, now, nearby) }.take(3)
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

    fun selectRoutePlan(plan: RoutePlan) {
        queryJob?.cancel()
        app.savedStops.select(plan.origin)
        load {
            val point = lastPoint?.takeIf { SystemClock.elapsedRealtime() - lastFix <= 90_000 }
            val selected = plan.origin.copy(distance = point?.let { plan.origin.point?.distanceTo(it)?.toInt() })
            mutable.value = mutable.value.copy(selectedStop = selected, departures = emptyList(), routeFilter = null,
                schoolTrip = null, plannedRoute = plan)
            val result = plannedLoader.load(listOf(plan), Instant.now())
            mutable.value = mutable.value.copy(departures = result.departures[plan.id].orEmpty(),
                error = if (result.failed > 0) "Einige Fahrten konnten nicht geprüft werden. Bitte erneut versuchen." else null)
        }
    }
    fun chooseRoutePlan(plan: RoutePlan, departure: Departure) { chooseWithTarget(departure, plan.destination.id, 0) }

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
                    app.reports.create(snapshot) to app.reports.list()
                }
                reportState.value = ReportsState(entries = entries, share = file)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                reportState.value = reportState.value.copy(busy = false, error = "Der Bericht konnte nicht gespeichert werden.")
            }
        }
    }

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

    fun shareReport(id: String) {
        val file = runCatching { app.reports.file(id).takeIf { it.isFile } }.getOrNull()
        reportState.value = reportState.value.copy(share = file,
            error = if (file == null) "Der Bericht ist nicht mehr vorhanden." else null)
    }
    fun consumeShare() { reportState.value = reportState.value.copy(share = null) }
    fun clearReportError() { reportState.value = reportState.value.copy(error = null) }
    fun markArrival(stop: Stop) { app.arrivals.record(stop) }
    fun boardBus() {
        tracking.value.ride?.let { app.store.setRide(it.copy(boarded = true), preserveStatus = true) }
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
            mutable.value = mutable.value.copy(stops = app.vrr.nearby(point), searched = true)
        }
    }
    fun selectRoute(route: FrequentRoute) { selectStop(route.visit.stop, route) }
    fun selectStop(stop: Stop, route: FrequentRoute? = null, schoolTrip: SchoolTrip? = null) {
        queryJob?.cancel()
        app.savedStops.select(stop)
        load {
            val nearby = homeState.value.stops.firstOrNull { stopKey(it.id) == stopKey(stop.id) }
            val point = lastPoint?.takeIf { SystemClock.elapsedRealtime() - lastFix <= 90_000 }
                ?: tracking.value.location?.takeIf { tracking.value.locationAt?.let { at -> Instant.now().epochSecond - at.epochSecond in 0..90 } == true }
            val selected = nearby ?: stop.copy(distance = if (point != null) stop.point?.distanceTo(point)?.toInt() else null)
            mutable.value = mutable.value.copy(selectedStop = selected, departures = emptyList(), routeFilter = route, schoolTrip = schoolTrip, plannedRoute = null)
            mutable.value = mutable.value.copy(departures = filterDepartures(app.vrr.departures(stop), route))
        }
    }
    fun backToStops() { job?.cancel(); mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), loading = false, error = null, routeFilter = null, schoolTrip = null, plannedRoute = null) }
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
    fun chooseSchoolDeparture(trip: SchoolTrip, departure: Departure) { chooseWithTarget(departure, trip.targetId, trip.targetOccurrence) }
    fun choose(departure: Departure) {
        val plan = mutable.value.plannedRoute
        if (plan != null) chooseRoutePlan(plan, departure)
        else chooseWithTarget(departure, mutable.value.schoolTrip?.targetId, mutable.value.schoolTrip?.targetOccurrence ?: 0)
    }
    fun chooseAlternative(departure: Departure) { chooseWithTarget(departure, tracking.value.ride?.exitTargetStop()?.id, tracking.value.ride?.targetOccurrence() ?: 0) }
    private fun chooseWithTarget(departure: Departure, targetId: String?, occurrence: Int) {
        if (departure.cancelled) return
        load {
            mutable.value = mutable.value.copy(choosing = departure.key)
            val ride = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = Instant.now())
            val selected = if (targetId == null) ride else ride.withExitTarget(SchoolTrips.targetIndex(ride, targetId, occurrence))
            if (targetId != null && selected.exitTargetStop() == null) {
                actionState.value = "Diese Fahrt bedient deinen Ausstieg nicht. Bitte eine andere Fahrt wählen."
                mutable.value = mutable.value.copy(choosing = null)
                return@load
            }
            mutable.value = mutable.value.copy(pendingRide = selected, choosing = null)
        }
    }
    fun consumeRide() { mutable.value = mutable.value.copy(pendingRide = null) }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    private fun filterDepartures(departures: List<Departure>, route: FrequentRoute?): List<Departure> = departures.filter {
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
