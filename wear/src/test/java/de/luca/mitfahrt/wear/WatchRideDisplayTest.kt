package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchRideDisplayTest {
    private val now = Instant.parse("2026-11-07T08:00:00Z")
    private fun waiting() = WatchRide(now, active = true, running = true, fetchedAt = now)

    @Test fun `waiting without live arrival says no live time instead of expected now`() {
        val display = rideTimeDisplay(waiting(), now)
        assertEquals("–", display.value)
        assertEquals("Keine Live-Zeit", display.detail)
        assertEquals(display, rideTimeDisplay(waiting(), now, minutesOnly = true))
    }

    @Test fun `fresh live waiting time has seconds in app and rounded minutes in tile`() {
        val ride = waiting().copy(boardingAt = now.plusSeconds(121), boardingIsArrival = true)
        assertEquals(RideTimeDisplay("2:01", "bis dein Bus kommt"), rideTimeDisplay(ride, now))
        assertEquals(RideTimeDisplay("3 Min", "bis dein Bus kommt"), rideTimeDisplay(ride, now, minutesOnly = true))
    }

    @Test fun `only a known passed live target may say expected`() {
        assertEquals("Erwartet", rideTimeDisplay(waiting().copy(boardingAt = now.minusSeconds(1)), now).value)
        assertNotEquals("Erwartet", rideTimeDisplay(waiting(), now).value)
    }

    @Test fun `cancelled status takes priority over stale and missing time`() {
        val cancelled = waiting().copy(cancelled = true, fetchedAt = now.minusSeconds(200))
        assertEquals(RideTimeDisplay("Fällt aus", "Andere Fahrt wählen"), rideTimeDisplay(cancelled, now))
    }

    @Test fun `stale paused and expired snapshots never retain a live countdown`() {
        val ride = waiting().copy(boardingAt = now.plusSeconds(90))
        assertEquals(RideTimeDisplay("–", "Daten veraltet"), rideTimeDisplay(ride.copy(fetchedAt = now.minusSeconds(121)), now))
        assertEquals(RideTimeDisplay("–", "Fahrt pausiert"), rideTimeDisplay(ride.copy(running = false), now))
        assertEquals(RideTimeDisplay("–", "Daten veraltet"), rideTimeDisplay(ride, now, expired = true))
    }

    @Test fun `boarded missing destination time remains distinct from missing boarding time`() {
        assertEquals(RideTimeDisplay("–", "Ankunft unbekannt"), rideTimeDisplay(waiting().copy(boarded = true), now))
        assertEquals(RideTimeDisplay("2 Min", "bis zum Ziel"),
            rideTimeDisplay(waiting().copy(boarded = true, terminalAt = now.plusSeconds(90)), now))
    }
}
