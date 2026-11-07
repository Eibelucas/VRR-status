package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
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
    val nearbyOnly: Boolean = true
)

data class RoutePlansState(val automaticLearning: Boolean = true, val routes: List<RoutePlan> = emptyList())

object RoutePlans {
    const val MAX_ROUTES = 24
    const val MAX_NAME_LENGTH = 80
    const val MAX_LINE_LENGTH = 30

    fun active(plan: RoutePlan, now: Instant, nearbyStops: List<Stop>): Boolean =
        plan.enabled && plan.window.active(now) &&
            (!plan.nearbyOnly || nearbyStops.any { stopKey(it.id) == stopKey(plan.origin.id) })

    /** These are boarding candidates only; the trip must still be checked for the chosen target. */
    @Suppress("UNUSED_PARAMETER") // Kept alongside nearby-stop callers; manual opening also works away from the origin.
    fun candidates(plan: RoutePlan, stops: List<Stop>, departures: List<Departure>, now: Instant): List<Departure> {
        val clean = clean(plan) ?: return emptyList()
        val line = normalizeLine(clean.line)
        return departures.asSequence().filter { departure ->
            stopKey(departure.stopId) == clean.origin.id && !departure.cancelled &&
                (clean.match == RouteMatch.STOP || normalizeLine(departure.line) == line) &&
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
        if (id.isEmpty() || id.length > 128 || name.isEmpty() || name.length > MAX_NAME_LENGTH ||
            line.length > MAX_LINE_LENGTH || (plan.match == RouteMatch.LINE && normalizeLine(line).isEmpty()) ||
            origin.id == destination.id || !plan.window.valid()) return null
        return plan.copy(id = id, name = name, origin = origin, destination = destination, line = line,
            window = plan.window.copy(days = plan.window.days.toSortedSet()))
    }

    fun encode(state: RoutePlansState): String = JSONObject().apply {
        put("automaticLearning", state.automaticLearning)
        put("routes", JSONArray(state.routes.mapNotNull(::clean).distinctBy { it.id }.take(MAX_ROUTES).map { plan ->
            JSONObject().apply {
                put("id", plan.id); put("name", plan.name)
                put("origin", stopJson(plan.origin)); put("destination", stopJson(plan.destination))
                put("match", plan.match.name); put("line", plan.line)
                put("enabled", plan.enabled); put("nearbyOnly", plan.nearbyOnly)
                put("window", JSONObject().apply {
                    put("days", JSONArray(plan.window.days.sorted()))
                    put("startMinute", plan.window.startMinute); put("endMinute", plan.window.endMinute)
                })
            }
        }))
    }.toString()

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
                clean(RoutePlan(item.getString("id"), item.getString("name"), stop("origin"), stop("destination"),
                    RouteMatch.valueOf(item.optString("match", RouteMatch.STOP.name)), item.optString("line", ""),
                    RouteWindow(days, window?.let { integer(it, "startMinute", 0) } ?: 0,
                        window?.let { integer(it, "endMinute", 0) } ?: 0),
                    item.optBoolean("enabled", true), item.optBoolean("nearbyOnly", true)))
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

    private fun read(): RoutePlansState = RoutePlans.decode(runCatching { prefs.getString("state", "{}") }.getOrNull() ?: "{}")
    private fun update(state: RoutePlansState) {
        prefs.edit().putString("state", RoutePlans.encode(state)).apply()
        mutable.value = state
    }
}
