package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RideRefreshPolicyTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private fun ride() = DemoRide.create(now.minusSeconds(1000)).copy(demo = false, fetchedAt = now)
    @Test fun `completion requires a last refresh even inside the ordinary polling interval`() {
        val r = ride()
        assertTrue(r.complete(now))
        assertTrue(RideRefreshPolicy.needsRefresh(r, 1000, null, now))
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(r, running = true), null, now))
        assertTrue(RideRefreshPolicy.canFinish(TrackingState(r, running = true), r.terminal.effective(), now))
    }
    @Test fun `late forecasts postpone completion and receive a fresh final check for the new prediction`() {
        val old = ride()
        val changed = old.copy(stops = old.stops.mapIndexed { i, s -> if (i == old.stops.lastIndex)
            s.copy(estimatedArrival = now.plusSeconds(300)) else s })
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(changed, running = true), old.terminal.effective(), now))
        val later = now.plusSeconds(430)
        assertTrue(RideRefreshPolicy.needsRefresh(changed, 1000, old.terminal.effective(), later))
    }
    @Test fun `failed stale and cancelled completion data never automatically terminate the ride`() {
        val r = ride()
        val end = r.terminal.effective()
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(r, running = true, lastRefreshFailed = true), end, now))
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(r.copy(fetchedAt = now.minusSeconds(121)), running = true), end, now))
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(r.copy(departure = r.departure.copy(cancelled = true)), running = true), end, now))
        assertFalse(RideRefreshPolicy.needsRefresh(r, 1000, end, now))
        assertTrue(RideRefreshPolicy.needsRefresh(r, 30_000, end, now))
    }
    @Test fun `a missing departed monitor does not invalidate independent live trip estimates`() {
        val base = ride()
        val delayed = base.copy(departure = base.departure.copy(estimated = base.departure.planned.plusSeconds(120)))
        assertTrue(RideRefreshPolicy.usesMonitorBaseline(delayed))
        val live = delayed.copy(stops = delayed.stops.mapIndexed { i, s -> if (i == delayed.stops.lastIndex)
            s.copy(estimatedArrival = now.plusSeconds(120)) else s })
        assertFalse(RideRefreshPolicy.usesMonitorBaseline(live))
        assertFalse(RideRefreshPolicy.usesMonitorBaseline(base))
        assertFalse(RideRefreshPolicy.usesMonitorBaseline(base.copy(departure = base.departure.copy(estimated = base.departure.planned))))
    }
    @Test fun `a waiting boarding estimate remains dependent on its actual monitor source`() {
        val base = ride().copy(boarded = false)
        val monitor = base.copy(departure = base.departure.copy(estimated = now.plusSeconds(60)))
        assertTrue(RideRefreshPolicy.usesMonitorBaseline(monitor))
        val direct = monitor.copy(stops = monitor.stops.mapIndexed { i, s -> if (i == monitor.boardingIndex)
            s.copy(estimatedArrival = now.plusSeconds(60)) else s })
        assertFalse(RideRefreshPolicy.usesMonitorBaseline(direct))
    }
    @Test fun `a fresh earlier selected target cannot validate an old terminal forecast`() {
        val r = ride().let { base -> base.copy(
            departure = base.departure.copy(estimated = base.departure.planned.plusSeconds(60), observedAt = now.minusSeconds(180)),
            stops = base.stops.mapIndexed { i, stop -> if (i == base.boardingIndex + 1)
                stop.copy(estimatedArrival = now.minusSeconds(300)) else stop }) }.withExitTarget(1)
        assertFalse(r.isStale(now))
        assertFalse(RideRefreshPolicy.canFinish(TrackingState(r, running = true), r.terminal.effective(r.offsetSeconds), now))
    }

}
