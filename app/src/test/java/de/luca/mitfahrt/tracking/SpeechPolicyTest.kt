package de.luca.mitfahrt.tracking

import de.luca.mitfahrt.data.*
import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test

/** Synthetic rides; the text-to-speech engine itself is not part of these tests. */
class SpeechPolicyTest {
    private val quiet = AudioSituation(headphones = false, inCall = false, doNotDisturb = false)

    @Test fun `headphone mode stays silent without headphones`() {
        assertFalse(SpeechPolicy.allowed(SpeechRoute.HEADPHONES, quiet))
        assertTrue(SpeechPolicy.allowed(SpeechRoute.HEADPHONES, quiet.copy(headphones = true)))
        assertFalse("never during a call", SpeechPolicy.allowed(SpeechRoute.HEADPHONES, quiet.copy(headphones = true, inCall = true)))
        assertFalse(SpeechPolicy.allowed(SpeechRoute.OFF, quiet.copy(headphones = true)))
    }

    @Test fun `loud speaker respects do not disturb`() {
        assertTrue(SpeechPolicy.allowed(SpeechRoute.ALWAYS, quiet))
        assertFalse(SpeechPolicy.allowed(SpeechRoute.ALWAYS, quiet.copy(doNotDisturb = true)))
        assertTrue(SpeechPolicy.allowed(SpeechRoute.ALWAYS, quiet.copy(doNotDisturb = true, headphones = true)))
    }

    private val ride = RouteFixtures.ride(boarded = false, delaySeconds = 0L, fetchedAt = T0).board("auto", T0)
    /** Live forecasts fetched shortly before [now]. */
    private fun at(now: java.time.Instant, base: Ride = ride) = base.copy(fetchedAt = now.minusSeconds(10),
        departure = base.departure.copy(observedAt = now.minusSeconds(10)))
    private fun state(ride: Ride = this.ride, gps: GpsProgressState? = null, paused: Boolean = false, failed: Boolean = false) =
        TrackingState(ride = ride, running = true, gpsProgress = gps, announcementsPaused = paused, lastRefreshFailed = failed)

    @Test fun `announces live progress once and only forward`() {
        assertNull("boarding stop is not announced", SpeechPolicy.announcement(state(), T0.plusSeconds(30), null, true))
        val now = T0.plusSeconds(150)
        val first = SpeechPolicy.announcement(state(at(now)), now, null, true)!!
        assertEquals(2, first.first); assertEquals("Nächster Halt: Halt 2.", first.second)
        assertNull("same stop again", SpeechPolicy.announcement(state(at(now)), now, first.first, true))
        val later = SpeechPolicy.announcement(state(at(T0.plusSeconds(260))), T0.plusSeconds(260), first.first, true)!!
        assertEquals(3, later.first)
        val atExit = SpeechPolicy.announcement(state(at(T0.plusSeconds(500))), T0.plusSeconds(500), 4, true)
        assertEquals("Nächster Halt: Halt 5. Hier aussteigen.", atExit!!.second)
    }

    @Test fun `no announcement while paused, waiting, uncertain or timetable only`() {
        val now = T0.plusSeconds(30)
        assertNull(SpeechPolicy.announcement(state(paused = true), now, null, true))
        assertNull(SpeechPolicy.announcement(state(RouteFixtures.ride(boarded = false, delaySeconds = 0L)), now, null, true))
        // Old data and no GPS: only the timetable is left, which is not real progress.
        assertNull(SpeechPolicy.announcement(state(failed = true), now, null, false))
        val uncertain = GpsProgressState(journeyKey = ride.departure.key, active = true, status = GpsProgressStatus.OFF_ROUTE)
        assertNull(SpeechPolicy.announcement(state(gps = uncertain, failed = true), now, null, true))
    }
}
