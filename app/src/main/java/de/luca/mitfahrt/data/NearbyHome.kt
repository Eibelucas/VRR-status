package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

enum class NearbyStatus { NO_PERMISSION, LOCATION_OFF, LOCATING, READY, UNAVAILABLE }
data class HomeState(val status: NearbyStatus = NearbyStatus.LOCATING, val stops: List<Stop> = emptyList(),
    val departures: List<Departure> = emptyList(), val loading: Boolean = false, val departuresLoading: Boolean = false,
    val approximate: Boolean = false, val updatedAt: Instant? = null, val error: String? = null)

data class NearbyDepartures(val departures: List<Departure>, val failed: Int)

class NearbyHomeLoader(private val fetchStops: suspend (GeoPoint) -> List<Stop>,
    private val fetchDepartures: suspend (Stop) -> List<Departure>) {
    suspend fun stops(point: GeoPoint): List<Stop> = fetchStops(point).map { stop -> stop.copy(distance = stop.distance ?: stop.point?.distanceTo(point)?.toInt()) }.distinctBy { stopKey(it.id) }
        .sortedBy { it.distance ?: it.point?.distanceTo(point)?.toInt() ?: Int.MAX_VALUE }.take(12)

    suspend fun departures(stops: List<Stop>): NearbyDepartures = coroutineScope {
        val semaphore = Semaphore(3)
        val results = stops.map { stop -> async {
            semaphore.withPermit {
                try { fetchDepartures(stop) to false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList<Departure>() to true }
            }
        } }.awaitAll()
        NearbyDepartures(results.flatMap { it.first }.distinctBy { "${it.key}/${it.stopId}" }, results.count { it.second })
    }
}

fun validHomeFix(point: GeoPoint, accuracy: Float, ageMillis: Long): Boolean =
    point.lat in -90.0..90.0 && point.lon in -180.0..180.0 && accuracy in 0f..3000f && ageMillis in 0..90_000
