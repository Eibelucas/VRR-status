package de.luca.mitfahrt.shared

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchProtocolTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val ride = WatchRide(now, active = true, running = true, fetchedAt = now, line = "006",
        destination = "Rheydt Hbf", nextStop = "Marienplatz", boardingAt = now.plusSeconds(180),
        boardingIsArrival = true, terminalAt = now.plusSeconds(1200), source = "VRR live", delay = "+3 Min")

    @Test fun `phone and watch roundtrip times flags and German labels`() {
        assertEquals(ride, WatchProtocol.decode(WatchProtocol.encode(ride)))
        assertEquals(ride.copy(boarded = true, demo = true, cancelled = true, refreshFailed = true),
            WatchProtocol.decode(WatchProtocol.encode(ride.copy(boarded = true, demo = true, cancelled = true, refreshFailed = true))))
    }
    @Test fun `ended journey replaces active trip with an empty snapshot`() {
        val ended = WatchProtocol.decode(WatchProtocol.encode(WatchRide(now)))
        assertFalse(ended.active)
        assertEquals("", ended.line)
        assertNull(ended.boardingAt)
    }
    @Test fun `cached heartbeat cannot make old forecasts live`() {
        assertFalse(ride.stale(now.plusSeconds(90)))
        assertTrue(ride.copy(sentAt = now.plusSeconds(130)).stale(now.plusSeconds(130)))
        assertTrue(ride.copy(fetchedAt = now.plusSeconds(130)).stale(now.plusSeconds(130)))
        assertTrue(ride.copy(running = false).stale(now))
        assertTrue(ride.copy(refreshFailed = true).stale(now))
        assertTrue(ride.copy(fetchedAt = null).stale(now))
        assertTrue(ride.copy(sentAt = now.plusSeconds(90)).stale(now))
    }
    @Test fun `truncated unsupported oversized and trailing payloads fail closed`() {
        val bytes = WatchProtocol.encode(ride)
        listOf(bytes.copyOf(10), bytes.copyOf().apply { this[3] = 2 }, ByteArray(9000), bytes + 1.toByte()).forEach {
            assertTrue(runCatching { WatchProtocol.decode(it) }.isFailure)
        }
    }
    @Test fun `unexpectedly long labels cannot exceed the Data Layer payload bound`() {
        val decoded = WatchProtocol.decode(WatchProtocol.encode(ride.copy(destination = "ü".repeat(6000))))
        assertEquals(256, decoded.destination.length)
    }
}
