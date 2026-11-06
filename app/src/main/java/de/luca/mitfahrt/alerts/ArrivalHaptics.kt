package de.luca.mitfahrt.alerts

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator

/** A single short notification pulse; system vibration/DND settings remain authoritative. */
object ArrivalHaptics {
    fun pulse(context: Context, id: String) {
        val prefs = context.getSharedPreferences("arrival_haptics", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return
        prefs.edit().putStringSet("seen", (seen.toList().takeLast(99) + id).toSet()).apply()
        val vibrator = context.getSystemService(Vibrator::class.java)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        }
    }
}
