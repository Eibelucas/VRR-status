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
    val candidates: List<Departure> = emptyList(),
    val liveBus: LiveBusState = LiveBusState(),
    val pendingArrivalStopId: String? = null,
    val pendingArrivalAt: Instant? = null,
    val wifiBusDetected: Boolean = false,
    /** Verified real line geometry for the current journey, if any (public data, in memory). */
    val routeShape: RouteShape? = null,
    /** Locally measured progress; positions are never persisted or uploaded. */
    val gpsProgress: GpsProgressState? = null
)

class RideStore(context: Context, private val onRideUpdated: (Ride) -> Unit = {}) {
    private val settings = AppSettingsStore(context)
    private val prefs = context.getSharedPreferences("ride", Context.MODE_PRIVATE)
    // Keep deleted journeys hidden when their active tracking session refreshes or resumes.
    private var forgottenJourneys = runCatching {
        JSONArray(prefs.getString("forgotten_journeys", "[]")).let { entries ->
            (0 until entries.length()).mapNotNull { index ->
                entries.optString(index).takeIf { it.isNotBlank() }
            }.distinct().take(MAX_FORGOTTEN_JOURNEYS)
        }
    }.getOrDefault(emptyList())
    private val last = MutableStateFlow(runCatching {
        prefs.getString("last_ride", null)?.let(RideCodec::decode)?.takeUnless {
            it.demo || it.departure.key in forgottenJourneys || !settings.current().bool("history_enabled", true) ||
                expired(it, settings.current().int("history_days", 0))
        }
    }.getOrNull())
    val lastRide = last.asStateFlow()
    private val mutable = MutableStateFlow(TrackingState(ride = runCatching {
        prefs.getString("active", null)?.let { RideCodec.decode(it) }?.takeUnless {
            it.demo || it.complete(Instant.now()) || Instant.now().epochSecond - it.fetchedAt.epochSecond > 10_800
        }
    }.getOrNull()))
    val state = mutable.asStateFlow()
    fun update(transform: (TrackingState) -> TrackingState) { mutable.value = transform(mutable.value) }
    @Synchronized
    fun setRide(ride: Ride?, preserveStatus: Boolean = false) {
        update { it.copy(ride = ride, lastRefreshFailed = if (preserveStatus) it.lastRefreshFailed else false,
            message = if (preserveStatus) it.message else null,
            pendingArrivalStopId = if (it.ride?.departure?.key == ride?.departure?.key) it.pendingArrivalStopId else null,
            pendingArrivalAt = if (it.ride?.departure?.key == ride?.departure?.key) it.pendingArrivalAt else null,
            liveBus = if (it.ride?.departure?.key == ride?.departure?.key)
                it.liveBus.copy(revision = it.liveBus.revision + if (it.ride?.boarded != ride?.boarded) 1 else 0)
                else LiveBusState(journeyKey = ride?.departure?.key),
            routeShape = it.routeShape?.takeIf { shape -> shape.journeyKey == ride?.departure?.key },
            gpsProgress = it.gpsProgress?.takeIf { progress -> progress.journeyKey == ride?.departure?.key }) }
        val savedRide = ride?.takeUnless { it.demo }
        val encoded = savedRide?.let(RideCodec::encode)
        val rememberedRide = savedRide?.takeUnless { it.departure.key in forgottenJourneys || !settings.current().bool("history_enabled", true) }
        prefs.edit().apply {
            if (encoded == null) remove("active") else putString("active", encoded)
            if (rememberedRide != null) putString("last_ride", encoded)
            if (!settings.current().bool("history_enabled", true)) remove("last_ride")
        }.apply()
        if (rememberedRide != null) last.value = rememberedRide
        if (!settings.current().bool("history_enabled", true)) last.value = null
        savedRide?.let(onRideUpdated)
    }

    @Synchronized
    fun forgetLastRide(journeyKey: String): Ride? {
        val ride = last.value?.takeIf { it.departure.key == journeyKey } ?: return null
        forgottenJourneys = (listOf(journeyKey) + forgottenJourneys).distinct().take(MAX_FORGOTTEN_JOURNEYS)
        prefs.edit().remove("last_ride")
            .putString("forgotten_journeys", JSONArray(forgottenJourneys).toString()).apply()
        last.value = null
        return ride
    }

    @Synchronized
    fun restoreLastRide(ride: Ride): Boolean {
        // An old snackbar must not replace a newer trip or undo a later deletion.
        if (ride.demo || !settings.current().bool("history_enabled", true) || last.value != null || forgottenJourneys.firstOrNull() != ride.departure.key) return false
        val restored = mutable.value.ride?.takeIf { !it.demo && it.departure.key == ride.departure.key } ?: ride
        forgottenJourneys = forgottenJourneys.filterNot { it == ride.departure.key }
        prefs.edit().putString("last_ride", RideCodec.encode(restored))
            .putString("forgotten_journeys", JSONArray(forgottenJourneys).toString()).apply()
        last.value = restored
        return true
    }

    fun clear() {
        prefs.edit().remove("active").apply()
        mutable.value = TrackingState()
    }

    fun applyPrivacy() {
        val current = last.value
        if (!settings.current().bool("history_enabled", true) || current?.let { expired(it, settings.current().int("history_days", 0)) } == true) {
            prefs.edit().remove("last_ride").apply()
            last.value = null
        }
    }

    private fun expired(ride: Ride, days: Int): Boolean = days > 0 &&
        Instant.now().epochSecond - ride.fetchedAt.epochSecond > days.toLong() * 86_400

    private companion object { const val MAX_FORGOTTEN_JOURNEYS = 32 }
}

object RideCodec {
    fun encode(ride: Ride): String {
        val d = ride.departure
        return JSONObject().apply {
            put("lineId", d.lineId); put("line", d.line); put("destination", d.destination)
            put("stopId", d.stopId); put("stopName", d.stopName); put("tripCode", d.tripCode)
            put("planned", d.planned.toString()); put("estimated", d.estimated?.toString())
            put("observedAt", d.observedAt?.toString())
            if (d.operator.isNotBlank()) put("operator", d.operator)
            put("platform", d.platform); put("cancelled", d.cancelled); put("notices", JSONArray(d.notices))
            put("boardingIndex", ride.boardingIndex); put("fetchedAt", ride.fetchedAt.toString())
            put("demo", ride.demo); put("gpsPassedIndex", ride.gpsPassedIndex)
            put("boarded", ride.boarded); put("trackingStartedAt", ride.trackingStartedAt?.toString())
            put("targetStopId", ride.targetStopId); put("targetStopIndex", ride.targetStopIndex)
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
                (0 until a.length()).map { a.getString(it) } }.orEmpty(), obj.instant("observedAt"), obj.str("operator"))
        val stops = obj.getJSONArray("stops").objects().map { stop ->
            RideStop(stop.str("id"), stop.str("name"), if (stop.has("lat") && stop.has("lon"))
                GeoPoint(stop.getDouble("lat"), stop.getDouble("lon")) else null,
                stop.instant("arrival"), stop.instant("departure"), stop.instant("estimatedArrival"),
                stop.instant("estimatedDeparture"), stop.optBoolean("cancelled"))
        }
        require(stops.isNotEmpty())
        return Ride(d, stops, obj.getInt("boardingIndex"), Instant.parse(obj.str("fetchedAt")),
            obj.optBoolean("demo"), obj.optInt("gpsPassedIndex", -1),
            obj.optBoolean("boarded", true), obj.instant("trackingStartedAt"),
            obj.str("targetStopId").takeIf { it.isNotBlank() },
            if (obj.has("targetStopIndex") && !obj.isNull("targetStopIndex")) obj.getInt("targetStopIndex") else null)
    }
}
