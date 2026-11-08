package de.luca.mitfahrt.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import de.luca.mitfahrt.R

/** Turns a [WidgetModel] into RemoteViews; all decisions about content are made by the presenter. */
internal object WidgetRenderer {
    private const val SLOT_OPEN = 0
    private const val SLOT_REFRESH = 1
    private const val SLOT_MAP = 2
    private const val SLOT_ROW = 3
    private const val SLOTS = 8

    fun build(context: Context, widgetId: Int, config: WidgetConfig?, model: WidgetModel, options: Bundle?): RemoteViews {
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            val sizes = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width > 0f && it.height > 0f }
                ?.distinctBy { it.width.toInt() to it.height.toInt() }?.take(8).orEmpty()
            if (sizes.isNotEmpty()) return RemoteViews(sizes.associateWith {
                views(context, widgetId, config, model, it.width.toInt(), it.height.toInt())
            })
        }
        // Portrait size: the narrowest width with the tallest height the launcher reports.
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)?.takeIf { it > 0 } ?: 250
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)?.takeIf { it > 0 } ?: 110
        return views(context, widgetId, config, model, width, height)
    }

    private fun views(context: Context, widgetId: Int, config: WidgetConfig?, model: WidgetModel,
                      widthDp: Int, heightDp: Int): RemoteViews {
        val kind = config?.kind ?: WidgetKind.STOP
        val spec = WidgetSizing.spec(widthDp, heightDp, config?.info ?: InfoLevel.NORMAL, kind)
        val paint = Painter(config?.theme ?: ThemeMode.SYSTEM, systemDark(context), config?.accent ?: WidgetAccent.BERRY)
        val views = RemoteViews(context.packageName, R.layout.mitfahrt_widget_root)

        paint.color(views, R.id.mitfahrt_widget_background, "setColorFilter") { it.background }
        views.setInt(R.id.mitfahrt_widget_background, "setImageAlpha", WidgetPalette.alpha(config?.opacity ?: 100))

        views.setTextViewText(R.id.mitfahrt_widget_title, model.title)
        paint.color(views, R.id.mitfahrt_widget_title, "setTextColor") { it.text }

        views.setViewVisibility(R.id.mitfahrt_widget_header, if (spec.showHeader) View.VISIBLE else View.GONE)
        val status = model.statusLine?.takeIf { spec.showStatusLine }
        if (status != null) {
            views.setViewVisibility(R.id.mitfahrt_widget_status, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_widget_status, status)
            paint.color(views, R.id.mitfahrt_widget_status, "setTextColor") { if (model.warning) it.warning else it.muted }
        } else views.setViewVisibility(R.id.mitfahrt_widget_status, View.GONE)

        views.setOnClickPendingIntent(R.id.mitfahrt_widget_container, pending(context, widgetId, SLOT_OPEN, model.open))

        if (model.status == WidgetStatus.NOT_CONFIGURED) {
            views.setViewVisibility(R.id.mitfahrt_widget_refresh, View.GONE)
        } else {
            views.setViewVisibility(R.id.mitfahrt_widget_refresh, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.mitfahrt_widget_refresh, refresh(context, widgetId))
            paint.color(views, R.id.mitfahrt_widget_refresh, "setColorFilter") { it.muted }
        }

        val map = model.mapAction?.takeIf { spec.showMap }
        if (map != null) {
            views.setViewVisibility(R.id.mitfahrt_widget_map, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.mitfahrt_widget_map, pending(context, widgetId, SLOT_MAP, map))
            paint.color(views, R.id.mitfahrt_widget_map, "setColorFilter") { it.accent }
        } else views.setViewVisibility(R.id.mitfahrt_widget_map, View.GONE)

        views.removeAllViews(R.id.mitfahrt_widget_rows)
        val rows = model.rows.take(spec.rows)
        rows.forEachIndexed { index, row ->
            views.addView(R.id.mitfahrt_widget_rows, row(context, widgetId, index, row, spec, paint))
        }
        val message = model.message?.takeIf { rows.isEmpty() }
        if (message != null) {
            views.setViewVisibility(R.id.mitfahrt_widget_message, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_widget_message, message)
            paint.color(views, R.id.mitfahrt_widget_message, "setTextColor") { it.muted }
        } else views.setViewVisibility(R.id.mitfahrt_widget_message, View.GONE)
        return views
    }

    private fun row(context: Context, widgetId: Int, index: Int, row: WidgetRow, spec: WidgetLayoutSpec,
                    paint: Painter): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.mitfahrt_widget_row)
        if (row.badge.isNullOrBlank()) {
            views.setViewVisibility(R.id.mitfahrt_widget_row_badge, View.INVISIBLE)
        } else {
            views.setViewVisibility(R.id.mitfahrt_widget_row_badge, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_widget_row_line, row.badge)
            paint.color(views, R.id.mitfahrt_widget_row_badge_bg, "setColorFilter") { it.accent }
            paint.color(views, R.id.mitfahrt_widget_row_line, "setTextColor") { it.onAccent }
        }
        views.setTextViewText(R.id.mitfahrt_widget_row_title, row.title)
        paint.color(views, R.id.mitfahrt_widget_row_title, "setTextColor") { it.text }

        val detail = row.detail?.takeIf { spec.showDetail }
        if (detail != null) {
            views.setViewVisibility(R.id.mitfahrt_widget_row_detail, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_widget_row_detail, detail)
            paint.color(views, R.id.mitfahrt_widget_row_detail, "setTextColor") { it.muted }
        } else views.setViewVisibility(R.id.mitfahrt_widget_row_detail, View.GONE)

        if (row.time.isBlank()) {
            views.setViewVisibility(R.id.mitfahrt_widget_row_times, View.GONE)
        } else {
            views.setViewVisibility(R.id.mitfahrt_widget_row_times, View.VISIBLE)
            views.setTextViewText(R.id.mitfahrt_widget_row_time, row.time)
            views.setTextViewText(R.id.mitfahrt_widget_row_label, row.timeLabel)
            // The source of a time (live, Prognose, Plan, Ausfall) stays visible on every size.
            views.setViewVisibility(R.id.mitfahrt_widget_row_label,
                if (row.timeLabel.isNotBlank()) View.VISIBLE else View.GONE)
            paint.color(views, R.id.mitfahrt_widget_row_time, "setTextColor") {
                when (row.timeKind) {
                    TimeKind.LIVE -> it.accent
                    TimeKind.CANCELLED -> it.warning
                    else -> it.text
                }
            }
            paint.color(views, R.id.mitfahrt_widget_row_label, "setTextColor") {
                when (row.timeKind) {
                    TimeKind.LIVE -> it.accent
                    TimeKind.CANCELLED -> it.warning
                    else -> it.muted
                }
            }
        }
        views.setContentDescription(R.id.mitfahrt_widget_row, row.description)
        views.setOnClickPendingIntent(R.id.mitfahrt_widget_row,
            pending(context, widgetId, SLOT_ROW + index.coerceAtMost(SLOTS - SLOT_ROW - 1), row.action))
        return views
    }

    private fun pending(context: Context, widgetId: Int, slot: Int, action: WidgetAction): PendingIntent {
        val intent = when (action) {
            is WidgetAction.Open -> WidgetIntents.mainActivity(context, action)
            WidgetAction.Configure -> WidgetIntents.configuration(context, widgetId)
        }
        return PendingIntent.getActivity(context, requestCode(widgetId, slot), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refresh(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode(widgetId, SLOT_REFRESH),
            Intent(context, MitfahrtWidgetProvider::class.java).setAction(MitfahrtWidgets.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun requestCode(widgetId: Int, slot: Int): Int = widgetId * SLOTS + slot

    private fun systemDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private class Painter(private val theme: ThemeMode, private val systemDark: Boolean, accent: WidgetAccent) {
        private val light = WidgetPalette.of(false, accent)
        private val dark = WidgetPalette.of(true, accent)

        fun color(views: RemoteViews, id: Int, method: String, pick: (WidgetPalette) -> Int) {
            when {
                theme == ThemeMode.LIGHT -> views.setInt(id, method, pick(light))
                theme == ThemeMode.DARK -> views.setInt(id, method, pick(dark))
                // Android 12+ switches with the launcher's night mode without a new update.
                Build.VERSION.SDK_INT >= 31 -> views.setColorInt(id, method, pick(light), pick(dark))
                else -> views.setInt(id, method, pick(if (systemDark) dark else light))
            }
        }
    }
}
