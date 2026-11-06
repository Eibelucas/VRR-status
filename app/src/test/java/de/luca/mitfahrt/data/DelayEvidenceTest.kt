package de.luca.mitfahrt.data

import de.luca.mitfahrt.reports.DelayEvidence
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DelayEvidenceTest {
    private val now = Instant.parse("2026-11-06T07:00:00Z")
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/$name.json")!!.readText())
    private fun ride(): Ride {
        val departure = EfaParser.departures(fixture("departures"), now.minusSeconds(30)).first { !it.cancelled }
        return EfaParser.ride(fixture("ride"), departure, now)
    }

    @Test fun `boarding report uses departure monitor forecast with its original retrieval time`() {
        val ride = ride()
        val evidence = DelayEvidence.fromRide(ride, ride.boardingIndex, now)
        assertEquals(744, evidence.delaySeconds)
        assertEquals("12 Min 24 Sek Verspätung", evidence.delayText)
        assertEquals("Abfahrt", evidence.event)
        assertEquals(now.minusSeconds(30), evidence.observedAt)
        assertTrue(evidence.sourceUrl.contains("XML_DM_REQUEST"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `extrapolated arrival cannot be exported as provider evidence`() {
        val ride = ride()
        assertNotNull(ride.terminal.effective(ride.offsetSeconds))
        assertNull(ride.terminal.estimated)
        DelayEvidence.fromRide(ride, ride.stops.lastIndex, now)
    }

    @Test fun `actual stop forecast overrides extrapolation and uses arrival event`() {
        val original = ride()
        val stop = original.stops[1]
        val updated = original.copy(stops = original.stops.mapIndexed { i, s ->
            if (i == 1) s.copy(estimatedArrival = stop.arrival!!.plusSeconds(240)) else s
        })
        val evidence = DelayEvidence.fromRide(updated, 1, now)
        assertEquals("Ankunft", evidence.event)
        assertEquals(240, evidence.delaySeconds)
        assertEquals(now, evidence.observedAt)
        assertTrue(evidence.sourceUrl.contains("XML_TRIPSTOPTIMES_REQUEST"))
        assertTrue(evidence.sourceUrl.contains("date=20261106"))
    }

    @Test fun `refreshing a trip does not make an old departure forecast look current`() {
        val ride = ride().let { it.copy(departure = it.departure.copy(observedAt = now.minusSeconds(600))) }
        val evidence = DelayEvidence.fromRide(ride.copy(fetchedAt = now), 0, now)
        assertEquals(now.minusSeconds(600), evidence.observedAt)
        assertTrue(evidence.isOld())
    }

    @Test fun `legacy ride with unknown forecast retrieval time is marked old`() {
        val ride = ride().let { it.copy(departure = it.departure.copy(observedAt = null)) }
        val evidence = DelayEvidence.fromRide(ride, 0, now)
        assertNull(evidence.observedAt)
        assertTrue(evidence.isOld())
    }

    @Test fun `snapshot survives later ride updates and JSON storage unchanged`() {
        val original = ride()
        val snapshot = DelayEvidence.fromRide(original, 0, now)
        val updated = original.copy(departure = original.departure.copy(estimated = original.departure.planned))
        assertEquals(0, DelayEvidence.fromRide(updated, 0, now).delaySeconds)
        assertEquals(744, snapshot.delaySeconds)
        assertEquals(snapshot, DelayEvidence.decode(snapshot.encode()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `demo cannot be used as a school report`() { DelayEvidence.fromRide(DemoRide.create(now), 0, now) }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown stop cannot be reported`() { DelayEvidence.fromRide(ride(), 999, now) }

    @Test fun `small delays are not described as zero minutes`() {
        val ride = ride().let { it.copy(departure = it.departure.copy(estimated = it.departure.planned.plusSeconds(20))) }
        assertEquals("Unter 1 Min Verspätung", DelayEvidence.timingFor(ride, 0)!!.shortDelay)
        assertEquals("20 Sek Verspätung", DelayEvidence.fromRide(ride, 0, now).delayText)
    }

    @Test fun `on time and early forecasts are never labelled late`() {
        val ride = ride()
        val onTime = ride.copy(departure = ride.departure.copy(estimated = ride.departure.planned))
        val early = ride.copy(departure = ride.departure.copy(estimated = ride.departure.planned.minusSeconds(60)))
        assertEquals("Pünktlich laut Prognose", DelayEvidence.fromRide(onTime, 0, now).delayText)
        assertEquals("1 Min früher", DelayEvidence.fromRide(early, 0, now).delayText)
    }

    @Test fun `cancellation and provider notices are retained in evidence`() {
        val ride = ride().let { it.copy(departure = it.departure.copy(cancelled = true, notices = listOf("Stau"))) }
        val report = DelayEvidence.fromRide(ride, 0, now)
        assertTrue(report.cancelled)
        assertEquals(listOf("Stau"), report.notices)
    }

    @Test fun `old saved rides remain readable after adding retrieval time`() {
        val data = JSONObject(RideCodec.encode(ride()))
        data.remove("observedAt")
        assertNull(RideCodec.decode(data.toString()).departure.observedAt)
    }

    @Test fun `a stop with only a departure forecast can provide departure evidence`() {
        val original = ride()
        val updated = original.copy(stops = original.stops.mapIndexed { i, stop ->
            if (i == 1) stop.copy(estimatedDeparture = stop.departure!!.plusSeconds(120)) else stop
        })
        val evidence = DelayEvidence.fromRide(updated, 1, now)
        assertEquals("Abfahrt", evidence.event)
        assertEquals(120, evidence.delaySeconds)
        assertTrue(evidence.sourceUrl.contains("XML_TRIPSTOPTIMES_REQUEST"))
    }

    @Test fun `later changes to provider notices do not modify a frozen report`() {
        val notices = mutableListOf("Stau")
        val original = ride().let { it.copy(departure = it.departure.copy(notices = notices)) }
        val evidence = DelayEvidence.fromRide(original, 0, now)
        notices.clear()
        assertEquals(listOf("Stau"), evidence.notices)
    }
}
