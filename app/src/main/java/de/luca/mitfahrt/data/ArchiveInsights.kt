package de.luca.mitfahrt.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** One day cell of the archive calendar; [day] is null for padding before the first of the month. */
data class CalendarDay(val day: LocalDate?, val rides: Int)

object ArchiveCalendar {
    /** Weeks Monday to Sunday for [month], with the number of archived rides per day. */
    fun month(entries: List<ArchivedJourney>, month: YearMonth): List<List<CalendarDay>> {
        val counts = entries.groupingBy { it.day }.eachCount()
        val first = month.atDay(1)
        val padding = (first.dayOfWeek.value - DayOfWeek.MONDAY.value)
        val cells = List(padding) { CalendarDay(null, 0) } + (1..month.lengthOfMonth()).map { d ->
            val day = month.atDay(d); CalendarDay(day, counts[day] ?: 0)
        }
        return cells.chunked(7).map { week -> week + List(7 - week.size) { CalendarDay(null, 0) } }
    }

    fun months(entries: List<ArchivedJourney>, today: LocalDate): List<YearMonth> =
        (entries.map { YearMonth.from(it.day) } + YearMonth.from(today)).distinct().sortedDescending()
}

data class PunctualityRoute(val key: String, val label: String)

/** Separate counts: what VRR forecast, and what the user recorded. Rides without data are left out of the rate. */
data class PunctualityResult(
    val rides: Int,
    val forecastRides: Int, val forecastPunctual: Int, val forecastMedianDelay: Long?, val forecastMissing: Int,
    val ownRides: Int, val ownPunctual: Int, val ownMedianDelay: Long?, val ownMissing: Int
) {
    val forecastRate: Int? get() = if (forecastRides == 0) null else forecastPunctual * 100 / forecastRides
    val ownRate: Int? get() = if (ownRides == 0) null else ownPunctual * 100 / ownRides
}

/**
 * Punctuality of the own school routes, computed from the local archive only. "Punctual" means
 * less than [LATE_SECONDS] behind the timetable. The VRR value is the last forecast the app saw
 * before the ride ended, not a measured arrival.
 */
object PunctualityStats {
    const val LATE_SECONDS = 180L

    fun route(entry: ArchivedJourney): PunctualityRoute = PunctualityRoute(
        "${stopKey(entry.ride.departure.stopId)}>${entry.ride.exitTargetStop()?.id?.let(::stopKey) ?: stopKey(entry.ride.terminal.id)}",
        "${shortName(entry.boardingName)} → ${shortName(entry.targetName)}")

    fun routes(entries: List<ArchivedJourney>): List<PunctualityRoute> = entries.map(::route)
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun compute(entries: List<ArchivedJourney>, marks: List<ArrivalMark>, from: LocalDate?, to: LocalDate, route: String?): PunctualityResult {
        val chosen = entries.filter { (from == null || !it.day.isBefore(from)) && !it.day.isAfter(to) && (route == null || route(it).key == route) }
        val forecast = chosen.mapNotNull { entry ->
            val exit = entry.ride.exitTargetStop() ?: entry.ride.terminal
            val planned = exit.arrival ?: exit.departure
            val estimate = exit.estimatedArrival ?: exit.estimatedDeparture
            if (planned == null || estimate == null) null else Duration.between(planned, estimate).seconds
        }
        val own = chosen.mapNotNull { entry ->
            val exit = entry.ride.exitTargetStop() ?: entry.ride.terminal
            val planned = exit.arrival ?: exit.departure ?: return@mapNotNull null
            // A recorded arrival at the exit stop within three hours of the plan belongs to this ride.
            val mark = marks.filter { stopKey(it.stopId) == stopKey(exit.id) && Duration.between(planned, it.at).abs().seconds <= 3 * 3600 }
                .minByOrNull { Duration.between(planned, it.at).abs() } ?: return@mapNotNull null
            Duration.between(planned, mark.at).seconds
        }
        fun median(values: List<Long>) = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        return PunctualityResult(chosen.size,
            forecast.size, forecast.count { it < LATE_SECONDS }, median(forecast), chosen.size - forecast.size,
            own.size, own.count { it < LATE_SECONDS }, median(own), chosen.size - own.size)
    }
}
