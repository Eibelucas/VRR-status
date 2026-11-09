package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import de.luca.mitfahrt.data.RouteFixtures.at
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GpsStopProgressTest {
    private val depart = T0.plusSeconds(120)

    /** Feeds fixes every [step] seconds along [offsetAt] and returns the final state. */
    private fun drive(ride: Ride, reference: RouteReference?, from: Instant, until: Instant, step: Long = 5,
                      start: GpsProgressState? = null, accuracy: Float = 10f, side: Double = 4.0,
                      offsetAt: (Instant) -> Double): GpsProgressState? {
        var state = start
        var t = from
        while (!t.isAfter(until)) {
            state = GpsStopProgress.update(state, ride, reference, RouteFixtures.fix(offsetAt(t), t, accuracy, side))
            t = t.plusSeconds(step)
        }
        return state
    }

    private fun exactRef(ride: Ride) = RouteReference.of(ride, RouteFixtures.shape(ride))
    private fun busAt(t: Instant, speed: Double = 8.0) = 500.0 + speed * (t.epochSecond - depart.epochSecond)

    @Test fun `waiting never counts progress and walking past the boarding stop does not board`() {
        val ride = RouteFixtures.ride(boarded = false)
        val state = drive(ride, exactRef(ride), T0, T0.plusSeconds(120)) { 300.0 + 1.4 * (it.epochSecond - T0.epochSecond) }
        assertFalse(state!!.active)
        assertEquals(-1, state.passedIndex)
        assertFalse(ride.boarded)
    }

    @Test fun `stops are passed in order along the real line`() {
        val ride = RouteFixtures.ride(boarded = true)
        val until = depart.plusSeconds(80) // 500 + 640 = 1140 m: two fixes past stop 2 (1000 m)
        val state = drive(ride, exactRef(ride), depart, until) { busAt(it) }!!
        assertEquals(2, state.passedIndex)
        assertTrue(GpsStopProgress.confident(state, until))
    }

    @Test fun `delayed bus - progress follows the position although the timetable is several stops further`() {
        // Timetable says the bus should be at stop 5 now, it is really just after stop 2.
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = T0)
        val now = T0.plusSeconds(700)
        val state = drive(ride, exactRef(ride), now.minusSeconds(70), now) { 1000.0 + 8.0 * (it.epochSecond - now.plusSeconds(-60).epochSecond) }
        val tracking = TrackingState(ride = ride.copy(gpsPassedIndex = state!!.passedIndex), running = true,
            lastRefreshFailed = true, gpsProgress = state)
        val next = NextStopResolver.resolve(tracking, now)!!
        assertEquals(NextStopSource.GPS, next.source)
        assertEquals(3, next.index)
        assertNull("GPS never produces a remaining time", next.minutes)
        // The plain timetable would have skipped far ahead.
        assertTrue(ride.nextIndex(now) > 3)
    }

    @Test fun `a single GPS jump never skips stops or resets the display`() {
        val ride = RouteFixtures.ride(boarded = true)
        val reference = exactRef(ride)
        val before = drive(ride, reference, depart, depart.plusSeconds(30)) { busAt(it) }!!
        assertEquals(1, before.passedIndex)
        val jumped = GpsStopProgress.update(before, ride, reference, RouteFixtures.fix(2400.0, depart.plusSeconds(35)))
        assertEquals(1, jumped.passedIndex)
        val back = GpsStopProgress.update(jumped, ride, reference, RouteFixtures.fix(busAt(depart.plusSeconds(40)), depart.plusSeconds(40)))
        assertEquals(1, back.passedIndex)
        val backwards = GpsStopProgress.update(back, ride, reference, RouteFixtures.fix(200.0, depart.plusSeconds(45)))
        assertEquals(1, backwards.passedIndex)
        assertEquals(back.offset!!, backwards.offset!!, 0.001)
    }

    @Test fun `opposite stops on an out-and-back street keep their order`() {
        val loop = (0..15).map { at(it * 100.0, 0.0) } + (14 downTo 0).map { at(it * 100.0, -20.0) }
        val positions = listOf(0.0 to 8.0, 500.0 to 8.0, 1000.0 to 8.0, 1500.0 to 8.0, 1000.0 to -28.0, 500.0 to -28.0, 0.0 to -28.0)
        val stops = positions.mapIndexed { index, (e, n) ->
            RideStop("de:05116:${300 + index}:1:1", "Halt $index", at(e, n), T0.plusSeconds(index * 120L), T0.plusSeconds(index * 120L))
        }
        val ride = RouteFixtures.ride(boarded = true, stops = stops, target = 6)
        val reference = RouteReference.of(ride, RouteShape.verify(ride, loop, RouteShapeSource.TRIP))!!
        // Eastbound on the north side, then back west on the south side.
        fun point(t: Instant): PhoneFix {
            val d = 500.0 + 8.0 * (t.epochSecond - depart.epochSecond)
            val p = if (d <= 1500.0) at(d, 3.0) else at(1500.0 - (d - 1520.0), -22.0)
            return PhoneFix(p, 10f, t)
        }
        var state: GpsProgressState? = null
        var t = depart
        while (!t.isAfter(depart.plusSeconds(100))) { state = GpsStopProgress.update(state, ride, reference, point(t)); t = t.plusSeconds(5) }
        // ~1300 m: past stops 1 and 2 eastbound, NOT the westbound stop 4 opposite of stop 2.
        assertEquals(2, state!!.passedIndex)
        while (!t.isAfter(depart.plusSeconds(200))) { state = GpsStopProgress.update(state, ride, reference, point(t)); t = t.plusSeconds(5) }
        // ~2100 m along: stop 3 (1500) and the westbound stop 4 (1000 m on the way back, ~2020 m along).
        assertEquals(4, state!!.passedIndex)
    }

    @Test fun `loop serving the same stop twice counts the occurrences in order`() {
        val ids = RouteFixtures.stops().map { it.id }.toMutableList().also { it[5] = it[2] }
        val ride = RouteFixtures.ride(boarded = true, stops = RouteFixtures.stops(ids = ids), target = 6)
        // Stop 5 shares stop 2's id but lies on the north part; ordering is by sequence, not by id.
        val state = drive(ride, exactRef(ride), depart, depart.plusSeconds(80)) { busAt(it) }!!
        assertEquals(2, state.passedIndex)
    }

    @Test fun `stop passed without halting is recognised without claiming a halt`() {
        val ride = RouteFixtures.ride(boarded = true)
        val state = drive(ride, exactRef(ride), depart, depart.plusSeconds(90)) { busAt(it) }!!
        assertTrue(state.passedIndex >= 2)
        assertFalse(2 in state.dwellIndexes)
        // Same ride with a 30 s stop at stop 2.
        val halted = drive(ride, exactRef(ride), depart, depart.plusSeconds(120)) {
            val s = it.epochSecond - depart.epochSecond
            when {
                s < 60 -> 500.0 + 8.0 * s
                s < 90 -> 980.0
                else -> 980.0 + 8.0 * (s - 90)
            }
        }!!
        assertTrue(2 in halted.dwellIndexes)
    }

    @Test fun `tunnel - no progress by time and a confirmed resume afterwards`() {
        val ride = RouteFixtures.ride(boarded = true, fetchedAt = T0)
        val reference = exactRef(ride)
        val before = drive(ride, reference, depart, depart.plusSeconds(30)) { busAt(it) }!!
        assertEquals(1, before.passedIndex)
        // Three minutes without any fix: nothing is marked as passed, display is uncertain.
        val during = depart.plusSeconds(210)
        val tracking = TrackingState(ride = ride.copy(gpsPassedIndex = before.passedIndex), running = true,
            lastRefreshFailed = true, gpsProgress = before)
        val uncertain = NextStopResolver.resolve(tracking, during)!!
        assertEquals(NextStopSource.UNCERTAIN, uncertain.source)
        assertNull(uncertain.stop)
        assertTrue(uncertain.note!!.startsWith("Letzte bestätigte Position"))
        // Resume far ahead (~1950 m): first fix is held, the second confirms, a third agrees on the passes.
        var state = GpsStopProgress.update(before, ride, reference, RouteFixtures.fix(1940.0, depart.plusSeconds(215)))
        assertEquals(1, state.passedIndex)
        state = GpsStopProgress.update(state, ride, reference, RouteFixtures.fix(1980.0, depart.plusSeconds(220)))
        state = GpsStopProgress.update(state, ride, reference, RouteFixtures.fix(2020.0, depart.plusSeconds(225)))
        assertEquals(3, state.passedIndex)
    }

    @Test fun `getting off and walking on is not counted as bus progress`() {
        val ride = RouteFixtures.ride(boarded = true, target = 6)
        val reference = exactRef(ride)
        val riding = drive(ride, reference, depart, depart.plusSeconds(80)) { busAt(it) }!!
        assertEquals(2, riding.passedIndex)
        val exitAt = depart.plusSeconds(85)
        // Walks on along the route towards stop 3 (1500 m) at 1.3 m/s for three minutes, beyond it.
        val walked = drive(ride, reference, exitAt, exitAt.plusSeconds(480), step = 8, start = riding) {
            1100.0 + 1.3 * (it.epochSecond - exitAt.epochSecond)
        }!!
        assertEquals(GpsProgressStatus.ON_FOOT, walked.status)
        assertEquals(2, walked.passedIndex)
        val info = NextStopResolver.resolve(TrackingState(ride = ride, running = true, lastRefreshFailed = true, gpsProgress = walked),
            exitAt.plusSeconds(480))!!
        assertEquals(NextStopSource.UNCERTAIN, info.source)
    }

    @Test fun `stop-and-go traffic is not mistaken for walking`() {
        val ride = RouteFixtures.ride(boarded = true)
        val state = drive(ride, exactRef(ride), depart, depart.plusSeconds(150)) {
            val s = it.epochSecond - depart.epochSecond
            // 45 s standing, then 9 m/s, repeatedly: average ~2 m/s.
            val cycle = s % 60
            500.0 + (s / 60) * 135.0 + if (cycle < 45) 0.0 else (cycle - 45) * 9.0
        }!!
        assertEquals(GpsProgressStatus.TRACKING, state.status)
    }

    @Test fun `without geometry a parallel street never passes stops that were not approached`() {
        val ride = RouteFixtures.ride(boarded = true)
        val chain = RouteReference.of(ride, null)!!
        // 150 m north of the line: within the wide chain tolerance, but never near a stop.
        val parallel = drive(ride, chain, depart, depart.plusSeconds(90), side = 150.0) { busAt(it) }!!
        assertTrue(parallel.passedIndex <= 1)
        val onLine = drive(ride, chain, depart, depart.plusSeconds(90)) { busAt(it) }!!
        assertTrue(onLine.passedIndex >= 2)
        assertFalse(onLine.exact)
    }

    @Test fun `inaccurate positions are ignored and a diversion is reported as uncertain`() {
        val ride = RouteFixtures.ride(boarded = true)
        val reference = exactRef(ride)
        val inaccurate = drive(ride, reference, depart, depart.plusSeconds(90), accuracy = 120f) { busAt(it) }!!
        assertEquals(0, inaccurate.passedIndex)
        val diverted = drive(ride, reference, depart, depart.plusSeconds(90), side = 200.0) { busAt(it) }!!
        assertEquals(GpsProgressStatus.OFF_ROUTE, diverted.status)
        val info = NextStopResolver.resolve(TrackingState(ride = ride, running = true, lastRefreshFailed = true, gpsProgress = diverted),
            depart.plusSeconds(90))!!
        assertEquals(NextStopSource.UNCERTAIN, info.source)
        assertEquals("Position weicht vom bekannten Fahrtverlauf ab", info.note)
    }

    @Test fun `next stop source is live when VRR is fresh, schedule without location`() {
        val ride = RouteFixtures.ride(boarded = true, delaySeconds = 60, fetchedAt = depart)
        val live = NextStopResolver.resolve(TrackingState(ride = ride, running = true), depart.plusSeconds(10))!!
        assertEquals(NextStopSource.LIVE, live.source)
        assertNotNull(live.minutes)
        val schedule = NextStopResolver.resolve(TrackingState(ride = RouteFixtures.ride(boarded = true, fetchedAt = T0),
            running = true, lastRefreshFailed = true), depart.plusSeconds(10))!!
        assertEquals(NextStopSource.SCHEDULE, schedule.source)
        assertNull(schedule.minutes)
    }

    // ---- Exit alarm -------------------------------------------------------------------------

    @Test fun `GPS supports the exit alarm only with fresh accurate progress`() {
        val ride = RouteFixtures.ride(boarded = true, target = 4, fetchedAt = T0)
        val reference = exactRef(ride)
        val until = depart.plusSeconds(195) // ~2060 m: past stop 3 (1500 m), before target 4 (2400 m)
        val progress = drive(ride, reference, depart, until) { busAt(it) }!!
        assertEquals(3, progress.passedIndex)
        val state = TrackingState(ride = ride.copy(gpsPassedIndex = progress.passedIndex), running = true,
            lastRefreshFailed = true, gpsProgress = progress)
        assertTrue(ExitAlarm.due(state, until))
        // An old measurement must not trigger a "GPS-confirmed" alarm.
        assertFalse(ExitAlarm.due(state, until.plusSeconds(120)))
        // Timetable alone never triggers it.
        assertFalse(ExitAlarm.due(state.copy(gpsProgress = null), until))
        // Inaccurate last position.
        assertFalse(ExitAlarm.due(state.copy(gpsProgress = progress.copy(lastAccuracy = 80f)), until))
        // Already passed the target.
        assertFalse(GpsExitSignal.due(ride, progress.copy(passedIndex = 4), until))
    }

    @Test fun `GPS alarm uses the same identity so it is not repeated`() {
        val ride = RouteFixtures.ride(boarded = true, target = 4)
        assertEquals(ExitAlarm.id(ride), ExitAlarm.id(ride.copy(gpsPassedIndex = 3)))
    }

    @Test fun `approach to the target without real geometry needs order and decreasing distance`() {
        val ride = RouteFixtures.ride(boarded = true, target = 4, fetchedAt = T0)
        val chain = RouteReference.of(ride, null)!!
        val far = drive(ride, chain, depart, depart.plusSeconds(100)) { busAt(it) }!!
        assertFalse(GpsExitSignal.due(ride, far, depart.plusSeconds(100)))
    }
}
