package de.luca.mitfahrt.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchFaceMapProtocolTest {

    @Test
    fun roundTripsActiveRideMapSnapshot() {
        val now = Instant.ofEpochMilli(1_760_000_000_000L)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        val original = WatchFaceMapSnapshot(
            sentAt = now,
            active = true,
            running = true,
            line = "009",
            destination = "Halt Sued",
            nextStop = "Halt Mitte",
            nextStopAt = now.plusSeconds(120),
            arrivalAt = now.plusSeconds(780),
            boarded = true,
            remainingStops = 4,
            totalStops = 9,
            progressPercent = 56,
            mapPng = png
        )

        val encoded = WatchFaceMapProtocol.encode(original)
        val decoded = WatchFaceMapProtocol.decode(encoded)

        assertEquals(original, decoded)
        assertArrayEquals(png, decoded.mapPng)
        assertFalse(decoded.stale(now.plusSeconds(60)))
        assertTrue(decoded.stale(now.plusSeconds(240)))
    }

    @Test
    fun dropsOversizedPngGracefullyWhilePreservingTransitFields() {
        val now = Instant.ofEpochMilli(1_760_000_000_000L)
        val hugePng = ByteArray(WatchFaceMapProtocol.MAX_PNG_BYTES + 1024) { 7 }
        val original = WatchFaceMapSnapshot(
            sentAt = now,
            active = true,
            running = true,
            line = "017",
            destination = "Halt Nord",
            nextStop = "Halt 2",
            nextStopAt = now.plusSeconds(90),
            arrivalAt = now.plusSeconds(420),
            boarded = true,
            remainingStops = 2,
            totalStops = 5,
            progressPercent = 60,
            mapPng = hugePng
        )

        val decoded = WatchFaceMapProtocol.decode(WatchFaceMapProtocol.encode(original))
        assertEquals(0, decoded.mapPng.size)
        assertEquals("Halt 2", decoded.nextStop)
        assertEquals("017", decoded.line)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownProtocolVersion() {
        val bad = WatchFaceMapProtocol.encode(WatchFaceMapSnapshot(Instant.ofEpochMilli(1_000L)))
        bad[3] = 99
        WatchFaceMapProtocol.decode(bad)
    }
}
