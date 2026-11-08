package de.luca.mitfahrt.reports

import de.luca.mitfahrt.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReportCustomizationTest {
    private val now = Instant.parse("2026-11-08T07:00:00Z")
    private fun ride(delay: Long? = 300, observed: Instant = now): Ride {
        val planned = now.plusSeconds(120)
        val estimated = delay?.let { planned.plusSeconds(it) }
        val d = Departure("new006", "006", "Hbf", "de:05116:23001", "Hehn", "trip", planned, estimated, "", observedAt = observed)
        return Ride(d, listOf(RideStop(d.stopId, d.stopName, null, null, planned, estimatedDeparture = estimated),
            RideStop("de:05116:23007", "Hbf", null, planned.plusSeconds(900), null)), 0, observed, boarded = false)
    }
    private fun evidence() = DelayEvidence.fromRide(ride(), 0, now)

    @Test fun `personal identity appears only after explicit opt in and notes stay bounded`() {
        val values = mapOf("report_name" to "  Luca  ", "report_class" to "  10a  ", "report_note" to "x".repeat(1100))
        val hidden = ReportCustomization.from(AppSettings(values))
        assertEquals("", hidden.name)
        assertEquals("", hidden.schoolClass)
        assertEquals(1000, hidden.note.length)
        val shown = ReportCustomization.from(AppSettings(values + ("report_personal" to "true")))
        assertEquals("Luca", shown.name)
        assertEquals("10a", shown.schoolClass)
    }

    @Test fun `export filenames preserve extension and cannot escape selected folder`() {
        val name = ReportCustomization(filename = "../../Meine Belege/Schule").exportName(evidence())
        assertTrue(name.endsWith(".pdf"))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.startsWith('.'))
        assertTrue(name.contains("2026-11-08"))
    }

    @Test fun `field choices and compact style do not change original evidence`() {
        val evidence = evidence()
        val before = evidence.encode()
        val options = ReportCustomization.from(AppSettings(mapOf("report_style" to "compact", "report_fields" to "line,own_arrival")))
        assertTrue(options.compact)
        assertTrue(options.includes("line"))
        assertTrue(options.includes("own_arrival"))
        assertFalse(options.includes("operator_times"))
        assertEquals(before, evidence.encode())
    }

    @Test fun `automatic offer requires a fresh original prediction at configured threshold`() {
        val settings = AppSettings(mapOf("report_offer" to "true", "report_offer_threshold" to "5"))
        assertEquals(0, ReportOfferPolicy.candidate(ride(), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride(299), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride(null), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride(observed = now.minusSeconds(121)), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride(observed = now.plusSeconds(31)), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride().copy(demo = true), settings, now))
        assertNull(ReportOfferPolicy.candidate(ride(), AppSettings(mapOf("report_offer" to "false")), now))
    }
}
