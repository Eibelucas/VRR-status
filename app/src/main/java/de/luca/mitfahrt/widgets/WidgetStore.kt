package de.luca.mitfahrt.widgets

import android.content.Context
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.Departure
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Widget data lives only in its own preferences file; app stores are read, never written. */
class WidgetStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        synchronized(guard) {
            if (!prefs.getBoolean("private_snapshot_cleanup", false)) prefs.edit().apply {
                prefs.all.filterKeys { it.endsWith(".snapshot") }.forEach { (key, value) ->
                    if (value is String) putString(key, WidgetSnapshotCodec.encode(WidgetSnapshotCodec.decode(value)))
                }
                putBoolean("private_snapshot_cleanup", true)
            }.apply()
        }
    }

    fun config(widgetId: Int): WidgetConfig? = storedConfig(widgetId)?.let { raw ->
        WidgetConfigs.clean(if (raw.useAppDefaults) WidgetDefaults.resolve(app, raw) else raw)
    }

    fun storedConfig(widgetId: Int): WidgetConfig? = runCatching {
        val kind = WidgetKind.fromKey(prefs.getString("$widgetId.kind", null)) ?: return null
        WidgetConfigs.clean(WidgetConfig(
            kind = kind,
            targetId = prefs.getString("$widgetId.target", "").orEmpty(),
            targetName = prefs.getString("$widgetId.name", "").orEmpty(),
            info = InfoLevel.fromKey(prefs.getString("$widgetId.info", null)),
            theme = ThemeMode.fromKey(prefs.getString("$widgetId.theme", null)),
            accent = WidgetAccent.fromKey(prefs.getString("$widgetId.accent", null)),
            opacity = prefs.getInt("$widgetId.opacity", 100),
            useAppDefaults = prefs.getBoolean("$widgetId.defaults", false),
            action = prefs.getString("$widgetId.action", "departures").orEmpty(),
            rowCount = prefs.getInt("$widgetId.rows", 3)
        ))
    }.getOrNull()

    fun saveConfig(widgetId: Int, config: WidgetConfig): Boolean = synchronized(guard) {
        if (widgetId <= 0) return@synchronized false
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
            putBoolean("$widgetId.defaults", clean.useAppDefaults)
            putString("$widgetId.action", clean.action)
            putInt("$widgetId.rows", clean.rowCount)
            putString("$widgetId.revision", java.util.UUID.randomUUID().toString())
            // Data of a different stop or route must never appear under the new title.
            if (previous?.kind != clean.kind || previous.targetId != clean.targetId) {
                remove("$widgetId.snapshot"); remove("$widgetId.snapshot_content"); activeSnapshots.remove(widgetId)
            }
        }.commit()
    }

    fun revision(widgetId: Int): String = prefs.getString("$widgetId.revision", "legacy").orEmpty() +
        "/" + (config(widgetId)?.let(::content) ?: "missing")

    fun snapshot(widgetId: Int): WidgetSnapshot = synchronized(guard) {
        val config = config(widgetId) ?: return@synchronized WidgetSnapshot()
        val content = content(config)
        if (config.kind == WidgetKind.ACTIVE) return@synchronized activeSnapshots[widgetId]?.takeIf { it.first == content }?.second ?: WidgetSnapshot()
        if (prefs.getString("$widgetId.snapshot_content", "") != content) return@synchronized WidgetSnapshot()
        prefs.getString("$widgetId.snapshot", null)?.let(WidgetSnapshotCodec::decode) ?: WidgetSnapshot()
    }

    fun saveSnapshotIfCurrent(widgetId: Int, expected: WidgetConfig, revision: String, snapshot: WidgetSnapshot): Boolean = synchronized(guard) {
        if (this.revision(widgetId) != revision || config(widgetId) != expected) return@synchronized false
        val content = content(expected)
        if (expected.kind == WidgetKind.ACTIVE) {
            activeSnapshots[widgetId] = content to snapshot
            // Personal tracked rides never become a second persistent history in widget preferences.
            prefs.edit().remove("$widgetId.snapshot").remove("$widgetId.snapshot_content").apply()
        } else prefs.edit().putString("$widgetId.snapshot", WidgetSnapshotCodec.encode(snapshot.copy(ride = null)))
            .putString("$widgetId.snapshot_content", content).apply()
        true
    }

    fun remove(widgetId: Int) = synchronized(guard) {
        activeSnapshots.remove(widgetId)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$widgetId.") }.forEach { key -> remove(key) }
        }.apply()
    }

    fun clearSnapshots() = synchronized(guard) {
        activeSnapshots.clear()
        prefs.edit().apply {
            prefs.all.keys.filter { it.endsWith(".snapshot") || it.endsWith(".snapshot_content") }.forEach { key -> remove(key) }
        }.apply()
    }

    fun clearActiveSnapshots() = synchronized(guard) {
        activeSnapshots.clear()
        prefs.edit().apply {
            prefs.all.keys.filter { it.endsWith(".kind") && prefs.getString(it, null) == WidgetKind.ACTIVE.key }
                .forEach { key ->
                    val id = key.substringBefore('.')
                    remove("$id.snapshot"); remove("$id.snapshot_content")
                }
        }.apply()
    }

    fun removeAll() = synchronized(guard) { activeSnapshots.clear(); prefs.edit().clear().apply() }

    private companion object {
        const val PREFS = "mitfahrt_widgets"
        val guard = Any()
        val activeSnapshots = mutableMapOf<Int, Pair<String, WidgetSnapshot>>()
    }

    private fun content(config: WidgetConfig): String {
        val application = app as? MitfahrtApplication
        val detail = when (config.kind) {
            WidgetKind.ROUTE -> application?.routePlans?.current()?.routes?.firstOrNull { it.id == config.targetId }?.toString() ?: "missing"
            WidgetKind.ACTIVE -> application?.store?.state?.value?.ride?.departure?.key.orEmpty()
            WidgetKind.STOP -> ""
        }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(detail.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${config.kind.key}/${config.targetId}/$fingerprint"
    }
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
        // A tracked ride is personal. The optional display-only refresh stays in memory.
    }.toString()

    fun decode(value: String): WidgetSnapshot = runCatching {
        require(value.toByteArray().size <= 262_144)
        val obj = JSONObject(value)
        val array = obj.optJSONArray("departures")
        val departures = (0 until minOf(array?.length() ?: 0, MAX_DEPARTURES)).mapNotNull { index ->
            array?.optJSONObject(index)?.let { runCatching { departure(it) }.getOrNull() }
        }
        WidgetSnapshot(
            fetchedAt = obj.instant("fetchedAt"),
            departures = departures,
            ride = null,
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
