package de.luca.mitfahrt.data

import de.luca.mitfahrt.watch.watchSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchSnapshotTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")

    @Test fun `waiting watch receives the reported arrival and independent data timestamp`() {
        val base = DemoRide.create(now)
        val boarding = base.stops[0].copy(estimatedArrival = now.plusSeconds(180))
        val ride = base.copy(demo = false, boarded = false, stops = listOf(boarding) + base.stops.drop(1))
        val snapshot = TrackingState(ride, running = true).watchSnapshot(now.plusSeconds(20))
        assertEquals(now.plusSeconds(180), snapshot.boardingAt)
        assertEquals(ride.fetchedAt, snapshot.fetchedAt)
        assertEquals(now.plusSeconds(20), snapshot.sentAt)
        assertTrue(snapshot.boardingIsArrival)
        assertFalse(snapshot.boarded)
        assertEquals("VRR live", snapshot.source)
    }
    @Test fun `watch preserves extrapolated source failed refresh and demo status`() {
        val base = DemoRide.create(now)
        val ride = base.copy(demo = false, departure = base.departure.copy(estimated = base.departure.planned.plusSeconds(180)))
        val snapshot = TrackingState(ride, running = true, lastRefreshFailed = true).watchSnapshot(now)
        assertEquals("Geschätzt", snapshot.source)
        assertTrue(snapshot.stale(now))
        assertEquals(base.terminal.planned!!.plusSeconds(180), snapshot.terminalAt)
        assertTrue(TrackingState(base, running = true).watchSnapshot(now).demo)
    }
    @Test fun `no active journey clears the watch instead of sending the last report journey`() {
        val snapshot = TrackingState(monitoring = true, running = true).watchSnapshot(now)
        assertFalse(snapshot.active)
        assertEquals("", snapshot.line)
    }
    @Test fun `a fresh trip fetch cannot revive an old boarding forecast for alerts`() {
        val base = DemoRide.create(now)
        val ride = base.copy(demo = false, boarded = false, departure = base.departure.copy(
            estimated = now.plusSeconds(50), observedAt = now.minusSeconds(180)))
        val snapshot = TrackingState(ride, running = true).watchSnapshot(now)
        assertTrue(snapshot.stale(now))
        assertNull(snapshot.boardingAt)
        assertEquals("Keine Live-Zeit", snapshot.source)
        assertFalse(de.luca.mitfahrt.shared.ArrivalAlert.due(snapshot, now))
    }

    @Test fun `fresh timetable is transmitted without a boarding countdown`() {
        val ride = DemoRide.create(now).copy(demo = false, boarded = false)
        val snapshot = TrackingState(ride, running = true).watchSnapshot(now)
        assertNull(snapshot.boardingAt)
        assertEquals("Keine Live-Zeit", snapshot.source)
        assertFalse(snapshot.stale(now))
        assertEquals(ride.fetchedAt, snapshot.fetchedAt)
        assertEquals(snapshot, de.luca.mitfahrt.shared.WatchProtocol.decode(de.luca.mitfahrt.shared.WatchProtocol.encode(snapshot)))
    }

    @Test fun `failed or paused live waiting data never retains a boarding countdown`() {
        val base = DemoRide.create(now)
        val ride = base.copy(demo = false, boarded = false, departure = base.departure.copy(
            estimated = now.plusSeconds(60), observedAt = now))
        assertEquals(now.plusSeconds(60), TrackingState(ride, running = true).watchSnapshot(now).boardingAt)
        listOf(TrackingState(ride, running = false), TrackingState(ride, running = true, lastRefreshFailed = true))
            .forEach { state ->
                val snapshot = state.watchSnapshot(now)
                assertNull(snapshot.boardingAt)
                assertEquals("Keine Live-Zeit", snapshot.source)
                assertTrue(snapshot.stale(now))
            }
    }

    @Test fun `cancelled boarding suppresses an otherwise fresh live forecast`() {
        val base = DemoRide.create(now)
        val ride = base.copy(demo = false, boarded = false, stops = base.stops.mapIndexed { i, stop ->
            if (i == 0) stop.copy(estimatedArrival = now.plusSeconds(30), cancelled = true) else stop })
        assertNull(TrackingState(ride, running = true).watchSnapshot(now).boardingAt)
    }

}
