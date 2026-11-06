package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class TrackingState(
    val ride: Ride? = null, val running: Boolean = false,
    val monitoring: Boolean = false, val wifi: WifiSignal = WifiSignal.NONE,
    val location: GeoPoint? = null, val locationAt: Instant? = null,
    val message: String? = null, val lastRefreshFailed: Boolean = false,
    val candidates: List<Departure> = emptyList()
)

class RideStore(context: Context) {
    private val prefs = context.getSharedPreferences("ride", Context.MODE_PRIVATE)
    private val last = MutableStateFlow(runCatching {
        prefs.getString("last_ride", null)?.let(RideCodec::decode)?.takeUnless { it.demo }
    }.getOrNull())
    val lastRide = last.asStateFlow()
    private val mutable = MutableStateFlow(TrackingState(ride = runCatching {
        prefs.getString("active", null)?.let { RideCodec.decode(it) }?.takeUnless {
            it.demo || it.complete(Instant.now()) || Instant.now().epochSecond - it.fetchedAt.epochSecond > 10_800
        }
    }.getOrNull()))
    val state = mutable.asStateFlow()
    fun update(transform: (TrackingState) -> TrackingState) { mutable.value = transform(mutable.value) }
    fun setRide(ride: Ride?, preserveStatus: Boolean = false) {
        update { it.copy(ride = ride, lastRefreshFailed = if (preserveStatus) it.lastRefreshFailed else false,
            message = if (preserveStatus) it.message else null) }
        prefs.edit().apply {
            if (ride == null || ride.demo) remove("active") else putString("active", RideCodec.encode(ride))
            if (ride != null && !ride.demo) {
                last.value = ride
                putString("last_ride", RideCodec.encode(ride))
            }
        }.apply()
    }
    fun clear() {
        prefs.edit().remove("active").apply()
        mutable.value = TrackingState()
    }
}

object RideCodec {
    fun encode(ride: Ride): String {
        val d = ride.departure
        return JSONObject().apply {
            put("lineId", d.lineId); put("line", d.line); put("destination", d.destination)
            put("stopId", d.stopId); put("stopName", d.stopName); put("tripCode", d.tripCode)
            put("planned", d.planned.toString()); put("estimated", d.estimated?.toString())
            put("observedAt", d.observedAt?.toString())
            put("platform", d.platform); put("cancelled", d.cancelled); put("notices", JSONArray(d.notices))
            put("boardingIndex", ride.boardingIndex); put("fetchedAt", ride.fetchedAt.toString())
            put("demo", ride.demo); put("gpsPassedIndex", ride.gpsPassedIndex)
            put("boarded", ride.boarded); put("trackingStartedAt", ride.trackingStartedAt?.toString())
            put("stops", JSONArray(ride.stops.map { stop -> JSONObject().apply {
                put("id", stop.id); put("name", stop.name)
                put("lat", stop.point?.lat); put("lon", stop.point?.lon)
                put("arrival", stop.arrival?.toString()); put("departure", stop.departure?.toString())
                put("estimatedArrival", stop.estimatedArrival?.toString()); put("estimatedDeparture", stop.estimatedDeparture?.toString())
                put("cancelled", stop.cancelled)
            } }))
        }.toString()
    }

    fun decode(value: String): Ride {
        val obj = JSONObject(value)
        val d = Departure(obj.str("lineId"), obj.str("line"), obj.str("destination"), obj.str("stopId"),
            obj.str("stopName"), obj.str("tripCode"), Instant.parse(obj.str("planned")), obj.instant("estimated"),
            obj.str("platform"), obj.optBoolean("cancelled"), obj.optJSONArray("notices")?.let { a ->
                (0 until a.length()).map { a.getString(it) } }.orEmpty(), obj.instant("observedAt"))
        val stops = obj.getJSONArray("stops").objects().map { stop ->
            RideStop(stop.str("id"), stop.str("name"), if (stop.has("lat") && stop.has("lon"))
                GeoPoint(stop.getDouble("lat"), stop.getDouble("lon")) else null,
                stop.instant("arrival"), stop.instant("departure"), stop.instant("estimatedArrival"),
                stop.instant("estimatedDeparture"), stop.optBoolean("cancelled"))
        }
        require(stops.isNotEmpty())
        return Ride(d, stops, obj.getInt("boardingIndex"), Instant.parse(obj.str("fetchedAt")),
            obj.optBoolean("demo"), obj.optInt("gpsPassedIndex", -1),
            obj.optBoolean("boarded", true), obj.instant("trackingStartedAt"))
    }
}
