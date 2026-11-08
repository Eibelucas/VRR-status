package de.luca.mitfahrt.alerts

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.RuntimeAlertOptions
import java.time.Instant

/** A single short notification pulse; system vibration/DND settings remain authoritative. */
object ArrivalHaptics {
    fun pulse(context: Context, id: String, kind: String = "arrival") {
        val store = AppSettingsStore(context)
        val settings = store.current()
        store.close()
        if (!RuntimeAlertOptions.from(settings).allowed(kind, Instant.now())) return
        val waveform = waveform(settings.string("vibration_$kind", if (kind == "exit") "long" else "double")) ?: return
        val prefs = context.getSharedPreferences("arrival_haptics", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(99) + id).toSet()).apply()
        trigger(context, waveform)
    }

    /** Does not read or change journey-alert history. A true result means the API accepted the request. */
    fun testPulse(context: Context): Boolean = runCatching {
        if (!context.getSystemService(Vibrator::class.java).hasVibrator()) return@runCatching false
        trigger(context)
    }.getOrDefault(false)

    internal fun waveform(name: String): LongArray? = when (name) {
        "off" -> null
        "short" -> longArrayOf(0, 160)
        "long" -> longArrayOf(0, 650)
        "triple" -> longArrayOf(0, 180, 100, 180, 100, 180)
        else -> longArrayOf(0, 250, 120, 250)
    }

    private fun trigger(context: Context, waveform: LongArray = longArrayOf(0, 250, 120, 250)): Boolean = runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java)
            vibrator.vibrate(VibrationEffect.createWaveform(waveform, -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
            true
    }.getOrDefault(false)
}
