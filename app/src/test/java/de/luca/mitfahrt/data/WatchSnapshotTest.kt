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
        assertFalse(de.luca.mitfahrt.shared.ArrivalAlert.due(snapshot, now))
    }

}
