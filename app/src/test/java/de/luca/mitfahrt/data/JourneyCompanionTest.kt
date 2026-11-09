package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.TripFixtures.T0
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JourneyCompanionTest {
    private val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), T0).single()
    private val bus = journey.transit[0]
    private val train = journey.transit[1]

    /** Trip of a leg as the single-ride tracking would load it (exit not yet chosen). */
    private fun rideOf(leg: TripLeg, fetchedAt: java.time.Instant = T0, exitArrivalEstimate: Long? = null,
        departureEstimate: Long? = null, cancelled: Boolean = false): Ride {
        val departure = leg.asDeparture(fetchedAt)!!.copy(cancelled = cancelled)
        val origin = leg.origin
        val stops = listOf(
            RideStop("de:05116:9999:1:1", "Vorher", null, origin.plannedDeparture!!.minusSeconds(120), origin.plannedDeparture!!.minusSeconds(120)),
            RideStop(origin.id, origin.name, origin.point, origin.plannedDeparture, origin.plannedDeparture,
                estimatedDeparture = departureEstimate?.let { T0.plusSeconds(it * 60) }, cancelled = cancelled),
            RideStop(leg.destination.id, leg.destination.name, leg.destination.point, leg.destination.plannedArrival, leg.destination.plannedArrival,
                estimatedArrival = exitArrivalEstimate?.let { T0.plusSeconds(it * 60) }),
            RideStop("de:05116:8888:1:1", "Danach", null, leg.destination.plannedArrival!!.plusSeconds(300), null))
        return Ride(departure, stops, 1, fetchedAt, boarded = false)
    }

    @Test fun `accompaniment starts with the first vehicle and never by timetable time`() {
        val companion = JourneyCompanions.start(journey, T0)!!
        assertEquals(1, companion.legIndex); assertEquals(1, companion.position); assertEquals(2, companion.count)
        assertEquals(train, companion.nextLeg)
        assertEquals(180L, companion.transferWalkSeconds)
        // Not yet tracked: no leg is shown as started, regardless of the time.
        assertEquals(CompanionPhase.DETACHED, JourneyCompanions.phase(companion, null, everTracked = false))
        val tracked = JourneyCompanions.tracking(companion, bus.asDeparture(T0)!!.key)
        assertEquals(CompanionPhase.TRACKING, JourneyCompanions.phase(tracked, tracked.trackedKey, everTracked = true))
        // The bus ride ended (user stopped it or it completed): transfer waits for an explicit action.
        assertEquals(CompanionPhase.TRANSFER, JourneyCompanions.phase(tracked, null, everTracked = true))
        assertEquals(1, tracked.legIndex)
        // Another ride started by hand: the companion does not claim it.
        assertEquals(CompanionPhase.DETACHED, JourneyCompanions.phase(tracked, "other", everTracked = true))
        // The ride was never seen running (e.g. permission denied): no transfer either.
        assertEquals(CompanionPhase.DETACHED, JourneyCompanions.phase(tracked, null, everTracked = false))
    }

    @Test fun `explicit advance moves to the next vehicle and finishes after the last`() {
        val companion = JourneyCompanions.tracking(JourneyCompanions.start(journey, T0)!!, "bus")
        val next = JourneyCompanions.advance(companion)!!
        assertEquals(3, next.legIndex); assertNull(next.trackedKey); assertEquals(2, next.position)
        assertNull(next.nextLeg); assertNull(JourneyCompanions.advance(next))
        assertEquals(300L, next.finalWalkSeconds)
        val last = JourneyCompanions.tracking(next, "train")
        assertEquals(CompanionPhase.FINISHED, JourneyCompanions.phase(last, null, everTracked = true))
    }

    @Test fun `journeys without trackable vehicle are refused`() {
        assertNull(JourneyCompanions.start(TripJourney(listOf(journey.legs[0]), 0, T0), T0))
        val anonymous = journey.copy(legs = journey.legs.map { if (it.kind == LegKind.TRANSIT) it.copy(tripCode = "") else it })
        assertNull(JourneyCompanions.start(anonymous, T0))
    }

    @Test fun `exit is found on the exact platform after boarding`() {
        val ride = rideOf(bus)
        assertEquals(2, JourneyCompanions.exitIndex(ride, bus))
        // Same station, other platform id: still found via the station.
        val otherPlatform = bus.copy(destination = bus.destination.copy(id = "de:05116:7500:9:9"))
        assertEquals(2, JourneyCompanions.exitIndex(ride, otherPlatform))
        val missing = bus.copy(destination = bus.destination.copy(id = "de:05116:1234:1:1"))
        assertNull(JourneyCompanions.exitIndex(ride, missing))
    }

    @Test fun `transfer is judged only with fresh live data on both sides`() {
        val tracked = JourneyCompanions.tracking(JourneyCompanions.start(journey, T0)!!, bus.asDeparture(T0)!!.key)
        val incoming = rideOf(bus, exitArrivalEstimate = 16).let { it.withExitTarget(2) }
        val outgoing = rideOf(train, departureEstimate = 22)
        val ok = JourneyCompanions.transfer(tracked, incoming, outgoing, T0)!!
        assertEquals(TransferRisk.OK, ok.risk); assertEquals(180L, ok.slackSeconds)
        assertEquals(TransferRisk.MISSED, JourneyCompanions.transfer(tracked, rideOf(bus, exitArrivalEstimate = 20).withExitTarget(2), outgoing, T0)!!.risk)
        // Planned times only: no statement.
        assertNull(JourneyCompanions.transfer(tracked, rideOf(bus).withExitTarget(2), outgoing, T0))
        assertNull(JourneyCompanions.transfer(tracked, incoming, rideOf(train), T0))
        assertNull(JourneyCompanions.transfer(tracked, incoming, null, T0))
        // Old data: no statement.
        assertNull(JourneyCompanions.transfer(tracked, incoming, outgoing, T0.plusSeconds(600)))
        // A cancelled connection is reported even without forecasts.
        val cancelled = JourneyCompanions.transfer(tracked, null, rideOf(train, cancelled = true), T0)!!
        assertTrue(cancelled.outgoingCancelled); assertEquals(TransferRisk.MISSED, cancelled.risk)
    }

    @Test fun `alternatives are searched from the transfer stop to the final destination`() {
        val companion = JourneyCompanions.start(journey, T0)!!
        val query = JourneyCompanions.alternatives(companion, T0, TripOptions())
        assertEquals("de:05116:7500", query.origin.id); assertEquals(PlaceKind.STOP, query.origin.kind)
        assertEquals("streetID:2:Domplatte", query.destination.id); assertEquals("Domkloster 4", query.destination.name)
        assertEquals(TripTimeMode.NOW, query.mode); assertFalse(query.needsOwnPosition)
        assertEquals("Umstieg: RE8 um 08:20, Gleis 2", JourneyCompanions.transferHint(companion))
    }

    @Test fun `companion survives a restart without coordinates of the user`() {
        val companion = JourneyCompanions.tracking(JourneyCompanions.start(journey, T0)!!, "bus-key")
        val encoded = JourneyCompanions.encode(companion)
        val decoded = JourneyCompanions.decode(encoded)!!
        assertEquals(companion.legIndex, decoded.legIndex); assertEquals("bus-key", decoded.trackedKey)
        assertEquals(journey.key, decoded.journey.key)
        assertEquals(bus.origin.estimatedDeparture, decoded.journey.transit[0].origin.estimatedDeparture)
        assertEquals(listOf("Treppe", "Aufzug"), decoded.journey.legs[2].footpath)
        assertTrue(decoded.journey.transit[0].path.isEmpty())
        assertFalse(JSONObject(encoded).has("ownPosition"))
        assertNull(JourneyCompanions.decode("kaputt"))
        assertNull(JourneyCompanions.decode(JSONObject(encoded).put("legIndex", 0).toString()))
    }

    @Test fun `NEW live features stay with NEW trips`() {
        assertTrue(bus.asDeparture(T0)!!.newOperated)
        assertFalse(train.asDeparture(T0)!!.newOperated)
        assertEquals("DBNW", train.asDeparture(T0)!!.operator)
        // Legacy departures from the NEW board have no operator stored and stay NEW.
        assertTrue(Departure("x", "1", "Ziel", "s", "Stop", "1", T0, null, "").newOperated)
        val ride = rideOf(train)
        assertEquals("DBNW", RideCodec.decode(RideCodec.encode(ride)).departure.operator)
        assertEquals("", RideCodec.decode(RideCodec.encode(rideOf(bus))).departure.operator)
    }

    @Test fun `departure board without NEW filter keeps trains for journey legs`() {
        val root = JSONObject(javaClass.classLoader!!.getResource("departures.json")!!.readText())
        val newOnly = EfaParser.departures(root, T0)
        val all = EfaParser.departures(root, T0, newOnly = false)
        assertTrue(newOnly.all { it.newOperated })
        assertTrue(all.size > newOnly.size)
        val train = all.first { !it.newOperated }
        assertEquals("DBNW", train.operator)
        assertTrue(all.containsAll(newOnly))
    }
}
