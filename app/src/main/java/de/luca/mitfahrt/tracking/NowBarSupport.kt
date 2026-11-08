package de.luca.mitfahrt.tracking

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.drawable.Icon
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.AppSettingsStore

object NowBarSupport {
    const val PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val PREFS = "notification_options"

    fun samsungEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("samsung_compatibility", true)

    fun setSamsungEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("samsung_compatibility", enabled).apply()
    }

    fun ownTitleEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean("samsung_own_title", Build.VERSION.SDK_INT >= 37)

    fun setOwnTitleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("samsung_own_title", enabled).apply()
    }

    // Undocumented Samsung hints, always gated by the real live-update permission.
    // The private-card lane is restricted to Android 17 / One UI 9: it breaks 8.5.
    fun useSamsungHints(manufacturer: String, sdk: Int, canPromote: Boolean, enabled: Boolean): Boolean =
        manufacturer.equals("samsung", true) && sdk >= 36 && canPromote && enabled

    fun extras(context: Context, title: String, text: String, chip: String, progress: Int): Bundle {
        val settings = AppSettingsStore(context)
        val enabled = settings.current().bool("nowbar_enabled", true)
        settings.close()
        if (!enabled) return Bundle().apply { putBoolean("android.requestPromotedOngoing", false) }
        val manager = context.getSystemService(NotificationManager::class.java)
        val allowed = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()
        return notificationExtras(context.packageName, Build.MANUFACTURER, Build.VERSION.SDK_INT, allowed, samsungEnabled(context),
            ownTitleEnabled(context), title, text, chip, progress).apply {
            if (containsKey("android.ongoingActivityNoti.style")) {
                val icon = Icon.createWithResource(context, R.drawable.ic_bus)
                putParcelable("android.ongoingActivityNoti.chipIcon", icon)
                putParcelable("android.ongoingActivityNoti.nowbarIcon", icon)
            }
        }
    }

    internal fun notificationExtras(packageName: String, manufacturer: String, sdk: Int, canPromote: Boolean, enabled: Boolean,
        ownTitle: Boolean = false, title: String = "Mitfahrt", text: String = "", chip: String = "Mitfahrt", progress: Int = 0): Bundle = Bundle().apply {
        putBoolean("android.requestPromotedOngoing", true)
        if (useSamsungHints(manufacturer, sdk, canPromote, enabled)) {
            putBoolean("android.ongoingActivityNoti.automation", true)
            putString("android.ongoingActivityNoti.automationPackage", packageName)
            if (sdk >= 37 && ownTitle) {
                putInt("android.ongoingActivityNoti.style", 1)
                putString("android.ongoingActivityNoti.primaryInfo", "Mitfahrt · $title")
                putString("android.ongoingActivityNoti.nowbarPrimaryInfo", "Mitfahrt · $title")
                putString("android.ongoingActivityNoti.secondaryInfo", text)
                putString("android.ongoingActivityNoti.chipExpandedText", chip)
                putInt("android.ongoingActivityNoti.chipBgColor", Color.rgb(180, 0, 85))
                putInt("android.ongoingActivityNoti.progress", progress.coerceIn(0, 100))
                putInt("android.ongoingActivityNoti.progressMax", 100)
                putBoolean("android.showSmallIcon", true)
            }
        }
    }

    fun status(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val settings = AppSettingsStore(context)
        val enabled = settings.current().bool("nowbar_enabled", true)
        settings.close()
        return when {
            !enabled -> "Now Bar ist in Mitfahrt ausgeschaltet."
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
