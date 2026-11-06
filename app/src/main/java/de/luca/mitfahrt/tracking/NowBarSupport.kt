package de.luca.mitfahrt.tracking

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

object NowBarSupport {
    const val PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val PREFS = "notification_options"

    fun samsungEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("samsung_compatibility", true)

    fun setSamsungEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("samsung_compatibility", enabled).apply()
    }

    // Small, isolated vendor hint. No private style, reflected API, spoofed package or custom view.
    // Guarded by the user's actual Android live-notification permission.
    fun useSamsungHints(manufacturer: String, sdk: Int, canPromote: Boolean, enabled: Boolean): Boolean =
        manufacturer.equals("samsung", true) && sdk >= 36 && canPromote && enabled

    fun extras(context: Context): Bundle {
        val manager = context.getSystemService(NotificationManager::class.java)
        val allowed = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()
        return notificationExtras(context.packageName, Build.MANUFACTURER, Build.VERSION.SDK_INT, allowed, samsungEnabled(context))
    }

    internal fun notificationExtras(packageName: String, manufacturer: String, sdk: Int, canPromote: Boolean, enabled: Boolean): Bundle = Bundle().apply {
        putBoolean("android.requestPromotedOngoing", true)
        if (useSamsungHints(manufacturer, sdk, canPromote, enabled)) {
            putBoolean("android.ongoingActivityNoti.automation", true)
            putString("android.ongoingActivityNoti.automationPackage", packageName)
        }
    }

    fun status(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        return when {
            !manager.areNotificationsEnabled() -> "Benachrichtigungen sind ausgeschaltet."
            manager.getNotificationChannel(RideNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> "Fahrt-Benachrichtigungen sind ausgeschaltet."
            Build.VERSION.SDK_INT < 36 -> "Live-Updates benötigen Android 16 oder neuer."
            !manager.canPostPromotedNotifications() -> "Live-Benachrichtigungen sind nicht freigegeben."
            else -> {
                val notification = manager.activeNotifications.firstOrNull { it.id == RideNotifications.ID }?.notification
                when {
                    notification == null || !notification.extras.getBoolean("android.requestPromotedOngoing") -> "Wähle zuerst einen Bus aus, den du verfolgen möchtest."
                    notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 -> "Android-Live-Update aktiv."
                    notification.hasPromotableCharacteristics() -> "Live-Update bereit. Prüfe die Now Bar bei geschlossener App."
                    else -> "Android hat die Fahrt noch nicht als Live-Update übernommen."
                }
            }
        }
    }

    fun openSettings(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 36 && manager.areNotificationsEnabled())
                add(Intent(PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        for (intent in candidates) if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
