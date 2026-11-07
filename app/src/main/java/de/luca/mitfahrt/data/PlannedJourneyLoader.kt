package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

data class PlannedDepartures(val departures: Map<String, List<Departure>>, val failed: Int)

/** Only a checked, later, served destination makes a departure a route suggestion. */
class PlannedJourneyLoader(
    private val fetchDepartures: suspend (Stop) -> List<Departure>,
    private val fetchRide: suspend (Departure) -> Ride
) {
    private data class Cached<T>(val value: T, val at: Instant)
    private data class Read<T>(val cached: Cached<T>?)
    private val loadMutex = Mutex()
    private val requests = Semaphore(3)
    private val boards = linkedMapOf<String, Cached<List<Departure>>>()
    private val trips = linkedMapOf<String, Cached<Ride>>()

    suspend fun load(plans: List<RoutePlan>, now: Instant): PlannedDepartures {
        val started = System.nanoTime()
        fun currentNow(): Instant = now.plusNanos((System.nanoTime() - started).coerceAtLeast(0))
        return loadMutex.withLock {
            prune(currentNow())
            val uniquePlans = plans.distinctBy { it.id }
            val origins = uniquePlans.map { it.origin }.distinctBy { stopKey(it.id) }
            val boardResults = coroutineScope {
                origins.map { origin ->
                    val key = stopKey(origin.id)
                    val cached = boards[key]?.takeIf { fresh(it.at, currentNow()) }
                    async {
                        key to if (cached != null) Read(cached) else requests.withPermit {
                            try { Read(Cached(fetchDepartures(origin), now)) }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { Read<List<Departure>>(null) }
                        }
                    }
                }.awaitAll().toMap()
            }
            // Only this parent coroutine mutates the caches, including under Default dispatchers.
            boardResults.forEach { (key, read) -> read.cached?.let { boards[key] = it } }

            val candidates = uniquePlans.associate { plan ->
                val departures = boardResults[stopKey(plan.origin.id)]?.cached?.value.orEmpty()
                val valid = RoutePlans.candidates(plan, listOf(plan.origin), departures, currentNow())
                plan.id to candidatesToCheck(plan, valid)
            }
            val lookups = candidates.values.flatten().distinctBy(::tripKey)
            val tripResults = coroutineScope {
                lookups.map { departure ->
                    val key = tripKey(departure)
                    val cached = trips[key]?.takeIf {
                        fresh(it.at, currentNow()) && validSource(it.value, departure, currentNow())
                    }
                    async {
                        key to if (cached != null) Read(cached) else requests.withPermit {
                            try {
                                val ride = fetchRide(departure)
                                val at = currentNow()
                                requireValidSource(ride, departure, at)
                                Read(Cached(ride, now))
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { Read<Ride>(null) }
                        }
                    }
                }.awaitAll().toMap()
            }
            tripResults.forEach { (key, read) -> read.cached?.let { trips[key] = it } }

            var failed = 0
            val result = uniquePlans.associate { plan ->
                val checked = candidates[plan.id].orEmpty()
                if (boardResults[stopKey(plan.origin.id)]?.cached == null || checked.any { departure ->
                    val ride = tripResults[tripKey(departure)]?.cached?.value
                    ride == null || !validSource(ride, departure, currentNow())
                }) failed++
                val verified = checked.filter { departure ->
                    val ride = tripResults[tripKey(departure)]?.cached?.value ?: return@filter false
                    validSource(ride, departure, currentNow()) && !ride.departure.cancelled &&
                        SchoolTrips.targetIndex(ride, plan.destination.id) != null
                }
                // A long network lookup must not publish a meanwhile stale or departed monitor entry.
                plan.id to RoutePlans.candidates(plan, listOf(plan.origin), verified, currentNow()).take(3)
            }
            prune(currentNow())
            PlannedDepartures(result, failed)
        }
    }

    private fun candidatesToCheck(plan: RoutePlan, candidates: List<Departure>): List<Departure> {
        // At major stations, examine several lines rather than eight buses of one wrong line.
        // A destination headsign is only a search priority; the stop sequence remains mandatory.
        val prioritized = candidates.sortedByDescending { departure ->
            SavedStops.matches(Stop("headsign", departure.destination), plan.destination.name)
        }
        val distinctDirections = prioritized.distinctBy { it.lineId to it.destination.trim().lowercase(java.util.Locale.ROOT) }
        return (distinctDirections + prioritized).distinctBy(::tripKey).take(8)
    }

    private fun requireValidSource(ride: Ride, departure: Departure, now: Instant) {
        require(!ride.demo && ride.departure.key == departure.key)
        require(stopKey(ride.departure.stopId) == stopKey(departure.stopId))
        val boarding = requireNotNull(ride.stops.getOrNull(ride.boardingIndex))
        require(stopKey(boarding.id) == stopKey(departure.stopId))
        require(now.epochSecond - ride.fetchedAt.epochSecond in -30..120)
    }

    private fun validSource(ride: Ride, departure: Departure, now: Instant): Boolean =
        runCatching { requireValidSource(ride, departure, now) }.isSuccess

    private fun tripKey(departure: Departure): String = "${stopKey(departure.stopId)}/${departure.key}"
    private fun fresh(at: Instant, now: Instant): Boolean = now.toEpochMilli() - at.toEpochMilli() in 0L until 30_000L

    private fun prune(now: Instant) {
        boards.entries.removeAll { !fresh(it.value.at, now) }
        trips.entries.removeAll { !fresh(it.value.at, now) }
        while (boards.size + trips.size > 100) {
            val board = boards.minByOrNull { it.value.at }
            val trip = trips.minByOrNull { it.value.at }
            if (board != null && (trip == null || board.value.at <= trip.value.at)) boards.remove(board.key)
            else if (trip != null) trips.remove(trip.key)
        }
    }
}
