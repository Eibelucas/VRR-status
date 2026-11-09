package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Keeps NEW vehicle observations independent of VRR forecasts and stored school evidence. */
class LiveBusTracker(
    private val fetchFleet: suspend () -> List<NewVehicle>,
    private val fetchVehicle: suspend (String) -> NewVehicle?,
    private val clock: () -> Instant = Instant::now
) {
    private data class Fleet(val vehicles: List<NewVehicle>, val fetchedAt: Instant)
    private val mutex = Mutex()
    private var fleet: Fleet? = null
    private var journeyKey: String? = null
    private val history = VehicleHistory()

    /**
     * [phone] and [shape] are only read locally for [options]-controlled detection. Without
     * options the behaviour is the previous candidate/confirmation logic.
     */
    suspend fun refresh(ride: Ride, previous: LiveBusState, phone: List<PhoneFix> = emptyList(),
                        options: AutoMatchOptions = AutoMatchOptions.OFF, shape: RouteShape? = null): LiveBusState = mutex.withLock {
        val key = ride.departure.key
        if (journeyKey != key || previous.journeyKey != key) { fleet = null; history.clear() }
        journeyKey = key
        val base = previous.takeIf { it.journeyKey == key }
            ?: LiveBusState(journeyKey = key, revision = previous.revision)
        if (ride.demo || ride.departure.cancelled) {
            fleet = null
            return@withLock LiveBusState(journeyKey = key, revision = base.revision)
        }
        try {
            val selectedId = base.selectedVehicleId
            if (selectedId != null) {
                val manual = refreshSelected(ride, base, selectedId)
                val auto = if (options.enabled) AutoMatchEngine.step(base.auto, emptyList(), MatchMode.WAITING, clock(), options, manual = true)
                    else AutoMatchState(rejected = base.auto.rejected)
                return@withLock withRoute(manual.copy(auto = auto), shape, base)
            }

            var vehicles = fleetAt(clock())
            val knownId = base.vehicle?.id?.takeIf { base.confirmed }
                ?: base.auto.vehicleId?.takeIf { options.enabled && base.auto.holdsVehicle }
            var missingKnownVehicle = false
            if (knownId != null) {
                val observed = fetchVehicle(knownId)
                require(observed == null || observed.id == knownId)
                missingKnownVehicle = observed == null || !observed.isFresh(clock())
                vehicles = replace(vehicles, knownId, observed?.takeIf { it.isFresh(clock()) })
                fleet = fleet?.copy(vehicles = vehicles)
            }
            val now = clock()
            history.record(vehicles.filter { it.isFresh(now) }, now)
            val candidates = NewVehicleMatcher.candidates(ride, vehicles, now)
            val confirmed = NewVehicleMatcher.confirmed(ride, vehicles, now)
            val auto = if (options.enabled) {
                val reference = RouteReference.of(ride, shape)
                val mode = if (ride.boarded) MatchMode.RIDING else MatchMode.WAITING
                val scored = candidates.map { vehicle ->
                    val track = history.track(vehicle.id).filter { it.at.isBefore(vehicle.observedAt) }
                    val schedule = confirmed?.id == vehicle.id
                    if (mode == MatchMode.WAITING) VehicleScoring.waiting(ride, reference, vehicle, track, now, schedule)
                    else VehicleScoring.riding(ride, reference, vehicle, track, phone, now, schedule)
                }
                AutoMatchEngine.step(base.auto, scored, mode, now, options, manual = false)
            } else AutoMatchState(rejected = base.auto.rejected)
            val detected = auto.vehicleId?.takeIf { auto.holdsVehicle }
                ?.let { id -> vehicles.firstOrNull { it.id == id && it.isFresh(now) } }
            withRoute(base.copy(candidates = candidates, vehicle = confirmed ?: detected,
                selectedVehicleId = null, confirmed = confirmed != null, fetchedAt = now,
                loading = false, error = if (missingKnownVehicle && confirmed == null && detected == null &&
                    auto.status != AutoMatchStatus.RECHECKING)
                    "Für das bisherige Fahrzeug liegen keine aktuellen Positionsdaten vor." else null, auto = auto), shape, base)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            // A failed lookup must not let an older cached match reappear on the next refresh.
            fleet = null
            base.copy(candidates = emptyList(), vehicle = base.vehicle?.takeUnless { it.isFresh(clock()) },
                confirmed = false, loading = false, error = "NEW-Positionsdaten sind gerade nicht erreichbar.")
        }
    }

    private suspend fun refreshSelected(ride: Ride, state: LiveBusState, id: String): LiveBusState {
        val observed = fetchVehicle(id)
        require(observed == null || observed.id == id)
        val now = clock()
        if (observed == null || !observed.isFresh(now)) {
            fleet = fleet?.let { it.copy(vehicles = it.vehicles.filterNot { bus -> bus.id == id }) }
            return state.copy(candidates = NewVehicleMatcher.candidates(ride, state.candidates.filterNot { it.id == id }, now),
                vehicle = state.vehicle?.takeUnless { it.isFresh(now) }, confirmed = false,
                fetchedAt = now, loading = false, error = "Für dieses Fahrzeug liegen keine aktuellen Positionsdaten vor.")
        }
        if (!NewVehicleMatcher.matchesCandidate(ride, observed, now)) {
            fleet = fleet?.let { it.copy(vehicles = replace(it.vehicles, id, observed)) }
            return state.copy(candidates = NewVehicleMatcher.candidates(ride, state.candidates.filterNot { it.id == id }, now),
                vehicle = null, selectedVehicleId = null, confirmed = false, fetchedAt = now,
                loading = false, error = "Linie oder Richtung passen nicht mehr zur Auswahl.")
        }
        fleet = fleet?.let { it.copy(vehicles = replace(it.vehicles, id, observed)) }
        history.record(listOf(observed), now)
        return state.copy(candidates = NewVehicleMatcher.candidates(ride, replace(state.candidates, id, observed), now),
            vehicle = observed, confirmed = false, fetchedAt = now, loading = false, error = null)
    }

    private fun withRoute(state: LiveBusState, shape: RouteShape?, previous: LiveBusState): LiveBusState {
        val vehicle = state.vehicle ?: return state.copy(routePoint = null, routeOffset = null, offRoute = false)
        val around = previous.routeOffset?.takeIf { previous.vehicle?.id == vehicle.id }
        val result = RouteSnapping.vehicle(shape?.takeIf { it.journeyKey == state.journeyKey }, vehicle.point,
            history.track(vehicle.id).filter { it.at.isBefore(vehicle.observedAt) }, around)
        return state.copy(routePoint = result.projected?.point, routeOffset = result.projected?.offset, offRoute = result.deviating)
    }

    private suspend fun fleetAt(now: Instant): List<NewVehicle> {
        val cached = fleet?.takeIf { now.toEpochMilli() - it.fetchedAt.toEpochMilli() in 0L until 30_000L }
        if (cached != null) return cached.vehicles
        val observed = fetchFleet().toList()
        fleet = Fleet(observed, clock())
        return observed
    }

    private fun replace(vehicles: List<NewVehicle>, id: String, observed: NewVehicle?): List<NewVehicle> =
        vehicles.filterNot { it.id == id } + listOfNotNull(observed)

    companion object {
        /** A late result is applied only to the same running journey, boarding phase and revision. */
        fun resultApplies(state: TrackingState, journeyKey: String, boarded: Boolean, revision: Long): Boolean =
            state.running && state.ride?.departure?.key == journeyKey && state.ride?.boarded == boarded &&
                state.liveBus.revision == revision

        /** A click selects telemetry, not proof that this vehicle operates the chosen departure. */
        fun select(ride: Ride, state: LiveBusState, vehicle: NewVehicle, now: Instant): LiveBusState? {
            if (ride.demo || ride.departure.cancelled || state.journeyKey != ride.departure.key) return null
            val current = state.candidates.firstOrNull { it.id == vehicle.id } ?: return null
            if (!NewVehicleMatcher.matchesCandidate(ride, current, now)) return null
            return state.copy(vehicle = current, selectedVehicleId = current.id, confirmed = false,
                loading = false, error = null)
        }
    }
}
