package de.luca.mitfahrt.data

import java.time.Duration
import java.time.Instant

/** Refresh and location rhythm for the running ride. */
data class PowerPlan(val refreshMillis: Long, val location: LocationProfile, val saving: Boolean)

/**
 * Optional battery saver for long rides. It stretches VRR/NEW refreshes and GPS while nothing is
 * about to happen; around boarding, exit, a possible boarding or exit, and a cancellation the
 * normal rhythm returns so warnings are not delayed.
 */
object PowerPolicy {
    const val SAVING_REFRESH_MILLIS = 90_000L
    val SAVING_LOCATION = LocationProfile(20_000, 50f)
    /** "long": only when the remaining ride is longer than this. */
    const val LONG_RIDE_SECONDS = 20 * 60L
    const val NEAR_SECONDS = 5 * 60L

    fun plan(ride: Ride?, phase: BoardingPhase?, mode: String, normalRefreshSeconds: Int, now: Instant): PowerPlan {
        val normal = PowerPlan(normalRefreshSeconds.coerceIn(15, 120) * 1000L, LocationProfile.of(ride, phase, now), false)
        if (ride == null || ride.demo || mode == "off") return normal
        if (ride.departure.cancelled || phase == BoardingPhase.POSSIBLE_BOARDING || phase == BoardingPhase.POSSIBLE_EXIT) return normal
        val event = if (ride.boarded) ride.displayTarget().effective(ride.offsetSeconds) else ride.boardingTime().at
        val until = event?.let { Duration.between(now, it).seconds } ?: return normal
        if (until <= NEAR_SECONDS) return normal
        if (mode == "long" && until <= LONG_RIDE_SECONDS) return normal
        return PowerPlan(maxOf(normal.refreshMillis, SAVING_REFRESH_MILLIS), SAVING_LOCATION, true)
    }
}
