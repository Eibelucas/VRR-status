package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Synthetic scenarios on a made-up street (stops 500 m apart, boarding at 500 m, planned
 * departure T0 + 120 s). They check the state logic, not a real-world detection rate.
 */
class BoardingDetectorTest {
    private val depart = T0.plusSeconds(120)
    private val waitingRide = RouteFixtures.ride(boarded = false)
    private val reference = RouteReference.of(waitingRide, RouteFixtures.shape(waitingRide))

    private class Run(val states: List<BoardingState>) {
        val last get() = states.last()
        val phases get() = states.map { it.phase }.fold(listOf<BoardingPhase>()) { all, phase -> if (all.lastOrNull() == phase) all else all + phase }
        fun reached(phase: BoardingPhase) = states.any { it.phase == phase }
    }

    /**
     * Simulates [seconds] from [from]: a phone fix every 5 s (null = no location), a NEW bus
     * position every 15 s reported [busAge] s late (null = no bus data), one evaluation every 10 s.
     */
    private fun run(from: Instant, seconds: Long, ride: Ride = waitingRide, start: BoardingState? = null, busAge: Long = 5,
                    accuracy: Float = 12f, busId: String = "v1", phoneSide: Double = 3.0,
                    phone: (Instant) -> GeoPoint?, bus: ((Instant) -> GeoPoint?)? = null): Run {
        val track = PhoneTrack()
        var state = start
        var current = ride
        val states = mutableListOf<BoardingState>()
        var vehicle: NewVehicle? = null
        for (second in 0..seconds step 5) {
            val now = from.plusSeconds(second)
            phone(now)?.let { track.add(PhoneFix(it, accuracy, now)) }
            if (bus != null && second % 15 == 0L) {
                val observed = now.minusSeconds(busAge)
                vehicle = bus(observed)?.let { NewVehicle(busId, "006", "Ziel", it, observed) }
            }
            if (second % 10 == 0L) {
                state = BoardingDetector.step(state, BoardingInput(current, reference, track.recent(now, BoardingDetector.WINDOW_SECONDS),
                    vehicle?.takeIf { it.isFresh(now) }, WifiSignal.NONE, now))
                if (state.phase == BoardingPhase.ON_BOARD && !current.boarded) current = current.board("auto", now)
                states += state
            }
        }
        return Run(states)
    }

    private fun along(offset: Double, side: Double = 3.0) = RouteFixtures.along(offset, side)
    /** Bus stands at the stop until departure, then drives at [speed] m/s. */
    private fun busFrom(start: Instant, speed: Double = 8.0): (Instant) -> GeoPoint = { t ->
        along(if (t.isBefore(start)) 500.0 else 500.0 + speed * (t.epochSecond - start.epochSecond), 0.0)
    }

    @Test fun `boarding the assigned bus switches to on board after several measurements`() {
        val drive = busFrom(depart)
        val result = run(T0.plusSeconds(60), 150, phone = { drive(it) }, bus = drive)
        assertEquals(listOf(BoardingPhase.WAITING, BoardingPhase.POSSIBLE_BOARDING, BoardingPhase.ON_BOARD), result.phases)
        assertEquals("bus", result.last.basis)
        // Not at the first sign of movement: at least two supporting evaluations were needed.
        val firstOnBoard = result.states.indexOfFirst { it.phase == BoardingPhase.ON_BOARD }
        assertTrue(result.states.take(firstOnBoard).count { it.phase == BoardingPhase.POSSIBLE_BOARDING } >= 1)
    }

    @Test fun `standing at the stop while the bus is there and leaves does not board`() {
        val result = run(T0.plusSeconds(60), 240, phone = { along(500.0, 8.0) }, bus = busFrom(depart))
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
    }

    @Test fun `a bus passing by without stopping does not board`() {
        val passing: (Instant) -> GeoPoint = { t -> along(100.0 + 10.0 * (t.epochSecond - T0.epochSecond - 60), 0.0) }
        val result = run(T0.plusSeconds(60), 180, phone = { along(500.0, 8.0) }, bus = passing)
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
    }

    @Test fun `riding the opposite direction does not board`() {
        val back: (Instant) -> GeoPoint = { t -> along((500.0 - 8.0 * (t.epochSecond - depart.epochSecond).coerceAtLeast(0)).coerceAtLeast(0.0), 0.0) }
        val result = run(T0.plusSeconds(60), 180, phone = back, bus = busFrom(depart))
        assertFalse(result.reached(BoardingPhase.ON_BOARD))
        assertFalse(result.reached(BoardingPhase.POSSIBLE_BOARDING))
    }

    @Test fun `wrong bus - phone drives off while the assigned bus still waits`() {
        // The assigned vehicle keeps standing at the stop; the phone leaves with something else.
        val other = busFrom(depart.minusSeconds(40))
        val result = run(T0.plusSeconds(40), 200, phone = { other(it) }, bus = { along(500.0, 0.0) })
        assertFalse(result.reached(BoardingPhase.ON_BOARD))
    }

    @Test fun `walking along the route next to a slow bus does not board`() {
        val walk: (Instant) -> GeoPoint = { t -> along(450.0 + 1.5 * (t.epochSecond - T0.epochSecond), 6.0) }
        val crawl: (Instant) -> GeoPoint = { t -> along(450.0 + 1.5 * (t.epochSecond - T0.epochSecond), 0.0) }
        val result = run(T0, 300, phone = walk, bus = crawl)
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
    }

    @Test fun `traffic jam after boarding only delays the decision`() {
        // 60 s of crawling at 1 m/s, then normal driving.
        val jam: (Instant) -> GeoPoint = { t ->
            val s = (t.epochSecond - depart.epochSecond).coerceAtLeast(0)
            along(500.0 + if (s <= 60) s * 1.0 else 60.0 + (s - 60) * 8.0, 0.0)
        }
        val result = run(T0.plusSeconds(60), 240, phone = { jam(it) }, bus = jam)
        val crawling = result.states.take(10) // until depart + 30 s
        assertTrue(crawling.all { it.phase == BoardingPhase.WAITING })
        assertEquals(BoardingPhase.ON_BOARD, result.last.phase)
    }

    @Test fun `a single GPS jump does not board`() {
        val result = run(T0.plusSeconds(60), 180, phone = { t ->
            if (t == T0.plusSeconds(120)) along(1400.0) else along(500.0, 8.0)
        }, bus = { along(500.0, 0.0) })
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
    }

    @Test fun `location outage changes nothing`() {
        val result = run(T0.plusSeconds(60), 240, phone = { null }, bus = busFrom(depart))
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
        val boarded = waitingRide.board("manual", depart)
        val onBoard = run(depart, 300, ride = boarded, phone = { null }, bus = busFrom(depart))
        assertEquals(listOf(BoardingPhase.ON_BOARD), onBoard.phases)
    }

    @Test fun `an old vehicle position is not used - gps alone needs the longer way`() {
        val drive = busFrom(depart)
        // Positions 200 s old are not fresh, so the detector sees no vehicle at all.
        val result = run(T0.plusSeconds(60), 130, phone = { drive(it) }, bus = drive, busAge = 200)
        val early = result.states.first { it.lastEvaluatedAt == depart.plusSeconds(30) }
        assertEquals("240 m of driving is not enough without the bus", BoardingPhase.WAITING, early.phase)
        val later = run(T0.plusSeconds(60), 200, phone = { drive(it) }, bus = drive, busAge = 200)
        assertEquals(BoardingPhase.ON_BOARD, later.last.phase)
        assertEquals("gps", later.last.basis)
    }

    @Test fun `gps alone does not board long before the departure time`() {
        val early = busFrom(T0.minusSeconds(900))
        val result = run(T0.minusSeconds(900), 200, phone = { early(it) })
        assertFalse(result.reached(BoardingPhase.ON_BOARD))
    }

    @Test fun `poor accuracy gives no evidence`() {
        val drive = busFrom(depart)
        val result = run(T0.plusSeconds(60), 200, phone = { drive(it) }, bus = drive, accuracy = 90f)
        assertEquals(listOf(BoardingPhase.WAITING), result.phases)
    }

    @Test fun `manual correction is respected and pauses the automatic boarding`() {
        val drive = busFrom(depart)
        val boarded = run(T0.plusSeconds(60), 150, phone = { drive(it) }, bus = drive)
        assertEquals(BoardingPhase.ON_BOARD, boarded.last.phase)
        val now = T0.plusSeconds(215)
        val corrected = BoardingDetector.corrected(boarded.last, waitingRide, now)
        assertEquals(BoardingPhase.WAITING, corrected.phase)
        // The same movement continues, but the detector must not board again right away.
        val after = run(now, 200, start = corrected, phone = { drive(it) }, bus = drive)
        assertEquals(listOf(BoardingPhase.WAITING), after.phases)
    }

    @Test fun `manual boarding is taken over immediately`() {
        val state = BoardingDetector.step(null, BoardingInput(waitingRide.board("manual", depart), reference, emptyList(), null, WifiSignal.NONE, depart))
        assertEquals(BoardingPhase.ON_BOARD, state.phase)
    }

    @Test fun `bus wifi alone never boards`() {
        var state: BoardingState? = null
        for (second in 0..300L step 10) state = BoardingDetector.step(state, BoardingInput(waitingRide, reference,
            listOf(PhoneFix(along(500.0, 8.0), 12f, T0.plusSeconds(second))), null, WifiSignal.CONNECTED, T0.plusSeconds(second)))
        assertEquals(BoardingPhase.WAITING, state!!.phase)
    }

    @Test fun `replaying one location window cannot confirm boarding`() {
        val fixes = (0..3).map { i -> PhoneFix(along(500.0 + i * 100), 10f, depart.plusSeconds(i * 10L)) }
        val now = depart.plusSeconds(30)
        val input = BoardingInput(waitingRide, reference, fixes, null, WifiSignal.NONE, now)
        val first = BoardingDetector.step(null, input)
        assertEquals(BoardingPhase.POSSIBLE_BOARDING, first.phase)
        val repeated = BoardingDetector.step(first, input.copy(now = now.plusSeconds(10)))
        assertEquals(BoardingPhase.POSSIBLE_BOARDING, repeated.phase)
        assertEquals(1, repeated.support)
    }

    // --- exit ---

    private val boardedRide = waitingRide.board("auto", depart)
    /** Target stop 5 lies at 2800 m along the line. */
    private val arriveAt = depart.plusSeconds(288)
    private fun rideToTarget(t: Instant) = along((500.0 + 8.0 * (t.epochSecond - depart.epochSecond)).coerceAtMost(2800.0), 0.0)

    @Test fun `a stop at a red light or a long halt is not an exit`() {
        val halt: (Instant) -> GeoPoint = { t -> along((500.0 + 8.0 * (t.epochSecond - depart.epochSecond)).coerceAtMost(1300.0), 0.0) }
        val result = run(depart, 400, ride = boardedRide, phone = { halt(it) }, bus = halt)
        assertEquals(listOf(BoardingPhase.ON_BOARD), result.phases)
    }

    @Test fun `walking away at the exit stop while the bus drives on ends the ride`() {
        val phone: (Instant) -> GeoPoint = { t ->
            if (t.isBefore(arriveAt)) rideToTarget(t) else RouteFixtures.at(2008.0 + 1.4 * (t.epochSecond - arriveAt.epochSecond), 800.0)
        }
        val bus: (Instant) -> GeoPoint = { t -> along(500.0 + 8.0 * (t.epochSecond - depart.epochSecond), 0.0) }
        val result = run(depart, 480, ride = boardedRide, phone = phone, bus = bus)
        assertEquals(listOf(BoardingPhase.ON_BOARD, BoardingPhase.POSSIBLE_EXIT, BoardingPhase.ENDED), result.phases)
        assertTrue(result.last.exitAtTarget)
        // Several evaluations lie between the first sign and the end.
        assertTrue(result.states.count { it.phase == BoardingPhase.POSSIBLE_EXIT } >= 2)
    }

    @Test fun `crawling near the target without bus positions never confirms an exit`() {
        val slow: (Instant) -> GeoPoint = { t -> along(2500.0 + 0.8 * (t.epochSecond - depart.epochSecond), 0.0) }
        val result = run(depart, 240, ride = boardedRide, phone = slow)
        assertEquals(listOf(BoardingPhase.ON_BOARD), result.phases)
    }

    @Test fun `getting off early is detected but not treated as the chosen exit`() {
        val leaveAt = depart.plusSeconds(100) // 1300 m, far from the target
        val phone: (Instant) -> GeoPoint = { t ->
            if (t.isBefore(leaveAt)) along(500.0 + 8.0 * (t.epochSecond - depart.epochSecond), 0.0)
            else RouteFixtures.at(1300.0, -1.4 * (t.epochSecond - leaveAt.epochSecond))
        }
        val bus: (Instant) -> GeoPoint = { t -> along(500.0 + 8.0 * (t.epochSecond - depart.epochSecond), 0.0) }
        val result = run(depart, 330, ride = boardedRide, phone = phone, bus = bus)
        assertEquals(BoardingPhase.ENDED, result.last.phase)
        assertFalse(result.last.exitAtTarget)
    }

    @Test fun `noch im Bus pauses the exit detection`() {
        val phone: (Instant) -> GeoPoint = { t ->
            if (t.isBefore(arriveAt)) rideToTarget(t) else RouteFixtures.at(2008.0 + 1.4 * (t.epochSecond - arriveAt.epochSecond), 800.0)
        }
        val first = run(depart, 380, ride = boardedRide, phone = phone)
        assertEquals(BoardingPhase.POSSIBLE_EXIT, first.last.phase)
        val now = depart.plusSeconds(380)
        val held = BoardingDetector.corrected(first.last, boardedRide, now)
        val after = run(now, 120, ride = boardedRide, start = held, phone = phone)
        assertEquals(listOf(BoardingPhase.ON_BOARD), after.phases)
    }

    @Test fun `location profile is tight only around boarding and exit`() {
        assertEquals(LocationProfile(20_000, 30f), LocationProfile.of(waitingRide, BoardingPhase.WAITING, depart.minusSeconds(1200)))
        assertEquals(LocationProfile(3_000, 0f), LocationProfile.of(waitingRide, BoardingPhase.WAITING, depart.minusSeconds(60)))
        assertEquals(LocationProfile(3_000, 0f), LocationProfile.of(waitingRide, BoardingPhase.POSSIBLE_BOARDING, depart.minusSeconds(1200)))
        assertEquals(LocationProfile(5_000, 10f), LocationProfile.of(boardedRide, BoardingPhase.ON_BOARD, depart))
    }
}
