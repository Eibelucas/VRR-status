package de.luca.mitfahrt.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import de.luca.mitfahrt.MainActivity
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.shortName

/** Small, network-free shortcut to the explicitly saved stop boards. */
class FavoriteStopsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) =
        render(context, manager, intArrayOf(id))

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            render(context, manager, manager.getAppWidgetIds(ComponentName(context, FavoriteStopsWidget::class.java)))
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val app = context.applicationContext as? MitfahrtApplication ?: return
            val favorites = app.savedStops.state.value.favorites
            val settings = WidgetDefaults.settings(app)
            val theme = settings.string("widget_theme").takeUnless { it == "system" } ?: settings.string("theme")
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val paper = if (dark) Color.rgb(39, 29, 44) else Color.WHITE
            val ink = if (dark) Color.rgb(248, 242, 248) else Color.rgb(34, 38, 47)
            val accent = if (dark) Color.rgb(255, 155, 199) else Color.rgb(146, 0, 70)
            ids.forEach { id ->
                val rows = ((manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 128) - 30) / 44).coerceIn(1, 4)
                val views = RemoteViews(app.packageName, R.layout.mitfahrt_favorite_stops_widget)
                views.setInt(R.id.favorite_stops_background, "setColorFilter", paper)
                views.setInt(R.id.favorite_stops_background, "setImageAlpha", settings.int("widget_opacity", 100).coerceIn(20, 100) * 255 / 100)
                views.setTextColor(R.id.favorite_stops_title, ink)
                val open = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("mitfahrt://favorites")).putExtra("open_favorites", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(R.id.favorite_stops_title, pending(app, open))
                val rowIds = listOf(R.id.favorite_stop_1, R.id.favorite_stop_2, R.id.favorite_stop_3, R.id.favorite_stop_4)
                rowIds.forEachIndexed { index, rowId ->
                    val favorite = favorites.getOrNull(index)?.takeIf { index < rows }
                    views.setTextColor(rowId, accent)
                    views.setViewVisibility(rowId, if (favorite != null || index == 0 && favorites.isEmpty()) View.VISIBLE else View.GONE)
                    if (favorite != null) {
                        val navigate = WidgetIntents.mainActivity(app, WidgetAction.Open(WidgetKind.STOP, favorite.id, "departures"))
                            .setAction(Intent.ACTION_VIEW)
                            .setData(Uri.Builder().scheme("mitfahrt").authority("favorite").appendPath(favorite.id).build())
                        views.setTextViewText(rowId, shortName(favorite.name))
                        views.setOnClickPendingIntent(rowId, pending(app, navigate))
                    } else if (index == 0 && favorites.isEmpty()) {
                        views.setTextViewText(rowId, context.getString(R.string.mitfahrt_favorite_stops_empty))
                        views.setOnClickPendingIntent(rowId, pending(app, open))
                    }
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun pending(context: Context, intent: Intent) = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
