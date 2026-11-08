package de.luca.mitfahrt.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Homescreen widget for departures of a stop, a saved route or the tracked ride. */
class MitfahrtWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MitfahrtWidgets.ACTION_REFRESH -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?.filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.toIntArray()
                    ?: MitfahrtWidgets.ids(context)
                // A tap on the widget's refresh button and the first load after setup are user requests.
                MitfahrtWidgets.refreshAsync(context, ids, userInitiated = true, pending = goAsync())
            }
            MitfahrtWidgets.ACTION_RENDER -> MitfahrtWidgets.render(context)
            else -> super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Show stored data at once, then refresh sparingly (system period: 30 minutes).
        MitfahrtWidgets.render(context, appWidgetIds)
        MitfahrtWidgets.refreshAsync(context, appWidgetIds, userInitiated = false, pending = goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager,
                                           appWidgetId: Int, newOptions: Bundle) {
        MitfahrtWidgets.render(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach(store::remove)
    }

    override fun onDisabled(context: Context) {
        MitfahrtWidgets.cancelScheduled(context)
        WidgetStore(context).removeAll()
    }
}
