package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test

class SavedStopsTest {
    private val hbf = Stop("de:05116:23007", "MG Hbf /Europaplatz", GeoPoint(51.197, 6.445), 250)

    @Test fun `selecting stations updates recency without duplicating platform ids`() {
        var state = SavedStops.selected(SavedStopsState(), hbf)
        state = SavedStops.selected(state, Stop("de:05116:23013", "Bismarckstraße"))
        state = SavedStops.selected(state, hbf.copy(id = hbf.id + ":1:6", name = "Hbf /Europaplatz", point = null))
        assertEquals(2, state.recent.size)
        assertEquals(hbf.id, state.recent.first().id)
        assertEquals("Hbf /Europaplatz", state.recent.first().name)
        assertEquals(hbf.point, state.recent.first().point)
        assertNull(state.recent.first().distance)
    }

    @Test fun `favorite limit does not silently evict an existing pin`() {
        val pinned = (1..SavedStops.MAX_FAVORITES).fold(SavedStopsState()) { state, i ->
            SavedStops.toggle(state, Stop("stop$i", "Stop $i"))
        }
        assertEquals(pinned, SavedStops.toggle(pinned, hbf))
        assertEquals(SavedStops.MAX_FAVORITES, pinned.favorites.size)
    }

    @Test fun `unpin is reversible in the original position without adding a visit`() {
        val first = SavedStops.toggle(SavedStopsState(), hbf)
        val pinned = SavedStops.toggle(first, Stop("other", "Other"))
        val removed = SavedStops.toggle(pinned, hbf.copy(id = hbf.id + ":1:1"))
        assertFalse(removed.isFavorite(hbf))
        assertEquals(pinned, SavedStops.restore(removed, hbf, 0))
        assertEquals(pinned, SavedStops.restore(pinned, hbf, 0))
        assertTrue(pinned.recent.isEmpty())
    }

    @Test fun `fresh selections update favorite names while preserving safe public geometry`() {
        val pinned = SavedStops.toggle(SavedStopsState(), hbf)
        val fresh = SavedStops.selected(pinned, hbf.copy(name = "Hbf /Europaplatz", point = null, distance = 15))
        assertEquals("Hbf /Europaplatz", fresh.favorites.single().name)
        assertEquals(hbf.point, fresh.favorites.single().point)
        assertNull(fresh.favorites.single().distance)
    }

    @Test fun `recent storage stays bounded and selection order wins`() {
        val state = (1..30).fold(SavedStopsState()) { current, i -> SavedStops.selected(current, Stop("stop$i", "Stop $i")) }
        assertEquals(SavedStops.MAX_RECENT, state.recent.size)
        assertEquals("stop30", state.recent.first().id)
        assertEquals("stop11", state.recent.last().id)
    }

    @Test fun `search matches accents station abbreviations city prefixes and multiple tokens`() {
        assertTrue(SavedStops.matches(hbf, "hauptbahnhof"))
        assertTrue(SavedStops.matches(Stop("r", "Mönchengladbach Rheydt Hauptbahnhof"), "Rheydt Hbf"))
        assertTrue(SavedStops.matches(Stop("b", "Bismarckstraße"), "bismarckstrasse"))
        assertTrue(SavedStops.matches(Stop("b", "Mönchengladbach Bismarckstraße"), "MG Bismarck"))
        assertFalse(SavedStops.matches(hbf, "Rheydt Hbf"))
    }

    @Test fun `suggestions deduplicate favorites and recents and keep pins before history`() {
        val state = SavedStopsState(listOf(hbf), listOf(hbf.copy(id = hbf.id + ":1:1"), Stop("r", "Rheydt Hbf")))
        assertEquals(listOf(hbf.id, "r"), SavedStops.suggestions(state, "Hbf").map { it.id })
        assertEquals(1, SavedStops.suggestions(state, "Hbf", 1).size)
        assertTrue(SavedStops.suggestions(state, "missing").isEmpty())
    }

    @Test fun `serialization never persists historic device distance and ignores malformed entries`() {
        val state = SavedStops.selected(SavedStops.toggle(SavedStopsState(), hbf), hbf)
        val encoded = SavedStops.encode(state)
        assertFalse(encoded.contains("distance"))
        assertEquals(state, SavedStops.decode(encoded))
        val decoded = SavedStops.decode("""{"favorites":[{}, {"id":"bad","name":"Bad geometry","lat":"not numeric","lon":6.4},{"id":"demo:1","name":"Demo"}],"recent":[{"id":"de:05116:23007:1:6","name":"Hbf"},{"id":"de:05116:23007","name":"Duplicate"}]}""")
        assertEquals(listOf("bad"), decoded.favorites.map { it.id })
        assertNull(decoded.favorites.single().point)
        assertEquals(1, decoded.recent.size)
        assertEquals(hbf.id, decoded.recent.single().id)
        assertEquals(SavedStopsState(), SavedStops.decode("broken"))
    }

    @Test fun `empty and demo stops cannot create saved data`() {
        val state = SavedStopsState()
        assertEquals(state, SavedStops.selected(state, Stop("", "Empty")))
        assertEquals(state, SavedStops.toggle(state, Stop("demo:0", "Demo")))
        assertEquals(state, SavedStops.selected(state, Stop("id", " ")))
    }
}
