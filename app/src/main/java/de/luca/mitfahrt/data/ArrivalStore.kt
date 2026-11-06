package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class ArrivalStore(context: Context) {
    private val prefs = context.getSharedPreferences("arrivals", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        ArrivalMark.decodeList(JSONArray(prefs.getString("marks", "[]")))
    }.getOrDefault(emptyList()))
    val marks = mutable.asStateFlow()

    fun record(stop: Stop) {
        val updated = mutable.value + ArrivalMark.create(stop)
        prefs.edit().putString("marks", JSONArray(updated.map { it.json() }).toString()).apply()
        mutable.value = updated
    }
}
