package de.luca.mitfahrt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ArrivalHistoryTest {
    private fun mark(id: String, stop: String, name: String, at: String, method: String = "manual") =
        ArrivalMark(id, stop, name, Instant.parse(at), method)

    private val marks = listOf(
        mark("1", "de:05116:7000:1:1", "Hbf", "2026-11-08T22:30:00Z"),            // 23:30 on 8 Nov in Berlin
        mark("2", "de:05116:7000:2:4", "Hbf", "2026-11-08T23:10:00Z", "location"), // 00:10 on 9 Nov in Berlin
        mark("3", "de:05116:7100", "Rheydt", "2026-11-09T07:00:00Z"),
        mark("3", "de:05116:7100", "Rheydt", "2026-11-09T07:00:00Z")
    )

    @Test fun `groups by local day newest first without duplicates`() {
        val days = ArrivalHistory.byDay(marks)
        assertEquals(listOf(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-08")), days.map { it.first })
        assertEquals(listOf("3", "2"), days.first().second.map { it.id })
    }

    @Test fun `stop filter treats platforms of one station as the same stop`() {
        val hbf = ArrivalHistory.stops(marks).first { it.name == "Hbf" }
        assertEquals(2, hbf.count)
        assertEquals(listOf("2", "1"), ArrivalHistory.byDay(marks, hbf.key).flatMap { it.second }.map { it.id })
    }

    @Test fun `origin label only for known methods`() {
        assertEquals("Selbst markiert", ArrivalHistory.originLabel(marks[0]))
        assertEquals("Über Standort erkannt", ArrivalHistory.originLabel(marks[1]))
        assertNull(ArrivalHistory.originLabel(marks[0].copy(method = "gps-guess")))
    }

    @Test fun `empty list stays empty`() {
        assertEquals(emptyList<Any>(), ArrivalHistory.byDay(emptyList()))
        assertEquals(emptyList<Any>(), ArrivalHistory.stops(emptyList()))
    }
}
