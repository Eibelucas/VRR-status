package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.Instant

class ArrivalStore(context: Context, private val clock: () -> Instant = Instant::now) {
    private val prefs = context.getSharedPreferences("arrivals", Context.MODE_PRIVATE)
    private val settings = AppSettingsStore(context)
    private val mutable = MutableStateFlow(runCatching {
        ArrivalMark.decodeList(JSONArray(prefs.getString("marks", "[]")))
    }.getOrDefault(emptyList()))
    val marks = mutable.asStateFlow()
    init { prune() }

    fun record(stop: Stop) {
        save(ArrivalMark.create(stop, clock()))
    }

    @Synchronized fun recordDetected(stop: Stop, at: Instant = clock()) {
        val now = clock()
        if (at > now.plusSeconds(15) || at < now.minusSeconds(86400) || stop.id.isBlank() || stop.name.isBlank()) return
        if (mutable.value.any { stopKey(it.stopId) == stopKey(stop.id) &&
                kotlin.math.abs(it.at.epochSecond - at.epochSecond) < 60 }) return
        save(ArrivalMark.create(stop, at).copy(method = "location"))
    }

    @Synchronized fun remove(id: String) { persist(mutable.value.filterNot { it.id == id }) }
    @Synchronized fun clear() { persist(emptyList()) }
    @Synchronized fun prune() { persist(mutable.value) }

    @Synchronized private fun save(mark: ArrivalMark) {
        if (!settings.current().bool("arrival_store", true)) return
        if (mark.stopId.isBlank() || mark.stopName.isBlank()) return
        persist(mutable.value + mark)
    }
    private fun persist(values: List<ArrivalMark>) {
        val now = clock()
        val days = settings.current().int("arrival_retention_days", 0).coerceIn(0, 3650)
        val updated = values.filter { it.at <= now.plusSeconds(15) && (days == 0 || it.at >= now.minusSeconds(days * 86400L)) }
            .distinctBy { it.id }.sortedBy { it.at }.takeLast(1000)
        prefs.edit().putString("marks", JSONArray(updated.map { it.json() }).toString()).apply()
        mutable.value = updated
    }
}
