package de.luca.mitfahrt.updates

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.MainActivity
import de.luca.mitfahrt.data.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object UpdateCheckPolicy {
    fun interval(mode: String): Long? = when (mode) {
        "daily" -> 86400_000L
        "weekly" -> 7 * 86400_000L
        else -> null
    }
    fun shouldCheck(mode: String, last: Long, now: Long): Boolean = when (mode) {
        "manual" -> false
        "open" -> last <= 0 || now < last || now - last >= 3600_000L
        else -> interval(mode)?.let { last <= 0 || now < last || now - last >= it } ?: false
    }
}

/** Inexact system scheduling respects Android battery restrictions; no background APK installation. */
object UpdateScheduler {
    private const val REQUEST = 42108
    fun schedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = PendingIntent.getBroadcast(context, REQUEST,
            Intent(context, UpdateCheckReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(operation)
        val interval = UpdateCheckPolicy.interval(updateMode(context)) ?: return
        alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + interval, interval, operation)
    }
}

private fun updateMode(context: Context): String {
    val settings = AppSettingsStore(context)
    return try { settings.current().string("update_check", "open") } finally { settings.close() }
}

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { UpdateScheduler.schedule(context); return }
        val mode = updateMode(context)
        if (UpdateCheckPolicy.interval(mode) == null) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(30_000) {
                    val info = UpdateClient(context).use { it.check() }
                    context.getSharedPreferences("update_checks", Context.MODE_PRIVATE).edit().putLong("last_check", System.currentTimeMillis()).apply()
                    if (info.versionCode <= BuildConfig.VERSION_CODE) return@withTimeout
                    val manager = context.getSystemService(NotificationManager::class.java) ?: return@withTimeout
                    if (!manager.areNotificationsEnabled()) return@withTimeout
                    manager.createNotificationChannel(NotificationChannel("updates", "App-Updates", NotificationManager.IMPORTANCE_LOW))
                    val open = PendingIntent.getActivity(context, 42109,
                        Intent(context, MainActivity::class.java).putExtra("open_settings", true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    manager.notify(42109, NotificationCompat.Builder(context, "updates")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Mitfahrt ${info.versionName} verfügbar")
                        .setContentText("App öffnen und Update ansehen").setContentIntent(open).setAutoCancel(true).build())
                }
            } catch (_: Exception) {
                // A failed background check never interrupts an active bus journey.
            } finally { pending.finish() }
        }
    }
}
