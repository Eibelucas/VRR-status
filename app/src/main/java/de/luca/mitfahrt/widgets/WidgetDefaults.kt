package de.luca.mitfahrt.widgets

import android.content.Context
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*

/** Same validated settings/profile codec as the app, without a leaking preference listener. */
internal object WidgetDefaults {
    fun settings(context: Context): AppSettings = runCatching {
        val prefs = context.applicationContext.getSharedPreferences(AppSettingsStore.PREFERENCES, Context.MODE_PRIVATE)
        AppSettingsCodec.decode(prefs.getString("state", "{\"version\":1}") ?: "{\"version\":1}")
    }.getOrDefault(AppSettings())

    fun resolve(context: Context, fallback: WidgetConfig? = null): WidgetConfig {
        val app = context.applicationContext as? MitfahrtApplication
        return from(settings(context), app?.routePlans?.current()?.routes.orEmpty(),
            app?.savedStops?.state?.value?.favorites.orEmpty(), fallback)
    }

    fun from(settings: AppSettings, routes: List<RoutePlan>, stops: List<Stop>, fallback: WidgetConfig? = null): WidgetConfig {
        val kind = WidgetKind.fromKey(settings.string("widget_kind", "active")) ?: WidgetKind.ACTIVE
        val id = settings.string(if (kind == WidgetKind.ROUTE) "widget_route_id" else "widget_stop_id")
        val route = routes.firstOrNull { kind == WidgetKind.ROUTE && (if (id.isNotEmpty()) it.id == id else it.enabled) }
        val stop = stops.firstOrNull { kind == WidgetKind.STOP && (id.isEmpty() || stopKey(it.id) == stopKey(id)) }
        val previous = fallback?.takeIf { it.kind == kind && (id.isEmpty() || it.targetId == id) }
        return WidgetConfig(kind,
            targetId = route?.id ?: stop?.id ?: previous?.targetId.orEmpty(),
            targetName = route?.name ?: stop?.name ?: previous?.targetName.orEmpty(),
            info = fallback?.info ?: InfoLevel.NORMAL,
            theme = ThemeMode.fromKey(settings.string("widget_theme", "system")),
            accent = fallback?.accent ?: WidgetAccent.BERRY,
            opacity = settings.int("widget_opacity", 100), useAppDefaults = true,
            action = settings.string("widget_action", "preview"), rowCount = settings.int("widget_count", 3))
    }
}
