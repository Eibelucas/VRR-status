package de.luca.mitfahrt.shared

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchCompanionTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @Test fun savedRouteAndPublicStopLibraryRetainsExplicitSelectionAndSettings() {
        val value = WatchLibrary(now, listOf(WatchEntry("route", "school", "Zur Schule", "Hehn → Hbf"),
            WatchEntry("stop", "de:05116:123", "Hehn")), "route/school",
            listOf(WatchDeparture("journey/1", "015", "Hbf", now.plusSeconds(60), true)),
            listOf(WatchTarget(8, "Hauptbahnhof")), mapOf("watch_start" to "routes", "journey_key" to "active/1"),
            "Abfahrt auswählen", WatchTile("route", "school", "Zur Schule", "015", "Hbf", now.plusSeconds(60), true, now),
            WatchRide(now, active = true, line = "015", destination = "Hbf"))
        assertEquals(value, WatchCompanion.decode(WatchCompanion.encode(value)))
    }

    @Test fun commandCarriesTheJourneyAndBrowsedContextTheUserConfirmed() {
        val value = WatchCommand("start", "departure/1", "currently-active/2", "route/school")
        assertEquals(value, WatchCompanion.decodeCommand(WatchCompanion.encodeCommand(value)))
    }

    @Test fun onlyKnownActionsCanBeSent() {
        assertThrows(IllegalArgumentException::class.java) { WatchCompanion.encodeCommand(WatchCommand("delete-everything")) }
    }

    @Test fun malformedAndTrailingPayloadsAreRejected() {
        val bytes = WatchCompanion.encode(WatchLibrary(now))
        assertThrows(Exception::class.java) { WatchCompanion.decode(bytes.copyOf(8)) }
        assertThrows(IllegalArgumentException::class.java) { WatchCompanion.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { WatchCompanion.decode(ByteArray(WatchCompanion.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { WatchCompanion.decodeCommand(byteArrayOf(0, 0, 0, 1)) }
    }

    @Test fun libraryWithoutOptionalTileStillDecodes() {
        val original = WatchLibrary(now, listOf(WatchEntry("stop", "123", "Hehn")))
        val payload = WatchCompanion.encode(original)
        assertEquals(original, WatchCompanion.decode(payload.copyOf(payload.size - 1)))
    }

    @Test fun publicLibraryIsBoundedBeforeSendingToSmallWatch() {
        val value = WatchLibrary(now, List(100) { WatchEntry("stop", "$it", "Haltestelle $it") },
            departures = List(30) { WatchDeparture("$it", "015", "Hbf", now, false) })
        val decoded = WatchCompanion.decode(WatchCompanion.encode(value))
        assertEquals(60, decoded.entries.size)
        assertEquals(12, decoded.departures.size)
    }
}
