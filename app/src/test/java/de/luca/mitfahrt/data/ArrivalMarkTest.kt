package de.luca.mitfahrt.data

import de.luca.mitfahrt.reports.DelayEvidence
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ArrivalMarkTest {
    private val now = Instant.parse("2026-11-06T07:00:00Z")
    private fun ride(): Ride {
        fun fixture(name: String) = JSONObject(javaClass.getResource("/$name.json")!!.readText())
        val d = EfaParser.departures(fixture("departures"), now).first { !it.cancelled }
        return EfaParser.ride(fixture("ride"), d, now)
    }
    private fun mark(ride: Ride = ride(), at: Instant = now.minusSeconds(120)) =
        ArrivalMark.create(Stop(stopKey(ride.stops.first().id), "Hbf /Europaplatz"), at)

    @Test fun `manual arrival keeps exact time through storage and restart decoding`() {
        val record = mark()
        assertEquals(record, ArrivalMark.decode(record.json()))
        assertEquals(listOf(record), ArrivalMark.decodeList(JSONArray().put(record.json())))
        assertEquals(now.minusSeconds(120), record.at)
    }

    @Test fun `stop selected before boarding matches platform on the bus journey`() {
        val ride = ride(); val record = mark(ride)
        assertNotEquals(record.stopId, ride.stops.first().id)
        assertEquals(listOf(record), ArrivalMark.forRide(listOf(record), ride, now))
    }

    @Test fun `unrelated stations are not included just because their ID has same prefix`() {
        val ride = ride(); val record = mark(ride).copy(stopId = "de:05116:230070")
        assertTrue(ArrivalMark.forRide(listOf(record), ride, now).isEmpty())
    }

    @Test fun `old and future records are excluded from this report`() {
        val ride = ride(); val record = mark(ride)
        val records = listOf(record.copy(at = now.minusSeconds(86400)), record.copy(at = now.plusSeconds(1)))
        assertTrue(ArrivalMark.forRide(records, ride, now).isEmpty())
    }

    @Test fun `repeated confirmation preserves old record but selects latest for the report`() {
        val ride = ride(); val earlier = mark(ride); val later = mark(ride, now.minusSeconds(60))
        val records = listOf(earlier, later)
        assertEquals(listOf(later), ArrivalMark.forRide(records, ride, now))
        assertEquals(now.minusSeconds(120), earlier.at)
        assertNotEquals(earlier.id, later.id)
    }

    @Test fun `own arrival is separate and never changes vehicle delay`() {
        val ride = ride(); val record = mark(ride)
        val evidence = DelayEvidence.fromRide(ride, 0, now, listOf(record))
        assertEquals(listOf(record), evidence.personalArrivals)
        assertEquals(ride.departure.estimated, evidence.estimated)
        assertEquals(744, evidence.delaySeconds)
        assertEquals(evidence, DelayEvidence.decode(evidence.encode()))
    }

    @Test fun `departure forecast is not substituted for missing vehicle arrival`() {
        val ride = ride()
        val report = DelayEvidence.fromRide(ride, 0, now)
        assertNotNull(report.estimated)
        assertNull(report.arrivalEstimated)
        assertNull(report.arrivalPlanned)
    }

    @Test fun `vehicle arrival forecast retains its own value independently of departure`() {
        val original = ride()
        val first = original.stops.first().copy(arrival = original.departure.planned.minusSeconds(20),
            estimatedArrival = original.departure.planned.plusSeconds(180))
        val updated = original.copy(stops = listOf(first) + original.stops.drop(1))
        val report = DelayEvidence.fromRide(updated, 0, now)
        assertEquals(first.arrival, report.arrivalPlanned)
        assertEquals(first.estimatedArrival, report.arrivalEstimated)
        assertEquals(original.departure.estimated, report.estimated)
        assertEquals(original.fetchedAt, report.arrivalObservedAt)
        assertTrue(report.sourceUrl.contains("XML_DM_REQUEST"))
        assertTrue(report.arrivalSourceUrl!!.contains("XML_TRIPSTOPTIMES_REQUEST"))
        assertEquals(report, DelayEvidence.decode(report.encode()))
    }

    @Test fun `version 02 reports decode without arrival fields`() {
        val original = DelayEvidence.fromRide(ride(), 0, now)
        val data = JSONObject(original.encode())
        listOf("arrivalPlanned", "arrivalEstimated", "personalArrivals", "arrivalObservedAt", "arrivalSourceUrl").forEach { data.remove(it) }
        val restored = DelayEvidence.decode(data.toString())
        assertNull(restored.arrivalPlanned)
        assertNull(restored.arrivalEstimated)
        assertTrue(restored.personalArrivals.isEmpty())
        assertNull(restored.arrivalObservedAt)
        assertNull(restored.arrivalSourceUrl)
        assertEquals(original.estimated, restored.estimated)
    }

    @Test fun `demos and absent manual confirmations never gain a personal arrival automatically`() {
        assertTrue(ArrivalMark.forRide(listOf(mark()), DemoRide.create(now), now).isEmpty())
        assertTrue(DelayEvidence.fromRide(ride(), 0, now).personalArrivals.isEmpty())
    }
}
