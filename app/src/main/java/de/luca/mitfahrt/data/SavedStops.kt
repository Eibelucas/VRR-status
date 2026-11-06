package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

data class SavedStopsState(val favorites: List<Stop> = emptyList(), val recent: List<Stop> = emptyList()) {
    fun isFavorite(stop: Stop): Boolean = favorites.any { stopKey(it.id) == stopKey(stop.id) }
}

/** Device location and historic distances never belong in a saved station. */
object SavedStops {
    const val MAX_FAVORITES = 12
    const val MAX_RECENT = 20

    private fun station(stop: Stop): Stop? {
        val id = stopKey(stop.id.trim())
        val name = stop.name.trim()
        if (id.isBlank() || id.length > 256 || name.isBlank() || name.length > 256 || id.startsWith("demo:")) return null
        val point = stop.point?.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        return Stop(id, name, point = point)
    }

    fun selected(state: SavedStopsState, stop: Stop): SavedStopsState {
        val clean = station(stop)?.let { current ->
            current.copy(point = current.point ?: (state.favorites + state.recent).firstOrNull { stopKey(it.id) == current.id }?.point)
        } ?: return state
        return state.copy(
            favorites = state.favorites.map { if (stopKey(it.id) == clean.id) clean else it },
            recent = (listOf(clean) + state.recent.filterNot { stopKey(it.id) == clean.id }).take(MAX_RECENT)
        )
    }

    fun toggle(state: SavedStopsState, stop: Stop): SavedStopsState {
        val clean = station(stop) ?: return state
        val existing = state.favorites.indexOfFirst { stopKey(it.id) == clean.id }
        return if (existing >= 0) state.copy(favorites = state.favorites.filterIndexed { index, _ -> index != existing })
        else if (state.favorites.size >= MAX_FAVORITES) state
        else state.copy(favorites = state.favorites + clean)
    }

    fun restore(state: SavedStopsState, stop: Stop, index: Int): SavedStopsState {
        val clean = station(stop) ?: return state
        if (state.isFavorite(clean)) return state
        if (state.favorites.size >= MAX_FAVORITES) return state
        val list = state.favorites.toMutableList().apply { add(index.coerceIn(0, size), clean) }
        return state.copy(favorites = list)
    }

    fun matches(stop: Stop, query: String): Boolean {
        val terms = normalize(query).split(' ').filter(String::isNotBlank)
        val haystack = normalize(stop.name)
        return terms.all { it in haystack }
    }

    fun suggestions(state: SavedStopsState, query: String, limit: Int = 8): List<Stop> =
        (state.favorites + state.recent).distinctBy { stopKey(it.id) }.filter { matches(it, query) }
            .sortedWith(compareBy<Stop> { !state.isFavorite(it) }.thenBy { !normalize(it.name).startsWith(normalize(query)) })
            .take(limit.coerceIn(0, MAX_FAVORITES + MAX_RECENT))

    fun restoreRecent(state: SavedStopsState, stop: Stop, index: Int): SavedStopsState {
        val clean = station(stop) ?: return state
        if (state.recent.any { stopKey(it.id) == clean.id } || state.recent.size >= MAX_RECENT) return state
        return state.copy(recent = state.recent.toMutableList().apply { add(index.coerceIn(0, size), clean) })
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace("ß", "ss").replace(Regex("\\bhbf\\b|\\bhauptbahnhof\\b"), "hauptbahnhof")
        .replace(Regex("^(monchengladbach|monchengl\\.?)[,\\s]+|^mg\\s+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun encode(state: SavedStopsState): String = JSONObject().apply {
        put("favorites", listJson(state.favorites.take(MAX_FAVORITES)))
        put("recent", listJson(state.recent.take(MAX_RECENT)))
    }.toString()

    private fun listJson(stops: List<Stop>): JSONArray = JSONArray(stops.mapNotNull(::station).map { stop -> JSONObject().apply {
        put("id", stop.id); put("name", stop.name)
        // A stop's public map coordinates are not a measurement of the user's position.
        stop.point?.let { put("lat", it.lat); put("lon", it.lon) }
    } })

    fun decode(value: String): SavedStopsState = runCatching {
        val obj = JSONObject(value)
        fun read(key: String, max: Int): List<Stop> = obj.optJSONArray(key)?.objects().orEmpty().mapNotNull { item ->
            runCatching {
                station(Stop(item.getString("id"), item.getString("name"),
                    if (item.has("lat") && item.has("lon")) GeoPoint(item.optDouble("lat", Double.NaN), item.optDouble("lon", Double.NaN)) else null))
            }.getOrNull()
        }.distinctBy { it.id }.take(max)
        SavedStopsState(read("favorites", MAX_FAVORITES), read("recent", MAX_RECENT))
    }.getOrDefault(SavedStopsState())
}

class SavedStopsStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved_stops", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SavedStops.decode(prefs.getString("state", "{}") ?: "{}"))
    val state = mutable.asStateFlow()

    @Synchronized fun select(stop: Stop) { update(SavedStops.selected(mutable.value, stop)) }
    /** Returns the actual saved state, including when the favorite limit has been reached. */
    @Synchronized fun toggleFavorite(stop: Stop): Boolean {
        update(SavedStops.toggle(mutable.value, stop))
        return mutable.value.isFavorite(stop)
    }
    @Synchronized fun restoreFavorite(stop: Stop, index: Int) { update(SavedStops.restore(mutable.value, stop, index)) }
    @Synchronized fun removeRecent(stop: Stop) { update(mutable.value.copy(recent = mutable.value.recent.filterNot { stopKey(it.id) == stopKey(stop.id) })) }
    @Synchronized fun restoreRecent(stop: Stop, index: Int) { update(SavedStops.restoreRecent(mutable.value, stop, index)) }
    @Synchronized fun clearRecent() { update(mutable.value.copy(recent = emptyList())) }

    private fun update(state: SavedStopsState) {
        if (state == mutable.value) return
        prefs.edit().putString("state", SavedStops.encode(state)).apply()
        mutable.value = state
    }
}
