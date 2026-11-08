package de.luca.mitfahrt.watch

import de.luca.mitfahrt.shared.WatchCommand
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WatchCommandPolicyTest {
    private val now = Instant.parse("2026-11-08T12:00:00Z")
    private val start = WatchCommand("start", "departure", "old-journey", "route/school")

    @Test fun viewingAStopCannotStartARide() {
        assertFalse(allowed(start.copy(action = "browse")))
    }

    @Test fun confirmationCanReplaceOnlyTheJourneyVisibleOnWatch() {
        assertTrue(allowed(start))
        assertFalse(allowed(start, current = "newer-journey"))
        assertFalse(allowed(start.copy(journey = "")))
    }

    @Test fun firstJourneyCanBeStartedAfterExplicitConfirmation() {
        assertTrue(allowed(start.copy(journey = ""), current = ""))
    }

    @Test fun staleOrFutureDepartureCacheDoesNotStartTrip() {
        assertTrue(allowed(start, at = now.minusSeconds(120)))
        assertFalse(allowed(start, at = now.minusSeconds(121)))
        assertFalse(allowed(start, at = now.plusSeconds(1)))
    }

    @Test fun browsingAnotherRouteInvalidatesTheOldConfirmation() {
        assertFalse(allowed(start, selected = "route/home"))
        assertFalse(allowed(start.copy(contextId = "")))
        assertFalse(allowed(start.copy(id = "other-departure")))
    }

    @Test fun actionsFromAnOldJourneyCannotAlterTheCurrentOne() {
        assertTrue(WatchCommandPolicy.sameJourney(WatchCommand("board", journey = "trip-a"), "trip-a"))
        assertFalse(WatchCommandPolicy.sameJourney(WatchCommand("stop", journey = "trip-a"), "trip-b"))
        assertFalse(WatchCommandPolicy.sameJourney(WatchCommand("arrival"), ""))
    }

    private fun allowed(command: WatchCommand, current: String = "old-journey", selected: String = "route/school",
        at: Instant = now): Boolean = WatchCommandPolicy.startAllowed(command, current, selected, at, now, setOf("departure"))
}
