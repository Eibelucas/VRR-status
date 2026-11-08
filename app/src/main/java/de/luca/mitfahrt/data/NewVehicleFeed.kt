package de.luca.mitfahrt.data

import de.luca.mitfahrt.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class NewVehicleStop(
    val point: GeoPoint,
    val plannedSeconds: Int?,
    val name: String = "",
    val plannedDayOfMonth: Int? = null
)

/** An observation from NEW, not an inferred EFA departure or an arrival forecast. */
data class NewVehicle(
    val id: String,
    val line: String,
    val destination: String,
    val point: GeoPoint,
    val observedAt: Instant,
    val currentStopId: String? = null,
    val currentStopName: String? = null,
    val nextStopId: String? = null,
    val nextStopName: String? = null,
    val delaySeconds: Int? = null,
    val tripId: String? = null,
    val scheduleId: String? = null,
    val journeyStartSeconds: Int? = null,
    val routeStops: List<NewVehicleStop> = emptyList(),
    val routeObservedAt: Instant? = null,
    val directionReliable: Boolean = true
) {
    fun isFresh(now: Instant): Boolean =
        observedAt >= now.minusSeconds(90) && observedAt <= now.plusSeconds(15)
}

object NewVehicleParser {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val vehicleId = Regex("[A-Za-z0-9_-]{1,64}")
    private val pathTime = Regex("(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2})")

    fun parse(root: JSONObject, now: Instant): List<NewVehicle> =
        root.keys().asSequence().mapNotNull { id ->
            root.optJSONObject(id)?.let { parseVehicle(id, it, now) }
        }.sortedBy { it.id }.toList()

    fun parseVehicle(id: String, root: JSONObject, now: Instant): NewVehicle? {
        if (!validVehicleId(id)) return null
        val raw = root.optJSONObject("raw") ?: return null
        val geo = root.optJSONObject("geomatching") ?: return null
        if (geo.value("IS_GEORTET") != true || geo.value("IS_ONLINE") != true) return null
        val excluded = raw.value("exclusion_zone")
        if (excluded == true || excluded != null && excluded !is Boolean) return null
        // Never substitute a snapped/interpolated point for a missing or invalid raw observation.
        val point = point(raw, "lat", "lon") ?: return null
        val observed = observationTime(raw) ?: return null
        val line = text(raw, "route_translated") ?: text(raw, "route") ?: text(geo, "LI_NR") ?: return null
        val normalized = normalizeLine(line)
        if (normalized.isBlank() || normalized == "0") return null
        // NEW may explicitly publish an internal route code and its passenger-facing translation.
        // Accept only those actual aliases; never derive a numeric mapping ourselves.
        val rawAliases = listOfNotNull(text(raw, "route_translated"), text(raw, "route"))
            .map(::normalizeLine).filter { it.isNotBlank() && it != "0" }.toSet().ifEmpty { setOf(normalized) }
        val geometryLines = listOfNotNull(text(geo, "LI_NR"), text(geo, "route_translated"), text(geo, "route"))
        if (geometryLines.any { normalizeLine(it) !in rawAliases }) return null
        val routeObserved = timestamp(geo.value("time"))?.let { source ->
            source.choices.singleOrNull() ?: source.choices.filter { candidate ->
                abs(Duration.between(candidate, observed).seconds) <= 120
            }.singleOrNull()
        }
        val geometryFresh = routeObserved?.let { it >= now.minusSeconds(90) && it <= now.plusSeconds(15) } == true
        val destination = destination(text(raw, "terminus"), text(geo, "LIDNAME").takeIf { geometryFresh })
        val currentId = stopId(raw, "currentstationid") ?: stopId(raw, "stationid")
        val currentName = if (stopId(raw, "currentstationid") != null) text(raw, "currentstationname")
            else text(raw, "stationname")
        val vehicle = NewVehicle(
            id = id,
            line = line,
            destination = destination.label,
            point = point,
            observedAt = observed,
            currentStopId = currentId,
            currentStopName = currentName,
            nextStopId = stopId(geo, "NEXT_ORT_NR").takeIf { geometryFresh },
            nextStopName = text(geo, "NEXT_ORT_REF_ORT_NAME").takeIf { geometryFresh },
            delaySeconds = (integer(geo, "PLAN_ABWEICHNG", -86_400..86_400)
                ?: integer(geo, "PLAN_ABWEICHNG_MIN", -1_440..1_440)?.times(60)).takeIf { geometryFresh },
            tripId = text(raw, "trip"),
            scheduleId = text(geo, "SCHEDULE_ID").takeIf { geometryFresh },
            journeyStartSeconds = integer(geo, "FRT_START", 0..259_200).takeIf { geometryFresh },
            routeStops = if (geometryFresh) routeStops(geo.optJSONArray("FAHRTWEG")) else emptyList(),
            routeObservedAt = routeObserved,
            directionReliable = destination.reliable
        )
        return vehicle.takeIf { it.isFresh(now) }
    }

    private data class Destination(val label: String, val reliable: Boolean)
    private val ledControlPrefix = Regex("^5(?=[A-ZÄÖÜ\\s]|$)")
    private val advert = Regex("https?://|www\\.|\\b[a-z0-9-]+\\.(?:de|com|org|net)\\b|dein\\s+ziel|busfahren", RegexOption.IGNORE_CASE)

    private fun displayDestination(value: String): String = value
        .filterNot { it.isISOControl() && !it.isWhitespace() }
        .replace(ledControlPrefix, "")
        .replace(Regex("\\s+"), " ").trim()
        .let { if (it.length > 96) it.take(95) + "…" else it }

    private fun opaqueDestination(value: String): Boolean = value.isBlank() ||
        ledControlPrefix.containsMatchIn(value) || Regex("\\s{2,}").containsMatchIn(value) ||
        value.any(Char::isISOControl) || value.length > 80 || advert.containsMatchIn(value)

    private fun destination(raw: String?, geometry: String?): Destination {
        // Packed LED displays and advertisements are labels, never a decoded route direction.
        if (raw != null && !opaqueDestination(raw)) return Destination(displayDestination(raw), true)
        val explicitTail = geometry?.takeIf { it.contains('>') && it.substringBeforeLast('>').isNotBlank() }
            ?.substringAfterLast('>')?.trim()?.takeIf { it.isNotBlank() && !opaqueDestination(it) }
        if (explicitTail != null) return Destination(displayDestination(explicitTail), true)
        return Destination(displayDestination(raw ?: geometry.orEmpty()), false)
    }

    fun validVehicleId(id: String): Boolean = vehicleId.matches(id)

    fun normalizeLine(value: String): String = value.trim().uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[0-9]+")) { it.value.trimStart('0').ifEmpty { "0" } }

    private fun JSONObject.value(key: String): Any? = if (has(key) && !isNull(key)) opt(key) else null

    private fun text(obj: JSONObject, key: String): String? = when (val value = obj.value(key)) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it.length <= 512 }
        is Number -> value.toString().takeIf { value.toDouble().isFinite() }
        else -> null
    }

    private fun stopId(obj: JSONObject, key: String): String? =
        text(obj, key)?.takeUnless { it.all { character -> character == '0' } }

    private fun number(obj: JSONObject, key: String): Double? = when (val value = obj.value(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun integer(obj: JSONObject, key: String, range: IntRange): Int? =
        number(obj, key)?.takeIf { it == it.toInt().toDouble() && it.toInt() in range }?.toInt()

    private fun point(obj: JSONObject, latitude: String, longitude: String): GeoPoint? {
        val lat = number(obj, latitude) ?: return null
        val lon = number(obj, longitude) ?: return null
        return GeoPoint(lat, lon).takeIf { lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) }
    }

    private data class Timestamp(val choices: List<Instant>, val explicit: Boolean)

    private fun timestamp(value: Any?): Timestamp? {
        val numeric = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        if (numeric != null) {
            if (!numeric.isFinite() || numeric < 946_684_800.0) return null
            val milliseconds = if (numeric >= 1_000_000_000_000.0) numeric else numeric * 1000.0
            if (milliseconds !in 946_684_800_000.0..4_102_444_800_000.0) return null
            return Timestamp(listOf(Instant.ofEpochMilli(milliseconds.roundToLong())), true)
        }
        val text = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { Instant.parse(text) }.getOrNull()?.let { return explicitTimestamp(it) }
        runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return explicitTimestamp(it) }
        val local = runCatching {
            LocalDateTime.parse(text.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        }.getOrNull() ?: return null
        if (local.year !in 2000..2100) return null
        val offsets = berlin.rules.getValidOffsets(local)
        if (offsets.isEmpty()) return null // The spring clock jump has no such instant.
        return Timestamp(offsets.map { local.toInstant(it) }, false)
    }

    private fun explicitTimestamp(instant: Instant): Timestamp? = instant.takeIf {
        it >= Instant.parse("2000-01-01T00:00:00Z") && it <= Instant.parse("2100-01-01T00:00:00Z")
    }?.let { Timestamp(listOf(it), true) }

    private fun observationTime(raw: JSONObject): Instant? {
        val primaryKey = listOf("time", "send_time", "sendtime").firstOrNull { raw.value(it) != null } ?: return null
        val primary = timestamp(raw.value(primaryKey)) ?: return null
        if (primary.choices.size == 1) return primary.choices.single()
        // A received-at time or our local clock cannot disambiguate an old measurement.
        val anchors = listOf("time", "send_time", "sendtime").filterNot { it == primaryKey }
            .mapNotNull { timestamp(raw.value(it)) }.filter { it.explicit && it.choices.size == 1 }
            .flatMap { it.choices }
        return primary.choices.filter { choice ->
            anchors.any { abs(Duration.between(choice, it).seconds) <= 120 }
        }.singleOrNull()
    }

    private fun routeStops(array: JSONArray?): List<NewVehicleStop> {
        if (array == null || array.length() == 0 || array.length() > 300) return emptyList()
        val result = ArrayList<NewVehicleStop>(array.length())
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: return emptyList()
            val point = point(row, "ORT_POS_BREITE_GPS", "ORT_POS_LAENGE_GPS") ?: return emptyList()
            val publishedTime = text(row, "ABFAHRT_ZEIT") ?: text(row, "ANKUNFT_ZEIT")
            val parts = publishedTime?.let(pathTime::matchEntire)?.groupValues
            if (publishedTime != null && parts == null) return emptyList()
            val day = parts?.get(1)?.toInt()?.takeIf { it in 1..31 }
            val hour = parts?.get(2)?.toInt()?.takeIf { it in 0..23 }
            val minute = parts?.get(3)?.toInt()?.takeIf { it in 0..59 }
            val second = parts?.get(4)?.toInt()?.takeIf { it in 0..59 }
            if (parts != null && (day == null || hour == null || minute == null || second == null)) return emptyList()
            val seconds = if (hour != null && minute != null && second != null) hour * 3600 + minute * 60 + second else null
            result += NewVehicleStop(point, seconds, text(row, "ORT_REF_ORT_NAME").orEmpty(), day)
        }
        return result
    }
}

/** Bounded, anonymous access to the feed used by NEW's public map. */
class NewVehicleClient(
    private val clock: () -> Instant = Instant::now,
    private val request: suspend (String) -> String = ::readNewVehicleText
) {
    private data class Cached<T>(val value: T, val fetchedAt: Instant)
    private val mutex = Mutex()
    private var fleet: Cached<List<NewVehicle>>? = null
    private val individual = linkedMapOf<String, Cached<NewVehicle?>>()

    suspend fun fetch(): List<NewVehicle> = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val now = clock()
        fleet?.takeIf { cached(it.fetchedAt, now) }?.let { return@withLock it.value.filter { bus -> bus.isFresh(now) } }
        fleet = null
        val text = request(BASE + ".json")
        currentCoroutineContext().ensureActive()
        val observedNow = clock()
        val vehicles = if (text.trim() == "null") emptyList() else NewVehicleParser.parse(JSONObject(text), observedNow)
        fleet = Cached(vehicles, observedNow)
        vehicles
    }

    suspend fun vehicle(id: String): NewVehicle? {
        require(NewVehicleParser.validVehicleId(id)) { "Ungültiges Fahrzeug." }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            val now = clock()
            individual[id]?.takeIf { cached(it.fetchedAt, now) }?.let { return@withLock it.value?.takeIf { bus -> bus.isFresh(now) } }
            fleet?.takeIf { cached(it.fetchedAt, now) }?.value?.firstOrNull { it.id == id && it.isFresh(now) }
                ?.let { return@withLock it }
            individual.remove(id)
            val text = request(BASE + "/" + id + ".json")
            currentCoroutineContext().ensureActive()
            val observedNow = clock()
            val bus = if (text.trim() == "null") null else NewVehicleParser.parseVehicle(id, JSONObject(text), observedNow)
            individual[id] = Cached(bus, observedNow)
            individual.entries.removeAll { !cached(it.value.fetchedAt, observedNow) }
            while (individual.size > 32) individual.remove(individual.keys.first())
            bus
        }
    }

    private fun cached(at: Instant, now: Instant): Boolean = Duration.between(at, now).toMillis() in 0L until 10_000L

    companion object {
        const val BASE = "https://fahrgastzahlen-default-rtdb.europe-west1.firebasedatabase.app/livegeomatching/busses"
    }
}

private suspend fun readNewVehicleText(url: String): String = withContext(Dispatchers.IO) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Mitfahrt-Android/" + BuildConfig.VERSION_NAME + " (https://github.com/Eibelucas/VRR-status)")
        if (connection.responseCode != 200) throw IOException("NEW-Livedaten gerade nicht erreichbar (" + connection.responseCode + ").")
        val maximum = 2 * 1024 * 1024
        if (connection.contentLengthLong > maximum) throw IOException("NEW-Antwort ist zu groß.")
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maximum) throw IOException("NEW-Antwort ist zu groß.")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    } finally { connection.disconnect() }
}
