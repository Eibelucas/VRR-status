package de.luca.mitfahrt.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import de.luca.mitfahrt.MitfahrtApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Coordinates rendering and refreshing. Network refreshes happen only on the system's
 * periodic widget update (at most every 30 minutes), after configuration and on a tap
 * on the refresh button. Clock times and a host-updated source-age label remain honest if Doze delays a re-render.
 */
object MitfahrtWidgets {
    const val ACTION_REFRESH = "de.luca.mitfahrt.widgets.action.REFRESH"
    const val ACTION_RENDER = "de.luca.mitfahrt.widgets.action.RENDER"

    private const val BATCH_TIMEOUT_MS = 20_000L
    private const val PERIODIC_MIN_AGE_SECONDS = 60L
    private const val MANUAL_MIN_GAP_SECONDS = 10L
    private const val TRACKING_RENDER_GAP_MS = 15_000L
    private const val MAX_RENDER_DELAY_SECONDS = 6 * 3600L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()
    private var lastTrackingRender = 0L
    private var lastTrackingKey: String? = null
    private data class Request(val config: WidgetConfig, val revision: String)

    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, MitfahrtWidgetProvider::class.java))

    /** Renders from stored data only; safe to call often and from any thread. */
    @Synchronized
    fun render(context: Context, ids: IntArray = ids(context), loading: Set<Int> = emptySet()) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val store = WidgetStore(app)
        val now = Instant.now()
        val active by lazy { runCatching { WidgetRepository(app).activeInput() }.getOrNull() }
        val currentIds = MitfahrtWidgets.ids(app).toSet()
        ids.filter { it in currentIds }.forEach { id ->
            runCatching {
                val config = store.config(id)
                val model = WidgetPresenter.present(config, store.snapshot(id), now, loading = id in loading,
                    active = if (config?.kind == WidgetKind.ACTIVE) active else null)
                manager.updateAppWidget(id, WidgetRenderer.build(app, id, config, model, manager.getAppWidgetOptions(id)))
            }
        }
        scheduleRender(app, now)
    }

    /** Runs a refresh in the background; [pending] keeps the broadcast alive until it is done. */
    fun refreshAsync(context: Context, ids: IntArray, userInitiated: Boolean, pending: BroadcastReceiver.PendingResult?) {
        val app = context.applicationContext
        val store = WidgetStore(app)
        val currentIds = MitfahrtWidgets.ids(app).toSet()
        val requests = ids.filter { it in currentIds }.mapNotNull { id ->
            store.config(id)?.let { id to Request(it, store.revision(id)) }
        }.toMap()
        if (requests.isEmpty()) { pending?.finish(); return }
        scope.launch {
            try {
                withTimeoutOrNull(BATCH_TIMEOUT_MS) { refresh(app, requests, userInitiated) }
                    ?: markTimedOut(app, requests)
            } finally {
                runCatching { render(app, ids) }
                pending?.finish()
            }
        }
    }

    private suspend fun refresh(context: Context, requests: Map<Int, Request>, userInitiated: Boolean) = refreshLock.withLock {
        val store = WidgetStore(context)
        val repository = WidgetRepository(context)
        val configured = requests.keys.filter { id -> requests[id]?.let { store.config(id) == it.config && store.revision(id) == it.revision } == true }
        if (configured.isEmpty()) return@withLock
        if (userInitiated) render(context, configured.toIntArray(), loading = configured.toSet())
        val online = repository.isOnline()
        configured.forEach { id ->
            val request = requests[id] ?: return@forEach
            val config = request.config
            if (store.config(id) != config || store.revision(id) != request.revision) return@forEach
            val previous = store.snapshot(id)
            val now = Instant.now()
            val age = previous.fetchedAt?.let { now.epochSecond - it.epochSecond }
            val recent = age != null && age in 0 until
                (if (userInitiated) MANUAL_MIN_GAP_SECONDS else PERIODIC_MIN_AGE_SECONDS)
            if (config.kind != WidgetKind.ACTIVE && recent && previous.failure == null) return@forEach
            if (config.kind == WidgetKind.ROUTE && repository.route(config.targetId) == null) {
                store.saveSnapshotIfCurrent(id, config, request.revision, WidgetSnapshot(targetMissing = true)); return@forEach
            }
            if (!online && config.kind != WidgetKind.ACTIVE) {
                store.saveSnapshotIfCurrent(id, config, request.revision, previous.copy(failure = FetchFailure.OFFLINE, failedAt = now)); return@forEach
            }
            store.saveSnapshotIfCurrent(id, config, request.revision, repository.fetch(config, previous, now))
        }
    }

    private fun markTimedOut(context: Context, requests: Map<Int, Request>) {
        val store = WidgetStore(context)
        val now = Instant.now()
        requests.forEach { (id, request) ->
            val previous = store.snapshot(id)
            val updated = previous.fetchedAt?.let { now.epochSecond - it.epochSecond < BATCH_TIMEOUT_MS / 1000 } == true
            if (store.config(id) != null && !updated)
                store.saveSnapshotIfCurrent(id, request.config, request.revision, previous.copy(failure = FetchFailure.SOURCE, failedAt = now))
        }
    }

    /** Called after the configuration screen saved a widget. */
    fun configured(context: Context, widgetId: Int) {
        val app = context.applicationContext
        render(app, intArrayOf(widgetId), loading = setOf(widgetId))
        app.sendBroadcast(Intent(app, MitfahrtWidgetProvider::class.java).setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId)))
    }

    /** Application hook for widget settings, scheduled profiles, saved-route and favorite changes. */
    fun settingsChanged(context: Context) {
        val app = context.applicationContext
        val current = ids(app)
        if (current.isEmpty()) return
        render(app, current)
        refreshAsync(app, current, userInitiated = false, pending = null)
    }

    /** Privacy controls may clear cached public boards without deleting widget configuration. */
    fun clearCache(context: Context) { WidgetStore(context).clearSnapshots(); render(context) }

    /**
     * Optional hook for the ride service: re-renders "Aktuelle Fahrt" widgets from the
     * tracked ride without any network request. Throttled unless the ride itself changes.
     */
    fun trackingChanged(context: Context) {
        val app = context.applicationContext
        val state = runCatching { (app as MitfahrtApplication).store.state.value }.getOrNull()
        val key = state?.ride?.let { "${it.departure.key}/${it.boarded}/${it.targetStopIndex}/${state.running}/${state.lastRefreshFailed}" }
        val elapsed = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (key == lastTrackingKey && elapsed - lastTrackingRender < TRACKING_RENDER_GAP_MS) return
            if (key != lastTrackingKey) WidgetStore(app).clearActiveSnapshots()
            lastTrackingKey = key
            lastTrackingRender = elapsed
        }
        runCatching {
            val store = WidgetStore(app)
            val active = ids(app).filter { store.config(it)?.kind == WidgetKind.ACTIVE }
            if (active.isNotEmpty()) render(app, active.toIntArray())
        }
    }

    private fun scheduleRender(context: Context, now: Instant) {
        val store = WidgetStore(context)
        val active = runCatching { WidgetRepository(context).activeInput() }.getOrNull()
        val next = ids(context).toList().mapNotNull { id ->
            runCatching {
                val config = store.config(id) ?: return@runCatching null
                WidgetPresenter.present(config, store.snapshot(id), now,
                    active = if (config.kind == WidgetKind.ACTIVE) active else null).nextRenderAt
            }.getOrNull()
        }.filter { it.epochSecond - now.epochSecond <= MAX_RENDER_DELAY_SECONDS }.minOrNull()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(context, 0,
            Intent(context, MitfahrtWidgetProvider::class.java).setAction(ACTION_RENDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact and non-waking: a sleeping phone shows no widget, it renders on wake-up.
        if (next == null) alarms.cancel(intent) else alarms.set(AlarmManager.RTC, next.toEpochMilli(), intent)
    }

    internal fun cancelScheduled(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(PendingIntent.getBroadcast(context, 0,
            Intent(context, MitfahrtWidgetProvider::class.java).setAction(ACTION_RENDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}
