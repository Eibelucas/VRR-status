package de.luca.mitfahrt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** These controls gate both devices; the separate, explicit vibration test stays available. */
data class AlertPreferences(
    val arrivalEnabled: Boolean = true,
    val exitEnabled: Boolean = true,
    val leadSeconds: Int = 60
) {
    init { require(leadSeconds in LEAD_OPTIONS) }
    companion object { val LEAD_OPTIONS = listOf(60, 120, 180) }
}

class AlertPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("journey_alert_options", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()

    /** Read in the service loop so a running session sees settings changed by another UI instance. */
    fun current(): AlertPreferences = read()
    fun setArrivalEnabled(enabled: Boolean) { update(current().copy(arrivalEnabled = enabled)) }
    fun setExitEnabled(enabled: Boolean) { update(current().copy(exitEnabled = enabled)) }
    fun setLeadSeconds(seconds: Int) { update(current().copy(leadSeconds = seconds)) }

    private fun read(): AlertPreferences = AlertPreferences(
        arrivalEnabled = runCatching { prefs.getBoolean("arrival", true) }.getOrDefault(true),
        exitEnabled = runCatching { prefs.getBoolean("exit", true) }.getOrDefault(true),
        leadSeconds = runCatching { prefs.getInt("lead_seconds", 60) }.getOrDefault(60)
            .takeIf { it in AlertPreferences.LEAD_OPTIONS } ?: 60
    )
    private fun update(value: AlertPreferences) {
        prefs.edit().putBoolean("arrival", value.arrivalEnabled).putBoolean("exit", value.exitEnabled)
            .putInt("lead_seconds", value.leadSeconds).apply()
        mutable.value = value
    }
}

object ArrivalAlertPolicy {
    fun due(state: TrackingState, preferences: AlertPreferences, now: Instant): Boolean {
        if (!preferences.arrivalEnabled || !state.running || state.lastRefreshFailed) return false
        val ride = state.ride?.takeUnless { it.boarded } ?: return false
        val boarding = ride.liveBoardingTime(now) ?: return false
        return boarding.at.epochSecond - now.epochSecond in 1..preferences.leadSeconds.toLong()
    }
    fun exitDue(state: TrackingState, preferences: AlertPreferences, now: Instant): Boolean =
        preferences.exitEnabled && ExitAlarm.due(state, now)
}
