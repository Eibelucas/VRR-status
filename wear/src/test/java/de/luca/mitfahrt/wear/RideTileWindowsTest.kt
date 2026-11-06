package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RideTileWindowsTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val ride = WatchRide(now, active = true, running = true, fetchedAt = now, boardingAt = now.plusSeconds(190))

    @Test fun `countdown advances at the next minute without another phone update`() {
        val windows = rideTileWindows(ride, now)
        assertEquals(3, windows.size)
        assertEquals(now.toEpochMilli(), windows.first().startMillis)
        assertEquals(now.plusSeconds(10).toEpochMilli(), windows[0].endMillis)
        assertEquals(now.plusSeconds(70).toEpochMilli(), windows[1].endMillis)
        assertEquals(now.plusSeconds(121).toEpochMilli(), windows.last().endMillis)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.endMillis, b.startMillis) }
    }

    @Test fun `source age expires even if the connection heartbeat is fresh`() {
        val windows = rideTileWindows(ride.copy(fetchedAt = now.minusSeconds(90)), now)
        assertEquals(now.plusSeconds(31).toEpochMilli(), windows.last().endMillis)
        assertTrue(rideTileWindows(ride.copy(fetchedAt = now.minusSeconds(121)), now).isEmpty())
    }

    @Test fun `paused ended and invalid future clock data cannot produce a live countdown`() {
        listOf(ride.copy(running = false), ride.copy(active = false), ride.copy(refreshFailed = true),
            ride.copy(sentAt = now.plusSeconds(61)), ride.copy(fetchedAt = null))
            .forEach { assertTrue(rideTileWindows(it, now).isEmpty()) }
        assertTrue(rideTileWindows(null, now).isEmpty())
    }

    @Test fun `a passed target never creates an unbounded timeline or a negative countdown`() {
        val windows = rideTileWindows(ride.copy(boardingAt = now.minusSeconds(10)), now)
        assertEquals(1, windows.size)
        assertTrue(windows.all { it.endMillis > it.startMillis })
    }
}
