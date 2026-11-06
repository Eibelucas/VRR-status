package de.luca.mitfahrt

import android.app.Application
import de.luca.mitfahrt.data.RideStore
import de.luca.mitfahrt.data.ArrivalStore
import de.luca.mitfahrt.data.VrrClient
import de.luca.mitfahrt.reports.ReportFiles
import de.luca.mitfahrt.watch.WatchPublisher

class MitfahrtApplication : Application() {
    val store by lazy { RideStore(this) }
    val vrr by lazy { VrrClient() }
    val reports by lazy { ReportFiles(this) }
    val arrivals by lazy { ArrivalStore(this) }
    val watch by lazy { WatchPublisher(this, store) }

    override fun onCreate() {
        super.onCreate()
        runCatching { watch.start() }
    }
}
