package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One ride the user explicitly started. Times shown from it are history, never a forecast. */
data class ArchivedJourney(
    val id: String,
    val ride: Ride,
    val startedAt: Instant,
    val updatedAt: Instant,
    val revision: Long = 1
) {
    val line: String get() = ride.departure.line
    val boardingName: String get() = ride.stops.getOrNull(ride.boardingIndex)?.name ?: ride.departure.stopName
    val targetName: String get() = ride.displayTarget().name
    val plannedBoarding: Instant get() = ride.stops.getOrNull(ride.boardingIndex)?.let { it.departure ?: it.arrival } ?: ride.departure.planned
    val day: LocalDate get() = startedAt.atZone(JourneyArchive.zone).toLocalDate()
}

data class JourneyArchiveSettings(val enabled: Boolean = true, val retentionDays: Int = 0)

data class JourneyArchiveState(
    val settings: JourneyArchiveSettings = JourneyArchiveSettings(),
    /** Newest first. */
    val entries: List<ArchivedJourney> = emptyList(),
    internal val sequence: Long = 0,
    /** id → sequence of its latest deletion; lets an undo detect later changes. */
    internal val tombstones: Map<String, Long> = emptyMap()
)

/** Token returned by a deletion; restoring is refused once anything newer happened to those entries. */
data class JourneyArchiveUndo(val entries: List<ArchivedJourney>, val sequence: Long)

data class JourneyArchiveFilter(
    val query: String = "",
    val line: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null
)

/**
 * Pure archive rules. Only [record] can create an entry, and callers use it solely when a
 * ride is explicitly started. Refreshes go through [update], which never creates entries,
 * so departure boards, previews and background lookups cannot add anything.
 */
object JourneyArchive {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    const val MAX_ENTRIES = 500
    const val MAX_TOMBSTONES = MAX_ENTRIES
    const val MAX_FILE_BYTES = 4L * 1024 * 1024
    const val MAX_RIDE_CHARS = 64_000
    val RETENTION_OPTIONS = listOf(0, 30, 90, 365)

    fun id(ride: Ride): String = MessageDigest.getInstance("SHA-256")
        .digest("${stopKey(ride.departure.stopId)}/${ride.departure.key}".toByteArray(Charsets.UTF_8))
        .take(16).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    /** Adds or replaces the entry of an explicitly started ride. */
    fun record(state: JourneyArchiveState, ride: Ride, now: Instant): JourneyArchiveState {
        if (!state.settings.enabled || !validRide(ride)) return state
        val id = id(ride)
        val existing = state.entries.firstOrNull { it.id == id }
        val entry = existing?.copy(ride = ride, updatedAt = now, revision = existing.revision + 1)
            ?: ArchivedJourney(id, ride, ride.trackingStartedAt?.takeIf { !it.isAfter(now.plusSeconds(60)) } ?: now, now)
        return prune(state.copy(entries = listOf(entry) + state.entries.filterNot { it.id == id },
            tombstones = state.tombstones - id), now)
    }

    /** Refreshes an existing entry only; returns the state unchanged for unknown rides. */
    fun update(state: JourneyArchiveState, ride: Ride, now: Instant): JourneyArchiveState {
        val id = id(ride)
        val existing = state.entries.firstOrNull { it.id == id } ?: return state
        if (!validRide(ride) || !state.settings.enabled) return state
        val entry = existing.copy(ride = ride, updatedAt = now, revision = existing.revision + 1)
        return prune(state.copy(entries = state.entries.map { if (it.id == id) entry else it }), now)
    }

    fun remove(state: JourneyArchiveState, ids: Set<String>): Pair<JourneyArchiveState, JourneyArchiveUndo?> {
        val removed = state.entries.filter { it.id in ids }
        if (removed.isEmpty()) return state to null
        val sequence = state.sequence + 1
        val tombstones = (state.tombstones + removed.associate { it.id to sequence }).entries
            .sortedByDescending { it.value }.take(MAX_TOMBSTONES).associate { it.key to it.value }
        return state.copy(entries = state.entries.filterNot { it.id in ids }, sequence = sequence, tombstones = tombstones) to
            JourneyArchiveUndo(removed, sequence)
    }

    /**
     * Restores the entries of [undo] that are still absent and were not deleted again later.
     * A re-recorded (newer) entry or a later deletion always wins.
     */
    fun restore(state: JourneyArchiveState, undo: JourneyArchiveUndo, now: Instant): Pair<JourneyArchiveState, Int> {
        val restorable = undo.entries.filter { entry ->
            state.entries.none { it.id == entry.id } && state.tombstones[entry.id] == undo.sequence
        }
        if (restorable.isEmpty()) return state to 0
        val restored = prune(state.copy(entries = (state.entries + restorable).sortedByDescending { it.startedAt },
            tombstones = state.tombstones - restorable.map { it.id }.toSet()), now)
        return restored to restorable.count { entry -> restored.entries.any { it.id == entry.id } }
    }

    /** Empties the archive; every pending undo becomes obsolete. */
    fun clear(state: JourneyArchiveState): JourneyArchiveState {
        val sequence = state.sequence + 1
        return state.copy(entries = emptyList(), sequence = sequence,
            tombstones = (state.tombstones.mapValues { sequence } + state.entries.associate { it.id to sequence }).entries
                .sortedByDescending { it.value }.take(MAX_TOMBSTONES).associate { it.key to it.value })
    }

    /** Applies the retention period (0 = keep until deleted) and the entry limit. */
    fun prune(state: JourneyArchiveState, now: Instant): JourneyArchiveState {
        val days = state.settings.retentionDays
        val kept = state.entries
            .filter { validRide(it.ride) && it.id == id(it.ride) &&
                (days <= 0 || !it.startedAt.isBefore(now.minusSeconds(days.coerceAtMost(3650) * 86_400L))) }
            .distinctBy { it.id }.sortedByDescending { it.startedAt }.take(MAX_ENTRIES)
        return state.copy(entries = kept)
    }

    fun withSettings(state: JourneyArchiveState, settings: JourneyArchiveSettings, now: Instant): JourneyArchiveState {
        val days = settings.retentionDays.coerceIn(0, 3650)
        return prune(state.copy(settings = settings.copy(retentionDays = days)), now)
    }

    fun filter(entries: List<ArchivedJourney>, filter: JourneyArchiveFilter): List<ArchivedJourney> {
        val terms = normalize(filter.query).split(' ').filter(String::isNotBlank)
        val line = filter.line?.let(::normalize)
        return entries.filter { entry ->
            val haystack = normalize("${entry.line} ${entry.boardingName} ${entry.targetName} ${entry.ride.departure.destination}")
            terms.all { it in haystack } &&
                (line == null || normalize(entry.line) == line) &&
                (filter.from == null || !entry.day.isBefore(filter.from)) &&
                (filter.to == null || !entry.day.isAfter(filter.to))
        }
    }

    fun lines(entries: List<ArchivedJourney>): List<String> = entries.map { it.line }.filter { it.isNotBlank() }
        .distinct().sortedWith(compareBy<String> { it.trimStart('0').length }.thenBy { it.trimStart('0') })

    fun byDay(entries: List<ArchivedJourney>): List<Pair<LocalDate, List<ArchivedJourney>>> =
        entries.groupBy { it.day }.toSortedMap(compareByDescending { it }).map { (day, items) ->
            day to items.sortedByDescending { it.startedAt }
        }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("ß", "ss")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        .split(' ').joinToString(" ") { part -> if (part.isNotEmpty() && part.all(Char::isDigit)) part.trimStart('0').ifEmpty { "0" } else part }

    private fun validRide(ride: Ride): Boolean = !ride.demo && ride.boardingIndex in ride.stops.indices

    private fun envelope(state: JourneyArchiveState, entries: JSONArray): JSONObject = JSONObject().apply {
        put("version", 1)
        put("enabled", state.settings.enabled)
        put("retentionDays", state.settings.retentionDays)
        put("sequence", state.sequence)
        put("tombstones", JSONObject(state.tombstones.entries.sortedByDescending { it.value }.take(MAX_TOMBSTONES)
            .associate { it.key to it.value }))
        put("entries", entries)
    }

    /** Byte bound includes escaped JSON, UTF-8 text and metadata, not just ride character counts. */
    internal fun storage(state: JourneyArchiveState): Pair<JourneyArchiveState, String> {
        val entries = JSONArray()
        val kept = mutableListOf<ArchivedJourney>()
        var bytes = envelope(state, entries).toString().toByteArray(Charsets.UTF_8).size.toLong()
        for (entry in state.entries.distinctBy { it.id }.sortedByDescending { it.startedAt }.take(MAX_ENTRIES)) {
            if (!validRide(entry.ride) || entry.id != id(entry.ride)) continue
            val ride = RideCodec.encode(entry.ride).takeIf { it.length <= MAX_RIDE_CHARS } ?: continue
            val json = JSONObject().apply {
                put("id", entry.id); put("ride", ride); put("startedAt", entry.startedAt.toString())
                put("updatedAt", entry.updatedAt.toString()); put("revision", entry.revision)
            }
            val size = json.toString().toByteArray(Charsets.UTF_8).size + if (kept.isEmpty()) 0 else 1
            if (bytes + size > MAX_FILE_BYTES) continue
            entries.put(json); kept += entry; bytes += size
        }
        val snapshot = state.copy(entries = kept)
        return snapshot to envelope(snapshot, entries).toString()
    }

    fun encode(state: JourneyArchiveState): String = storage(state).second

    fun decode(value: String, now: Instant): JourneyArchiveState = runCatching {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)
        val obj = JSONObject(value)
        val array = obj.optJSONArray("entries")
        val entries = (0 until minOf(array?.length() ?: 0, MAX_ENTRIES)).mapNotNull { index ->
            runCatching {
                val item = array!!.getJSONObject(index)
                val encoded = item.getString("ride").takeIf { it.length <= MAX_RIDE_CHARS } ?: return@runCatching null
                val ride = RideCodec.decode(encoded).takeIf(::validRide) ?: return@runCatching null
                val id = item.getString("id").takeIf { it == id(ride) } ?: return@runCatching null
                ArchivedJourney(id, ride, Instant.parse(item.getString("startedAt")), Instant.parse(item.getString("updatedAt")),
                    item.optLong("revision", 1).coerceAtLeast(1))
            }.getOrNull()
        }
        val tombstoneJson = obj.optJSONObject("tombstones")
        val tombstones = tombstoneJson?.keys()?.asSequence()?.mapNotNull { key ->
            tombstoneJson.optLong(key, -1).takeIf { it >= 0 && key.matches(Regex("[0-9a-f]{32}")) }?.let { key to it }
        }?.take(MAX_TOMBSTONES)?.toMap().orEmpty()
        val days = obj.optInt("retentionDays", 0).coerceIn(0, 3650)
        prune(JourneyArchiveState(JourneyArchiveSettings(obj.optBoolean("enabled", true), days), entries,
            obj.optLong("sequence", 0).coerceAtLeast(tombstones.values.maxOrNull() ?: 0), tombstones), now)
    }.getOrDefault(JourneyArchiveState())
}

/**
 * Private archive in the app's no-backup directory. Nothing is shared or exported.
 * Use [record] only where a ride is explicitly started, [update] for its refreshes.
 */
class JourneyArchiveStore(private val file: File, private val clock: () -> Instant = Instant::now) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "journey_archive.json"))

    private var persisted: String? = null
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<JourneyArchiveState> = mutable.asStateFlow()
    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()
    fun clearError() { errorState.value = null }
    init { persist(mutable.value) }

    @Synchronized fun record(ride: Ride) { persist(JourneyArchive.record(mutable.value, ride, clock())) }
    @Synchronized fun update(ride: Ride) {
        val updated = JourneyArchive.update(mutable.value, ride, clock())
        if (updated !== mutable.value) persist(updated)
    }
    @Synchronized fun remove(ids: Set<String>): JourneyArchiveUndo? {
        val (updated, undo) = JourneyArchive.remove(mutable.value, ids)
        if (undo != null && !persist(updated)) return null
        return undo
    }
    /** Returns the number of restored entries; 0 if a newer change made the undo obsolete. */
    @Synchronized fun restore(undo: JourneyArchiveUndo): Int {
        val existingIds = mutable.value.entries.map { it.id }.toSet()
        val (updated, count) = JourneyArchive.restore(mutable.value, undo, clock())
        if (count > 0 && !persist(updated)) return 0
        return if (count == 0) 0 else undo.entries.count { entry -> entry.id !in existingIds &&
            mutable.value.entries.any { it.id == entry.id } }
    }
    @Synchronized fun clear() { persist(JourneyArchive.clear(mutable.value)) }
    @Synchronized fun setEnabled(enabled: Boolean) {
        persist(JourneyArchive.withSettings(mutable.value, mutable.value.settings.copy(enabled = enabled), clock()))
    }
    @Synchronized fun setRetentionDays(days: Int) {
        persist(JourneyArchive.withSettings(mutable.value, mutable.value.settings.copy(retentionDays = days), clock()))
    }
    @Synchronized fun prune() { persist(JourneyArchive.prune(mutable.value, clock())) }

    private fun read(): JourneyArchiveState = runCatching {
        if (!file.isFile || file.length() > JourneyArchive.MAX_FILE_BYTES) return@runCatching JourneyArchiveState()
        val encoded = file.readText(Charsets.UTF_8)
        persisted = encoded
        JourneyArchive.decode(encoded, clock())
    }.getOrDefault(JourneyArchiveState())

    private fun persist(state: JourneyArchiveState): Boolean {
        val (snapshot, encoded) = JourneyArchive.storage(state)
        if (encoded == persisted && file.isFile) { mutable.value = snapshot; return true }
        val temp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            FileOutputStream(temp).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            mutable.value = snapshot
            persisted = encoded
            errorState.value = null
            true
        } catch (_: Exception) {
            errorState.value = "Fahrtenverlauf konnte nicht gespeichert werden."
            false
        } finally { temp.delete() }
    }
}
