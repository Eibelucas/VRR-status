package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Accompaniment of a planned journey with several legs. Exactly one leg is tracked at a time
 * with the existing single-ride tracking. The current leg only changes by an explicit action;
 * a passed timetable time never marks a leg as started.
 */
data class JourneyCompanion(
    val journey: TripJourney,
    /** Index in [TripJourney.legs] of the leg that is (or was last) tracked. */
    val legIndex: Int,
    /** Key of the ride started for [legIndex]; null while it is not started yet. */
    val trackedKey: String?,
    val startedAt: Instant,
    /** Final destination as chosen in the search (may be an address after the last footpath). */
    val destinationName: String = journey.legs.last().destination.name
) {
    val transitIndices: List<Int> get() = journey.legs.indices.filter { journey.legs[it].kind == LegKind.TRANSIT }
    val leg: TripLeg get() = journey.legs[legIndex]
    /** 1-based position among the vehicle legs, for "Abschnitt 1 von 2". */
    val position: Int get() = transitIndices.indexOf(legIndex) + 1
    val count: Int get() = transitIndices.size
    val nextIndex: Int? get() = transitIndices.firstOrNull { it > legIndex }
    val nextLeg: TripLeg? get() = nextIndex?.let { journey.legs[it] }
    /** Reported walking time between the current and the next vehicle. */
    val transferWalkSeconds: Long get() = nextIndex?.let { next ->
        journey.legs.subList(legIndex + 1, next).filter { it.kind == LegKind.WALK }.sumOf { it.durationSeconds }
    } ?: 0L
    val finalWalkSeconds: Long get() = journey.legs.drop(legIndex + 1).takeIf { nextIndex == null }.orEmpty()
        .filter { it.kind == LegKind.WALK }.sumOf { it.durationSeconds }
}

enum class CompanionPhase {
    /** The current leg is tracked (waiting or riding). */
    TRACKING,
    /** The current leg's ride ended; the next leg waits for an explicit start. */
    TRANSFER,
    /** The last leg's ride ended. */
    FINISHED,
    /** The current leg was never started, or another ride replaced it. */
    DETACHED
}

data class CompanionTransfer(val slackSeconds: Long, val risk: TransferRisk, val outgoingCancelled: Boolean)

object JourneyCompanions {
    /** Starts with the first vehicle leg; null if no leg can be tracked with a VRR trip identity. */
    fun start(journey: TripJourney, now: Instant): JourneyCompanion? {
        val first = journey.legs.indices.firstOrNull { journey.legs[it].kind == LegKind.TRANSIT } ?: return null
        if (journey.legs[first].asDeparture(journey.fetchedAt) == null) return null
        return JourneyCompanion(journey, first, null, now)
    }

    fun tracking(companion: JourneyCompanion, rideKey: String): JourneyCompanion = companion.copy(trackedKey = rideKey)

    /** Explicit transfer: the next vehicle leg becomes current, not yet tracked. */
    fun advance(companion: JourneyCompanion): JourneyCompanion? = companion.nextIndex?.let { companion.copy(legIndex = it, trackedKey = null) }

    fun phase(companion: JourneyCompanion, currentRideKey: String?, everTracked: Boolean): CompanionPhase = when {
        companion.trackedKey != null && currentRideKey == companion.trackedKey -> CompanionPhase.TRACKING
        companion.trackedKey == null -> CompanionPhase.DETACHED
        currentRideKey != null -> CompanionPhase.DETACHED
        !everTracked -> CompanionPhase.DETACHED
        companion.nextIndex != null -> CompanionPhase.TRANSFER
        else -> CompanionPhase.FINISHED
    }

    /**
     * Index of the leg's exit in the tracked ride: the exact stop point first, then the same
     * station after boarding. Null if the ride does not serve it.
     */
    fun exitIndex(ride: Ride, leg: TripLeg): Int? {
        val after = (ride.boardingIndex + 1..ride.stops.lastIndex)
        val planned = leg.destination.plannedArrival
        fun closest(candidates: List<Int>) = candidates.minByOrNull { index ->
            val time = ride.stops[index].arrival ?: ride.stops[index].departure
            if (planned == null || time == null) 0L else kotlin.math.abs(time.epochSecond - planned.epochSecond)
        }
        return closest(after.filter { ride.stops[it].id == leg.destination.id })
            ?: closest(after.filter { stopKey(ride.stops[it].id) == stopKey(leg.destination.id) })
    }

    /**
     * Transfer evaluation only from live forecasts on both sides: the expected arrival at the
     * exit of the tracked ride and the expected departure of the next leg. Otherwise null.
     */
    fun transfer(companion: JourneyCompanion, incoming: Ride?, outgoing: Ride?, now: Instant): CompanionTransfer? {
        val next = companion.nextLeg ?: return null
        val nextRide = outgoing?.takeIf { it.departure.key == next.asDeparture(companion.journey.fetchedAt)?.key &&
            now.epochSecond - it.fetchedAt.epochSecond in -30..180 } ?: return null
        if (nextRide.departure.cancelled) return CompanionTransfer(Long.MIN_VALUE, TransferRisk.MISSED, true)
        val ride = incoming?.takeIf { it.departure.key == companion.trackedKey } ?: return null
        val exit = ride.exitTargetStop() ?: return null
        val arrival = exit.estimatedArrival ?: exit.estimatedDeparture ?: return null
        if (now.epochSecond - ride.fetchedAt.epochSecond !in -30..180) return null
        val boarding = nextRide.stops.getOrNull(nextRide.boardingIndex)
        val departure = boarding?.estimatedDeparture ?: return null
        if (boarding.cancelled) return CompanionTransfer(Long.MIN_VALUE, TransferRisk.MISSED, true)
        val slack = departure.epochSecond - arrival.epochSecond - companion.transferWalkSeconds
        return CompanionTransfer(slack, when {
            slack < 0 -> TransferRisk.MISSED
            slack < TripTransfers.TIGHT_SECONDS -> TransferRisk.TIGHT
            else -> TransferRisk.OK
        }, false)
    }

    /** New search from the transfer stop to the journey's destination, for alternatives. */
    fun alternatives(companion: JourneyCompanion, now: Instant, options: TripOptions): TripQuery {
        val from = companion.leg.destination
        val last = companion.journey.legs.last().destination
        return TripQuery(
            origin = TripPlace(PlaceKind.STOP, stopKey(from.id), from.name, point = from.point),
            destination = TripPlace(if (last.id.startsWith("de:")) PlaceKind.STOP else PlaceKind.OTHER,
                if (last.id.startsWith("de:")) stopKey(last.id) else last.id, companion.destinationName, point = last.point),
            mode = TripTimeMode.NOW, at = null, options = options)
    }

    /** Short line for notifications and the exit hint, e.g. "Umstieg: RE8 um 08:20, Gleis 2". */
    fun transferHint(companion: JourneyCompanion): String? {
        val next = companion.nextLeg ?: return null
        val time = next.origin.plannedDeparture?.let(::clockTime) ?: return null
        return listOfNotNull("Umstieg: ${next.line.ifBlank { next.lineName }} um $time", next.origin.platform.takeIf { it.isNotBlank() })
            .joinToString(", ")
    }

    // --- persistence: the journey is public timetable data; no own position is part of it ---

    fun encode(companion: JourneyCompanion): String = JSONObject().apply {
        put("version", 1); put("legIndex", companion.legIndex); put("trackedKey", companion.trackedKey)
        put("startedAt", companion.startedAt.toString()); put("destinationName", companion.destinationName)
        put("fetchedAt", companion.journey.fetchedAt.toString()); put("interchanges", companion.journey.interchanges)
        put("legs", JSONArray(companion.journey.legs.map(::legJson)))
    }.toString()

    fun decode(value: String): JourneyCompanion? = runCatching {
        val obj = JSONObject(value)
        require(obj.optInt("version") == 1)
        val legs = obj.getJSONArray("legs").objects().map(::readLeg)
        require(legs.isNotEmpty())
        val journey = TripJourney(legs, obj.optInt("interchanges"), Instant.parse(obj.getString("fetchedAt")))
        val index = obj.getInt("legIndex")
        require(index in legs.indices && legs[index].kind == LegKind.TRANSIT)
        JourneyCompanion(journey, index, obj.str("trackedKey").ifBlank { null }, Instant.parse(obj.getString("startedAt")),
            obj.str("destinationName").ifBlank { legs.last().destination.name })
    }.getOrNull()

    private fun timeJson(stop: TripStopTime) = JSONObject().apply {
        put("id", stop.id); put("name", stop.name); put("platform", stop.platform); put("cancelled", stop.cancelled)
        stop.point?.let { put("lat", it.lat); put("lon", it.lon) }
        put("pa", stop.plannedArrival?.toString()); put("ea", stop.estimatedArrival?.toString())
        put("pd", stop.plannedDeparture?.toString()); put("ed", stop.estimatedDeparture?.toString())
    }

    private fun readTime(obj: JSONObject) = TripStopTime(obj.str("id"), obj.str("name"),
        if (obj.has("lat") && obj.has("lon")) GeoPoint(obj.getDouble("lat"), obj.getDouble("lon")).takeIf(Geo::valid) else null,
        obj.str("platform"), obj.instant("pa"), obj.instant("ea"), obj.instant("pd"), obj.instant("ed"), obj.optBoolean("cancelled"))

    private fun legJson(leg: TripLeg) = JSONObject().apply {
        put("kind", leg.kind.name); put("origin", timeJson(leg.origin)); put("destination", timeJson(leg.destination))
        put("lineId", leg.lineId); put("line", leg.line); put("lineName", leg.lineName)
        leg.productClass?.let { put("productClass", it) }; put("productName", leg.productName)
        put("direction", leg.direction); put("operator", leg.operatorCode); put("tripCode", leg.tripCode)
        put("intermediate", JSONArray(leg.intermediate.map(::timeJson)))
        put("duration", leg.durationSeconds); leg.distanceMetres?.let { put("distance", it) }
        put("notices", JSONArray(leg.notices)); put("footpath", JSONArray(leg.footpath))
        put("realtime", leg.realtime); put("cancelled", leg.cancelled)
        // The path is public line geometry but may be large; it is reloaded for the map when needed.
    }

    private fun readLeg(obj: JSONObject): TripLeg {
        fun strings(key: String) = obj.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
        return TripLeg(LegKind.valueOf(obj.getString("kind")), readTime(obj.getJSONObject("origin")), readTime(obj.getJSONObject("destination")),
            obj.str("lineId"), obj.str("line"), obj.str("lineName"), obj.takeIf { it.has("productClass") }?.optInt("productClass"),
            obj.str("productName"), obj.str("direction"), obj.str("operator"), obj.str("tripCode"),
            obj.optJSONArray("intermediate")?.objects().orEmpty().map(::readTime), emptyList(),
            obj.optLong("duration", 0L), obj.takeIf { it.has("distance") }?.optInt("distance"),
            strings("notices"), strings("footpath"), obj.optBoolean("realtime"), obj.optBoolean("cancelled"))
    }
}

/** Keeps the accompanied journey across app restarts while one of its legs is tracked. */
class JourneyCompanionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("journey_companion", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching { prefs.getString("state", null) }.getOrNull()?.let(JourneyCompanions::decode))
    val state = mutable.asStateFlow()
    /** Whether the current leg's ride has actually been seen running (survives restarts). */
    private val trackedSeen = MutableStateFlow(runCatching { prefs.getBoolean("seen", false) }.getOrDefault(false))
    val seen = trackedSeen.asStateFlow()

    @Synchronized fun set(companion: JourneyCompanion?) {
        val previousKey = mutable.value?.trackedKey
        mutable.value = companion
        if (companion?.trackedKey == null || companion.trackedKey != previousKey) trackedSeen.value = false
        prefs.edit().apply { if (companion == null) remove("state") else putString("state", JourneyCompanions.encode(companion)) }
            .putBoolean("seen", trackedSeen.value).apply()
    }
    @Synchronized fun markSeen(key: String) {
        if (mutable.value?.trackedKey != key || trackedSeen.value) return
        trackedSeen.value = true
        prefs.edit().putBoolean("seen", true).apply()
    }
    fun clear() = set(null)
}
