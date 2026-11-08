package de.luca.mitfahrt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class JourneyArchiveTest {
    private val t0 = Instant.parse("2026-11-09T06:40:00Z")
    private var now = t0

    private fun ride(trip: String = "t1", line: String = "017", target: String = "Rheydt Hbf", demo: Boolean = false,
                     startedAt: Instant? = t0): Ride {
        val planned = t0.plusSeconds(300)
        val dep = Departure("new:$line", line, target, "de:05116:7000:1:1", "Mönchengladbach Hbf", trip, planned, null, "1",
            observedAt = t0)
        return Ride(dep, listOf(
            RideStop("de:05116:7000:1:1", "Mönchengladbach Hbf", null, planned, planned),
            RideStop("de:05116:7100:1:1", target, null, planned.plusSeconds(900), planned.plusSeconds(900))
        ), 0, t0, demo = demo, boarded = false, trackingStartedAt = startedAt)
    }

    private val empty = JourneyArchiveState()

    @Test fun `explicit start records once and refreshes replace without duplicates`() {
        var state = JourneyArchive.record(empty, ride(), now)
        now = now.plusSeconds(30)
        state = JourneyArchive.update(state, ride().copy(boarded = true, fetchedAt = now), now)
        state = JourneyArchive.record(state, ride().copy(fetchedAt = now), now)
        assertEquals(1, state.entries.size)
        assertEquals(3, state.entries.single().revision)
        assertEquals(Instant.parse("2026-11-09T06:40:00Z"), state.entries.single().startedAt)
    }

    @Test fun `refresh preview or board lookup never creates an entry`() {
        val updated = JourneyArchive.update(empty, ride(), now)
        assertSame(empty, updated)
        assertTrue(updated.entries.isEmpty())
    }

    @Test fun `demo rides and disabled archive are not recorded`() {
        assertTrue(JourneyArchive.record(empty, ride(demo = true), now).entries.isEmpty())
        val off = empty.copy(settings = JourneyArchiveSettings(enabled = false))
        assertTrue(JourneyArchive.record(off, ride(), now).entries.isEmpty())
    }

    @Test fun `undo restores a deleted entry exactly once`() {
        val state = JourneyArchive.record(empty, ride(), now)
        val (removed, undo) = JourneyArchive.remove(state, setOf(state.entries.single().id))
        assertNotNull(undo)
        val (restored, count) = JourneyArchive.restore(removed, undo!!, now)
        assertEquals(1, count)
        assertEquals(state.entries, restored.entries)
        assertEquals(0, JourneyArchive.restore(restored, undo, now).second)
    }

    @Test fun `old undo never overwrites a newer recording`() {
        val first = JourneyArchive.record(empty, ride(), now)
        val (removed, undo) = JourneyArchive.remove(first, setOf(first.entries.single().id))
        now = now.plusSeconds(60)
        val recorded = JourneyArchive.record(removed, ride().copy(boarded = true, fetchedAt = now), now)
        val (after, count) = JourneyArchive.restore(recorded, undo!!, now)
        assertEquals(0, count)
        assertTrue(after.entries.single().ride.boarded)
    }

    @Test fun `old undo never reverts a later deletion`() {
        val state = JourneyArchive.record(empty, ride(), now)
        val id = state.entries.single().id
        val (removed, oldUndo) = JourneyArchive.remove(state, setOf(id))
        val (restored, _) = JourneyArchive.restore(removed, oldUndo!!, now)
        val (deletedAgain, _) = JourneyArchive.remove(restored, setOf(id))
        assertEquals(0, JourneyArchive.restore(deletedAgain, oldUndo, now).second)
        assertTrue(JourneyArchive.restore(deletedAgain, oldUndo, now).first.entries.isEmpty())
    }

    @Test fun `multi delete and clear`() {
        var state = JourneyArchive.record(empty, ride("a"), now)
        state = JourneyArchive.record(state, ride("b"), now)
        state = JourneyArchive.record(state, ride("c"), now)
        val (removed, undo) = JourneyArchive.remove(state, state.entries.take(2).map { it.id }.toSet())
        assertEquals(1, removed.entries.size)
        assertEquals(2, undo!!.entries.size)
        assertTrue(JourneyArchive.clear(removed).entries.isEmpty())
        // Undo after clearing is refused.
        assertEquals(0, JourneyArchive.restore(JourneyArchive.clear(removed), undo, now).second)
    }

    @Test fun `retention only applies when chosen`() {
        var state = JourneyArchive.record(empty, ride("old", startedAt = now.minusSeconds(40 * 86_400L)), now)
        state = JourneyArchive.record(state, ride("new"), now)
        assertEquals(2, JourneyArchive.prune(state, now).entries.size)
        val limited = JourneyArchive.withSettings(state, JourneyArchiveSettings(retentionDays = 30), now)
        assertEquals(listOf("new"), limited.entries.map { it.ride.departure.tripCode })
        // Unsupported values fall back to "no automatic deletion".
        assertEquals(0, JourneyArchive.withSettings(state, JourneyArchiveSettings(retentionDays = 12), now).settings.retentionDays)
    }

    @Test fun `entry limit is enforced`() {
        var state = empty
        repeat(JourneyArchive.MAX_ENTRIES + 5) { state = JourneyArchive.record(state, ride("t$it", startedAt = now.plusSeconds(it.toLong())), now.plusSeconds(it.toLong())) }
        assertEquals(JourneyArchive.MAX_ENTRIES, state.entries.size)
        assertEquals("t504", state.entries.first().ride.departure.tripCode)
    }

    @Test fun `search and filters`() {
        var state = JourneyArchive.record(empty, ride("a", "017", "Rheydt Hbf"), now)
        state = JourneyArchive.record(state, ride("b", "007", "Odenkirchen"), now)
        assertEquals(1, JourneyArchive.filter(state.entries, JourneyArchiveFilter(query = "rheydt")).size)
        assertEquals(1, JourneyArchive.filter(state.entries, JourneyArchiveFilter(line = "7")).size)
        assertEquals(2, JourneyArchive.filter(state.entries, JourneyArchiveFilter(query = "hbf")).size)
        assertEquals(0, JourneyArchive.filter(state.entries, JourneyArchiveFilter(from = LocalDate.parse("2026-11-10"))).size)
        assertEquals(listOf("007", "017"), JourneyArchive.lines(state.entries))
        assertEquals(LocalDate.parse("2026-11-09"), JourneyArchive.byDay(state.entries).single().first)
    }

    @Test fun `codec round trip and damaged storage`() {
        var state = JourneyArchive.record(empty, ride("a"), now)
        state = JourneyArchive.remove(JourneyArchive.record(state, ride("b"), now), setOf(JourneyArchive.id(ride("b")))).first
        val decoded = JourneyArchive.decode(JourneyArchive.encode(state), now)
        assertEquals(state.entries, decoded.entries)
        assertEquals(state.tombstones, decoded.tombstones)
        assertEquals(JourneyArchiveState(), JourneyArchive.decode("{kaputt", now))
        // An entry whose id does not belong to its ride is dropped.
        val tampered = JourneyArchive.encode(state).replace(state.entries.single().id, "fremd")
        assertTrue(JourneyArchive.decode(tampered, now).entries.isEmpty())
    }

    @Test fun `store persists privately and ignores oversized files`() {
        val dir = Files.createTempDirectory("archive").toFile()
        val file = File(dir, "journey_archive.json")
        val store = JourneyArchiveStore(file) { now }
        store.record(ride())
        store.update(ride("unknown"))
        assertEquals(1, JourneyArchiveStore(file) { now }.state.value.entries.size)
        file.writeText("x".repeat((JourneyArchive.MAX_FILE_BYTES + 1).toInt()))
        assertTrue(JourneyArchiveStore(file) { now }.state.value.entries.isEmpty())
        file.writeText("nicht json")
        assertNull(JourneyArchiveStore(file) { now }.state.value.entries.firstOrNull())
        dir.deleteRecursively()
    }
}
