package de.luca.mitfahrt.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only view helpers for personal arrival marks. These are the user's own records and
 * are never presented as an operator-confirmed bus arrival.
 */
object ArrivalHistory {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")

    data class StopOption(val key: String, val name: String, val count: Int)

    /** Origin as stored by ArrivalMark.method; unknown values get no label instead of a guess. */
    fun originLabel(mark: ArrivalMark): String? = when (mark.method) {
        "manual" -> "Selbst markiert"
        "location" -> "Über Standort erkannt"
        else -> null
    }

    fun stops(marks: List<ArrivalMark>): List<StopOption> = marks.distinctBy { it.id }.groupBy { stopKey(it.stopId) }
        .map { (key, items) -> StopOption(key, items.maxBy { it.at }.stopName, items.size) }
        .sortedWith(compareByDescending<StopOption> { it.count }.thenBy { it.name })

    /** Newest day first, newest mark first; optionally only one stop. */
    fun byDay(marks: List<ArrivalMark>, stopKeyFilter: String? = null): List<Pair<LocalDate, List<ArrivalMark>>> =
        marks.filter { stopKeyFilter == null || stopKey(it.stopId) == stopKeyFilter }
            .distinctBy { it.id }
            .groupBy { it.at.atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (day, items) -> day to items.sortedByDescending { it.at } }
}
