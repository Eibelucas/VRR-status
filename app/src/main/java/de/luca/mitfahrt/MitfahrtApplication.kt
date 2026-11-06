package de.luca.mitfahrt

import android.app.Application
import de.luca.mitfahrt.data.RideStore
import de.luca.mitfahrt.data.ArrivalStore
import de.luca.mitfahrt.data.VrrClient
import de.luca.mitfahrt.reports.ReportFiles

class MitfahrtApplication : Application() {
    val store by lazy { RideStore(this) }
    val vrr by lazy { VrrClient() }
    val reports by lazy { ReportFiles(this) }
    val arrivals by lazy { ArrivalStore(this) }
}
