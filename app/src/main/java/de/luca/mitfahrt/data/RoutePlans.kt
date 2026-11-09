package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

enum class RouteMatch { STOP, LINE }

/** ISO weekdays (Monday = 1). An overnight interval belongs to its starting day. */
data class RouteWindow(
    val days: Set<Int> = (1..7).toSet(),
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun active(now: Instant): Boolean {
        if (!valid()) return false
        val local = now.atZone(ZoneId.of("Europe/Berlin"))
        val day = local.dayOfWeek.value
        val minute = local.hour * 60 + local.minute
        return when {
            startMinute == endMinute -> day in days
            startMinute < endMinute -> day in days && minute >= startMinute && minute < endMinute
            minute >= startMinute -> day in days
            minute < endMinute -> (if (day == 1) 7 else day - 1) in days
            else -> false
        }
    }

    fun label(): String {
        val names = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val selected = days.filter { it in 1..7 }.sorted()
        val dayLabel = when {
            selected.isEmpty() -> "Keine Tage"
            selected.size == 7 -> "Täglich"
            selected == (1..5).toList() -> "Mo–Fr"
            else -> selected.joinToString(", ") { names[it - 1] }
        }
        fun time(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
        val hours = when {
            startMinute !in 0..1439 || endMinute !in 0..1439 -> "Ungültige Zeit"
            startMinute == endMinute -> "Ganztägig"
            else -> "${time(startMinute)}–${time(endMinute)}"
        }
        return "$dayLabel · $hours"
    }

    internal fun valid(): Boolean = days.isNotEmpty() && days.all { it in 1..7 } &&
        startMinute in 0..1439 && endMinute in 0..1439
}

data class RoutePlan(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val origin: Stop,
    val destination: Stop,
    val match: RouteMatch = RouteMatch.STOP,
    val line: String = "",
    val window: RouteWindow = RouteWindow(),
    val enabled: Boolean = true,
    val nearbyOnly: Boolean = true,
    /** Empty preserves the legacy single-window representation. */
    val windows: List<RouteWindow> = emptyList(),
    val allowedLines: Set<String> = emptySet(),
    val color: String = "new",
    val icon: String = "bus",
    val walkingMinutes: Int = 5,
    val bufferMinutes: Int = 2,
    val schoolOnly: Boolean = false,
    val manualOnly: Boolean = false,
    val excludedDates: Set<String> = emptySet(),
    val alternateRouteId: String? = null,
    val order: Int = 0
) {
    fun effectiveWindows(): List<RouteWindow> = windows.ifEmpty { listOf(window) }
    fun lineLabel(): String = (allowedLines.ifEmpty { setOf(line) }).filter { it.isNotBlank() }.joinToString(", ")
}

data class RoutePlansState(val automaticLearning: Boolean = true, val routes: List<RoutePlan> = emptyList())

object RoutePlans {
    const val MAX_ROUTES = 24
    const val MAX_NAME_LENGTH = 80
    const val MAX_LINE_LENGTH = 30
    const val MAX_WINDOWS = 12
    const val MAX_LINES = 12
    val COLORS = listOf("new", "blue", "violet", "orange", "pink", "teal", "red", "yellow")
    val ICONS = listOf("bus", "school", "home", "work", "sport", "star")
    fun validColor(value: String): Boolean = value in COLORS || value.matches(Regex("#[0-9a-fA-F]{6}"))

    fun active(plan: RoutePlan, now: Instant, nearbyStops: List<Stop>, schoolPaused: Boolean = false,
        freeDates: Set<String> = emptySet(), timeOverride: Boolean? = null): Boolean =
        plan.enabled && !plan.manualOnly && (timeOverride ?: plan.effectiveWindows().any { it.active(now) }) &&
            now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate().toString().let { date ->
                date !in plan.excludedDates && (!plan.schoolOnly || !schoolPaused && date !in freeDates)
            } &&
            (!plan.nearbyOnly || nearbyStops.any { stopKey(it.id) == stopKey(plan.origin.id) })

    /** These are boarding candidates only; the trip must still be checked for the chosen target. */
    @Suppress("UNUSED_PARAMETER") // Kept alongside nearby-stop callers; manual opening also works away from the origin.
    fun candidates(plan: RoutePlan, stops: List<Stop>, departures: List<Departure>, now: Instant): List<Departure> {
        val clean = clean(plan) ?: return emptyList()
        val lines = (clean.allowedLines.ifEmpty { setOf(clean.line) }).map(::normalizeLine).toSet()
        return departures.asSequence().filter { departure ->
            stopKey(departure.stopId) == clean.origin.id && !departure.cancelled &&
                (clean.match == RouteMatch.STOP || normalizeLine(departure.line) in lines) &&
                departure.effective >= now && departure.effective <= now.plusSeconds(3600) &&
                departure.observedAt?.let { now.epochSecond - it.epochSecond in -30..120 } == true
        }.distinctBy { it.key }.sortedBy { it.effective }.toList()
    }

    private fun normalizeLine(value: String): String = value.trim().uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[0-9]+")) { it.value.trimStart('0').ifEmpty { "0" } }

    private fun station(stop: Stop): Stop? {
        val id = stopKey(stop.id.trim())
        val name = stop.name.trim()
        if (id.isBlank() || id.length > 256 || id.startsWith("demo:") || name.isBlank() || name.length > 256) return null
        val point = stop.point?.takeIf { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        return Stop(id, name, point)
    }

    internal fun clean(plan: RoutePlan): RoutePlan? {
        val origin = station(plan.origin) ?: return null
        val destination = station(plan.destination) ?: return null
        val id = plan.id.trim()
        val name = plan.name.trim()
        val line = plan.line.trim()
        val lines = plan.allowedLines.map { it.trim() }.toSet()
        val alternate = plan.alternateRouteId?.trim()?.takeIf { it.isNotEmpty() }
        if (id.isEmpty() || id.length > 128 || name.isEmpty() || name.length > MAX_NAME_LENGTH ||
            line.length > MAX_LINE_LENGTH || lines.size > MAX_LINES || lines.any { it.isBlank() || it.length > MAX_LINE_LENGTH } ||
            (plan.match == RouteMatch.LINE && normalizeLine(line).isEmpty() && lines.isEmpty()) ||
            origin.id == destination.id || !plan.window.valid() || plan.windows.size > MAX_WINDOWS ||
            plan.windows.any { !it.valid() } || !validColor(plan.color) || plan.icon !in ICONS ||
            plan.walkingMinutes !in 0..60 || plan.bufferMinutes !in 0..30 || plan.order !in -1000..1000 ||
            plan.excludedDates.size > 365 || plan.excludedDates.any { runCatching { LocalDate.parse(it).toString() != it }.getOrDefault(true) } ||
            alternate == id || (alternate != null && alternate.length > 128)) return null
        return plan.copy(id = id, name = name, origin = origin, destination = destination, line = line,
            window = plan.window.copy(days = plan.window.days.toSortedSet()),
            windows = plan.windows.distinct().map { it.copy(days = it.days.toSortedSet()) },
            allowedLines = lines, excludedDates = plan.excludedDates.toSortedSet(), alternateRouteId = alternate)
    }

    fun encode(state: RoutePlansState): String = JSONObject().apply {
        put("automaticLearning", state.automaticLearning)
        put("routes", JSONArray(state.routes.mapNotNull(::clean).distinctBy { it.id }.take(MAX_ROUTES).map { plan ->
            JSONObject().apply {
                put("id", plan.id); put("name", plan.name)
                put("origin", stopJson(plan.origin)); put("destination", stopJson(plan.destination))
                put("match", plan.match.name); put("line", plan.line)
                put("enabled", plan.enabled); put("nearbyOnly", plan.nearbyOnly)
                if (plan.windows.isNotEmpty()) put("windows", JSONArray(plan.windows.map(::windowJson)))
                if (plan.allowedLines.isNotEmpty()) put("allowedLines", JSONArray(plan.allowedLines.sorted()))
                put("color", plan.color); put("icon", plan.icon)
                put("walkingMinutes", plan.walkingMinutes); put("bufferMinutes", plan.bufferMinutes)
                put("schoolOnly", plan.schoolOnly); put("manualOnly", plan.manualOnly)
                put("excludedDates", JSONArray(plan.excludedDates.sorted())); put("alternateRouteId", plan.alternateRouteId)
                put("order", plan.order)
                put("window", JSONObject().apply {
                    put("days", JSONArray(plan.window.days.sorted()))
                    put("startMinute", plan.window.startMinute); put("endMinute", plan.window.endMinute)
                })
            }
        }))
    }.toString()

    private fun windowJson(window: RouteWindow): JSONObject = JSONObject().apply {
        put("days", JSONArray(window.days.sorted())); put("startMinute", window.startMinute); put("endMinute", window.endMinute)
    }

    private fun stopJson(stop: Stop): JSONObject = JSONObject().apply {
        put("id", stop.id); put("name", stop.name)
        // These are public station coordinates, never the device's location or past distance.
        stop.point?.let { put("lat", it.lat); put("lon", it.lon) }
    }

    fun decode(value: String): RoutePlansState = runCatching {
        val json = JSONObject(value)
        val routes = json.optJSONArray("routes")?.objects().orEmpty().mapNotNull { item ->
            runCatching {
                fun integer(obj: JSONObject, key: String, default: Int): Int {
                    if (!obj.has(key)) return default
                    val number = obj.get(key) as? Number ?: error("Invalid $key")
                    val value = number.toDouble()
                    require(value.isFinite() && value == number.toInt().toDouble())
                    return number.toInt()
                }
                fun stop(key: String): Stop {
                    val obj = item.getJSONObject(key)
                    val point = if (obj.has("lat") && obj.has("lon"))
                        GeoPoint(obj.optDouble("lat", Double.NaN), obj.optDouble("lon", Double.NaN)) else null
                    return Stop(obj.getString("id"), obj.getString("name"), point)
                }
                val window = if (item.has("window")) item.getJSONObject("window") else null
                val days = window?.takeIf { it.has("days") }?.getJSONArray("days")?.let { array ->
                    (0 until array.length()).map { index ->
                        val value = array.get(index) as? Number ?: error("Invalid weekday")
                        require(value.toDouble() == value.toInt().toDouble())
                        value.toInt()
                    }.toSet()
                } ?: (1..7).toSet()
                fun windows(): List<RouteWindow> = if (!item.has("windows")) emptyList() else item.getJSONArray("windows").let { array ->
                    (0 until array.length()).map { index ->
                        val value = array.getJSONObject(index)
                        val selectedDays = value.getJSONArray("days").let { d ->
                            (0 until d.length()).map { i ->
                                val n = d.get(i) as? Number ?: error("Invalid weekday")
                                require(n.toDouble() == n.toInt().toDouble()); n.toInt()
                            }.toSet()
                        }
                        RouteWindow(selectedDays, integer(value, "startMinute", 0), integer(value, "endMinute", 0))
                    }
                }
                fun strings(key: String): Set<String> = if (!item.has(key)) emptySet() else item.getJSONArray(key).let { array ->
                    (0 until array.length()).map { array.getString(it) }.toSet()
                }
                clean(RoutePlan(item.getString("id"), item.getString("name"), stop("origin"), stop("destination"),
                    RouteMatch.valueOf(item.optString("match", RouteMatch.STOP.name)), item.optString("line", ""),
                    RouteWindow(days, window?.let { integer(it, "startMinute", 0) } ?: 0,
                        window?.let { integer(it, "endMinute", 0) } ?: 0),
                    item.optBoolean("enabled", true), item.optBoolean("nearbyOnly", true),
                    windows(), strings("allowedLines"), item.optString("color", "new"), item.optString("icon", "bus"),
                    integer(item, "walkingMinutes", 5), integer(item, "bufferMinutes", 2),
                    item.optBoolean("schoolOnly", false), item.optBoolean("manualOnly", false), strings("excludedDates"),
                    item.optString("alternateRouteId").takeIf { it.isNotBlank() && it != "null" }, integer(item, "order", 0)))
            }.getOrNull()
        }.distinctBy { it.id }.take(MAX_ROUTES)
        RoutePlansState(json.optBoolean("automaticLearning", true), routes)
    }.getOrDefault(RoutePlansState())
}

class RoutePlansStore(context: Context) {
    private val prefs = context.getSharedPreferences("route_plans", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    /** Read preferences again so another store instance cannot leave learning enabled by accident. */
    @Synchronized fun current(): RoutePlansState = read().also { mutable.value = it }

    @Synchronized fun setAutomaticLearning(enabled: Boolean) { update(current().copy(automaticLearning = enabled)) }

    @Synchronized fun save(plan: RoutePlan): Boolean {
        val clean = RoutePlans.clean(plan) ?: return false
        val current = current()
        val index = current.routes.indexOfFirst { it.id == clean.id }
        if (index < 0 && current.routes.size >= RoutePlans.MAX_ROUTES) return false
        val routes = current.routes.toMutableList()
        if (index < 0) routes.add(clean) else routes[index] = clean
        update(current.copy(routes = routes))
        return true
    }

    @Synchronized fun remove(id: String): RoutePlan? {
        val current = current()
        val removed = current.routes.firstOrNull { it.id == id } ?: return null
        update(current.copy(routes = current.routes.filterNot { it.id == id }))
        return removed
    }

    /** Undo never replaces a route edited or recreated in the meantime. */
    @Synchronized fun restore(plan: RoutePlan): Boolean {
        val clean = RoutePlans.clean(plan) ?: return false
        val current = current()
        if (current.routes.any { it.id == clean.id } || current.routes.size >= RoutePlans.MAX_ROUTES) return false
        update(current.copy(routes = current.routes + clean))
        return true
    }

    @Synchronized fun move(id: String, delta: Int): Boolean {
        val current = current()
        val routes = current.routes.sortedBy { it.order }.toMutableList()
        val from = routes.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + delta).coerceIn(0, routes.lastIndex)
        if (from == to) return false
        routes.add(to, routes.removeAt(from))
        update(current.copy(routes = routes.mapIndexed { index, route -> route.copy(order = index) }))
        return true
    }

    @Synchronized fun importMerge(plans: List<RoutePlan>): Boolean {
        if (plans.size > RoutePlans.MAX_ROUTES) return false
        val clean = plans.map { RoutePlans.clean(it) ?: return false }
        if (clean.distinctBy { it.id }.size != clean.size) return false
        val current = current()
        val byId = clean.associateBy { it.id }
        val merged = current.routes.map { byId[it.id] ?: it } + clean.filter { imported -> current.routes.none { it.id == imported.id } }
        if (merged.size > RoutePlans.MAX_ROUTES) return false
        update(current.copy(routes = merged.sortedBy { it.order }))
        return true
    }

    @Synchronized fun setTodayHidden(id: String, hidden: Boolean, now: Instant = Instant.now()): Boolean {
        val plan = current().routes.firstOrNull { it.id == id } ?: return false
        val date = now.atZone(ZoneId.of("Europe/Berlin")).toLocalDate().toString()
        val dates = if (hidden) plan.excludedDates + date else plan.excludedDates - date
        return save(plan.copy(excludedDates = dates))
    }

    private fun read(): RoutePlansState = RoutePlans.decode(runCatching { prefs.getString("state", "{}") }.getOrNull() ?: "{}")
    private fun update(state: RoutePlansState) {
        prefs.edit().putString("state", RoutePlans.encode(state)).apply()
        mutable.value = state
    }
}
