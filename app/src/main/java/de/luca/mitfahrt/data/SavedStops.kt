package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.security.MessageDigest
import java.time.Instant
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

class SavedStopsStore(context: Context, private val clock: () -> Instant = Instant::now) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("saved_stops", Context.MODE_PRIVATE)
    private val routePreferences = app.getSharedPreferences("route_plans", Context.MODE_PRIVATE)
    private val plans = RoutePlansStore(app)
    private data class Observation(val stopHash: String, val visits: List<String>, val at: Instant, val explicit: Boolean = false) {
        val qualified: Boolean get() = explicit || visits.size >= MIN_OPENS
    }
    private var stored = SavedStops.decode(runCatching { prefs.getString("state", "{}") }.getOrNull() ?: "{}")
    private var observations = readObservations()
    private val mutable = MutableStateFlow(SavedStopsState())
    val state = mutable.asStateFlow()
    private val learningListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "state" || key == null) synchronized(this) { publish() }
    }

    init {
        if (!runCatching { prefs.getBoolean("observations_initialized", false) }.getOrDefault(false)) {
            // A legacy recent entry proves at most one opening, never repeated interest.
            stored.recent.forEach { stop ->
                val stopHash = hash(stopKey(stop.id))
                if (observations.none { it.stopHash == stopHash }) observations = observations +
                    Observation(stopHash, listOf(hash("legacy/$stopHash")), clock())
            }
        }
        persist()
        routePreferences.registerOnSharedPreferenceChangeListener(learningListener)
    }

    /** Browsing remembers no station details until three distinct explicit board openings. */
    @Synchronized fun observe(stop: Stop, visitId: String) {
        if (!plans.current().automaticLearning) { publish(); return }
        if (visitId.isBlank() || visitId.length > 512) return
        val clean = SavedStops.selected(SavedStopsState(), stop).recent.singleOrNull() ?: return
        prune()
        val stopHash = hash(clean.id)
        val visitHash = hash(visitId)
        val previous = observations.firstOrNull { it.stopHash == stopHash }
        if (previous?.visits?.contains(visitHash) == true) { publish(); return }
        val seen = Observation(stopHash, (previous?.visits.orEmpty() + visitHash).takeLast(MIN_OPENS), clock(),
            previous?.explicit == true)
        observations = listOf(seen) + observations.filterNot { it.stopHash == stopHash }
        val refreshed = SavedStops.selected(stored, clean)
        stored = if (seen.qualified) refreshed else stored.copy(favorites = refreshed.favorites)
        persist()
    }

    /** Explicit compatibility API; automatic callers must use observe instead. */
    @Synchronized fun select(stop: Stop) {
        val updated = SavedStops.selected(stored, stop)
        val clean = updated.recent.firstOrNull()?.takeIf { stopKey(it.id) == stopKey(stop.id) } ?: return
        rememberExplicit(clean)
        stored = updated
        persist()
    }

    /** Returns the actual saved state, including when the favorite limit has been reached. */
    @Synchronized fun toggleFavorite(stop: Stop): Boolean {
        stored = SavedStops.toggle(stored, stop)
        persist()
        return stored.isFavorite(stop)
    }
    @Synchronized fun restoreFavorite(stop: Stop, index: Int) {
        stored = SavedStops.restore(stored, stop, index)
        persist()
    }
    @Synchronized fun removeRecent(stop: Stop) {
        val id = stopKey(stop.id)
        stored = stored.copy(recent = stored.recent.filterNot { stopKey(it.id) == id })
        observations = observations.filterNot { it.stopHash == hash(id) }
        persist()
    }
    @Synchronized fun restoreRecent(stop: Stop, index: Int) {
        val updated = SavedStops.restoreRecent(stored, stop, index)
        updated.recent.firstOrNull { stopKey(it.id) == stopKey(stop.id) }?.let(::rememberExplicit)
        stored = updated
        persist()
    }
    @Synchronized fun clearRecent() {
        stored = stored.copy(recent = emptyList())
        observations = emptyList()
        persist()
    }

    private fun rememberExplicit(stop: Stop) {
        val id = hash(stopKey(stop.id))
        val previous = observations.firstOrNull { it.stopHash == id }
        observations = listOf(Observation(id, previous?.visits.orEmpty(), clock(), explicit = true)) +
            observations.filterNot { it.stopHash == id }
    }
    private fun prune() {
        val now = clock()
        observations = observations.filter { it.at >= now.minusSeconds(MAX_AGE_SECONDS) && it.at <= now.plusSeconds(30) }
            .distinctBy { it.stopHash }.sortedByDescending { it.at }.take(MAX_OBSERVATIONS)
        val qualified = observations.filter { it.qualified }.map { it.stopHash }.toSet()
        stored = stored.copy(recent = stored.recent.filter { hash(stopKey(it.id)) in qualified })
    }
    private fun publish() {
        prune()
        mutable.value = if (plans.current().automaticLearning) stored else stored.copy(recent = emptyList())
    }
    private fun persist() {
        prune()
        val data = JSONArray(observations.map { observed -> JSONObject().apply {
            put("h", observed.stopHash); put("v", JSONArray(observed.visits)); put("t", observed.at.toEpochMilli())
            if (observed.explicit) put("explicit", true)
        } })
        prefs.edit().putString("state", SavedStops.encode(stored)).putString("observations", data.toString())
            .putBoolean("observations_initialized", true).apply()
        publish()
    }
    private fun readObservations(): List<Observation> = runCatching {
        JSONArray(prefs.getString("observations", "[]")).objects().mapNotNull { item ->
            runCatching {
                val id = item.getString("h").takeIf(HASH::matches) ?: return@mapNotNull null
                val tokens = item.optJSONArray("v")?.let { values ->
                    (0 until values.length()).mapNotNull { index -> values.optString(index).takeIf(HASH::matches) }
                }.orEmpty().distinct().takeLast(MIN_OPENS)
                val number = item.get("t") as? Number ?: return@mapNotNull null
                val time = number.toLong()
                if (number.toDouble() != time.toDouble()) return@mapNotNull null
                Observation(id, tokens, Instant.ofEpochMilli(time), item.optBoolean("explicit", false))
            }.getOrNull()
        }.distinctBy { it.stopHash }.take(MAX_OBSERVATIONS)
    }.getOrDefault(emptyList())

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private companion object {
        const val MIN_OPENS = 3
        const val MAX_OBSERVATIONS = 100
        const val MAX_AGE_SECONDS = 90 * 86400L
        val HASH = Regex("[0-9a-f]{64}")
    }
}
