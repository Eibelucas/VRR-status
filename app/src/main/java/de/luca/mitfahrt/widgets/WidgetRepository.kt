package de.luca.mitfahrt.widgets

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.CustomizationPolicy
import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.PlannedJourneyLoader
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.RouteMatch
import de.luca.mitfahrt.data.RoutePlan
import de.luca.mitfahrt.data.Stop
import de.luca.mitfahrt.data.VrrClient
import de.luca.mitfahrt.data.stopKey
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Instant

/** Reads the app's existing stores and VRR queries. It never starts, saves or changes a ride. */
internal class WidgetRepository(private val context: Context) {
    private val app = context.applicationContext as MitfahrtApplication
    private val vrr: VrrClient get() = app.vrr
    // One loader per refresh batch shares its short cache between widgets of the same route.
    private val loader = PlannedJourneyLoader(vrr::departures, vrr::ride)
    private val boards = mutableMapOf<String, List<Departure>>()

    fun activeInput(): ActiveRideInput {
        val state = app.store.state.value
        return ActiveRideInput(state.ride, state.running, state.lastRefreshFailed)
    }

    fun route(id: String): RoutePlan? = app.routePlans.current().routes.firstOrNull { it.id == id }

    /** Picks the currently active route respecting school calendar, day overrides, and optional 30-min flip. */
    fun selectSmartRoute(now: Instant, flippedRouteId: String? = null): RoutePlan? {
        val routes = app.routePlans.current().routes
        if (routes.isEmpty()) return null
        if (!flippedRouteId.isNullOrBlank()) {
            routes.firstOrNull { it.id == flippedRouteId && it.enabled }?.let { return it }
        }
        val settings = app.settingsStore.current()
        val overrides = app.dayOverrides.current()
        val calendar = app.schoolCalendar.state.value
        return routes.firstOrNull { plan ->
            CustomizationPolicy.active(plan, now, emptyList(), settings, overrides, calendar)
        }
    }

    /** Finds the next route to flip to when tapping the SMART_ROUTE header (alternateRouteId or next enabled route). */
    fun nextSmartFlipRoute(now: Instant, currentFlippedId: String?): RoutePlan? {
        val routes = app.routePlans.current().routes.filter { it.enabled }
        if (routes.size < 2) return null
        val current = selectSmartRoute(now, currentFlippedId)
        val explicitAlternate = current?.alternateRouteId?.let { altId -> routes.firstOrNull { it.id == altId } }
        if (explicitAlternate != null && explicitAlternate.id != current.id) return explicitAlternate
        val idx = routes.indexOfFirst { it.id == current?.id }
        return routes[(idx + 1) % routes.size]
    }

    fun isOnline(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        // NEW hotspots may be captive portals, so INTERNET is enough; VALIDATED would hide them.
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** Returns a new snapshot; on failure falls back to local OfflineBoards when available, or keeps previous data. */
    suspend fun fetch(config: WidgetConfig, previous: WidgetSnapshot, now: Instant, flippedRouteId: String? = null): WidgetSnapshot = try {
        when (config.kind) {
            WidgetKind.STOP -> stop(config, now)
            WidgetKind.ROUTE -> route(config, previous, now)
            WidgetKind.SMART_ROUTE -> smartRoute(previous, now, flippedRouteId)
            WidgetKind.ACTIVE -> active(previous, now)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val failure = classify(error)
        offlineFallback(config, now, failure, flippedRouteId) ?: previous.copy(failure = failure, failedAt = now)
    }

    /** Explicit offline board fallback callable even when [isOnline] is already known to be false. */
    fun offlineFallback(config: WidgetConfig, now: Instant, failure: FetchFailure = FetchFailure.OFFLINE, flippedRouteId: String? = null): WidgetSnapshot? {
        return when (config.kind) {
            WidgetKind.STOP -> {
                val board = app.offlineBoards.getById(config.targetId, now.atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()) ?: return null
                val deps = board.asDepartures(Stop(config.targetId, config.targetName), now).take(12)
                if (deps.isEmpty()) null else WidgetSnapshot(
                    fetchedAt = board.savedAt, departures = deps, failure = failure, failedAt = now, offlineFallback = true
                )
            }
            WidgetKind.ROUTE -> {
                val plan = route(config.targetId) ?: return null
                offlineForPlan(plan, now, failure)
            }
            WidgetKind.SMART_ROUTE -> {
                val plan = selectSmartRoute(now, flippedRouteId) ?: return WidgetSnapshot(
                    fetchedAt = now, smartEmptyReason = "Heute keine geplante Fahrt"
                )
                offlineForPlan(plan, now, failure)
            }
            WidgetKind.ACTIVE -> null
        }
    }

    private fun offlineForPlan(plan: RoutePlan, now: Instant, failure: FetchFailure): WidgetSnapshot? {
        val board = app.offlineBoards.getById(plan.origin.id, now.atZone(java.time.ZoneId.of("Europe/Berlin")).toLocalDate()) ?: return null
        val allowed = (plan.allowedLines + plan.line).map { it.trim() }.filter { it.isNotBlank() }
        val deps = board.asDepartures(plan.origin, now).filter { dep ->
            plan.match == RouteMatch.STOP || allowed.isEmpty() || allowed.any { it.equals(dep.line, true) }
        }.take(12)
        if (deps.isEmpty()) return null
        return WidgetSnapshot(
            fetchedAt = board.savedAt,
            departures = deps,
            failure = failure,
            failedAt = now,
            offlineFallback = true,
            resolvedRouteId = plan.id,
            resolvedRouteName = plan.name,
            resolvedWalkMinutes = plan.walkingMinutes,
            resolvedBufferMinutes = plan.bufferMinutes
        )
    }

    private suspend fun stop(config: WidgetConfig, now: Instant): WidgetSnapshot {
        val key = stopKey(config.targetId)
        val departures = boards[key] ?: vrr.departures(Stop(config.targetId, config.targetName)).also { boards[key] = it }
        return WidgetSnapshot(
            fetchedAt = now,
            departures = departures.filter {
                (if (it.cancelled) it.planned else it.effective).epochSecond >= now.epochSecond - 60
            }.take(12)
        )
    }

    private suspend fun route(config: WidgetConfig, previous: WidgetSnapshot, now: Instant): WidgetSnapshot {
        val plan = route(config.targetId) ?: return WidgetSnapshot(targetMissing = true)
        return fetchPlan(plan, previous, now)
    }

    private suspend fun smartRoute(previous: WidgetSnapshot, now: Instant, flippedRouteId: String?): WidgetSnapshot {
        val plan = selectSmartRoute(now, flippedRouteId) ?: return WidgetSnapshot(
            fetchedAt = now,
            departures = emptyList(),
            smartEmptyReason = "Heute keine geplante Fahrt"
        )
        return fetchPlan(plan, previous, now)
    }

    private suspend fun fetchPlan(plan: RoutePlan, previous: WidgetSnapshot, now: Instant): WidgetSnapshot {
        // Reuses the app's checked route logic: only trips that really reach the destination.
        val result = loader.load(listOf(plan), now)
        val departures = result.departures[plan.id].orEmpty()
        if (result.failed > 0 && departures.isEmpty()) {
            return offlineForPlan(plan, now, FetchFailure.SOURCE)
                ?: previous.copy(
                    failure = FetchFailure.SOURCE, failedAt = now, targetMissing = false,
                    resolvedRouteId = plan.id, resolvedRouteName = plan.name,
                    resolvedWalkMinutes = plan.walkingMinutes, resolvedBufferMinutes = plan.bufferMinutes
                )
        }
        return WidgetSnapshot(
            fetchedAt = now,
            departures = departures,
            partial = result.failed > 0,
            resolvedRouteId = plan.id,
            resolvedRouteName = plan.name,
            resolvedWalkMinutes = plan.walkingMinutes,
            resolvedBufferMinutes = plan.bufferMinutes
        )
    }

    private suspend fun active(previous: WidgetSnapshot, now: Instant): WidgetSnapshot {
        val input = activeInput()
        val tracked = input.ride ?: return WidgetSnapshot(fetchedAt = now)
        // While the ride service refreshes the trip, the widget adds no extra requests.
        if (tracked.demo || (input.tracking && !input.refreshFailed && !tracked.isStale(now)))
            return WidgetSnapshot(fetchedAt = tracked.fetchedAt)
        val fetched: Ride = vrr.ride(tracked.departure)
        val display = mergeDisplayRide(tracked, fetched)
            ?: return previous.copy(failure = FetchFailure.SOURCE, failedAt = now)
        return WidgetSnapshot(fetchedAt = display.fetchedAt, ride = display)
    }

    private fun classify(error: Exception): FetchFailure = when (error) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> FetchFailure.OFFLINE
        is IOException -> if (isOnline()) FetchFailure.SOURCE else FetchFailure.OFFLINE
        else -> FetchFailure.SOURCE
    }
}
