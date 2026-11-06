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

data class SearchState(
    val query: String = "", val stops: List<Stop> = emptyList(),
    val selectedStop: Stop? = null, val departures: List<Departure> = emptyList(),
    val loading: Boolean = false, val error: String? = null,
    val pendingRide: Ride? = null, val choosing: String? = null,
    val searched: Boolean = false
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
    private val reportState = MutableStateFlow(ReportsState())
    val reports = reportState.asStateFlow()
    private val mutable = MutableStateFlow(SearchState())
    val search = mutable.asStateFlow()
    private var job: Job? = null

    init { viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) { app.reports.list() }
        reportState.value = reportState.value.copy(entries = saved)
    } }

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

    fun query(value: String) { mutable.value = mutable.value.copy(query = value) }
    fun search() {
        val query = mutable.value.query.trim()
        if (query.length < 2) return
        load {
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList())
            val stops = app.vrr.search(query)
            mutable.value = mutable.value.copy(stops = stops, searched = true)
        }
    }
    fun nearby() {
        val state = tracking.value
        val point = state.location
        if (point == null || state.locationAt?.let { Instant.now().epochSecond - it.epochSecond > 90 } != false) {
            mutable.value = mutable.value.copy(error = "Standort wird gesucht. Du kannst die Haltestelle auch eintippen.")
            return
        }
        load {
            mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList())
            mutable.value = mutable.value.copy(stops = app.vrr.nearby(point), searched = true)
        }
    }
    fun selectStop(stop: Stop) {
        load {
            mutable.value = mutable.value.copy(selectedStop = stop, departures = emptyList())
            mutable.value = mutable.value.copy(departures = app.vrr.departures(stop).filter {
                it.effective.isAfter(Instant.now().minusSeconds(180))
            })
        }
    }
    fun backToStops() { job?.cancel(); mutable.value = mutable.value.copy(selectedStop = null, departures = emptyList(), loading = false, error = null) }
    fun choose(departure: Departure) {
        if (departure.cancelled) return
        load {
            mutable.value = mutable.value.copy(choosing = departure.key)
            val ride = app.vrr.ride(departure)
            mutable.value = mutable.value.copy(pendingRide = ride, choosing = null)
        }
    }
    fun consumeRide() { mutable.value = mutable.value.copy(pendingRide = null) }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }

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
