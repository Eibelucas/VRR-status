package de.luca.mitfahrt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

enum class PlaceField { ORIGIN, DESTINATION, VIA }

data class PlannerForm(
    val origin: TripPlace? = null,
    val destination: TripPlace? = null,
    val via: TripPlace? = null,
    val mode: TripTimeMode = TripTimeMode.NOW,
    val at: Instant? = null
) {
    val complete: Boolean get() = origin != null && destination != null && origin.key != destination.key
}

data class PlaceSearchState(
    val field: PlaceField? = null, val text: String = "", val results: List<TripPlace> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val searched: Boolean = false
)

/**
 * Journey planner. Separate from [RideViewModel]: it never starts a ride, never writes the
 * journey archive and never creates a saved route. Starting an accompaniment is an explicit
 * call to [RideViewModel.startJourney] from the screen.
 */
class TripPlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MitfahrtApplication
    private val settingsStore = AppSettingsStore(app)
    private val engine = TripSearchEngine(viewModelScope, { query, page -> app.vrr.trips(query, page) })
    val results: StateFlow<TripResultsState> = engine.state
    val places = app.tripPlaces.state

    private val formState = MutableStateFlow(PlannerForm())
    val form = formState.asStateFlow()
    private val placeState = MutableStateFlow(PlaceSearchState())
    val placeSearch = placeState.asStateFlow()
    private val selectedState = MutableStateFlow<String?>(null)
    /** Key of the journey whose details are open; the journey itself is read from [results]. */
    val selected = selectedState.asStateFlow()
    private var selectedSnapshot: TripJourney? = null
    private val waitingState = MutableStateFlow(false)
    /** "Mein Standort" is chosen but no fresh position is known yet. */
    val waitingForPosition = waitingState.asStateFlow()

    private var position: GeoPoint? = null
    private var placeJob: Job? = null

    /**
     * Called by the screen with the in-memory position; it is never stored. [unavailable] means
     * no permission, location off or switched off in Mitfahrt – not just "no fix yet".
     */
    fun updatePosition(point: GeoPoint?, unavailable: Boolean) {
        position = point?.takeIf { !unavailable && Geo.valid(it) }
        if (unavailable && formState.value.let { it.origin?.isMyLocation == true || it.destination?.isMyLocation == true }) {
            // Location was switched off: fall back to manual input instead of a silent failure.
            formState.value = formState.value.let { f ->
                f.copy(origin = f.origin?.takeUnless { it.isMyLocation }, destination = f.destination?.takeUnless { it.isMyLocation })
            }
            engine.clear(); waitingState.value = false
            return
        }
        if (waitingState.value && position != null) search()
    }

    /** Default start "Mein Standort" only when allowed and wanted. */
    fun applyDefaultOrigin(locationUsable: Boolean) {
        val settings = settingsStore.current()
        if (formState.value.origin == null && locationUsable && settings.bool("trip_my_location", true) &&
            settings.string("location_mode", "active") != "off") formState.value = formState.value.copy(origin = TripPlace.MY_LOCATION)
    }

    fun setPlace(field: PlaceField, place: TripPlace?) {
        val form = formState.value
        formState.value = when (field) {
            PlaceField.ORIGIN -> form.copy(origin = place)
            PlaceField.DESTINATION -> form.copy(destination = place)
            PlaceField.VIA -> form.copy(via = place)
        }
        changed()
    }

    fun swap() {
        val form = formState.value
        formState.value = form.copy(origin = form.destination, destination = form.origin)
        changed()
    }

    fun setTime(mode: TripTimeMode, at: Instant?) {
        formState.value = formState.value.copy(mode = mode, at = if (mode == TripTimeMode.NOW) null else at ?: Instant.now())
        changed()
    }

    fun setOptions(options: TripOptions) {
        TripSettings.values(options).forEach { (key, value) -> settingsStore.set(key, value) }
        changed()
    }

    fun options(): TripOptions = TripSettings.options(settingsStore.current())

    /** Every change of start, destination, time or options cancels the running search. */
    private fun changed() {
        selectedState.value = null; selectedSnapshot = null
        if (formState.value.complete) search() else { engine.clear(); waitingState.value = false }
    }

    fun search(force: Boolean = false) {
        val form = formState.value
        val origin = form.origin ?: return
        val destination = form.destination ?: return
        if (!form.complete) return
        val query = TripQuery(origin, destination, form.via, form.mode, form.at, options())
        if (query.needsOwnPosition && position == null) {
            engine.clear(); waitingState.value = true
            return
        }
        waitingState.value = false
        app.tripPlaces.remember(origin, destination)
        engine.search(query.copy(ownPosition = if (query.needsOwnPosition) position else null), force)
    }

    fun earlier() = engine.earlier()
    fun later() = engine.later()
    fun refresh() = engine.refresh()

    fun clear() {
        engine.clear(); selectedState.value = null; selectedSnapshot = null; waitingState.value = false
        formState.value = PlannerForm(origin = formState.value.origin)
    }

    fun openDetails(journey: TripJourney) { selectedSnapshot = journey; selectedState.value = journey.key }
    fun closeDetails() { selectedState.value = null; selectedSnapshot = null }
    /** The open journey with the newest data, or its last known state if VRR no longer returns it. */
    fun detailJourney(state: TripResultsState): TripJourney? {
        val key = selectedState.value ?: return null
        return state.journeys.firstOrNull { it.key == key }?.also { selectedSnapshot = it } ?: selectedSnapshot
    }

    // --- place search ---

    fun openPlaceSearch(field: PlaceField) {
        placeJob?.cancel()
        placeState.value = PlaceSearchState(field)
    }

    fun closePlaceSearch() { placeJob?.cancel(); placeState.value = PlaceSearchState() }

    fun placeQuery(text: String) {
        val field = placeState.value.field ?: return
        placeJob?.cancel()
        placeState.value = placeState.value.copy(text = text, error = null, loading = text.trim().length >= 2,
            results = if (text.isBlank()) emptyList() else placeState.value.results, searched = false)
        if (text.trim().length < 2) return
        placeJob = viewModelScope.launch {
            delay(350)
            try {
                val found = app.vrr.places(text)
                if (placeState.value.field == field && placeState.value.text == text)
                    placeState.value = placeState.value.copy(results = found, loading = false, searched = true)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (placeState.value.text == text) placeState.value = placeState.value.copy(loading = false,
                    error = "Orte konnten nicht geladen werden. Prüfe dein Internet.")
            }
        }
    }

    fun choosePlace(place: TripPlace) {
        val field = placeState.value.field ?: return
        closePlaceSearch()
        setPlace(field, place)
    }

    fun chooseRecent(trip: RecentTrip) {
        closePlaceSearch()
        formState.value = formState.value.copy(origin = trip.origin, destination = trip.destination)
        changed()
    }

    fun savePlace(place: TripPlace, label: String = "") = app.tripPlaces.save(place, label)
    fun unsavePlace(place: TripPlace) = app.tripPlaces.unsave(place)
    fun removeRecent(trip: RecentTrip) = app.tripPlaces.removeRecent(trip.key)
    fun restoreRecent(trip: RecentTrip, index: Int) = app.tripPlaces.restoreRecent(trip, index)

    /** Opens the planner for a saved route (stop to stop); creates nothing. */
    fun planRoute(plan: RoutePlan) {
        formState.value = PlannerForm(TripPlace.stop(plan.origin), TripPlace.stop(plan.destination))
        changed()
    }

    /** Opens the planner for alternatives from a transfer stop. */
    fun planQuery(query: TripQuery) {
        formState.value = PlannerForm(query.origin, query.destination, query.via, query.mode, query.at)
        changed()
    }

    /**
     * A direct NEW connection can become a saved route (stop to stop, line). Only on explicit
     * request; returns null when the journey cannot be expressed as such a route.
     */
    fun routeFrom(journey: TripJourney, name: String): RoutePlan? {
        val legs = journey.transit
        val leg = legs.singleOrNull()?.takeIf { it.isNew } ?: return null
        val origin = canonicalStopId(leg.origin.id) ?: return null
        val destination = canonicalStopId(leg.destination.id) ?: return null
        return RoutePlan(name = name.trim().take(40).ifBlank { "${shortName(leg.origin.name)} → ${shortName(leg.destination.name)}" },
            origin = Stop(origin, leg.origin.name, leg.origin.point), destination = Stop(destination, leg.destination.name, leg.destination.point),
            match = RouteMatch.LINE, line = leg.line, nearbyOnly = false)
    }

    fun saveRoute(plan: RoutePlan): Boolean = app.routePlans.save(plan)
}
