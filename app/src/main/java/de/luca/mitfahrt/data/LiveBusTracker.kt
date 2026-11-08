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

    suspend fun refresh(ride: Ride, previous: LiveBusState): LiveBusState = mutex.withLock {
        val key = ride.departure.key
        if (journeyKey != key || previous.journeyKey != key) fleet = null
        journeyKey = key
        val base = previous.takeIf { it.journeyKey == key }
            ?: LiveBusState(journeyKey = key, revision = previous.revision)
        if (ride.demo || ride.departure.cancelled) {
            fleet = null
            return@withLock LiveBusState(journeyKey = key, revision = base.revision)
        }
        try {
            val selectedId = base.selectedVehicleId
            if (selectedId != null) return@withLock refreshSelected(ride, base, selectedId)

            var vehicles = fleetAt(clock())
            val knownId = base.vehicle?.id?.takeIf { base.confirmed }
            var missingKnownVehicle = false
            if (knownId != null) {
                val observed = fetchVehicle(knownId)
                require(observed == null || observed.id == knownId)
                missingKnownVehicle = observed == null || !observed.isFresh(clock())
                vehicles = replace(vehicles, knownId, observed?.takeIf { it.isFresh(clock()) })
                fleet = fleet?.copy(vehicles = vehicles)
            }
            val now = clock()
            val candidates = NewVehicleMatcher.candidates(ride, vehicles, now)
            val confirmed = NewVehicleMatcher.confirmed(ride, vehicles, now)
            base.copy(candidates = candidates, vehicle = confirmed,
                selectedVehicleId = null, confirmed = confirmed != null, fetchedAt = now,
                loading = false, error = if (missingKnownVehicle && confirmed == null)
                    "Für das bisherige Fahrzeug liegen keine aktuellen Positionsdaten vor." else null)
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
        return state.copy(candidates = NewVehicleMatcher.candidates(ride, replace(state.candidates, id, observed), now),
            vehicle = observed, confirmed = false, fetchedAt = now, loading = false, error = null)
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
