package de.luca.mitfahrt.shared

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ArrivalAlertTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val ride = WatchRide(now, active = true, running = true, fetchedAt = now, boardingAt = now.plusSeconds(60))
    @Test fun `one minute window excludes past and distant predictions`() {
        assertTrue(ArrivalAlert.due(ride, now))
        assertTrue(ArrivalAlert.due(ride.copy(boardingAt = now.plusSeconds(1)), now))
        assertFalse(ArrivalAlert.due(ride.copy(boardingAt = now.plusSeconds(61)), now))
        assertFalse(ArrivalAlert.due(ride.copy(boardingAt = now), now))
    }
    @Test fun `delay changes move the warning window instead of using the old prediction`() {
        assertFalse(ArrivalAlert.due(ride.copy(boardingAt = now.plusSeconds(360)), now))
        assertTrue(ArrivalAlert.due(ride.copy(boardingAt = now.plusSeconds(360), sentAt = now.plusSeconds(300), fetchedAt = now.plusSeconds(300)), now.plusSeconds(300)))
    }
    @Test fun `cancelled stale demo boarded paused and inactive journeys never alert`() {
        listOf(ride.copy(cancelled = true), ride.copy(demo = true), ride.copy(boarded = true), ride.copy(running = false),
            ride.copy(active = false), ride.copy(refreshFailed = true), ride.copy(fetchedAt = now.minusSeconds(121)))
            .forEach { assertFalse(ArrivalAlert.due(it, now)) }
    }
    @Test fun `watch rejects old malformed and oversized events`() {
        val bytes = ArrivalAlert.encode("trip-1", now)
        assertEquals("trip-1", ArrivalAlert.recentId(bytes, now.plusSeconds(20)))
        assertNull(ArrivalAlert.recentId(ArrivalAlert.encode("late", now, now.plusSeconds(5)), now.plusSeconds(6)))
        assertNull(ArrivalAlert.recentId(bytes, now.plusSeconds(31)))
        assertNull(ArrivalAlert.recentId(bytes, now.minusSeconds(6)))
        assertNull(ArrivalAlert.recentId(ByteArray(3000), now))
        assertNull(ArrivalAlert.recentId("broken".toByteArray(), now))
    }
}
