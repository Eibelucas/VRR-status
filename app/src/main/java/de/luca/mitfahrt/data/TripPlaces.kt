package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One recent search. "Mein Standort" is kept only as a marker, never with coordinates. */
data class RecentTrip(val origin: TripPlace, val destination: TripPlace, val at: Instant) {
    val key: String get() = "${origin.key}>${destination.key}"
}

data class SavedPlace(val place: TripPlace, val label: String = "") {
    val title: String get() = label.ifBlank { place.name }
}

data class TripPlacesState(val saved: List<SavedPlace> = emptyList(), val recent: List<RecentTrip> = emptyList()) {
    fun isSaved(place: TripPlace): Boolean = saved.any { it.place.key == place.key }
}

object TripPlaces {
    const val MAX_SAVED = 20
    const val MAX_RECENT = 12

    /** Removes everything that must not be stored: own position, invalid or oversized values. */
    fun clean(place: TripPlace): TripPlace? {
        if (place.isMyLocation) return TripPlace.MY_LOCATION
        val name = place.name.trim().take(200)
        val id = place.id.trim()
        if (name.isBlank() || id.length > 512 || id.startsWith("demo:")) return null
        if (id.isBlank() && place.point == null) return null
        return place.copy(id = id, name = name, locality = place.locality.trim().take(120), point = place.point?.takeIf(Geo::valid))
    }

    fun remember(state: TripPlacesState, origin: TripPlace, destination: TripPlace, at: Instant): TripPlacesState {
        val from = clean(origin) ?: return state
        val to = clean(destination)?.takeUnless { it.isMyLocation } ?: return state
        if (from.key == to.key) return state
        val entry = RecentTrip(from, to, at)
        return state.copy(recent = (listOf(entry) + state.recent.filterNot { it.key == entry.key }).take(MAX_RECENT))
    }

    fun removeRecent(state: TripPlacesState, key: String) = state.copy(recent = state.recent.filterNot { it.key == key })

    fun save(state: TripPlacesState, place: TripPlace, label: String = ""): TripPlacesState {
        val clean = clean(place)?.takeUnless { it.isMyLocation } ?: return state
        val existing = state.saved.indexOfFirst { it.place.key == clean.key }
        val entry = SavedPlace(clean, label.trim().take(40))
        return when {
            existing >= 0 -> state.copy(saved = state.saved.toMutableList().apply { set(existing, entry) })
            state.saved.size >= MAX_SAVED -> state
            else -> state.copy(saved = state.saved + entry)
        }
    }

    fun unsave(state: TripPlacesState, place: TripPlace) = state.copy(saved = state.saved.filterNot { it.place.key == place.key })

    fun prune(state: TripPlacesState, now: Instant, retentionDays: Int): TripPlacesState =
        if (retentionDays <= 0) state
        else state.copy(recent = state.recent.filter { it.at >= now.minusSeconds(retentionDays * 86_400L) })

    /** Suggestions for the destination field: saved places first, then recent destinations. */
    fun suggestions(state: TripPlacesState, text: String, limit: Int = 8): List<TripPlace> {
        val needle = text.trim().lowercase()
        val saved = state.saved.map { it.place }
        val recent = state.recent.map { it.destination } + state.recent.map { it.origin }.filterNot { it.isMyLocation }
        return (saved + recent).distinctBy { it.key }
            .filter { needle.isEmpty() || needle in it.name.lowercase() || needle in it.locality.lowercase() }
            .take(limit)
    }

    private fun placeJson(place: TripPlace) = JSONObject().apply {
        put("kind", place.kind.name); put("id", place.id); put("name", place.name)
        if (place.locality.isNotBlank()) put("locality", place.locality)
        // Only public coordinates of a chosen stop/address; never the user's own position.
        if (!place.isMyLocation) place.point?.let { put("lat", it.lat); put("lon", it.lon) }
    }

    private fun readPlace(obj: JSONObject): TripPlace? = runCatching {
        val kind = PlaceKind.valueOf(obj.getString("kind"))
        if (kind == PlaceKind.MY_LOCATION) return@runCatching TripPlace.MY_LOCATION
        val point = if (obj.has("lat") && obj.has("lon")) GeoPoint(obj.optDouble("lat", Double.NaN), obj.optDouble("lon", Double.NaN)) else null
        clean(TripPlace(kind, obj.optString("id"), obj.getString("name"), obj.optString("locality"), point))
    }.getOrNull()

    fun encode(state: TripPlacesState): String = JSONObject().apply {
        put("saved", JSONArray(state.saved.take(MAX_SAVED).map { saved ->
            placeJson(saved.place).apply { if (saved.label.isNotBlank()) put("label", saved.label) }
        }))
        put("recent", JSONArray(state.recent.take(MAX_RECENT).map { trip ->
            JSONObject().put("origin", placeJson(trip.origin)).put("destination", placeJson(trip.destination)).put("at", trip.at.toEpochMilli())
        }))
    }.toString()

    fun decode(value: String): TripPlacesState = runCatching {
        val obj = JSONObject(value)
        val saved = obj.optJSONArray("saved")?.objects().orEmpty().mapNotNull { item ->
            readPlace(item)?.takeUnless { it.isMyLocation }?.let { SavedPlace(it, item.optString("label").take(40)) }
        }.distinctBy { it.place.key }.take(MAX_SAVED)
        val recent = obj.optJSONArray("recent")?.objects().orEmpty().mapNotNull { item ->
            val origin = item.optJSONObject("origin")?.let(::readPlace) ?: return@mapNotNull null
            val destination = item.optJSONObject("destination")?.let(::readPlace)?.takeUnless { it.isMyLocation } ?: return@mapNotNull null
            RecentTrip(origin, destination, Instant.ofEpochMilli(item.optLong("at", 0L)))
        }.distinctBy { it.key }.take(MAX_RECENT)
        TripPlacesState(saved, recent)
    }.getOrDefault(TripPlacesState())
}

class TripPlacesStore(context: Context, private val clock: () -> Instant = Instant::now) {
    private val prefs = context.applicationContext.getSharedPreferences("trip_places", Context.MODE_PRIVATE)
    private val settings = AppSettingsStore(context.applicationContext)
    private var stored = TripPlaces.decode(runCatching { prefs.getString("state", "{}") }.getOrNull() ?: "{}")
    private val mutable = MutableStateFlow(stored)
    val state = mutable.asStateFlow()

    init { persist() }

    private fun historyEnabled() = settings.current().bool("trip_history", true)

    /** Called only after a search was actually started by the user. */
    @Synchronized fun remember(origin: TripPlace, destination: TripPlace) {
        if (!historyEnabled()) return
        stored = TripPlaces.remember(stored, origin, destination, clock()); persist()
    }
    @Synchronized fun removeRecent(key: String) { stored = TripPlaces.removeRecent(stored, key); persist() }
    @Synchronized fun restoreRecent(trip: RecentTrip, index: Int) {
        if (stored.recent.any { it.key == trip.key }) return
        stored = stored.copy(recent = stored.recent.toMutableList().apply { add(index.coerceIn(0, size), trip) }.take(TripPlaces.MAX_RECENT))
        persist()
    }
    @Synchronized fun clearRecent() { stored = stored.copy(recent = emptyList()); persist() }
    @Synchronized fun save(place: TripPlace, label: String = "") { stored = TripPlaces.save(stored, place, label); persist() }
    @Synchronized fun unsave(place: TripPlace) { stored = TripPlaces.unsave(stored, place); persist() }
    @Synchronized fun clearAll() { stored = TripPlacesState(); persist() }
    /** Re-applies settings, e.g. after the history was switched off or the retention changed. */
    @Synchronized fun refresh() { persist() }

    private fun persist() {
        if (!historyEnabled()) stored = stored.copy(recent = emptyList())
        stored = TripPlaces.prune(stored, clock(), settings.current().int("history_days", 30))
        prefs.edit().putString("state", TripPlaces.encode(stored)).apply()
        mutable.value = stored
    }
}
