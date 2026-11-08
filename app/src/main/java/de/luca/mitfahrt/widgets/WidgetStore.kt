package de.luca.mitfahrt.widgets

import android.content.Context
import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.RideCodec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Widget data lives only in its own preferences file; app stores are read, never written. */
class WidgetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(widgetId: Int): WidgetConfig? = runCatching {
        val kind = WidgetKind.fromKey(prefs.getString("$widgetId.kind", null)) ?: return null
        WidgetConfigs.clean(WidgetConfig(
            kind = kind,
            targetId = prefs.getString("$widgetId.target", "").orEmpty(),
            targetName = prefs.getString("$widgetId.name", "").orEmpty(),
            info = InfoLevel.fromKey(prefs.getString("$widgetId.info", null)),
            theme = ThemeMode.fromKey(prefs.getString("$widgetId.theme", null)),
            accent = WidgetAccent.fromKey(prefs.getString("$widgetId.accent", null)),
            opacity = prefs.getInt("$widgetId.opacity", 100)
        ))
    }.getOrNull()

    fun saveConfig(widgetId: Int, config: WidgetConfig): Boolean {
        val clean = WidgetConfigs.clean(config) ?: return false
        val previous = config(widgetId)
        prefs.edit().apply {
            putString("$widgetId.kind", clean.kind.key)
            putString("$widgetId.target", clean.targetId)
            putString("$widgetId.name", clean.targetName)
            putString("$widgetId.info", clean.info.key)
            putString("$widgetId.theme", clean.theme.key)
            putString("$widgetId.accent", clean.accent.key)
            putInt("$widgetId.opacity", clean.opacity)
            // Data of a different stop or route must never appear under the new title.
            if (previous?.kind != clean.kind || previous.targetId != clean.targetId) remove("$widgetId.snapshot")
        }.commit()
        return true
    }

    fun snapshot(widgetId: Int): WidgetSnapshot =
        prefs.getString("$widgetId.snapshot", null)?.let(WidgetSnapshotCodec::decode) ?: WidgetSnapshot()

    fun saveSnapshot(widgetId: Int, snapshot: WidgetSnapshot) {
        prefs.edit().putString("$widgetId.snapshot", WidgetSnapshotCodec.encode(snapshot)).apply()
    }

    fun remove(widgetId: Int) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$widgetId.") }.forEach { key -> remove(key) }
        }.apply()
    }

    fun removeAll() { prefs.edit().clear().apply() }

    private companion object { const val PREFS = "mitfahrt_widgets" }
}

object WidgetSnapshotCodec {
    private const val MAX_DEPARTURES = 12

    fun encode(snapshot: WidgetSnapshot): String = JSONObject().apply {
        snapshot.fetchedAt?.let { put("fetchedAt", it.toString()) }
        snapshot.failure?.let { put("failure", it.name) }
        snapshot.failedAt?.let { put("failedAt", it.toString()) }
        put("partial", snapshot.partial)
        put("targetMissing", snapshot.targetMissing)
        put("departures", JSONArray(snapshot.departures.take(MAX_DEPARTURES).map(::departure)))
        snapshot.ride?.takeUnless { it.demo }?.let { put("ride", RideCodec.encode(it)) }
    }.toString()

    fun decode(value: String): WidgetSnapshot = runCatching {
        val obj = JSONObject(value)
        val array = obj.optJSONArray("departures")
        val departures = (0 until (array?.length() ?: 0)).mapNotNull { index ->
            array?.optJSONObject(index)?.let { runCatching { departure(it) }.getOrNull() }
        }
        WidgetSnapshot(
            fetchedAt = obj.instant("fetchedAt"),
            departures = departures,
            ride = obj.optString("ride", "").takeIf { it.isNotBlank() }
                ?.let { runCatching { RideCodec.decode(it) }.getOrNull() },
            failure = obj.optString("failure", "").let { name -> FetchFailure.entries.firstOrNull { it.name == name } },
            failedAt = obj.instant("failedAt"),
            partial = obj.optBoolean("partial", false),
            targetMissing = obj.optBoolean("targetMissing", false)
        )
    }.getOrDefault(WidgetSnapshot())

    private fun departure(d: Departure): JSONObject = JSONObject().apply {
        put("lineId", d.lineId); put("line", d.line); put("destination", d.destination)
        put("stopId", d.stopId); put("stopName", d.stopName); put("tripCode", d.tripCode)
        put("planned", d.planned.toString())
        d.estimated?.let { put("estimated", it.toString()) }
        d.observedAt?.let { put("observedAt", it.toString()) }
        put("platform", d.platform); put("cancelled", d.cancelled)
        put("notices", JSONArray(d.notices.take(3)))
    }

    private fun departure(obj: JSONObject): Departure {
        val notices = obj.optJSONArray("notices")
        return Departure(
            obj.getString("lineId"), obj.getString("line"), obj.optString("destination", ""),
            obj.getString("stopId"), obj.optString("stopName", ""), obj.getString("tripCode"),
            Instant.parse(obj.getString("planned")), obj.instant("estimated"), obj.optString("platform", ""),
            obj.optBoolean("cancelled", false),
            (0 until (notices?.length() ?: 0)).mapNotNull { notices?.optString(it)?.takeIf(String::isNotBlank) },
            obj.instant("observedAt")
        )
    }

    private fun JSONObject.instant(key: String): Instant? =
        if (has(key) && !isNull(key)) runCatching { Instant.parse(getString(key)) }.getOrNull() else null
}
