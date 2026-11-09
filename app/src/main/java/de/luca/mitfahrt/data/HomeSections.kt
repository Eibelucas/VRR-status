package de.luca.mitfahrt.data

import java.time.Instant

/**
 * Sections under "Wohin?" on the start page. A section is listed only when it has something
 * to show right now, so there is never an empty heading or placeholder card. Nearby stops
 * and favourites are not part of the start page any more ("In der Nähe" tab).
 */
object HomeSections {
    fun activePlans(plans: List<RoutePlan>, now: Instant, nearby: List<Stop>, settings: AppSettings,
        overrides: List<RouteDayOverride>, calendar: SchoolCalendarSettings?): List<RoutePlan> =
        plans.filter { CustomizationPolicy.active(it, now, nearby, settings, overrides, calendar) }
            .take(settings.int("routes_visible_count", 3))

    fun visible(order: List<String>, settings: AppSettings, activePlans: List<RoutePlan>, now: Instant,
        calendar: SchoolCalendarSettings?, schoolTrips: Int, suggestions: Int, hasLastRide: Boolean): List<String> {
        val schoolFree = calendar != null && SchoolCalendar.status(now.atZone(RouteDayOverrides.zone).toLocalDate(), calendar).free
        return order.filter { it in SettingsMigration.HOME_CARDS }.distinct().filter { section ->
            when (section) {
                "planned" -> activePlans.isNotEmpty()
                "school" -> schoolTrips > 0 && !settings.bool("routes_school_paused", false) && !schoolFree
                "suggestions" -> suggestions > 0 && !settings.bool("manual_only", false)
                "last" -> hasLastRide
                "map" -> true
                else -> false
            }
        }
    }
}
