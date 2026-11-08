package de.luca.mitfahrt.widgets

import de.luca.mitfahrt.data.Departure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class WidgetSnapshotCodecTest {
    private val now = Instant.parse("2026-11-09T07:00:00Z")

    @Test fun `snapshot survives encoding including prediction source time`() {
        val departure = Departure("new:017", "017", "Rheydt", "de:1:2:3", "Hbf", "42", now.plusSeconds(300),
            now.plusSeconds(420), "2", cancelled = false, notices = listOf("Umleitung"), observedAt = now)
        val snapshot = WidgetSnapshot(fetchedAt = now, departures = listOf(departure),
            failure = FetchFailure.OFFLINE, failedAt = now.plusSeconds(60), partial = true)
        assertEquals(snapshot, WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(snapshot)))
    }

    @Test fun `timetable departure keeps missing prediction`() {
        val departure = Departure("new:023", "023", "Odenkirchen", "de:1:2:3", "Hbf", "7", now, null, "")
        val decoded = WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(WidgetSnapshot(now, listOf(departure))))
        assertEquals(null, decoded.departures.single().estimated)
        assertEquals(null, decoded.departures.single().observedAt)
    }

    @Test fun `broken data yields an empty snapshot`() {
        assertEquals(WidgetSnapshot(), WidgetSnapshotCodec.decode("kein json"))
    }
}
