package de.luca.mitfahrt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class OfflineDeparture(val line: String, val direction: String, val time: Instant, val platform: String)
data class OfflineBoard(val day: LocalDate, val savedAt: Instant, val departures: List<OfflineDeparture>, val reachedEnd: Boolean)

/** Saved operator timetables for individually selected favourites and dates. No live fields. */
class OfflineBoards(context: Context) {
    private val prefs = context.getSharedPreferences("offline_boards_v1", Context.MODE_PRIVATE)

    fun get(stop: Stop, day: LocalDate): OfflineBoard? {
        val text = prefs.getString(key(stop, day), null) ?: return null
        return runCatching {
            val json = JSONObject(text)
            val rows = json.getJSONArray("departures").objects().map { row ->
                OfflineDeparture(row.getString("line"), row.getString("direction"),
                    Instant.parse(row.getString("time")), row.getString("platform"))
            }.filter { it.time.atZone(zone).toLocalDate() == day }
            OfflineBoard(day, Instant.parse(json.getString("savedAt")), rows, json.optBoolean("reachedEnd"))
        }.getOrNull()
    }

    fun save(stop: Stop, board: OfflineBoard) {
        val today = LocalDate.now(zone)
        val editor = prefs.edit()
        prefs.all.keys.filter { id -> id.substringAfterLast('|') < today.toString() }.forEach(editor::remove)
        val json = JSONObject().put("savedAt", board.savedAt.toString()).put("reachedEnd", board.reachedEnd)
            .put("departures", JSONArray(board.departures.map {
                JSONObject().put("line", it.line).put("direction", it.direction).put("time", it.time.toString()).put("platform", it.platform)
            }))
        editor.putString(key(stop, board.day), json.toString()).apply()
    }

    fun remove(stop: Stop, day: LocalDate) { prefs.edit().remove(key(stop, day)).apply() }

    private fun key(stop: Stop, day: LocalDate): String = "${stopKey(stop.id)}|$day"

    companion object {
        val zone: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
