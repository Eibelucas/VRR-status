package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Who a piece of information comes from. The three groups are never mixed in the report. */
enum class ReportSource(val label: String) {
    OPERATOR("VRR-Angabe"),
    GPS("GPS-Erkennung der App"),
    OWN("Eigene Angabe"),
    APP("In der App erfasst")
}

enum class ReportEventType {
    TRACKING_STARTED, ARRIVED_AT_STOP, VEHICLE_ASSIGNED, BOARDED, EXITED, WAIT_ENDED, RIDE_ENDED,
    CANCELLED, NOTICE, TRANSFER_RISK, DATA_GAP, OBSERVATION, BOARDING_CORRECTED
}

enum class ReportState { RUNNING, FINISHED, ABORTED }

/** One dated statement. [at] is when it happened, [recordedAt] when the app stored it. */
data class ReportEvent(
    val id: String, val type: ReportEventType, val source: ReportSource, val at: Instant,
    val text: String, val legKey: String? = null, val recordedAt: Instant = at
)

/**
 * Times of one stop inside the accompanied connection. A forecast is an operator estimate with
 * the time it was fetched; it is never an actual arrival. Missing values stay null.
 */
data class ReportStop(
    val id: String, val name: String, val platform: String = "",
    val plannedArrival: Instant? = null, val plannedDeparture: Instant? = null,
    val forecastArrival: Instant? = null, val forecastDeparture: Instant? = null,
    val forecastFetchedAt: Instant? = null, val cancelled: Boolean = false
) {
    val hasForecast: Boolean get() = forecastArrival != null || forecastDeparture != null
}

data class ReportLeg(
    /** Ride key of the vehicle, or "leg/<index>" for a planned leg that was not tracked. */
    val key: String, val walk: Boolean = false,
    val line: String = "", val direction: String = "", val lineId: String = "", val tripCode: String = "",
    val operator: String = "", val stops: List<ReportStop> = emptyList(),
    val cancelled: Boolean = false, val notices: List<String> = emptyList(),
    /** True once the app really followed this vehicle; planned legs keep search data only. */
    val tracked: Boolean = false, val fetchedAt: Instant? = null,
    val walkSeconds: Long = 0, val vehicleId: String? = null
) {
    val origin: ReportStop? get() = stops.firstOrNull()
    val destination: ReportStop? get() = stops.lastOrNull()
}

/** A PDF that was written once. Its content never changes after [at]. */
data class ReportExport(val number: Int, val at: Instant, val preliminary: Boolean, val fileName: String)

/**
 * Version 2: one report for a whole accompanied connection (all vehicles and transfers).
 * Version 1 is the single-stop [DelayEvidence], which stays readable next to it.
 */
data class JourneyReport(
    val id: String,
    /** Identifies the accompaniment; updates and app restarts find the same report through it. */
    val sessionKey: String,
    val createdAt: Instant, val updatedAt: Instant,
    val state: ReportState = ReportState.RUNNING,
    val originName: String, val destinationName: String,
    val legs: List<ReportLeg> = emptyList(), val events: List<ReportEvent> = emptyList(),
    val waitFrom: Instant? = null, val waitUntil: Instant? = null,
    val exports: List<ReportExport> = emptyList(),
    /** Built later from the journey archive; nothing was observed while it ran. */
    val fromArchive: Boolean = false
) {
    val transit: List<ReportLeg> get() = legs.filterNot { it.walk }
    val serviceDay: Instant get() = transit.firstNotNullOfOrNull { it.origin?.plannedDeparture } ?: createdAt
    val title: String get() = "${shortName(originName)} → ${shortName(destinationName)}"
    val lines: String get() = transit.map { it.line }.filter { it.isNotBlank() }.joinToString(" · ")
    fun event(type: ReportEventType): ReportEvent? = events.lastOrNull { it.type == type }
    fun events(type: ReportEventType): List<ReportEvent> = events.filter { it.type == type }

    fun encode(): String = JSONObject().apply {
        put("version", VERSION); put("id", id); put("sessionKey", sessionKey)
        put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString()); put("state", state.name)
        put("originName", originName); put("destinationName", destinationName)
        put("waitFrom", waitFrom?.toString()); put("waitUntil", waitUntil?.toString()); put("fromArchive", fromArchive)
        put("legs", JSONArray(legs.map { leg -> JSONObject().apply {
            put("key", leg.key); put("walk", leg.walk); put("line", leg.line); put("direction", leg.direction)
            put("lineId", leg.lineId); put("tripCode", leg.tripCode); put("operator", leg.operator)
            put("cancelled", leg.cancelled); put("notices", JSONArray(leg.notices)); put("tracked", leg.tracked)
            put("fetchedAt", leg.fetchedAt?.toString()); put("walkSeconds", leg.walkSeconds); put("vehicleId", leg.vehicleId)
            put("stops", JSONArray(leg.stops.map { stop -> JSONObject().apply {
                put("id", stop.id); put("name", stop.name); put("platform", stop.platform)
                put("pa", stop.plannedArrival?.toString()); put("pd", stop.plannedDeparture?.toString())
                put("fa", stop.forecastArrival?.toString()); put("fd", stop.forecastDeparture?.toString())
                put("ff", stop.forecastFetchedAt?.toString()); put("cancelled", stop.cancelled)
            } }))
        } }))
        put("events", JSONArray(events.map { event -> JSONObject().apply {
            put("id", event.id); put("type", event.type.name); put("source", event.source.name)
            put("at", event.at.toString()); put("text", event.text); put("legKey", event.legKey)
            put("recordedAt", event.recordedAt.toString())
        } }))
        put("exports", JSONArray(exports.map { export -> JSONObject().apply {
            put("number", export.number); put("at", export.at.toString())
            put("preliminary", export.preliminary); put("fileName", export.fileName)
        } }))
    }.toString()

    companion object {
        const val VERSION = 2
        const val MAX_EVENTS = 120
        private val berlin = ZoneId.of("Europe/Berlin")
        fun day(time: Instant): String = time.atZone(berlin).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        fun clock(time: Instant?): String? = time?.atZone(berlin)?.format(DateTimeFormatter.ofPattern("HH:mm"))
        fun clockSeconds(time: Instant): String = time.atZone(berlin).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        /** Unknown enum values from a newer app version are skipped instead of failing the report. */
        fun decode(value: String): JourneyReport {
            val obj = JSONObject(value)
            require(obj.optInt("version") == VERSION) { "Unbekanntes Berichtsformat." }
            val legs = obj.getJSONArray("legs").objects().map { leg ->
                ReportLeg(leg.str("key"), leg.optBoolean("walk"), leg.str("line"), leg.str("direction"), leg.str("lineId"),
                    leg.str("tripCode"), leg.str("operator"),
                    leg.optJSONArray("stops")?.objects().orEmpty().map { stop ->
                        ReportStop(stop.str("id"), stop.str("name"), stop.str("platform"), stop.instant("pa"), stop.instant("pd"),
                            stop.instant("fa"), stop.instant("fd"), stop.instant("ff"), stop.optBoolean("cancelled"))
                    }, leg.optBoolean("cancelled"),
                    leg.optJSONArray("notices")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    leg.optBoolean("tracked"), leg.instant("fetchedAt"), leg.optLong("walkSeconds", 0),
                    leg.str("vehicleId").ifBlank { null })
            }
            val events = obj.optJSONArray("events")?.objects().orEmpty().mapNotNull { event -> runCatching {
                val at = Instant.parse(event.getString("at"))
                ReportEvent(event.getString("id"), ReportEventType.valueOf(event.getString("type")),
                    ReportSource.valueOf(event.getString("source")), at, event.str("text"),
                    event.str("legKey").ifBlank { null }, event.instant("recordedAt") ?: at)
            }.getOrNull() }
            val exports = obj.optJSONArray("exports")?.objects().orEmpty().mapNotNull { export -> runCatching {
                ReportExport(export.getInt("number"), Instant.parse(export.getString("at")),
                    export.optBoolean("preliminary"), export.getString("fileName"))
            }.getOrNull() }
            return JourneyReport(obj.getString("id"), obj.getString("sessionKey"), Instant.parse(obj.getString("createdAt")),
                Instant.parse(obj.getString("updatedAt")),
                runCatching { ReportState.valueOf(obj.getString("state")) }.getOrDefault(ReportState.FINISHED),
                obj.str("originName"), obj.str("destinationName"), legs, events,
                obj.instant("waitFrom"), obj.instant("waitUntil"), exports, obj.optBoolean("fromArchive"))
        }
    }
}

/** What the app knows about the accompaniment at one moment. Contains no phone positions. */
data class ReportSnapshot(
    val ride: Ride?, val companion: JourneyCompanion? = null,
    val refreshFailed: Boolean = false, val vehicleId: String? = null,
    val arrivals: List<ArrivalMark> = emptyList(),
    val transfer: CompanionTransfer? = null,
    val transferObservedAt: Instant? = null
) {
    companion object {
        fun capture(state: TrackingState, journey: JourneyCompanion?, next: Ride?, arrivals: List<ArrivalMark>, now: Instant): ReportSnapshot {
            val live = state.liveBus
            val vehicle = live.vehicle?.takeIf { live.journeyKey == state.ride?.departure?.key && it.isFresh(now) &&
                (live.confirmed || live.selectedVehicleId == it.id || live.auto.holdsVehicle) }
            val transfer = journey?.let { JourneyCompanions.transfer(it, state.ride, next, now) }
            return ReportSnapshot(state.ride, journey, state.lastRefreshFailed, vehicle?.id, arrivals, transfer,
                if (transfer != null) listOfNotNull(state.ride?.fetchedAt, next?.fetchedAt).minOrNull() else null)
        }
    }
}

enum class RideEnd { MANUAL, SCHEDULE, GPS_EXIT, TIMEOUT, INTERRUPTED }

/**
 * Builds and extends the report without touching storage. Every call returns the same report
 * for the same accompaniment, so refreshes, restarts and transfers never create a second one.
 */
object JourneyReportRecorder {
    fun sessionKey(ride: Ride, companion: JourneyCompanion?): String =
        companion?.takeIf { it.trackedKey == ride.departure.key }?.let(::journeyKey)
            ?: "ride/${ride.departure.key}/${ride.trackingStartedAt ?: ride.departure.planned}"

    fun journeyKey(companion: JourneyCompanion): String = "journey/${companion.startedAt}"

    fun start(id: String, ride: Ride, companion: JourneyCompanion?, now: Instant): JourneyReport {
        val journey = companion?.takeIf { sessionKey(ride, it).startsWith("journey/") }
        val boarding = ride.stops.getOrNull(ride.boardingIndex)
        val started = ride.trackingStartedAt ?: now
        return JourneyReport(id, sessionKey(ride, companion), now, now,
            originName = journey?.journey?.legs?.firstOrNull { it.kind == LegKind.TRANSIT }?.origin?.name ?: boarding?.name ?: ride.departure.stopName,
            destinationName = journey?.destinationName ?: ride.displayTarget().name,
            legs = journey?.let(::plannedLegs).orEmpty(),
            events = listOf(ReportEvent("start", ReportEventType.TRACKING_STARTED, ReportSource.APP, started,
                "Begleitung gestartet", recordedAt = now)),
            waitFrom = started.takeUnless { ride.boarded })
    }

    /** Legs as found by the search. They are replaced by tracked data once a vehicle is followed. */
    private fun plannedLegs(companion: JourneyCompanion): List<ReportLeg> = companion.journey.legs.mapIndexed { index, leg ->
        val fetched = companion.journey.fetchedAt
        fun stop(time: TripStopTime) = ReportStop(time.id, time.name, time.platform, time.plannedArrival, time.plannedDeparture,
            time.estimatedArrival, time.estimatedDeparture,
            fetched.takeIf { time.estimatedArrival != null || time.estimatedDeparture != null }, time.cancelled)
        if (leg.kind != LegKind.TRANSIT) ReportLeg("leg/$index", walk = true, stops = listOf(stop(leg.origin), stop(leg.destination)),
            walkSeconds = leg.durationSeconds, fetchedAt = fetched)
        else ReportLeg("leg/$index", line = leg.line.ifBlank { leg.lineName }, direction = leg.direction, lineId = leg.lineId,
            tripCode = leg.tripCode, operator = leg.operatorCode,
            stops = listOf(stop(leg.origin)) + leg.intermediate.map(::stop) + stop(leg.destination),
            cancelled = leg.cancelled, notices = leg.notices.take(5), fetchedAt = fetched)
    }

    fun update(report: JourneyReport, snapshot: ReportSnapshot, now: Instant): JourneyReport {
        val ride = snapshot.ride?.takeUnless { it.demo } ?: return report
        if (report.state != ReportState.RUNNING) return report
        val key = ride.departure.key
        val previous = report.legs.firstOrNull { it.key == key }
        val leg = trackedLeg(ride, previous, snapshot.vehicleId)
        val plannedIndex = snapshot.companion?.takeIf { it.trackedKey == key }?.legIndex
        var legs = when {
            previous != null -> report.legs.map { if (it.key == key) leg else it }
            plannedIndex != null && report.legs.any { it.key == "leg/$plannedIndex" } ->
                report.legs.map { if (it.key == "leg/$plannedIndex") leg else it }
            else -> report.legs + leg
        }
        // A later leg of the journey may get newer search data while the current one is followed.
        snapshot.companion?.takeIf { sessionKey(ride, it) == report.sessionKey }?.let { companion ->
            val fresh = plannedLegs(companion).associateBy { it.key }
            legs = legs.map { existing -> fresh[existing.key]?.takeIf { !existing.tracked }
                ?.let { mergePlanned(existing, it) } ?: existing }
        }
        var events = report.events
        fun once(event: ReportEvent) { if (events.none { it.id == event.id }) events = events + event }
        if (ride.departure.cancelled || leg.cancelled) once(ReportEvent("cancelled/$key", ReportEventType.CANCELLED, ReportSource.OPERATOR,
            ride.departure.observedAt ?: ride.fetchedAt, "Linie ${ride.departure.line}: Fahrt als ausgefallen gemeldet", key, now))
        leg.stops.filter { it.cancelled }.forEach { stop -> once(ReportEvent("stopcancelled/$key/${stop.id}", ReportEventType.CANCELLED,
            ReportSource.OPERATOR, ride.fetchedAt, "Halt ${shortName(stop.name)} als ausgefallen gemeldet", key, now)) }
        ride.departure.notices.take(5).forEach { notice ->
            val plain = plainNotice(notice)
            if (plain.isNotBlank()) once(ReportEvent("notice/$key/${plain.hashCode()}", ReportEventType.NOTICE, ReportSource.OPERATOR,
                ride.departure.observedAt ?: ride.fetchedAt, plain, key, now))
        }
        if (snapshot.refreshFailed) once(ReportEvent("gap/$key", ReportEventType.DATA_GAP, ReportSource.APP, now,
            "Keine neuen VRR-Daten abrufbar; der letzte Stand blieb stehen", key))
        snapshot.transfer?.takeIf { it.risk != TransferRisk.OK }?.let { transfer ->
            once(ReportEvent("transfer/$key/${transfer.risk}", ReportEventType.TRANSFER_RISK,
                if (transfer.outgoingCancelled) ReportSource.OPERATOR else ReportSource.APP, snapshot.transferObservedAt ?: now,
                if (transfer.outgoingCancelled) "Anschlussfahrt als ausgefallen gemeldet"
                else if (transfer.risk == TransferRisk.MISSED) "Anschluss laut Prognosen nicht erreichbar"
                else "Anschluss laut Prognosen knapp", key, now))
        }
        if (snapshot.vehicleId != null) once(ReportEvent("vehicle/$key/${snapshot.vehicleId}", ReportEventType.VEHICLE_ASSIGNED,
            ReportSource.GPS, now, "Fahrzeug ${snapshot.vehicleId} der Linie ${ride.departure.line} zugeordnet (NEW-Livedaten)", key))
        val corrected = !ride.boarded && events.any { it.type == ReportEventType.BOARDED && it.legKey == key }
        if (corrected) {
            events = events.filterNot { it.type == ReportEventType.BOARDED && it.legKey == key }
            once(ReportEvent("boarding-correction/$key/${now.toEpochMilli()}", ReportEventType.BOARDING_CORRECTED,
                ReportSource.OWN, now, "Einstieg zurückgenommen: doch nicht im Bus", key))
        }
        if (ride.boarded && events.none { it.id == "boarded/$key" }) {
            val manual = ride.boardedBy != "auto"
            once(ReportEvent("boarded/$key", ReportEventType.BOARDED, if (manual) ReportSource.OWN else ReportSource.GPS,
                ride.boardedAt ?: now, "Einstieg in Linie ${ride.departure.line} an ${shortName(ride.stops.getOrNull(ride.boardingIndex)?.name ?: ride.departure.stopName)}" +
                    if (ride.boardedAt == null) " (Zeitpunkt nicht erfasst)" else "", key, now))
        }
        ArrivalMark.forRide(snapshot.arrivals, ride, now).forEach { mark ->
            once(ReportEvent("arrival/${mark.id}", ReportEventType.ARRIVED_AT_STOP,
                if (mark.method == "location") ReportSource.GPS else ReportSource.OWN, mark.at,
                "An der Haltestelle ${shortName(mark.stopName)}", key, now))
        }
        val waitUntil = if (corrected) null else if (ride.boarded && report.waitFrom != null && report.waitUntil == null) ride.boardedAt ?: now else report.waitUntil
        return report.copy(legs = legs, events = events.takeLast(JourneyReport.MAX_EVENTS), updatedAt = now, waitUntil = waitUntil,
            destinationName = if (snapshot.companion == null) ride.displayTarget().name else report.destinationName)
    }

    private fun mergePlanned(existing: ReportLeg, fresh: ReportLeg): ReportLeg = fresh.copy(
        stops = fresh.stops.map { stop -> keepForecast(stop, existing.stops.firstOrNull { it.id == stop.id }) })

    /** Stops of my part of the trip: boarding to exit. Earlier forecasts survive a later fetch without one. */
    private fun trackedLeg(ride: Ride, previous: ReportLeg?, vehicleId: String?): ReportLeg {
        val last = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val stops = (ride.boardingIndex..last.coerceAtLeast(ride.boardingIndex)).map { index ->
            val stop = ride.stops[index]
            val monitor = ride.departure.estimated?.takeIf { index == ride.boardingIndex && stop.estimatedDeparture == null }
            val current = ReportStop(stop.id, stop.name, if (index == ride.boardingIndex) ride.departure.platform else "",
                stop.arrival, stop.departure ?: ride.departure.planned.takeIf { index == ride.boardingIndex },
                stop.estimatedArrival, stop.estimatedDeparture ?: monitor,
                when {
                    stop.estimatedArrival != null || stop.estimatedDeparture != null -> ride.fetchedAt
                    monitor != null -> ride.departure.observedAt
                    else -> null
                }, stop.cancelled)
            keepForecast(current, previous?.stops?.firstOrNull { it.id == stop.id })
        }
        return ReportLeg(ride.departure.key, line = ride.departure.line, direction = ride.departure.destination,
            lineId = ride.departure.lineId, tripCode = ride.departure.tripCode,
            operator = if (ride.departure.newOperated) "NEW" else ride.departure.operator, stops = stops,
            cancelled = ride.departure.cancelled,
            notices = (previous?.notices.orEmpty() + ride.departure.notices.map(::plainNotice)).filter { it.isNotBlank() }.distinct().take(5),
            tracked = true, fetchedAt = ride.fetchedAt, vehicleId = vehicleId ?: previous?.vehicleId)
    }

    private fun keepForecast(current: ReportStop, earlier: ReportStop?): ReportStop =
        if (current.hasForecast || earlier == null || !earlier.hasForecast) current
        else current.copy(forecastArrival = earlier.forecastArrival, forecastDeparture = earlier.forecastDeparture,
            forecastFetchedAt = earlier.forecastFetchedAt)

    /** Closes the report. A wait without boarding is documented as a wait, never as a cancellation. */
    fun finish(report: JourneyReport, lastRide: Ride?, end: RideEnd, now: Instant, moreLegs: Boolean): JourneyReport {
        if (report.state != ReportState.RUNNING) return report
        val key = lastRide?.departure?.key ?: report.transit.lastOrNull { it.tracked }?.key
        // After a process restart the last ride is unknown; the recorded boarding decides.
        val boarded = lastRide?.boarded ?: report.events.any { it.type == ReportEventType.BOARDED && it.legKey == key }
        var events = report.events
        fun once(event: ReportEvent) { if (events.none { it.id == event.id }) events = events + event }
        if (boarded) {
            val stop = shortName(lastRide?.displayTarget()?.name ?: report.legs.firstOrNull { it.key == key }?.destination?.name.orEmpty())
            once(when (end) {
                RideEnd.GPS_EXIT -> ReportEvent("exit/$key", ReportEventType.EXITED, ReportSource.GPS, now, "Ausstieg an $stop erkannt", key)
                RideEnd.MANUAL -> ReportEvent("exit/$key", ReportEventType.EXITED, ReportSource.OWN, now, "Fahrt in der App beendet", key)
                RideEnd.SCHEDULE -> ReportEvent("exit/$key", ReportEventType.RIDE_ENDED, ReportSource.APP, now,
                    "Begleitung nach der gemeldeten Ankunftszeit beendet; ein Ausstieg wurde nicht erfasst", key)
                RideEnd.TIMEOUT -> ReportEvent("exit/$key", ReportEventType.RIDE_ENDED, ReportSource.APP, now,
                    "Begleitung nach drei Stunden automatisch beendet", key)
                RideEnd.INTERRUPTED -> ReportEvent("exit/$key", ReportEventType.RIDE_ENDED, ReportSource.APP, now,
                    "Begleitung endete, ohne dass ein Ausstieg erfasst wurde", key)
            })
        }
        if (moreLegs && boarded) return report.copy(events = events, updatedAt = now)
        if (!boarded) {
            once(ReportEvent("waitend/$key", ReportEventType.WAIT_ENDED, ReportSource.APP, now,
                if (end == RideEnd.MANUAL) "Begleitung von dir beendet, kein Einstieg erfasst" else "Begleitung beendet, kein Einstieg erfasst", key))
            if (events.none { it.type == ReportEventType.VEHICLE_ASSIGNED && it.legKey == key })
                once(ReportEvent("nobus/$key", ReportEventType.DATA_GAP, ReportSource.APP, now,
                    "Während des Wartens hat die App kein passendes Fahrzeug erkannt", key))
        }
        val everBoarded = events.any { it.type == ReportEventType.BOARDED }
        return report.copy(events = events.takeLast(JourneyReport.MAX_EVENTS), updatedAt = now,
            state = if (boarded || everBoarded) ReportState.FINISHED else ReportState.ABORTED,
            waitUntil = report.waitUntil ?: now.takeIf { report.waitFrom != null })
    }

    /** An own statement such as "Bus nicht gekommen". It is always marked as the user's own. */
    fun observe(report: JourneyReport, id: String, text: String, now: Instant): JourneyReport {
        val clean = text.trim().replace(Regex("\\s+"), " ").take(300)
        if (clean.isEmpty()) return report
        return report.copy(events = (report.events + ReportEvent("own/$id", ReportEventType.OBSERVATION, ReportSource.OWN, now, clean))
            .takeLast(JourneyReport.MAX_EVENTS), updatedAt = now)
    }

    fun removeObservation(report: JourneyReport, eventId: String, now: Instant): JourneyReport =
        if (report.events.none { it.id == eventId && it.type == ReportEventType.OBSERVATION }) report
        else report.copy(events = report.events.filterNot { it.id == eventId }, updatedAt = now)

    /** Report for a ride from the archive: only the stored timetable and forecast data exist. */
    fun fromArchive(id: String, ride: Ride, arrivals: List<ArrivalMark>, now: Instant): JourneyReport {
        val leg = trackedLeg(ride, null, null)
        val marks = ArrivalMark.forRide(arrivals, ride, now).map { mark ->
            ReportEvent("arrival/${mark.id}", ReportEventType.ARRIVED_AT_STOP,
                if (mark.method == "location") ReportSource.GPS else ReportSource.OWN, mark.at,
                "An der Haltestelle ${shortName(mark.stopName)}", leg.key, now)
        }
        return JourneyReport(id, "archive/${ride.departure.key}", now, now, ReportState.FINISHED,
            ride.stops.getOrNull(ride.boardingIndex)?.name ?: ride.departure.stopName, ride.displayTarget().name,
            listOf(leg), marks, fromArchive = true)
    }

    private fun plainNotice(value: String): String = value.replace(Regex("<[^>]{0,200}>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim().take(400)
}
