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
    val searched: Boolean = false, val routeFilter: FrequentRoute? = null
)

data class ReportsState(
    val entries: List<DelayEvidence> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val share: File? = null
)

class RideViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MitfahrtApplication
    val tracking = app.store.state
    val lastRide = app.store.lastRide
    val arrivals = app.arrivals.marks
    val routes = app.routes.routes
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

    init { viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { app.reports.list() }
        if (!reportState.value.busy && reportState.value.entries.isEmpty()) reportState.value = reportState.value.copy(entries = saved)
    } }

    fun openHome() {
        if (homeJob?.isActive == true) return
        homeState.value = HomeState()
        homeJob = viewModelScope.launch {
            launch {
                HomeLocation(app).updates().collect { event ->
                    if (event.status in setOf(NearbyStatus.NO_PERMISSION, NearbyStatus.LOCATION_OFF)) {
                        nearbyJob?.cancel(); boardsJob?.cancel(); queryPoint = null; lastPoint = null
                        homeState.value = HomeState(status = event.status)
                    } else homeState.value = homeState.value.copy(status = event.status, approximate = event.approximate)
                    event.point?.let { point ->
                        lastPoint = point; lastFix = SystemClock.elapsedRealtime()
                        if (queryPoint?.distanceTo(point)?.let { it >= 100 } != false || lastFix - lastQuery >= 120_000)
                            loadNearby(point)
                    }
                }
            }
            launch { routes.drop(1).collect { refreshHomeDepartures() } }
            launch {
                while (isActive) {
                    delay(30_000)
                    if (lastPoint != null && SystemClock.elapsedRealtime() - lastFix > 90_000) {
                        nearbyJob?.cancel(); boardsJob?.cancel()
                        homeState.value = HomeState(status = NearbyStatus.UNAVAILABLE)
                        queryPoint = null
                    } else refreshHomeDepartures()
                }
            }
        }
    }

    fun closeHome() {
        homeJob?.cancel(); nearbyJob?.cancel(); boardsJob?.cancel(); queryPoint = null
        homeState.value = homeState.value.copy(loading = false, departuresLoading = false)
    }
    fun retryHome() { closeHome(); queryPoint = null; openHome() }
    fun clearRoutes() { app.routes.clear(); homeState.value = homeState.value.copy(departures = emptyList()) }

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
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                homeState.value = homeState.value.copy(loading = false, error = "Haltestellen konnten nicht geladen werden.")
            }
        }
    }

    private fun refreshHomeDepartures() {
        if (homeState.value.status != NearbyStatus.READY || homeState.value.stops.isEmpty() || routes.value.isEmpty() ||
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

    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun search() {
        val query = mutable.value.query.trim()
        if (query.length < 2) return
        load {
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), routeFilter = null)
            val stops = app.vrr.search(query)
            mutable.value = mutable.value.copy(stops = stops, searched = true)
        }
    }
    fun nearby() {
        if (homeState.value.stops.isNotEmpty() && homeState.value.status == NearbyStatus.READY) {
            job?.cancel()
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(),
                stops = homeState.value.stops, searched = true, loading = false, error = null, routeFilter = null)
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
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), routeFilter = null)
            mutable.value = mutable.value.copy(stops = app.vrr.nearby(point), searched = true)
        }
    }
    fun selectRoute(route: FrequentRoute) { selectStop(route.visit.stop, route) }
    fun selectStop(stop: Stop, route: FrequentRoute? = null) {
        load {
            mutable.value = mutable.value.copy(selectedStop = stop, departures = emptyList(), routeFilter = route)
            mutable.value = mutable.value.copy(departures = filterDepartures(app.vrr.departures(stop), route))
        }
    }
    fun backToStops() { job?.cancel(); mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), loading = false, error = null, routeFilter = null) }
    suspend fun refreshSelectedStop() {
        val previous = mutable.value
        val stop = previous.selectedStop ?: return
        if (previous.loading) return
        try {
            val departures = filterDepartures(app.vrr.departures(stop), previous.routeFilter)
            if (mutable.value.selectedStop?.id == stop.id && mutable.value.routeFilter?.key == previous.routeFilter?.key && !mutable.value.loading)
                mutable.value = mutable.value.copy(departures = departures)
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { /* Keep the timestamped last data; the manual refresh remains available. */ }
    }
    fun choose(departure: Departure) {
        if (departure.cancelled) return
        load {
            mutable.value = mutable.value.copy(choosing = departure.key)
            val ride = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = Instant.now())
            mutable.value = mutable.value.copy(pendingRide = ride, choosing = null)
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
