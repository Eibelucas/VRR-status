package de.luca.mitfahrt

import android.app.Application
import de.luca.mitfahrt.data.RideStore
import de.luca.mitfahrt.data.ArrivalStore
import de.luca.mitfahrt.data.VrrClient
import de.luca.mitfahrt.reports.ReportFiles
import de.luca.mitfahrt.watch.WatchPublisher
import de.luca.mitfahrt.data.RouteHistoryStore

class MitfahrtApplication : Application() {
    val store by lazy { RideStore(this) }
    val vrr by lazy { VrrClient() }
    val newVehicles by lazy { de.luca.mitfahrt.data.NewVehicleClient() }
    val liveBusTracker by lazy { de.luca.mitfahrt.data.LiveBusTracker(newVehicles::fetch, newVehicles::vehicle) }
    val reports by lazy { ReportFiles(this) }
    val arrivals by lazy { ArrivalStore(this) }
    val watch by lazy { WatchPublisher(this, store) }
    val routePlans by lazy { de.luca.mitfahrt.data.RoutePlansStore(this) }
    val routes by lazy { RouteHistoryStore(this, store.lastRide.value) }
    val savedStops by lazy { de.luca.mitfahrt.data.SavedStopsStore(this) }
    val schoolTrips by lazy { de.luca.mitfahrt.data.SchoolTripStore(this) }

    override fun onCreate() {
        super.onCreate()
        runCatching { watch.start() }
    }
}
