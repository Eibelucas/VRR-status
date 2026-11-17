package de.luca.mitfahrt.data

import java.time.Instant

/** One value that can be placed in an own text, e.g. `{linie}`. */
data class TemplateVariable(val name: String, val label: String, val sample: String)

/** A text the user can compose: where it appears and what it shows without an own template. */
data class TemplateSlot(val key: String, val group: String, val label: String, val default: String, val maxLength: Int = 120)

/**
 * Own texts from variables. A template is plain text with `{name}` placeholders. Parts are
 * separated by " · "; a part whose variable is empty right now is left out, so a missing live
 * time never shows as a gap or an invented value.
 */
object TextTemplates {
    const val SEPARATOR = " · "
    private val placeholder = Regex("\\{([a-zäöüß_0-9]{1,24})\\}")

    val variables = listOf(
        TemplateVariable("linie", "Linie", "017"),
        TemplateVariable("ziel", "Ausstieg", "Hbf"),
        TemplateVariable("richtung", "Richtung", "Rheydt Hbf"),
        TemplateVariable("einstieg", "Einstieg", "Bismarckplatz"),
        TemplateVariable("steig", "Steig oder Gleis", "Bstg. 1"),
        TemplateVariable("phase", "Warten oder Im Bus", "Im Bus"),
        TemplateVariable("status", "Aktueller Hinweis", "Bus kommt in 6:00"),
        TemplateVariable("quelle", "Datenquelle", "Live"),
        TemplateVariable("minuten", "Minuten (live)", "6"),
        TemplateVariable("countdown", "Countdown bis Abfahrt", "5:40"),
        TemplateVariable("berechnet", "Minuten (berechnet)", "5"),
        TemplateVariable("abfahrt", "Abfahrt erwartet", "08:08"),
        TemplateVariable("abfahrt_plan", "Abfahrt laut Plan", "08:06"),
        TemplateVariable("ankunft", "Ankunft erwartet", "08:18"),
        TemplateVariable("ankunft_plan", "Ankunft laut Plan", "08:16"),
        TemplateVariable("ankunft_berechnet", "Ankunft berechnet", "08:17"),
        TemplateVariable("verspaetung", "Verspätung", "+2 Min"),
        TemplateVariable("verspaetung_minuten", "Verspätung nur Zahl", "2"),
        TemplateVariable("ausfall", "Ausfallhinweis", "Fällt aus"),
        TemplateVariable("halt", "Nächster Halt", "Schillerplatz"),
        TemplateVariable("halt_zeit", "Nächster Halt Uhrzeit", "08:10"),
        TemplateVariable("halt_minuten", "Nächster Halt Minuten (live)", "2"),
        TemplateVariable("halt_berechnet", "Nächster Halt Minuten (berechnet)", "2"),
        TemplateVariable("halt_danach", "Übernächster Halt", "Marienplatz"),
        TemplateVariable("halte", "Halte bis Ausstieg", "3"),
        TemplateVariable("fortschritt", "Fortschritt in Prozent", "40"),
        TemplateVariable("im_bus_seit", "Im Bus seit", "08:08"),
        TemplateVariable("umstieg", "Umstieg", "Umstieg: RE8 um 08:20"),
        TemplateVariable("umstieg_linie", "Umstieg Linie", "RE8"),
        TemplateVariable("umstieg_zeit", "Umstieg Abfahrt (Plan)", "08:20"),
        TemplateVariable("umstieg_steig", "Umstieg Steig oder Gleis", "Gleis 2"),
        TemplateVariable("umstieg_ziel", "Endziel der Verbindung", "Köln Hbf"),
        TemplateVariable("fahrzeug", "Busnummer", "7314"),
        TemplateVariable("bus_stand", "Busposition Uhrzeit", "08:07"),
        TemplateVariable("bus_entfernung", "Bus bis Einstieg", "450 m"),
        TemplateVariable("uhrzeit", "Uhrzeit jetzt", "08:02"),
        TemplateVariable("datum", "Datum", "17.11."),
        TemplateVariable("datenstand", "Daten abgerufen", "08:01")
    )
    private val names = variables.map { it.name }.toSet()

    val slots = listOf(
        TemplateSlot("tpl_nowbar_title_wait", "Now Bar und Benachrichtigung", "Titel beim Warten", "{linie} → {ziel}"),
        TemplateSlot("tpl_nowbar_text_wait", "Now Bar und Benachrichtigung", "Text beim Warten", "{status} · {verspaetung} · {umstieg}"),
        TemplateSlot("tpl_nowbar_title_ride", "Now Bar und Benachrichtigung", "Titel im Bus", "{linie} → {ziel}"),
        TemplateSlot("tpl_nowbar_text_ride", "Now Bar und Benachrichtigung", "Text im Bus", "{status} · {verspaetung} · {umstieg}"),
        TemplateSlot("tpl_nowbar_chip", "Now Bar und Benachrichtigung", "Kurztext in der Now Bar", "{linie} · {minuten} Min", 24),
        TemplateSlot("tpl_mapwidget_title", "Widget Buskarte", "Titel", "{linie} · {ziel}", 60),
        TemplateSlot("tpl_mapwidget_status", "Widget Buskarte", "Zeile unter der Skizze", "{quelle} · noch etwa {berechnet} Min", 80)
    )

    val sample: Map<String, String> = variables.associate { it.name to it.sample }

    /** Names in [template] that are not known variables; shown to the user instead of being dropped silently. */
    fun unknown(template: String): List<String> = placeholder.findAll(template).map { it.groupValues[1] }.filter { it !in names }.distinct().toList()

    fun clean(template: String, maxLength: Int): String = template.replace(Regex("[\\n\\r\\t]+"), " ").take(maxLength)

    fun render(template: String, values: Map<String, String>): String =
        template.split(SEPARATOR.trim()).mapNotNull { part ->
            var missing = false
            val text = placeholder.replace(part) { match ->
                val value = values[match.groupValues[1]]?.trim().orEmpty()
                if (value.isEmpty()) missing = true
                value
            }.trim()
            text.takeIf { !missing && it.isNotEmpty() }
        }.joinToString(SEPARATOR)

    /** The own text for [slot], or null when own texts are off, the template is empty or nothing is left of it. */
    fun custom(settings: AppSettings, slot: String, values: Map<String, String>): String? {
        if (!settings.bool("templates_enabled", false)) return null
        val template = settings.string(slot).takeIf { it.isNotBlank() } ?: return null
        val limit = slots.firstOrNull { it.key == slot }?.maxLength ?: 120
        return render(template, values).take(limit).takeIf { it.isNotBlank() }
    }

    /**
     * All values for the tracked ride at [now]. A value the app does not know right now is an
     * empty string, so its part of a template disappears; nothing is filled in by guessing.
     */
    fun values(state: TrackingState, companion: JourneyCompanion?, now: Instant, gpsEnabled: Boolean = true): Map<String, String> {
        val ride = state.ride ?: return emptyMap()
        val key = ride.departure.key
        val waiting = !ride.boarded
        val fresh = !ride.isStale(now) && !state.lastRefreshFailed && state.running
        val goalIndex = ride.exitTargetIndex() ?: ride.stops.lastIndex
        val goal = ride.stops[goalIndex]
        val boardingStop = ride.stops.getOrNull(ride.boardingIndex)
        val liveBoarding = if (fresh) ride.liveBoardingTime(now) else null
        val info = NextStopResolver.resolve(state, now, gpsEnabled)
        val next = info?.stop
        val estimate = TravelEstimator.estimate(state, now, gpsEnabled)
        val delaySeconds = when {
            waiting -> liveBoarding?.let { it.at.epochSecond - ((if (it.isArrival) boardingStop?.arrival else boardingStop?.departure) ?: ride.departure.planned).epochSecond }
            fresh -> goal.estimated?.let { live -> goal.planned?.let { live.epochSecond - it.epochSecond } }
            else -> null
        }
        val liveMinutes = if (waiting) liveBoarding?.at?.let { minutesUntil(it, now) } else goal.estimated?.takeIf { fresh }?.let { minutesUntil(it, now) }
        val live = state.liveBus
        val bus = live.vehicle?.takeIf { live.journeyKey == key && it.isFresh(now) &&
            (live.selectedVehicleId == it.id || live.confirmed || live.auto.holdsVehicle) }
        val journey = companion?.takeIf { it.trackedKey == key }
        val transferLeg = journey?.nextLeg
        val after = info?.index?.let { index -> (index + 1..goalIndex).firstOrNull { !ride.stops[it].cancelled } }?.let { ride.stops[it] }
        fun clock(time: Instant?) = time?.let(::clockTime).orEmpty()
        val local = now.atZone(java.time.ZoneId.of("Europe/Berlin"))
        return mapOf(
            "linie" to ride.departure.line, "ziel" to shortName(goal.name), "richtung" to shortName(ride.departure.destination),
            "einstieg" to shortName(ride.departure.stopName), "steig" to ride.departure.platform,
            "phase" to if (waiting) "Warten" else "Im Bus",
            "status" to when {
                ride.departure.cancelled -> "Bus fällt aus"
                waiting -> liveBoarding?.let { if (it.at.isAfter(now)) "Bus kommt in ${countdownUntil(it.at, now)}" else "Bus wird erwartet" } ?: "Keine Live-Zeit"
                next != null -> "Nächster Halt: ${shortName(next.name)}"
                else -> "Ziel erreicht"
            },
            "quelle" to when {
                ride.demo -> "Demo"; !fresh -> "Veraltet"
                waiting -> if (liveBoarding != null) "Live" else "Fahrplan"
                goal.estimated != null -> "Live"
                else -> "Fahrplan"
            },
            "minuten" to liveMinutes?.toString().orEmpty(),
            "countdown" to liveBoarding?.takeIf { waiting && it.at.isAfter(now) }?.let { countdownUntil(it.at, now) }.orEmpty(),
            "berechnet" to estimate?.minutes?.toString().orEmpty(),
            "abfahrt" to clock(liveBoarding?.at ?: ride.boardingTime().at),
            "abfahrt_plan" to clock(boardingStop?.departure ?: ride.departure.planned),
            "ankunft" to clock(goal.effective(ride.offsetSeconds)), "ankunft_plan" to clock(goal.planned),
            "ankunft_berechnet" to estimate?.takeIf { !waiting }?.let { clock(now.plusSeconds(it.seconds)) }.orEmpty(),
            "verspaetung" to delaySeconds?.takeIf { it >= 60 }?.let { "+${it / 60} Min" }.orEmpty(),
            "verspaetung_minuten" to delaySeconds?.takeIf { it >= 60 }?.let { (it / 60).toString() }.orEmpty(),
            "ausfall" to if (ride.departure.cancelled) "Fällt aus" else "",
            "halt" to next?.name?.let(::shortName).orEmpty(),
            "halt_zeit" to clock(next?.let { if (fresh) it.effective(ride.offsetSeconds) else it.planned }),
            "halt_minuten" to info?.minutes?.toString().orEmpty(),
            "halt_berechnet" to estimate?.nextStopMinutes?.toString().orEmpty(),
            "halt_danach" to after?.name?.let(::shortName).orEmpty(),
            "halte" to info?.index?.takeIf { !waiting }?.let { index -> (index..goalIndex).count { !ride.stops[it].cancelled }.toString() }.orEmpty(),
            "fortschritt" to if (waiting || ride.demo) "" else (ride.progressToTarget(now) * 100).toInt().toString(),
            "im_bus_seit" to clock(ride.boardedAt),
            "umstieg" to journey?.let(JourneyCompanions::transferHint).orEmpty(),
            "umstieg_linie" to transferLeg?.let { it.line.ifBlank { it.lineName } }.orEmpty(),
            "umstieg_zeit" to clock(transferLeg?.origin?.plannedDeparture),
            "umstieg_steig" to transferLeg?.origin?.platform.orEmpty(),
            "umstieg_ziel" to journey?.destinationName?.let(::shortName).orEmpty(),
            "fahrzeug" to bus?.id.orEmpty(), "bus_stand" to clock(bus?.observedAt),
            "bus_entfernung" to bus?.takeIf { waiting }?.let { vehicle -> boardingStop?.point?.distanceTo(vehicle.point) }
                ?.let { if (it < 1000) "${(it / 10).toInt() * 10} m" else "%.1f km".format(java.util.Locale.GERMANY, it / 1000) }.orEmpty(),
            "uhrzeit" to clock(now), "datum" to "%02d.%02d.".format(local.dayOfMonth, local.monthValue),
            "datenstand" to clock(ride.fetchedAt))
    }
}
