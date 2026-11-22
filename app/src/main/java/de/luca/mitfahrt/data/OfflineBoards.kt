package de.luca.mitfahrt.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class OfflineDeparture(val line: String, val direction: String, val time: Instant, val platform: String)
data class OfflineBoard(val day: LocalDate, val savedAt: Instant, val departures: List<OfflineDeparture>, val reachedEnd: Boolean) {
    /** Converts stored timetable rows into schedule-only [Departure] items (never marked live). */
    fun asDepartures(stop: Stop, now: Instant): List<Departure> = departures
        .filter { !it.time.isBefore(now.minusSeconds(60)) }
        .map { row ->
            Departure(
                lineId = "offline:${row.line}",
                line = row.line,
                destination = row.direction,
                stopId = stop.id,
                stopName = stop.name,
                tripCode = "offline-${row.line}-${row.time.epochSecond}",
                planned = row.time,
                estimated = null,
                platform = row.platform,
                cancelled = false,
                notices = listOf("Offline-Fahrplan (Stand ${clockTime(savedAt)})"),
                observedAt = null
            )
        }
}

/**
 * Saved operator timetables for selected favourites and active route stops. No live fields.
 * Stored as per-stop JSON files to avoid SharedPreferences memory bloat; falls back transparently
 * to legacy SharedPreferences entries if present.
 */
class OfflineBoards(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "offline_boards")
    private val legacyPrefs by lazy { appContext.getSharedPreferences("offline_boards_v1", Context.MODE_PRIVATE) }

    fun get(stop: Stop, day: LocalDate): OfflineBoard? = getById(stop.id, day)

    fun getById(stopId: String, day: LocalDate): OfflineBoard? {
        val file = boardFile(stopId, day)
        val text = when {
            file.isFile -> runCatching { file.readText() }.getOrNull()
            else -> legacyPrefs.getString(legacyKey(stopId, day), null)
        } ?: return null
        return parseBoard(text, day)
    }

    fun save(stop: Stop, board: OfflineBoard) {
        val today = LocalDate.now(zone)
        pruneExpired(today)
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val json = JSONObject().put("savedAt", board.savedAt.toString()).put("reachedEnd", board.reachedEnd)
                .put("departures", JSONArray(board.departures.map {
                    JSONObject().put("line", it.line).put("direction", it.direction)
                        .put("time", it.time.toString()).put("platform", it.platform)
                }))
            val target = boardFile(stop.id, board.day)
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
            // Clear any legacy SharedPreferences copy for the same key.
            if (legacyPrefs.contains(legacyKey(stop.id, board.day))) {
                legacyPrefs.edit().remove(legacyKey(stop.id, board.day)).apply()
            }
        }
    }

    fun remove(stop: Stop, day: LocalDate) {
        runCatching { boardFile(stop.id, day).delete() }
        if (legacyPrefs.contains(legacyKey(stop.id, day))) {
            legacyPrefs.edit().remove(legacyKey(stop.id, day)).apply()
        }
    }

    private fun pruneExpired(today: LocalDate) {
        val todayStr = today.toString()
        runCatching {
            dir.listFiles().orEmpty().forEach { file ->
                val datePart = file.nameWithoutExtension.substringAfterLast('_', "")
                if (datePart.length == 10 && datePart < todayStr) file.delete()
            }
        }
    }

    private fun parseBoard(text: String, day: LocalDate): OfflineBoard? = runCatching {
        val json = JSONObject(text)
        val rows = json.getJSONArray("departures").objects().map { row ->
            OfflineDeparture(
                row.getString("line"),
                row.getString("direction"),
                Instant.parse(row.getString("time")),
                row.getString("platform")
            )
        }.filter { it.time.atZone(zone).toLocalDate() == day }
        OfflineBoard(day, Instant.parse(json.getString("savedAt")), rows, json.optBoolean("reachedEnd"))
    }.getOrNull()

    private fun boardFile(stopId: String, day: LocalDate): File {
        val safe = stopKey(stopId).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(dir, "${safe}_${day}.json")
    }

    private fun legacyKey(stopId: String, day: LocalDate): String = "${stopKey(stopId)}|$day"

    companion object {
        val zone: ZoneId = ZoneId.of("Europe/Berlin")
    }
}

/**
 * Quietly prefetches today's and tomorrow's offline timetables for active [RoutePlan] stops
 * once per day while on unmetered Wi-Fi (capped at 4 unique stops to stay respectful of VRR EFA).
 */
object OfflinePrefetcher {
    private const val PREFS = "offline_prefetch_v1"
    private const val MAX_STOPS = 4

    fun isUnmeteredWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    suspend fun prefetchIfDue(
        context: Context,
        settings: AppSettings,
        plans: List<RoutePlan>,
        boards: OfflineBoards,
        vrr: VrrClient,
        now: Instant = Instant.now()
    ) {
        if (!settings.bool("offline_prefetch", true)) return
        if (!isUnmeteredWifi(context)) return
        val today = now.atZone(OfflineBoards.zone).toLocalDate()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last_day", null) == today.toString()) return

        val stops = plans.filter { it.enabled }
            .flatMap { listOf(it.origin, it.effectiveDestination(today)) }
            .distinctBy { stopKey(it.id) }
            .take(MAX_STOPS)
        if (stops.isEmpty()) return

        var fetchedAny = false
        for (stop in stops) {
            for (day in listOf(today, today.plusDays(1))) {
                val existing = boards.get(stop, day)
                if (existing != null && now.epochSecond - existing.savedAt.epochSecond < 12 * 3600) continue
                val board = runCatching { vrr.offlineTimetable(stop, day) }.getOrNull() ?: continue
                boards.save(stop, board)
                fetchedAny = true
            }
        }
        if (fetchedAny) {
            prefs.edit().putString("last_day", today.toString()).apply()
        }
    }
}
