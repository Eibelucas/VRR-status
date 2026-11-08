package de.luca.mitfahrt.watch

import android.Manifest
import android.content.pm.PackageManager
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.shared.*
import de.luca.mitfahrt.tracking.RideService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.Instant

/** Only public saved stations/routes are mirrored. Browsing never creates a tracked ride. */
internal class WatchCompanionPublisher(private val app: MitfahrtApplication) {
    private val settings = AppSettingsStore(app)
    private val lock = Mutex()
    private var selected = ""
    private var choices: List<Departure> = emptyList()
    private var choicesAt = Instant.EPOCH
    private var message = ""
    private val planner by lazy { PlannedJourneyLoader(app.vrr::departures, app.vrr::ride) }
    private var tileKey = ""
    private var tileAttempt = Instant.EPOCH
    private var tile: WatchTile? = null

    suspend fun publish() {
        val config = settings.current()
        val tracking = app.store.state.value
        val ride = tracking.ride
        val entries = app.routePlans.current().routes.filter { it.enabled }.map {
            WatchEntry("route", it.id, it.name, "${shortName(it.origin.name)} → ${shortName(it.destination.name)}")
        } + app.savedStops.state.value.favorites.map { WatchEntry("stop", it.id, shortName(it.name), "Haltestelle") }
        val preferenceKeys = listOf("watch_start", "watch_detail", "watch_show_connection", "watch_actions", "watch_tile", "watch_tile_id",
            "alert_target", "quiet_enabled", "quiet_start", "quiet_end", "quiet_critical", "vibration_arrival", "vibration_exit",
            "vibration_walking", "vibration_delay", "vibration_cancelled", "watch_avoid_duplicates")
        val preferences = preferenceKeys.associateWith { config.string(it) } +
            mapOf("journey_key" to (ride?.departure?.key ?: ""), "stops_revision" to (ride?.let(::stopsRevision) ?: ""))
        val now = Instant.now()
        refreshTile(config, now)
        val library = WatchLibrary(now, entries, selected,
            choices.map { departure -> WatchDeparture(departure.key, departure.line, shortName(departure.destination),
                departure.liveEstimate(now) ?: departure.planned, departure.liveEstimate(now) != null, departure.cancelled) },
            ride?.let { active -> active.stops.mapIndexedNotNull { index, stop ->
                if (index > active.boardingIndex && !stop.cancelled) WatchTarget(index, shortName(stop.name)) else null
            } }.orEmpty(), preferences, message, tile, tracking.watchSnapshot(now))
        Wearable.getDataClient(app).putDataItem(PutDataRequest.create(WatchCompanion.LIBRARY)
            .setData(WatchCompanion.encode(library)).setUrgent()).await()
    }

    suspend fun command(command: WatchCommand): String = lock.withLock {
        try {
            when (command.action) {
                "browse" -> browse(command.id)
                "start" -> start(command)
                "board" -> withRide(command) { ride -> app.store.setRide(ride.copy(boarded = true), preserveStatus = true); "Einstieg bestätigt" }
                "arrival" -> withRide(command) { ride ->
                    val stop = if (ride.boarded) ride.exitTargetStop()
                        else ride.stops.getOrNull(ride.boardingIndex)
                    requireNotNull(stop) { "Bitte zuerst ein Ausstiegsziel wählen" }
                    app.arrivals.record(Stop(stopKey(stop.id), stop.name, stop.point))
                    "Eigene Ankunft festgehalten"
                }
                "stop" -> withRide(command) { RideService.stop(app); "Fahrt beendet" }
                "target" -> withRide(command) { ride ->
                    require(command.contextId == stopsRevision(ride)) { "Fahrtverlauf geändert. Bitte aktualisieren." }
                    val index = command.id.toIntOrNull() ?: error("Ungültiges Ziel")
                    app.store.setRide(ride.withExitTarget(index), preserveStatus = true); "Ausstiegsziel gespeichert"
                }
                else -> "Aktualisiert"
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { error.message?.take(120) ?: "Am Handy erneut versuchen" }
            .also { message = it }
    }

    private suspend fun browse(id: String): String {
        choices = emptyList(); selected = id; choicesAt = Instant.EPOCH
        val now = Instant.now()
        val route = app.routePlans.current().routes.firstOrNull { "route/${it.id}" == id && it.enabled }
        choices = if (route != null) planner
            .load(listOf(route), now).departures[route.id].orEmpty()
        else {
            val stop = app.savedStops.state.value.favorites.firstOrNull { "stop/${it.id}" == id }
                ?: error("Auswahl nicht mehr gespeichert")
            app.vrr.departures(stop).filter { !it.cancelled && it.effective >= now }.take(8)
        }
        choicesAt = Instant.now()
        return if (choices.isEmpty()) "Keine passende Abfahrt gefunden" else "Abfahrt auswählen"
    }

    private suspend fun start(command: WatchCommand): String {
        require(app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33) { "Mitfahrt am Handy einrichten" }
        val previous = app.store.state.value.ride?.departure?.key.orEmpty()
        require(previous == command.journey) { "Aktuelle Fahrt hat sich geändert" }
        require(WatchCommandPolicy.startAllowed(command, previous, selected, choicesAt, Instant.now(),
            choices.filterNot { it.cancelled }.map { it.key }.toSet())) { "Abfahrten bitte erneut auswählen" }
        val departure = choices.firstOrNull { it.key == command.id && !it.cancelled } ?: error("Abfahrt nicht mehr verfügbar")
        val started = Instant.now()
        var ride = app.vrr.ride(departure).copy(boarded = false, trackingStartedAt = started)
        require(!ride.departure.cancelled) { "Fahrt fällt aus" }
        val route = app.routePlans.current().routes.firstOrNull { "route/${it.id}" == selected }
        if (route != null) {
            val index = SchoolTrips.targetIndex(ride, route.destination.id) ?: error("Ziel wird nicht bedient")
            ride = ride.withExitTarget(index)
        }
        require(app.store.state.value.ride?.departure?.key.orEmpty() == previous) { "Aktuelle Fahrt hat sich geändert" }
        RideService.start(app, ride)
        return "Fahrt wird verfolgt"
    }

    private fun withRide(command: WatchCommand, block: (Ride) -> String): String {
        val ride = app.store.state.value.ride ?: error("Keine aktive Fahrt")
        require(WatchCommandPolicy.sameJourney(command, ride.departure.key)) { "Fahrt bitte aktualisieren" }
        return block(ride)
    }

    private fun stopsRevision(ride: Ride): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(ride.stops.joinToString("\n") { "${it.id}\t${it.planned}\t${it.cancelled}" }.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun refreshTile(config: AppSettings, now: Instant) {
        val kind = config.string("watch_tile", "active")
        if (kind == "active") { tile = null; tileKey = ""; return }
        val id = config.string("watch_tile_id")
        val route = if (kind == "route") app.routePlans.current().routes.firstOrNull { it.enabled && (id.isEmpty() || it.id == id) } else null
        val stop = if (kind == "stop") app.savedStops.state.value.favorites.firstOrNull { id.isEmpty() || it.id == id } else null
        val resolved = route?.id ?: stop?.id ?: run { tile = null; tileKey = ""; return }
        val key = "$kind/$resolved"
        if (tileKey != key) { tile = null; tileKey = key; tileAttempt = Instant.EPOCH }
        if (now.epochSecond - tileAttempt.epochSecond in 0..29) return
        tileAttempt = now
        try {
            val departure = if (route != null) planner.load(listOf(route), now).departures[route.id]?.firstOrNull()
                else app.vrr.departures(stop!!).firstOrNull { !it.cancelled && it.effective >= Instant.now() }
            val fetched = Instant.now()
            tile = WatchTile(kind, resolved, route?.name ?: shortName(stop!!.name), departure?.line.orEmpty(),
                departure?.destination?.let(::shortName).orEmpty(), departure?.liveEstimate(fetched) ?: departure?.planned,
                departure?.liveEstimate(fetched) != null,
                minOf(fetched, departure?.observedAt ?: fetched))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Keep the last source timestamp so failed refreshes visibly expire. */ }
    }
}
