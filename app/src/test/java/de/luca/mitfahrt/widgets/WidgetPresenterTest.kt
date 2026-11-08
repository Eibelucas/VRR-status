package de.luca.mitfahrt.widgets

import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.Ride
import de.luca.mitfahrt.data.RideStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WidgetPresenterTest {
    private val now = Instant.parse("2026-11-09T07:00:00Z") // 08:00 in Mönchengladbach
    private val stopConfig = WidgetConfig(WidgetKind.STOP, "de:05116:7000", "Mönchengladbach Hbf")
    private val routeConfig = WidgetConfig(WidgetKind.ROUTE, "route-1", "Zur Schule")
    private val activeConfig = WidgetConfig(WidgetKind.ACTIVE)

    private fun departure(
        trip: String = "1", plannedIn: Long = 600, estimatedIn: Long? = null,
        observedAgo: Long? = 10, cancelled: Boolean = false
    ) = Departure("new:017", "017", "Mönchengladbach Rheydt Hbf", "de:05116:7000:1:1", "Hbf", trip,
        now.plusSeconds(plannedIn), estimatedIn?.let { now.plusSeconds(it) }, "2", cancelled,
        listOf("Umleitung"), observedAgo?.let { now.minusSeconds(it) })

    private fun snapshot(vararg departures: Departure, fetchedAgo: Long = 10) =
        WidgetSnapshot(fetchedAt = now.minusSeconds(fetchedAgo), departures = departures.toList())

    @Test fun `missing configuration asks for setup`() {
        val model = WidgetPresenter.present(null, WidgetSnapshot(), now)
        assertEquals(WidgetStatus.NOT_CONFIGURED, model.status)
        assertEquals(WidgetAction.Configure, model.open)
        assertTrue(model.rows.isEmpty())
    }

    @Test fun `fresh live prediction becomes a countdown`() {
        val row = WidgetPresenter.present(stopConfig, snapshot(departure(estimatedIn = 840)), now).rows.single()
        assertEquals(TimeKind.LIVE, row.timeKind)
        assertEquals("14 min", row.time)
        assertEquals("live +4", row.timeLabel)
        assertEquals("017", row.badge)
        assertEquals("Rheydt Hbf", row.title)
    }

    @Test fun `older prediction is shown as clock time and never as countdown`() {
        val old = departure(estimatedIn = 840, observedAgo = 300)
        val row = WidgetPresenter.present(stopConfig, snapshot(old, fetchedAgo = 300), now).rows.single()
        assertEquals(TimeKind.PROGNOSIS, row.timeKind)
        assertEquals("08:14", row.time)
        assertFalse(row.time.contains("min"))
    }

    @Test fun `stale data falls back to the timetable and says so`() {
        val old = departure(plannedIn = 1800, estimatedIn = 2000, observedAgo = 3600)
        val model = WidgetPresenter.present(stopConfig, snapshot(old, fetchedAgo = 3600), now)
        assertEquals(WidgetStatus.STALE, model.status)
        assertTrue(model.statusLine!!.startsWith("Veraltet"))
        val row = model.rows.single()
        assertEquals(TimeKind.SCHEDULE, row.timeKind)
        assertEquals("08:30", row.time)
        assertEquals("Plan", row.timeLabel)
    }

    @Test fun `departure without prediction stays recognizable as timetable time`() {
        val row = WidgetPresenter.present(stopConfig, snapshot(departure()), now).rows.single()
        assertEquals(TimeKind.SCHEDULE, row.timeKind)
        assertEquals("08:10", row.time)
        assertEquals("Plan", row.timeLabel)
        assertTrue(row.description.contains("laut Fahrplan"))
    }

    @Test fun `cancellation is shown as such`() {
        val row = WidgetPresenter.present(stopConfig, snapshot(departure(estimatedIn = 600, cancelled = true)), now).rows.single()
        assertEquals(TimeKind.CANCELLED, row.timeKind)
        assertEquals("Ausfall", row.timeLabel)
        assertEquals("08:10", row.time)
    }

    @Test fun `departed buses disappear`() {
        val model = WidgetPresenter.present(stopConfig, snapshot(departure("gone", plannedIn = -300), departure("next")), now)
        assertEquals(1, model.rows.size)
        assertEquals("08:10", model.rows.single().time)
    }

    @Test fun `offline keeps the last data visible with a clear state`() {
        val snap = snapshot(departure(estimatedIn = 700, observedAgo = 400), fetchedAgo = 400)
            .copy(failure = FetchFailure.OFFLINE, failedAt = now)
        val model = WidgetPresenter.present(stopConfig, snap, now)
        assertEquals(WidgetStatus.OFFLINE, model.status)
        assertTrue(model.statusLine!!.startsWith("Keine Verbindung"))
        assertTrue(model.statusLine!!.contains("Stand 07:53"))
        assertTrue(model.rows.none { it.timeKind == TimeKind.LIVE })
    }

    @Test fun `source failure without data shows an error message`() {
        val model = WidgetPresenter.present(stopConfig, WidgetSnapshot(failure = FetchFailure.SOURCE, failedAt = now), now)
        assertEquals(WidgetStatus.ERROR, model.status)
        assertNotNull(model.message)
    }

    @Test fun `deleted route asks for a new selection`() {
        val model = WidgetPresenter.present(routeConfig, WidgetSnapshot(targetMissing = true), now)
        assertEquals(WidgetStatus.TARGET_MISSING, model.status)
        assertEquals(WidgetAction.Configure, model.open)
    }

    @Test fun `route rows open a preview and the header opens departures`() {
        val model = WidgetPresenter.present(routeConfig, snapshot(departure()), now)
        assertEquals(WidgetAction.Open(WidgetKind.ROUTE, "route-1", "departures"), model.open)
        assertEquals(WidgetAction.Open(WidgetKind.ROUTE, "route-1", "preview"), model.rows.single().action)
    }

    @Test fun `empty route board explains the empty result`() {
        val model = WidgetPresenter.present(routeConfig, WidgetSnapshot(fetchedAt = now), now)
        assertEquals(WidgetStatus.EMPTY, model.status)
        assertEquals("Keine passende Abfahrt in der nächsten Stunde", model.message)
    }

    @Test fun `detail line is only produced for the detailed level`() {
        val dep = departure(estimatedIn = 700)
        assertNull(WidgetPresenter.present(stopConfig, snapshot(dep), now).rows.single().detail)
        val detailed = WidgetPresenter.present(stopConfig.copy(info = InfoLevel.DETAILED), snapshot(dep), now).rows.single()
        assertEquals("Steig 2 · Plan 08:10 · Umleitung", detailed.detail)
    }

    @Test fun `countdown is re-rendered before the live window closes`() {
        val model = WidgetPresenter.present(stopConfig, snapshot(departure(estimatedIn = 840)), now)
        val next = model.nextRenderAt!!
        assertTrue(next.isAfter(now))
        assertTrue(next.epochSecond - now.epochSecond <= 111)
    }

    // --- Active ride ---

    private fun ride(boarded: Boolean = false, fetchedAgo: Long = 20, estimate: Boolean = true, demo: Boolean = false): Ride {
        val planned = now.plusSeconds(300)
        val fetched = now.minusSeconds(fetchedAgo)
        val dep = Departure("new:017", "017", "Rheydt Hbf", "a", "Hbf", "t1", planned,
            if (estimate) planned.plusSeconds(120) else null, "1", observedAt = fetched)
        return Ride(dep, listOf(
            RideStop("a", "Mönchengladbach Hbf", null, planned, planned,
                estimatedDeparture = if (estimate) planned.plusSeconds(120) else null),
            RideStop("b", "Bismarckplatz", null, now.plusSeconds(600), now.plusSeconds(600),
                estimatedArrival = if (estimate) now.plusSeconds(720) else null),
            RideStop("c", "Rheydt Hbf", null, now.plusSeconds(1200), now.plusSeconds(1200),
                estimatedArrival = if (estimate) now.plusSeconds(1320) else null)
        ), 0, fetched, demo = demo, boarded = boarded)
    }

    @Test fun `no tracked ride shows a calm empty state without map`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now, active = ActiveRideInput(null, false, false))
        assertEquals(WidgetStatus.EMPTY, model.status)
        assertEquals(WidgetAction.Open(WidgetKind.ACTIVE, null, "ride"), model.open)
        assertNull(model.mapAction)
    }

    @Test fun `fresh tracked boarding gets a live countdown`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now, active = ActiveRideInput(ride(), true, false))
        val row = model.rows.first()
        assertEquals(TimeKind.LIVE, row.timeKind)
        assertEquals("7 min", row.time)
        assertEquals("Einstieg Hbf", row.title)
        assertEquals(WidgetAction.Open(WidgetKind.ACTIVE, null, "map"), model.mapAction)
    }

    @Test fun `failed tracking refresh never shows a countdown`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now, active = ActiveRideInput(ride(), true, true))
        assertEquals(WidgetStatus.ERROR, model.status)
        assertTrue(model.rows.none { it.timeKind == TimeKind.LIVE })
    }

    @Test fun `old tracked ride is marked stale and uses the timetable`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now,
            active = ActiveRideInput(ride(fetchedAgo = 3600), false, false))
        assertEquals(WidgetStatus.STALE, model.status)
        assertEquals(TimeKind.SCHEDULE, model.rows.first().timeKind)
        assertEquals("08:05", model.rows.first().time)
    }

    @Test fun `boarded ride shows target and next stop`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now,
            active = ActiveRideInput(ride(boarded = true).copy(gpsPassedIndex = 0), true, false))
        assertEquals("Ziel Rheydt Hbf", model.rows[0].title)
        assertEquals("22 min", model.rows[0].time)
        assertEquals("Nächster Halt Bismarckplatz", model.rows[1].title)
        assertNull(model.rows[1].badge)
    }

    @Test fun `ride without prediction keeps timetable times`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now,
            active = ActiveRideInput(ride(estimate = false), true, false))
        assertEquals(TimeKind.SCHEDULE, model.rows.first().timeKind)
        assertEquals("Plan", model.rows.first().timeLabel)
    }

    @Test fun `demo ride is labeled and has no map action`() {
        val model = WidgetPresenter.present(activeConfig, WidgetSnapshot(), now,
            active = ActiveRideInput(ride(demo = true), true, false))
        assertTrue(model.statusLine!!.startsWith("Demo"))
        assertNull(model.mapAction)
        assertTrue(model.rows.none { it.timeKind == TimeKind.LIVE })
    }

    @Test fun `display refresh is used only for the same stop sequence`() {
        val tracked = ride(fetchedAgo = 600)
        val fetched = ride(fetchedAgo = 5)
        assertEquals(fetched.fetchedAt, mergeDisplayRide(tracked, fetched)?.fetchedAt)
        val diverted = fetched.copy(stops = fetched.stops.take(2) + RideStop("x", "Umleitung", null, null, null) + fetched.stops.last())
        assertNull(mergeDisplayRide(tracked, diverted))
        assertNull(mergeDisplayRide(tracked.copy(demo = true), fetched))
    }

    @Test fun `newer display refresh makes a stale tracked ride live again`() {
        val tracked = ride(fetchedAgo = 900)
        val snap = WidgetSnapshot(fetchedAt = now.minusSeconds(5), ride = mergeDisplayRide(tracked, ride(fetchedAgo = 5)))
        val model = WidgetPresenter.present(activeConfig, snap, now, active = ActiveRideInput(tracked, false, false))
        assertEquals(WidgetStatus.READY, model.status)
        assertEquals(TimeKind.LIVE, model.rows.first().timeKind)
        assertTrue(model.statusLine!!.startsWith("Verfolgung pausiert"))
    }
}
