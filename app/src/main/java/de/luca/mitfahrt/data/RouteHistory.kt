package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

data class RouteVisit(val journey: String, val lineId: String, val line: String, val destination: String,
    val stop: Stop, val at: Instant) {
    val routeKey: String get() = "$lineId/${destination.trim().lowercase(Locale.ROOT)}"
}
data class FrequentRoute(val visit: RouteVisit, val count: Int) { val key get() = visit.routeKey }
data class RouteSuggestion(val route: FrequentRoute, val stop: Stop, val departure: Departure)

object RouteHistory {
    fun record(visits: List<RouteVisit>, ride: Ride, now: Instant): List<RouteVisit> {
        if (ride.demo) return visits
        val d = ride.departure
        if (d.cancelled || visits.any { it.journey == d.key }) return visits
        if (ride.stops.getOrNull(ride.boardingIndex) == null) return visits
        val visit = RouteVisit(d.key, d.lineId, d.line, d.destination,
            Stop(stopKey(d.stopId), d.stopName), now)
        return (visits + visit).filter { it.at >= now.minusSeconds(90 * 86400L) && it.at <= now }
            .sortedByDescending { it.at }.take(200)
    }

    fun frequent(visits: List<RouteVisit>, now: Instant): List<FrequentRoute> = visits
        .filter { it.at >= now.minusSeconds(90 * 86400L) && it.at <= now }
        .distinctBy { it.journey }.groupBy { it.routeKey }.values
        .map { FrequentRoute(it.maxBy { v -> v.at }, it.size) }
        .sortedWith(compareByDescending<FrequentRoute> { it.count }.thenByDescending { it.visit.at })

    fun suggestions(routes: List<FrequentRoute>, stops: List<Stop>, departures: List<Departure>, now: Instant): List<RouteSuggestion> =
        routes.mapNotNull { route ->
            departures.asSequence().filter { d ->
                !d.cancelled && d.lineId == route.visit.lineId &&
                    d.destination.trim().equals(route.visit.destination.trim(), true) &&
                    d.effective >= now && d.effective <= now.plusSeconds(3600) &&
                    d.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
            }.mapNotNull { d -> stops.firstOrNull { stopKey(it.id) == stopKey(d.stopId) }?.let { RouteSuggestion(route, it, d) } }
                .sortedWith(compareBy<RouteSuggestion> { it.stop.distance ?: Int.MAX_VALUE }.thenBy { it.departure.effective })
                .firstOrNull()
        }

    fun encode(visits: List<RouteVisit>): String = JSONArray(visits.map { v -> JSONObject().apply {
        put("journey", v.journey); put("lineId", v.lineId); put("line", v.line); put("destination", v.destination)
        put("stopId", v.stop.id); put("stopName", v.stop.name); put("at", v.at.toString())
    } }).toString()

    fun decode(json: String): List<RouteVisit> = runCatching { JSONArray(json).objects().mapNotNull { obj ->
        runCatching { RouteVisit(obj.getString("journey"), obj.getString("lineId"), obj.getString("line"),
            obj.getString("destination"), Stop(obj.getString("stopId"), obj.getString("stopName")), Instant.parse(obj.getString("at"))) }
            .getOrNull()?.takeIf { it.journey.isNotBlank() && it.lineId.isNotBlank() && it.stop.id.isNotBlank() }
    }.distinctBy { it.journey }.sortedByDescending { it.at }.take(200) }.getOrDefault(emptyList())
}

class RouteHistoryStore(context: Context, previousRide: Ride?) {
    private val prefs = context.getSharedPreferences("route_history", Context.MODE_PRIVATE)
    private var visits = RouteHistory.decode(prefs.getString("visits", "[]") ?: "[]")
    private val mutable = MutableStateFlow(RouteHistory.frequent(visits, Instant.now()))
    val routes = mutable.asStateFlow()
    init {
        if (!prefs.getBoolean("initialized", false)) {
            previousRide?.takeUnless { it.demo }?.let { ride ->
                val now = Instant.now()
                visits = RouteHistory.record(visits, ride, minOf(ride.trackingStartedAt ?: ride.departure.planned, now))
            }
            prefs.edit().putBoolean("initialized", true).putString("visits", RouteHistory.encode(visits)).apply()
            mutable.value = RouteHistory.frequent(visits, Instant.now())
        }
    }
    fun record(ride: Ride) {
        visits = RouteHistory.record(visits, ride, Instant.now())
        prefs.edit().putString("visits", RouteHistory.encode(visits)).apply()
        mutable.value = RouteHistory.frequent(visits, Instant.now())
    }
    fun clear() {
        visits = emptyList(); mutable.value = emptyList()
        prefs.edit().putString("visits", "[]").apply()
    }
}
