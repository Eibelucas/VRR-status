package de.luca.mitfahrt.alerts

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import java.time.Instant
import java.time.ZoneId
import java.time.LocalTime

/** A single short notification pulse; system vibration/DND settings remain authoritative. */
object ArrivalHaptics {
    fun pulse(context: Context, id: String) {
        val prefs = context.getSharedPreferences("arrival_haptics", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(99) + id).toSet()).apply()
        val config = context.getSharedPreferences("watch_ride", Context.MODE_PRIVATE)
        if (config.getString("alert_target", "both") in setOf("phone", "off")) return
        if (config.getString("quiet_enabled", "false") == "true") {
            fun minute(value: String?) = value?.toIntOrNull()?.coerceIn(0, 1439) ?: runCatching {
                LocalTime.parse(value).let { it.hour * 60 + it.minute }
            }.getOrDefault(0)
            val local = Instant.now().atZone(ZoneId.of("Europe/Berlin")); val now = local.hour * 60 + local.minute
            val start = minute(config.getString("quiet_start", "22:00")); val end = minute(config.getString("quiet_end", "07:00"))
            val quiet = if (start == end) true else if (start < end) now in start until end else now >= start || now < end
            val critical = (id.startsWith("cancelled/") || id.startsWith("delay/")) && config.getString("quiet_critical", "false") == "true"
            if (quiet && !critical) return
        }
        val kind = when {
            id.startsWith("exit/") -> "exit"
            id.startsWith("walking/") -> "walking"
            id.startsWith("delay/") -> "delay"
            id.startsWith("cancelled/") -> "cancelled"
            else -> "arrival"
        }
        trigger(context, config.getString("vibration_$kind", "double") ?: "double")
    }

    /** Does not read or change journey-alert history. A true result means the API accepted the request. */
    fun testPulse(context: Context): Boolean = runCatching {
        if (!context.getSystemService(Vibrator::class.java).hasVibrator()) return@runCatching false
        trigger(context)
    }.getOrDefault(false)

    private fun trigger(context: Context, pattern: String = "double"): Boolean = runCatching {
            if (pattern == "off") return@runCatching false
            val vibrator = context.getSystemService(Vibrator::class.java)
            val wave = when (pattern) {
                "short" -> longArrayOf(0, 180)
                "long" -> longArrayOf(0, 600)
                else -> longArrayOf(0, 250, 120, 250)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(wave, -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            true
    }.getOrDefault(false)
}
