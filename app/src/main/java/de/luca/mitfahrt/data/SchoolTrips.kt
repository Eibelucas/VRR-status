package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

enum class SchoolDirection(val label: String) { TO_SCHOOL("Zur Schule"), HOME("Nach Hause") }
data class SchoolTrip(val direction: SchoolDirection, val lineId: String, val line: String,
    val destination: String, val boarding: Stop, val targetId: String, val targetName: String, val targetOccurrence: Int = 0) {
    fun route(): FrequentRoute = FrequentRoute(RouteVisit("school/${direction.name}", lineId, line, destination, boarding, Instant.EPOCH), 1)
}

object SchoolTrips {
    fun preferred(now: Instant): SchoolDirection = if (now.atZone(ZoneId.of("Europe/Berlin")).hour < 12)
        SchoolDirection.TO_SCHOOL else SchoolDirection.HOME

    fun fromRide(direction: SchoolDirection, ride: Ride): SchoolTrip? {
        if (ride.demo || ride.departure.cancelled) return null
        val target = ride.exitTargetStop()?.takeUnless { it.cancelled } ?: return null
        val boarding = ride.stops.getOrNull(ride.boardingIndex) ?: return null
        return SchoolTrip(direction, ride.departure.lineId, ride.departure.line, ride.departure.destination,
            Stop(stopKey(boarding.id), boarding.name, boarding.point), stopKey(target.id), target.name, ride.targetOccurrence())
    }

    fun targetIndex(ride: Ride, targetId: String, occurrence: Int = 0): Int? = ride.stops.indices.filter {
        it > ride.boardingIndex && !ride.stops[it].cancelled && stopKey(ride.stops[it].id) == stopKey(targetId)
    }.getOrNull(occurrence.coerceAtLeast(0))

    fun encode(trips: List<SchoolTrip>): String = JSONArray(trips.map { trip -> JSONObject().apply {
        put("direction", trip.direction.name); put("lineId", trip.lineId); put("line", trip.line)
        put("destination", trip.destination); put("stopId", trip.boarding.id); put("stopName", trip.boarding.name)
        trip.boarding.point?.let { put("lat", it.lat); put("lon", it.lon) }
        put("targetId", trip.targetId); put("targetName", trip.targetName); put("targetOccurrence", trip.targetOccurrence)
    } }).toString()

    fun decode(value: String): List<SchoolTrip> = runCatching { JSONArray(value).objects().mapNotNull { obj ->
        runCatching {
            SchoolTrip(SchoolDirection.valueOf(obj.getString("direction")), obj.getString("lineId"), obj.getString("line"),
                obj.getString("destination"), Stop(obj.getString("stopId"), obj.getString("stopName"),
                    if (obj.has("lat") && obj.has("lon")) GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")) else null),
                obj.getString("targetId"), obj.getString("targetName"), obj.optInt("targetOccurrence", 0).coerceIn(0, 50))
        }.getOrNull()?.takeIf { it.lineId.isNotBlank() && it.boarding.id.isNotBlank() && it.targetId.isNotBlank() }
    }.distinctBy { it.direction } }.getOrDefault(emptyList())
}

class SchoolTripStore(context: Context) {
    private val prefs = context.getSharedPreferences("school_trips", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SchoolTrips.decode(prefs.getString("trips", "[]") ?: "[]"))
    val trips = mutable.asStateFlow()
    fun save(trip: SchoolTrip) { update(mutable.value.filter { it.direction != trip.direction } + trip) }
    fun remove(direction: SchoolDirection) { update(mutable.value.filter { it.direction != direction }) }
    private fun update(trips: List<SchoolTrip>) {
        prefs.edit().putString("trips", SchoolTrips.encode(trips)).apply(); mutable.value = trips
    }
}
