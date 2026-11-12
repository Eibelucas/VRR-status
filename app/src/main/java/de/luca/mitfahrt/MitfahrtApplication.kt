package de.luca.mitfahrt

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import de.luca.mitfahrt.widgets.MitfahrtWidgets
import de.luca.mitfahrt.data.RideStore
import de.luca.mitfahrt.data.ArrivalStore
import de.luca.mitfahrt.data.VrrClient
import de.luca.mitfahrt.reports.ReportFiles
import de.luca.mitfahrt.watch.WatchPublisher
import de.luca.mitfahrt.data.RouteHistoryStore

class MitfahrtApplication : Application() {
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings by lazy { de.luca.mitfahrt.data.AppSettingsStore(this) }
    val archive by lazy { de.luca.mitfahrt.data.JourneyArchiveStore(this).apply {
        setEnabled(settings.current().bool("history_enabled", true))
        setRetentionDays(settings.current().int("history_days", 0))
    } }
    val dayOverrides by lazy { de.luca.mitfahrt.data.RouteDayOverridesStore(this) }
    val schoolCalendar by lazy { de.luca.mitfahrt.data.SchoolCalendarStore(this).apply {
        setEnabled(settings.current().bool("calendar_enabled", true))
    } }
    val store by lazy { RideStore(this, onRideUpdated = archive::update) }
    val vrr by lazy { VrrClient() }
    val newVehicles by lazy { de.luca.mitfahrt.data.NewVehicleClient() }
    val liveBusTracker by lazy { de.luca.mitfahrt.data.LiveBusTracker(newVehicles::fetch, newVehicles::vehicle) }
    /** Verified public line geometry; cached in the app's cache directory, no personal data. */
    val routeShapes by lazy { de.luca.mitfahrt.data.RouteShapeRepository(vrr::tripPath,
        de.luca.mitfahrt.data.RouteShapeCache(java.io.File(cacheDir, "route_shapes.json"))) }
    val reports by lazy { ReportFiles(this) }
    /** One report per accompanied connection; stays on the device until exported by hand. */
    val journeyReports by lazy { de.luca.mitfahrt.reports.JourneyReportStore(this) }
    val arrivals by lazy { ArrivalStore(this) }
    val watch by lazy { WatchPublisher(this, store) }
    val routePlans by lazy { de.luca.mitfahrt.data.RoutePlansStore(this) }
    val routes by lazy { RouteHistoryStore(this, store.lastRide.value) }
    val savedStops by lazy { de.luca.mitfahrt.data.SavedStopsStore(this) }
    val schoolTrips by lazy { de.luca.mitfahrt.data.SchoolTripStore(this) }
    /** Saved destinations and the optional search history of the journey planner; never own coordinates. */
    val tripPlaces by lazy { de.luca.mitfahrt.data.TripPlacesStore(this) }
    /** Journey with several legs that is accompanied leg by leg. */
    val companion by lazy { de.luca.mitfahrt.data.JourneyCompanionStore(this) }

    override fun onCreate() {
        super.onCreate()
        runCatching { watch.start() }
        runCatching { de.luca.mitfahrt.updates.UpdateScheduler.schedule(this) }
        widgetScope.launch { store.state.collect { runCatching { MitfahrtWidgets.trackingChanged(this@MitfahrtApplication) } } }
        // A leg counts as started only once its ride is really tracked, never by timetable time.
        widgetScope.launch { store.state.collect { state -> state.ride?.departure?.key?.let { runCatching { companion.markSeen(it) } } } }
        widgetScope.launch {
            kotlinx.coroutines.flow.combine(store.state, companion.state, arrivals.marks) { state, journey, marks -> Triple(state, journey, marks) }
                .collect { (state, journey, marks) -> runCatching {
                    val live = state.liveBus
                    val vehicle = live.vehicle?.id?.takeIf { live.journeyKey == state.ride?.departure?.key &&
                        (live.confirmed || live.selectedVehicleId != null || live.auto.holdsVehicle) }
                    journeyReports.sync(de.luca.mitfahrt.reports.ReportSnapshot(state.ride, journey, state.lastRefreshFailed, vehicle, marks),
                        settings.current().bool("report_auto", true))
                } }
        }
        widgetScope.launch { runCatching { journeyReports.prune() } }
        widgetScope.launch { settings.state.drop(1).collect { runCatching { MitfahrtWidgets.settingsChanged(this@MitfahrtApplication) } } }
        widgetScope.launch { routePlans.state.drop(1).collect { runCatching { MitfahrtWidgets.settingsChanged(this@MitfahrtApplication) } } }
        widgetScope.launch { savedStops.state.drop(1).collect { runCatching { MitfahrtWidgets.settingsChanged(this@MitfahrtApplication) } } }
        widgetScope.launch { runCatching { MitfahrtWidgets.settingsChanged(this@MitfahrtApplication) } }
    }
}
