package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.security.MessageDigest

data class RouteVisit(val journey: String, val lineId: String, val line: String, val destination: String,
    val stop: Stop, val at: Instant) {
    val routeKey: String get() = "$lineId/${destination.trim().lowercase(Locale.ROOT)}"
}
data class FrequentRoute(val visit: RouteVisit, val count: Int) { val key get() = visit.routeKey }
data class RouteSuggestion(val route: FrequentRoute, val stop: Stop, val departure: Departure)

object RouteHistory {
    const val MIN_SUGGESTION_VIEWS = 3
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

    fun remove(visits: List<RouteVisit>, routeKey: String): Pair<List<RouteVisit>, List<RouteVisit>> =
        visits.filterNot { it.routeKey == routeKey } to visits.filter { it.routeKey == routeKey }

    /** Current records win: an undo must not replace newer selections or refreshed metadata. */
    fun restore(visits: List<RouteVisit>, removed: List<RouteVisit>, now: Instant): List<RouteVisit> =
        (visits + removed).distinctBy { it.journey }
            .filter { it.at >= now.minusSeconds(90 * 86400L) && it.at <= now }
            .sortedByDescending { it.at }.take(200)

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

class RouteHistoryStore(context: Context, previousRide: Ride?, private val clock: () -> Instant = Instant::now) {
    private data class Observation(val route: String, val token: String, val at: Instant)
    private val prefs = context.getSharedPreferences("route_history", Context.MODE_PRIVATE)
    private val plans = RoutePlansStore(context)
    private val settings = AppSettingsStore(context)
    private var visits = RouteHistory.decode(prefs.getString("visits", "[]") ?: "[]")
    // Unqualified previews retain no line, destination, stop or actual journey information.
    private var observations = runCatching {
        JSONArray(prefs.getString("view_counts", "[]") ?: "[]").objects().mapNotNull { value ->
            runCatching {
                Observation(value.getString("route"), value.getString("token"), Instant.parse(value.getString("at")))
                    .takeIf { hashPattern.matches(it.route) && hashPattern.matches(it.token) }
            }.getOrNull()
        }.distinctBy { it.token }.sortedByDescending { it.at }.take(MAX_EVENTS)
    }.getOrDefault(emptyList())
    // Hashes suppress re-recording removed trips or views; a new deliberate view has a new token.
    private var deletedJourneys = runCatching {
        val array = JSONArray(prefs.getString("deleted_journeys", "[]") ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(hashPattern::matches) }
            .distinct().takeLast(512)
    }.getOrDefault(emptyList())
    private var clearedAt = runCatching { prefs.getString("cleared_at", null)?.let(Instant::parse) }.getOrNull()
    private val mutable = MutableStateFlow(RouteHistory.frequent(visits, clock()))
    val routes = mutable.asStateFlow()
    init {
        if (!prefs.getBoolean("initialized", false)) {
            previousRide?.takeUnless { it.demo || !learningEnabled() }?.let { ride ->
                val now = clock()
                visits = RouteHistory.record(visits, ride, minOf(ride.trackingStartedAt ?: ride.departure.planned, now))
            }
            prefs.edit().putBoolean("initialized", true).putString("visits", RouteHistory.encode(visits)).apply()
            mutable.value = RouteHistory.frequent(visits, clock())
        }
        persist()
    }

    /** Observe one explicit view. Background refreshes and preview-to-tracking reuse the same token. */
    @Synchronized fun observe(ride: Ride, visitId: String) {
        if (!learningEnabled() || visitId.isBlank() || visitId.length > 1024) return
        val d = ride.departure
        val boarding = ride.stops.getOrNull(ride.boardingIndex) ?: return
        if (ride.demo || d.cancelled || boarding.cancelled || d.lineId.isBlank() || d.line.isBlank() ||
            d.destination.isBlank() || d.stopId.isBlank() || d.stopName.isBlank()) return
        val now = clock()
        prune(now)
        val token = journeyHash(visitId)
        val eventId = "view/$token"
        if (journeyHash(eventId) in deletedJourneys || visits.any { it.journey == eventId } ||
            observations.any { it.token == token }) return
        val route = RouteVisit(eventId, d.lineId, d.line, d.destination, Stop(stopKey(d.stopId), d.stopName), now)
        val routeHash = journeyHash(route.routeKey)
        // Explicitly qualified previews continue counting when the display threshold rises.
        // One or two old actual journeys must never stand in for three deliberate previews.
        val existing = visits.filter { it.routeKey == route.routeKey }
        val qualified = existing.count { it.journey.startsWith("view/") } >= RouteHistory.MIN_SUGGESTION_VIEWS ||
            existing.count { !it.journey.startsWith("view/") } >= threshold()
        if (qualified) {
            visits = RouteHistory.restore(visits, listOf(route), now)
            observations = observations.filterNot { it.route == routeHash }
        } else {
            observations = observations + Observation(routeHash, token, now)
            val pending = observations.filter { it.route == routeHash }
            if (pending.size >= threshold()) {
                // These are view events, not claims that the earlier bus journeys were taken.
                visits = RouteHistory.restore(visits, pending.map { view ->
                    route.copy(journey = "view/${view.token}", at = view.at)
                }, now)
                observations = observations.filterNot { it.route == routeHash }
            }
        }
        persist(now)
    }

    /** Legacy actual-journey recording is kept for compatibility; previews use observe instead. */
    @Synchronized fun record(ride: Ride) {
        if (!learningEnabled()) return
        if (journeyHash(ride.departure.key) in deletedJourneys) return
        visits = RouteHistory.record(visits, ride, clock())
        persist()
    }
    @Synchronized fun remove(routeKey: String): List<RouteVisit> {
        val (remaining, removed) = RouteHistory.remove(visits, routeKey)
        val routeHash = journeyHash(routeKey)
        val pending = observations.filter { it.route == routeHash }
        if (removed.isEmpty() && pending.isEmpty()) return emptyList()
        rememberRemoved(removed)
        rememberViewTokens(pending)
        observations = observations.filterNot { it.route == routeHash }
        visits = remaining
        persist()
        return removed
    }
    @Synchronized fun restore(removed: List<RouteVisit>) {
        if (removed.isEmpty()) return
        // An old Undo cannot reverse a later explicit clearing of the entire history.
        val allowed = removed.filter { visit -> clearedAt?.let { visit.at > it } != false }
        if (allowed.isEmpty()) return
        visits = RouteHistory.restore(visits, allowed, clock())
        val requested = allowed.map { it.journey }.toSet()
        val restored = visits.filter { it.journey in requested }.map { journeyHash(it.journey) }.toSet()
        deletedJourneys = deletedJourneys.filterNot { it in restored }
        val restoredRoutes = visits.filter { it.journey in requested }.map { journeyHash(it.routeKey) }.toSet()
        observations = observations.filterNot { it.route in restoredRoutes }
        persist()
    }
    @Synchronized fun clear() {
        rememberRemoved(visits)
        rememberViewTokens(observations)
        visits = emptyList()
        observations = emptyList()
        clearedAt = clock()
        persist()
    }
    @Synchronized fun refresh() { persist() }
    private fun threshold() = settings.current().int("suggestion_threshold", 3).coerceIn(3, 10)
    private fun learningEnabled() = plans.current().automaticLearning && settings.current().bool("learn_routes", true) &&
        settings.current().bool("history_enabled", true)
    private fun retentionSeconds(): Long {
        val days = settings.current().int("history_days", 30).coerceIn(0, 3650)
        return (if (days == 0) 90 else days.coerceAtMost(90)) * 86400L
    }

    private fun rememberRemoved(removed: List<RouteVisit>) {
        deletedJourneys = (deletedJourneys + removed.map { journeyHash(it.journey) }).distinct().takeLast(512)
    }
    private fun rememberViewTokens(removed: List<Observation>) {
        deletedJourneys = (deletedJourneys + removed.map { journeyHash("view/${it.token}") }).distinct().takeLast(512)
    }
    private fun prune(now: Instant) {
        val cutoff = now.minusSeconds(retentionSeconds())
        visits = visits.filter { it.at in cutoff..now }.distinctBy { it.journey }
        observations = observations.filter { it.at in cutoff..now }.distinctBy { it.token }
        // Pending counters and materialized records share one storage bound.
        val newest = (visits.map { it.journey to it.at } + observations.map { "view/${it.token}" to it.at })
            .sortedByDescending { it.second }.take(MAX_EVENTS).map { it.first }.toSet()
        visits = visits.filter { it.journey in newest }.sortedByDescending { it.at }
        observations = observations.filter { "view/${it.token}" in newest }.sortedByDescending { it.at }
    }
    private fun persist(now: Instant = clock()) {
        prune(now)
        val counts = JSONArray(observations.map { observation -> JSONObject().apply {
            put("route", observation.route); put("token", observation.token); put("at", observation.at.toString())
        } })
        prefs.edit().putString("visits", RouteHistory.encode(visits)).putString("view_counts", counts.toString())
            .putString("deleted_journeys", JSONArray(deletedJourneys).toString())
            .putString("cleared_at", clearedAt?.toString()).apply()
        mutable.value = RouteHistory.frequent(visits, now)
    }
    private fun journeyHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_EVENTS = 200
        val hashPattern = Regex("[0-9a-f]{64}")
    }
}
