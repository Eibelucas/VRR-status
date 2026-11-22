package de.luca.mitfahrt.data

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Suggestion to temporarily switch a saved route's destination based on repeated recent exits. */
data class DynamicDestinationSuggestion(
    val routeId: String,
    val routeName: String,
    val regularDestination: Stop,
    val suggestedStop: Stop,
    val count: Int,
    val lastSeenAt: Instant
)

object TemporaryDestinationPresets {
    fun today(stop: Stop, today: LocalDate, label: String = ""): TemporaryDestination =
        TemporaryDestination(stop, today, today, label)

    /** Covers today through Sunday of the current week (at least 5 days on weekends). */
    fun thisWeek(stop: Stop, today: LocalDate, label: String = ""): TemporaryDestination {
        val untilSunday = (DayOfWeek.SUNDAY.value - today.dayOfWeek.value).coerceAtLeast(0).toLong()
        val end = if (untilSunday >= 2L) today.plusDays(untilSunday) else today.plusDays(6)
        return TemporaryDestination(stop, today, end, label)
    }

    fun twoWeeks(stop: Stop, today: LocalDate, label: String = ""): TemporaryDestination =
        TemporaryDestination(stop, today, today.plusDays(13), label)
}

/**
 * Pure local learner for temporary route-destination changes (e.g. a two-week internship
 * where the user repeatedly gets off at an intermediate stop on an existing route).
 * Uses only the user's local JourneyArchive; never transmits or invents stops.
 */
object DynamicRouteLearner {
    const val MIN_REPEATS = 2
    const val LOOKBACK_DAYS = 14L
    const val FRESH_DAYS = 7L
    val zone: ZoneId = ZoneId.of("Europe/Berlin")

    fun suggestions(
        plans: List<RoutePlan>,
        archive: List<ArchivedJourney>,
        now: Instant,
        dismissed: Map<String, Instant> = emptyMap()
    ): List<DynamicDestinationSuggestion> {
        val today = now.atZone(zone).toLocalDate()
        val cutoff = now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        val freshCutoff = now.minus(FRESH_DAYS, ChronoUnit.DAYS)
        val recent = archive.asSequence()
            .filter { !it.ride.demo && !it.ride.departure.cancelled && !it.startedAt.isBefore(cutoff) && !it.startedAt.isAfter(now) }
            .sortedByDescending { it.startedAt }
            .toList()
        if (recent.isEmpty()) return emptyList()

        return plans.asSequence()
            .filter { it.enabled }
            .mapNotNull { plan ->
                val originId = stopKey(plan.origin.id)
                val regularDestId = stopKey(plan.destination.id)
                val activeTempId = plan.activeTemporaryDestination(today)?.stop?.id?.let(::stopKey)
                val allowedLines = (plan.allowedLines.ifEmpty { setOf(plan.line) })
                    .map(::normalizeLine).filter { it.isNotEmpty() }.toSet()

                val matchingRides = recent.filter { entry ->
                    val ride = entry.ride
                    stopKey(ride.departure.stopId) == originId &&
                        (plan.match == RouteMatch.STOP || allowedLines.isEmpty() || normalizeLine(ride.departure.line) in allowedLines)
                }
                if (matchingRides.size < MIN_REPEATS) return@mapNotNull null

                // Only suggest when the user's latest ride on this route actually exited at the alternative stop.
                val latest = matchingRides.first()
                if (latest.startedAt.isBefore(freshCutoff)) return@mapNotNull null
                val latestExit = explicitExitStop(latest.ride) ?: return@mapNotNull null
                val latestExitKey = stopKey(latestExit.id)
                if (latestExitKey == originId || latestExitKey == regularDestId || latestExitKey == activeTempId) {
                    return@mapNotNull null
                }

                val sameAlt = matchingRides.filter { entry ->
                    explicitExitStop(entry.ride)?.let { stopKey(it.id) == latestExitKey } == true
                }
                if (sameAlt.size < MIN_REPEATS) return@mapNotNull null

                val dismissKey = dismissalKey(plan.id, latestExitKey)
                val dismissedAt = dismissed[dismissKey]
                if (dismissedAt != null && !latest.startedAt.isAfter(dismissedAt)) return@mapNotNull null

                DynamicDestinationSuggestion(
                    routeId = plan.id,
                    routeName = plan.name,
                    regularDestination = plan.destination,
                    suggestedStop = Stop(latestExitKey, latestExit.name, latestExit.point),
                    count = sameAlt.size,
                    lastSeenAt = latest.startedAt
                )
            }
            .toList()
    }

    fun dismissalKey(routeId: String, stopId: String): String = "$routeId/${stopKey(stopId)}"

    private fun explicitExitStop(ride: Ride): RideStop? {
        val index = ride.exitTargetIndex() ?: return null
        if (index <= ride.boardingIndex) return null
        return ride.stops.getOrNull(index)?.takeUnless { it.cancelled }
    }

    private fun normalizeLine(value: String): String = value.trim().uppercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")
        .replace(Regex("[0-9]+")) { it.value.trimStart('0').ifEmpty { "0" } }
}

/** Local-only dismissal store for dynamic destination suggestions. */
class DynamicRouteDismissalStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dynamic_route_dismissals", Context.MODE_PRIVATE)

    @Synchronized fun all(): Map<String, Instant> = prefs.all.mapNotNull { (key, value) ->
        (value as? Long)?.let { key to Instant.ofEpochMilli(it) }
    }.toMap()

    @Synchronized fun dismiss(routeId: String, stopId: String, at: Instant = Instant.now()) {
        val key = DynamicRouteLearner.dismissalKey(routeId, stopId)
        val editor = prefs.edit().putLong(key, at.toEpochMilli())
        // Keep the store compact by pruning dismissals older than 90 days.
        val oldest = at.minus(90, ChronoUnit.DAYS).toEpochMilli()
        prefs.all.forEach { (existing, value) ->
            if (existing != key && value is Long && value < oldest) editor.remove(existing)
        }
        editor.apply()
    }
}
