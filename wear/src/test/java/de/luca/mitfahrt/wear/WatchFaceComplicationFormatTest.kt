package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchFaceMapSnapshot
import de.luca.mitfahrt.shared.WatchRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WatchFaceComplicationFormatTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun formatsBoardedNextStopAndArrivalEta() {
        val now = Instant.parse("2026-11-20T13:20:00Z") // 14:20 CET
        val snapshot = WatchFaceMapSnapshot(
            sentAt = now,
            active = true,
            running = true,
            line = "009",
            destination = "Halt Sued",
            nextStop = "Halt Mitte",
            nextStopAt = now.plusSeconds(120), // 2 min
            arrivalAt = now.plusSeconds(900), // 14:35 CET
            boarded = true,
            remainingStops = 4,
            totalStops = 8,
            progressPercent = 50
        )

        val copy = WatchFaceTransitFormat.format(snapshot, null, now, berlin)
        assertTrue(copy.active)
        assertEquals("Halt Mitte · in 2 Min", copy.primaryText)
        assertEquals("009 · Ankunft 14:35 · 4 Halte", copy.secondaryTitle)
        assertEquals("2 Min", copy.shortText)
        assertEquals("009", copy.shortTitle)
    }

    @Test
    fun formatsWaitingBoardingCountdownAndArrival() {
        val now = Instant.parse("2026-11-20T13:20:00Z") // 14:20 CET
        val snapshot = WatchFaceMapSnapshot(
            sentAt = now,
            active = true,
            running = true,
            line = "017",
            destination = "Halt Nord",
            nextStop = "Halt 1",
            nextStopAt = now.plusSeconds(240), // 4 min
            arrivalAt = now.plusSeconds(1200), // 14:40 CET
            boarded = false
        )

        val copy = WatchFaceTransitFormat.format(snapshot, null, now, berlin)
        assertTrue(copy.active)
        assertEquals("017 in 4 Min · Halt 1", copy.primaryText)
        assertEquals("Ankunft 14:40 · Halt Nord", copy.secondaryTitle)
        assertEquals("4 Min", copy.shortText)
    }

    @Test
    fun fallsBackToWatchRideWhenMapSnapshotMissing() {
        val now = Instant.parse("2026-11-20T13:20:00Z")
        val ride = WatchRide(
            sentAt = now,
            active = true,
            running = true,
            line = "006",
            destination = "Halt Sued",
            nextStop = "Halt 3",
            boarded = true,
            terminalAt = now.plusSeconds(600) // 14:30 CET
        )

        val copy = WatchFaceTransitFormat.format(null, ride, now, berlin)
        assertTrue(copy.active)
        assertEquals("Halt 3", copy.primaryText)
        assertEquals("006 · Ankunft 14:30", copy.secondaryTitle)
    }

    @Test
    fun formatsIdleStateCleanlyWithoutEmDash() {
        val now = Instant.parse("2026-11-20T13:20:00Z")
        val copy = WatchFaceTransitFormat.format(null, null, now, berlin)
        assertFalse(copy.active)
        assertEquals("Keine aktive Fahrt", copy.primaryText)
        assertEquals("Mitfahrt öffnen", copy.secondaryTitle)
        assertEquals("Keine", copy.shortText)
        assertEquals("Fahrt", copy.shortTitle)
        assertFalse(copy.primaryText.contains("—"))
        assertFalse(copy.secondaryTitle.contains("—"))
    }
}
