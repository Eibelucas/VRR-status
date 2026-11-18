package de.luca.mitfahrt.widgets

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.RoutePlan

internal object RouteShortcuts {
    fun supported(context: Context): Boolean =
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    fun pin(context: Context, route: RoutePlan): Boolean = runCatching {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) return false
        val target = WidgetIntents.mainActivity(context, WidgetAction.Open(WidgetKind.ROUTE, route.id, "departures"))
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.Builder().scheme("mitfahrt").authority("route").appendPath(route.id).build())
        val shortcut = ShortcutInfo.Builder(context, "route-${route.id}")
            .setShortLabel(route.name)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(target)
            .build()
        manager.requestPinShortcut(shortcut, null)
    }.getOrDefault(false)
}
