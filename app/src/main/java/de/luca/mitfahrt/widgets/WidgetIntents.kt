package de.luca.mitfahrt.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import de.luca.mitfahrt.MainActivity

/** Extras MainActivity reads. Opening only navigates; it never starts, stops or saves a ride. */
object WidgetIntents {
    const val EXTRA_KIND = "widget_kind"
    const val EXTRA_ID = "widget_id"
    const val EXTRA_ACTION = "widget_action"
    const val EXTRA_OPEN_LIVE_MAP = "open_live_map"
    val ACTIONS = setOf("preview", "departures", "ride", "map")

    fun extras(action: WidgetAction.Open): Map<String, Any> {
        require(action.action in ACTIONS) { "Unknown widget action ${action.action}" }
        return buildMap {
            put(EXTRA_KIND, action.kind.key)
            action.id?.takeIf { it.isNotBlank() && action.kind != WidgetKind.ACTIVE }?.let { put(EXTRA_ID, it) }
            put(EXTRA_ACTION, action.action)
            if (action.action == "map") put(EXTRA_OPEN_LIVE_MAP, true)
        }
    }

    fun mainActivity(context: Context, action: WidgetAction.Open): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            extras(action).forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                }
            }
        }

    fun configuration(context: Context, widgetId: Int): Intent =
        Intent(context, WidgetConfigurationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
}
