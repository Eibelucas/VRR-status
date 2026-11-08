package de.luca.mitfahrt.widgets

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.PlannedJourneyLoader
import de.luca.mitfahrt.data.Ride
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

    fun isOnline(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        // NEW hotspots may be captive portals, so INTERNET is enough; VALIDATED would hide them.
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /** Returns a new snapshot; on failure the previous data is kept and marked. */
    suspend fun fetch(config: WidgetConfig, previous: WidgetSnapshot, now: Instant): WidgetSnapshot = try {
        when (config.kind) {
            WidgetKind.STOP -> stop(config, now)
            WidgetKind.ROUTE -> route(config, previous, now)
            WidgetKind.ACTIVE -> active(previous, now)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        previous.copy(failure = classify(error), failedAt = now)
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
        // Reuses the app's checked route logic: only trips that really reach the destination.
        val result = loader.load(listOf(plan), now)
        val departures = result.departures[plan.id].orEmpty()
        if (result.failed > 0 && departures.isEmpty())
            return previous.copy(failure = FetchFailure.SOURCE, failedAt = now, targetMissing = false)
        return WidgetSnapshot(fetchedAt = now, departures = departures, partial = result.failed > 0)
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
