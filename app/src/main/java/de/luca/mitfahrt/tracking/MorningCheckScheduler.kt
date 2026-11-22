package de.luca.mitfahrt.tracking

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import de.luca.mitfahrt.MainActivity
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.R
import de.luca.mitfahrt.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules a single inexact pre-departure check inside [RoutePlan.effectiveWindows]
 * for routes with [RoutePlan.morningCheck] enabled.
 * Respects [SchoolCalendarStore], [RouteDayOverridesStore], and quiet hours,
 * and never requires SCHEDULE_EXACT_ALARM.
 */
object MorningCheckScheduler {
    const val ACTION_CHECK = "de.luca.mitfahrt.MORNING_CHECK"
    const val EXTRA_ROUTE_ID = "route_id"
    const val NOTIFICATION_ID = 9
    private const val CHANNEL = "morning_check"
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    fun scheduleNext(context: Context, now: Instant = Instant.now()) {
        val app = context.applicationContext as? MitfahrtApplication ?: return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val plans = app.routePlans.current().routes.filter { it.enabled && it.morningCheck && !it.manualOnly }
        val target = nextTrigger(plans, now)
        val pending = pendingIntent(context, target?.first?.id.orEmpty())
        if (target == null) {
            manager.cancel(pending)
            return
        }
        // Inexact wakeup works cleanly on sideloaded builds without SCHEDULE_EXACT_ALARM permission.
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.second.toEpochMilli(), pending)
        }
    }

    /** Computes the earliest upcoming pre-departure trigger (window start minus walk + buffer + 10 min) within 7 days. */
    fun nextTrigger(plans: List<RoutePlan>, now: Instant): Pair<RoutePlan, Instant>? {
        val nowLocal = now.atZone(zone)
        var best: Pair<RoutePlan, Instant>? = null
        for (plan in plans) {
            if (!plan.enabled || !plan.morningCheck || plan.manualOnly) continue
            val leadMinutes = (plan.walkingMinutes.coerceIn(0, 60) + plan.bufferMinutes.coerceIn(0, 30) + 10).toLong()
            for (dayOffset in 0L..7L) {
                val date = nowLocal.toLocalDate().plusDays(dayOffset)
                if (date.toString() in plan.excludedDates) continue
                val dow = date.dayOfWeek.value
                for (window in plan.effectiveWindows()) {
                    if (dow !in window.days || !window.valid()) continue
                    if (window.startMinute == window.endMinute) continue
                    val startAt = ZonedDateTime.of(
                        date.year, date.monthValue, date.dayOfMonth,
                        window.startMinute / 60, window.startMinute % 60, 0, 0, zone
                    ).toInstant()
                    val triggerAt = startAt.minusSeconds(leadMinutes * 60L)
                    if (triggerAt.isAfter(now.plusSeconds(30))) {
                        if (best == null || triggerAt.isBefore(best.second)) {
                            best = plan to triggerAt
                        }
                    }
                }
            }
        }
        return best
    }

    private fun pendingIntent(context: Context, routeId: String): PendingIntent {
        val intent = Intent(context, MorningCheckReceiver::class.java)
            .setAction(ACTION_CHECK)
            .putExtra(EXTRA_ROUTE_ID, routeId)
        return PendingIntent.getBroadcast(
            context,
            901,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    internal fun postNotification(
        context: Context,
        plan: RoutePlan,
        departure: Departure,
        guidance: LeaveGuidance?,
        alternateName: String?,
        alternateRouteId: String?,
        disrupted: Boolean
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Morgen-Losgeh-Check", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Vorab-Prüfung geplanter Stammrouten vor dem Losgehen"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val openIntent = PendingIntent.getActivity(
            context,
            902,
            Intent(context, MainActivity::class.java).apply {
                putExtra("widget_kind", "route")
                putExtra("widget_id", plan.id)
                putExtra("widget_action", "departures")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Foreground-service start: a plain getService would be rejected while the app is in the background.
        val startRideIntent = PendingIntent.getForegroundService(
            context,
            903,
            Intent(context, RideService::class.java).apply {
                action = RideService.START_PLANNED_ROUTE
                putExtra(RideService.EXTRA_PLAN_ID, plan.id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val timeText = clockTime(departure.effective)
        val delay = departure.delayMinutes
        val delayLabel = when {
            departure.cancelled -> "Fällt aus"
            delay > 0 -> "+$delay Min"
            delay < 0 -> "$delay Min"
            else -> "pünktlich"
        }
        val leaveChip = guidance?.chipLabel()?.takeIf { it.isNotBlank() }
        val title = when {
            departure.cancelled -> "${plan.name}: Linie ${departure.line} fällt aus"
            leaveChip != null -> "${plan.name}: $leaveChip"
            else -> "${plan.name}: Linie ${departure.line} um $timeText"
        }
        val body = buildString {
            append("Linie ${departure.line} ab ${shortName(departure.stopName)} um $timeText ($delayLabel)")
            if (departure.platform.isNotBlank()) append(" · Steig ${departure.platform}")
            if (disrupted && !alternateName.isNullOrBlank()) append("\nAusweichroute: $alternateName")
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!disrupted)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Color.rgb(180, 0, 85))
            .addAction(Notification.Action.Builder(null, "Jetzt begleiten", startRideIntent).build())

        if (disrupted && !alternateRouteId.isNullOrBlank() && !alternateName.isNullOrBlank()) {
            val altIntent = PendingIntent.getForegroundService(
                context,
                904,
                Intent(context, RideService::class.java).apply {
                    action = RideService.START_PLANNED_ROUTE
                    putExtra(RideService.EXTRA_PLAN_ID, alternateRouteId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(Notification.Action.Builder(null, alternateName.take(20), altIntent).build())
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}

class MorningCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching { MorningCheckScheduler.scheduleNext(context) }
            return
        }
        if (intent?.action != MorningCheckScheduler.ACTION_CHECK) return
        val app = context.applicationContext as? MitfahrtApplication ?: return
        val routeId = intent.getStringExtra(MorningCheckScheduler.EXTRA_ROUTE_ID).orEmpty()
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                val settings = app.settingsStore.current()
                val runtime = RuntimeAlertOptions.from(settings)
                val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
                val plans = app.routePlans.current().routes
                val plan = plans.firstOrNull { it.id == routeId && it.enabled && it.morningCheck } ?: return@launch

                // Honor school calendar & day overrides.
                if (plan.schoolOnly) {
                    val schoolPaused = settings.bool("routes_school_paused", false) || app.schoolCalendar.status(today).free
                    if (schoolPaused) return@launch
                }
                if (RouteDayOverrides.decision(app.dayOverrides.current(), plan.id, now, plan.effectiveWindows()) == false) return@launch
                if (today.toString() in plan.excludedDates) return@launch

                // Skip if a ride is already actively tracked.
                if (app.store.state.value.running && app.store.state.value.ride != null) return@launch

                val loader = PlannedJourneyLoader(app.vrr::departures, app.vrr::ride)
                val result = loader.load(listOf(plan), now)
                val departure = result.departures[plan.id]?.firstOrNull() ?: return@launch

                val threshold = settings.int("delay_threshold", 5).coerceIn(1, 60)
                val disrupted = departure.cancelled || departure.delayMinutes >= threshold
                val kind = if (departure.cancelled) "cancelled" else if (disrupted) "delay" else "walking"
                if (!disrupted && !runtime.allowed(kind, now)) return@launch

                val guidance = LeaveCountdown.compute(
                    boardingAt = departure.liveEstimate(now) ?: departure.planned,
                    cancelled = departure.cancelled,
                    now = now,
                    plannedWalkMinutes = plan.walkingMinutes,
                    bufferMinutes = plan.bufferMinutes
                )
                val alt = plan.alternateRouteId?.let { altId -> plans.firstOrNull { it.id == altId && it.enabled } }
                MorningCheckScheduler.postNotification(
                    context = app,
                    plan = plan,
                    departure = departure,
                    guidance = guidance,
                    alternateName = alt?.name,
                    alternateRouteId = alt?.id,
                    disrupted = disrupted
                )
                if (disrupted) {
                    de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(app, "morning/${plan.id}/${departure.key}", kind)
                    app.watch.arrivalAlert("morning/${plan.id}/${departure.key}", departure.effective)
                }
            } catch (_: Exception) {
                // Network/offline hiccup: fail quietly without crashing receiver.
            } finally {
                MorningCheckScheduler.scheduleNext(app, Instant.now().plusSeconds(60))
                pendingResult.finish()
            }
        }
    }
}
