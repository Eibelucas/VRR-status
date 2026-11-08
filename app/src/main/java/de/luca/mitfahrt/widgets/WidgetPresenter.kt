package de.luca.mitfahrt.widgets

import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.RideStop
import de.luca.mitfahrt.data.boardingTime
import de.luca.mitfahrt.data.clockTime
import de.luca.mitfahrt.data.displayTarget
import de.luca.mitfahrt.data.liveBoardingTime
import de.luca.mitfahrt.data.liveEstimate
import de.luca.mitfahrt.data.minutesUntil
import de.luca.mitfahrt.data.nextStopToTarget
import de.luca.mitfahrt.data.shortName
import de.luca.mitfahrt.data.stopKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object WidgetTiming {
    /** Same window as [Departure.liveEstimate] and [Ride.liveBoardingTime]. */
    const val LIVE_SECONDS = 120L
    /** Older real predictions are still shown as a clock time, never as a countdown. */
    const val PROGNOSIS_SECONDS = 15 * 60L
    const val PAST_GRACE_SECONDS = 30L
    const val MIN_RENDER_DELAY_SECONDS = 30L
}

/** The tracked ride as the app currently knows it. */
data class ActiveRideInput(val ride: Ride?, val tracking: Boolean, val refreshFailed: Boolean)

object WidgetPresenter {
    private val zone = ZoneId.of("Europe/Berlin")

    fun present(
        config: WidgetConfig?, snapshot: WidgetSnapshot, now: Instant,
        loading: Boolean = false, active: ActiveRideInput? = null
    ): WidgetModel {
        if (config == null || !config.isComplete()) return WidgetModel(
            title = "Mitfahrt", status = WidgetStatus.NOT_CONFIGURED, statusLine = "Nicht eingerichtet",
            message = "Tippen zum Einrichten", open = WidgetAction.Configure)
        if (config.needsTarget && snapshot.targetMissing) return WidgetModel(
            title = shortName(config.targetName), status = WidgetStatus.TARGET_MISSING,
            statusLine = if (config.kind == WidgetKind.ROUTE) "Route nicht mehr vorhanden" else "Haltestelle nicht gefunden",
            message = "Tippen, um neu zu wählen", open = WidgetAction.Configure)
        return if (config.kind == WidgetKind.ACTIVE)
            activeRide(config, snapshot, active ?: ActiveRideInput(null, false, false), now, loading)
        else board(config, snapshot, now, loading)
    }

    // --- Stop and route boards -------------------------------------------------------------

    private fun board(config: WidgetConfig, snapshot: WidgetSnapshot, now: Instant, loading: Boolean): WidgetModel {
        val open = WidgetAction.Open(config.kind, config.targetId, "departures")
        val rowAction = if (config.kind == WidgetKind.ROUTE) WidgetAction.Open(config.kind, config.targetId, "preview") else open
        val rows = snapshot.departures.mapNotNull { departureRow(it, now, config.info, rowAction) }
        val fetchedAt = snapshot.fetchedAt
        val stale = fetchedAt == null || age(fetchedAt, now) !in -30..WidgetTiming.PROGNOSIS_SECONDS
        val status = when {
            loading -> WidgetStatus.LOADING
            snapshot.failure == FetchFailure.OFFLINE -> WidgetStatus.OFFLINE
            snapshot.failure == FetchFailure.SOURCE -> WidgetStatus.ERROR
            stale -> WidgetStatus.STALE
            rows.isEmpty() -> WidgetStatus.EMPTY
            else -> WidgetStatus.READY
        }
        val label = when (status) {
            WidgetStatus.LOADING -> "Aktualisiere …"
            WidgetStatus.OFFLINE -> "Keine Verbindung"
            WidgetStatus.ERROR -> "VRR nicht erreichbar"
            WidgetStatus.STALE -> if (fetchedAt == null) "Noch keine Daten" else "Veraltet"
            else -> if (snapshot.partial) "Teilweise geprüft" else null
        }
        val message = if (rows.isNotEmpty()) null else when (status) {
            WidgetStatus.LOADING -> "Abfahrten werden geladen"
            WidgetStatus.OFFLINE -> "Offline – später aktualisieren"
            WidgetStatus.ERROR -> "Daten gerade nicht verfügbar"
            WidgetStatus.STALE -> "Zum Laden aktualisieren"
            else -> if (config.kind == WidgetKind.ROUTE) "Keine passende Abfahrt in der nächsten Stunde"
                else "Gerade keine Abfahrten"
        }
        return WidgetModel(
            title = shortName(config.targetName), status = status,
            statusLine = joinStatus(label, fetchedAt, now), rows = rows, message = message,
            open = open, nextRenderAt = nextBoardRender(snapshot, now))
    }

    internal fun departureRow(departure: Departure, now: Instant, info: InfoLevel, action: WidgetAction): WidgetRow? {
        val sourceAge = departure.observedAt?.let { age(it, now) }
        val trustedEstimate = departure.estimated?.takeIf {
            !departure.cancelled && sourceAge != null && sourceAge in -30..WidgetTiming.PROGNOSIS_SECONDS
        }
        val reference = if (departure.cancelled) departure.planned else trustedEstimate ?: departure.planned
        if (reference.epochSecond < now.epochSecond - WidgetTiming.PAST_GRACE_SECONDS) return null
        val live = departure.liveEstimate(now)
        val delay = if (departure.estimated != null) departure.delayMinutes else 0
        val delayText = when { delay > 0 -> " +$delay"; delay < 0 -> " $delay"; else -> "" }
        val time: String
        val label: String
        val kind: TimeKind
        when {
            departure.cancelled -> { time = clockTime(departure.planned); label = "Ausfall"; kind = TimeKind.CANCELLED }
            live != null -> { time = countdown(live, now); label = "live$delayText"; kind = TimeKind.LIVE }
            trustedEstimate != null -> { time = clockTime(trustedEstimate); label = "Prognose$delayText"; kind = TimeKind.PROGNOSIS }
            else -> { time = clockTime(departure.planned); label = "Plan"; kind = TimeKind.SCHEDULE }
        }
        val destination = shortName(departure.destination).ifBlank { "Ziel unbekannt" }
        val detail = if (info != InfoLevel.DETAILED) null else listOfNotNull(
            departure.platform.takeIf { it.isNotBlank() }?.let { "Steig $it" },
            if (kind == TimeKind.LIVE || kind == TimeKind.PROGNOSIS) "Plan ${clockTime(departure.planned)}" else null,
            departure.notices.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { null }
        val spoken = when (kind) {
            TimeKind.CANCELLED -> "fällt aus, geplant ${clockTime(departure.planned)}"
            TimeKind.LIVE -> "live, ${spokenCountdown(live!!, now)}"
            TimeKind.PROGNOSIS -> "Prognose ${clockTime(trustedEstimate)}"
            TimeKind.SCHEDULE -> "laut Fahrplan ${clockTime(departure.planned)}"
        }
        return WidgetRow(departure.line.ifBlank { "?" }, destination, time, label, kind, detail,
            "Linie ${departure.line} nach $destination, $spoken", action)
    }

    private fun nextBoardRender(snapshot: WidgetSnapshot, now: Instant): Instant? {
        val candidates = mutableListOf<Instant>()
        snapshot.fetchedAt?.let { candidates += it.plusSeconds(WidgetTiming.PROGNOSIS_SECONDS + 1) }
        snapshot.departures.forEach { departure ->
            val observed = departure.observedAt
            if (departure.liveEstimate(now) != null && observed != null) {
                candidates += observed.plusSeconds(WidgetTiming.LIVE_SECONDS + 1)
                candidates += now.plusSeconds(60)
            }
            if (departure.estimated != null && observed != null)
                candidates += observed.plusSeconds(WidgetTiming.PROGNOSIS_SECONDS + 1)
            val reference = if (departure.cancelled) departure.planned else departure.estimated ?: departure.planned
            candidates += reference.plusSeconds(WidgetTiming.PAST_GRACE_SECONDS + 1)
            candidates += departure.planned.plusSeconds(WidgetTiming.PAST_GRACE_SECONDS + 1)
        }
        return earliest(candidates, now)
    }

    // --- Tracked ride ----------------------------------------------------------------------

    private fun activeRide(config: WidgetConfig, snapshot: WidgetSnapshot, input: ActiveRideInput,
                           now: Instant, loading: Boolean): WidgetModel {
        val stored = input.ride
        val openRide = WidgetAction.Open(WidgetKind.ACTIVE, null, "ride")
        if (stored == null) return WidgetModel(
            title = "Aktuelle Fahrt", status = if (loading) WidgetStatus.LOADING else WidgetStatus.EMPTY,
            statusLine = "Keine Fahrt aktiv", message = "Fahrt in der App wählen", open = openRide)
        // A display refresh is only used for the same trip and only while it is newer.
        val ride = snapshot.ride?.takeIf { it.fetchedAt.isAfter(stored.fetchedAt) }
            ?.let { mergeDisplayRide(stored, it) } ?: stored
        val fresh = !input.refreshFailed && age(ride.fetchedAt, now) in -30..WidgetTiming.LIVE_SECONDS
        val recent = age(ride.fetchedAt, now) in -30..WidgetTiming.PROGNOSIS_SECONDS
        val rows = mutableListOf<WidgetRow>()
        val badge = ride.departure.line.ifBlank { "?" }
        val cancelled = ride.departure.cancelled || ride.stops.getOrNull(ride.boardingIndex)?.cancelled == true
        val ended = ride.complete(now)

        if (!ride.boarded) {
            val boardingStop = ride.stops.getOrNull(ride.boardingIndex)
            val planned = boardingStop?.departure ?: boardingStop?.arrival ?: ride.departure.planned
            val boarding = ride.boardingTime()
            val live = if (fresh && !ride.demo && !cancelled) ride.liveBoardingTime(now) else null
            val sourceAt = boarding.observedAt ?: ride.fetchedAt
            val prognosis = boarding.at.takeIf { boarding.live && !cancelled && age(sourceAt, now) in -30..WidgetTiming.PROGNOSIS_SECONDS }
            val name = shortName(boardingStop?.name ?: ride.departure.stopName)
            rows += timedRow(badge, "Einstieg $name", planned, live?.at, prognosis, cancelled, now,
                detail = if (config.info == InfoLevel.DETAILED) "Richtung ${shortName(ride.departure.destination)}" else null,
                spokenPrefix = "Linie ${ride.departure.line}, Einstieg $name", action = openRide)
            val target = ride.displayTarget()
            rows += stopRow(null, "Ziel ${shortName(target.name)}", target, fresh && !ride.demo, recent, now, openRide)
        } else {
            val target = ride.displayTarget()
            rows += stopRow(badge, "Ziel ${shortName(target.name)}", target, fresh && !ride.demo, recent, now, openRide,
                cancelled = cancelled || target.cancelled,
                detail = if (config.info == InfoLevel.DETAILED) "Richtung ${shortName(ride.departure.destination)}" else null)
            ride.nextStopToTarget(now)?.takeIf { stopKey(it.id) != stopKey(target.id) }?.let { next ->
                rows += stopRow(null, "Nächster Halt ${shortName(next.name)}", next, fresh && !ride.demo, recent, now, openRide)
            }
        }
        ride.departure.notices.firstOrNull()?.trim()?.takeIf { it.isNotBlank() && config.info == InfoLevel.DETAILED }?.let {
            rows += WidgetRow(null, it, "", "", TimeKind.SCHEDULE, null, it, openRide)
        }

        val status = when {
            loading -> WidgetStatus.LOADING
            ride.demo -> WidgetStatus.READY
            input.refreshFailed -> WidgetStatus.ERROR
            snapshot.failure == FetchFailure.OFFLINE && !recent -> WidgetStatus.OFFLINE
            snapshot.failure == FetchFailure.SOURCE && !recent -> WidgetStatus.ERROR
            !recent -> WidgetStatus.STALE
            else -> WidgetStatus.READY
        }
        val label = when {
            ride.demo -> "Demo"
            status == WidgetStatus.LOADING -> "Aktualisiere …"
            cancelled -> "Fahrt fällt aus"
            ended -> "Fahrt beendet"
            status == WidgetStatus.ERROR -> "Aktualisierung fehlgeschlagen"
            status == WidgetStatus.OFFLINE -> "Keine Verbindung"
            status == WidgetStatus.STALE -> "Veraltet"
            !input.tracking -> "Verfolgung pausiert"
            else -> null
        }
        val candidates = mutableListOf(ride.fetchedAt.plusSeconds(WidgetTiming.PROGNOSIS_SECONDS + 1))
        if (rows.any { it.timeKind == TimeKind.LIVE }) {
            candidates += ride.fetchedAt.plusSeconds(WidgetTiming.LIVE_SECONDS + 1)
            candidates += now.plusSeconds(60)
        }
        return WidgetModel(
            title = "Aktuelle Fahrt", status = status, statusLine = joinStatus(label, ride.fetchedAt, now),
            rows = rows, open = openRide,
            mapAction = if (ride.demo || ended) null else WidgetAction.Open(WidgetKind.ACTIVE, null, "map"),
            nextRenderAt = earliest(candidates, now))
    }

    private fun stopRow(badge: String?, title: String, stop: RideStop, live: Boolean, recent: Boolean, now: Instant,
                        action: WidgetAction, cancelled: Boolean = stop.cancelled, detail: String? = null): WidgetRow {
        val estimate = stop.estimated?.takeIf { !cancelled }
        return timedRow(badge, title, stop.planned, estimate?.takeIf { live }, estimate?.takeIf { recent },
            cancelled, now, detail, title, action)
    }

    private fun timedRow(badge: String?, title: String, planned: Instant?, live: Instant?, prognosis: Instant?,
                         cancelled: Boolean, now: Instant, detail: String?, spokenPrefix: String,
                         action: WidgetAction): WidgetRow {
        val time: String
        val label: String
        val kind: TimeKind
        when {
            cancelled -> { time = clockTime(planned); label = "Ausfall"; kind = TimeKind.CANCELLED }
            live != null -> { time = countdown(live, now); label = "live"; kind = TimeKind.LIVE }
            prognosis != null -> { time = clockTime(prognosis); label = "Prognose"; kind = TimeKind.PROGNOSIS }
            else -> { time = clockTime(planned); label = "Plan"; kind = TimeKind.SCHEDULE }
        }
        val spoken = when (kind) {
            TimeKind.CANCELLED -> "fällt aus"
            TimeKind.LIVE -> "live, ${spokenCountdown(live!!, now)}"
            TimeKind.PROGNOSIS -> "Prognose $time"
            TimeKind.SCHEDULE -> "laut Fahrplan $time"
        }
        return WidgetRow(badge, title, time, label, kind, detail, "$spokenPrefix, $spoken", action)
    }

    // --- Helpers ---------------------------------------------------------------------------

    private fun age(at: Instant, now: Instant): Long = now.epochSecond - at.epochSecond

    internal fun countdown(at: Instant, now: Instant): String {
        val minutes = minutesUntil(at, now)
        return when {
            minutes == 0 -> "jetzt"
            minutes < 60 -> "$minutes min"
            else -> clockTime(at)
        }
    }

    private fun spokenCountdown(at: Instant, now: Instant): String {
        val minutes = minutesUntil(at, now)
        return when {
            minutes == 0 -> "jetzt"
            minutes == 1 -> "in einer Minute"
            minutes < 60 -> "in $minutes Minuten"
            else -> "um ${clockTime(at)}"
        }
    }

    internal fun dataStand(fetchedAt: Instant, now: Instant): String {
        val day = fetchedAt.atZone(zone).toLocalDate()
        return if (day == now.atZone(zone).toLocalDate()) "Stand ${clockTime(fetchedAt)}"
        else "Stand " + fetchedAt.atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
    }

    private fun joinStatus(label: String?, fetchedAt: Instant?, now: Instant): String? =
        listOfNotNull(label, fetchedAt?.let { dataStand(it, now) }).joinToString(" · ").ifBlank { null }

    private fun earliest(candidates: List<Instant>, now: Instant): Instant? =
        candidates.filter { it.isAfter(now) }.minOrNull()
            ?.let { maxOf(it, now.plusSeconds(WidgetTiming.MIN_RENDER_DELAY_SECONDS)) }
}

/** Accepts a fresh trip lookup for display only if it describes the same stop sequence. */
internal fun mergeDisplayRide(tracked: Ride, fetched: Ride): Ride? {
    if (tracked.demo || fetched.departure.key != tracked.departure.key) return null
    if (fetched.stops.map { stopKey(it.id) } != tracked.stops.map { stopKey(it.id) }) return null
    return tracked.copy(stops = fetched.stops, fetchedAt = fetched.fetchedAt)
}
