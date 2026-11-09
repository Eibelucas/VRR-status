package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.TripFixtures.T0
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TripSearchTest {
    private val a = TripPlace(PlaceKind.STOP, "de:05116:1", "A")
    private fun to(id: String) = TripPlace(PlaceKind.STOP, "de:05116:$id", "Ziel $id")
    private fun query(id: String, mode: TripTimeMode = TripTimeMode.NOW) = TripQuery(a, to(id), mode = mode, at = if (mode == TripTimeMode.NOW) null else T0)

    private fun journey(minutes: Long, delay: Long? = null, cancelled: Boolean = false, line: String = "017"): TripJourney {
        val dep = T0.plusSeconds(minutes * 60)
        val origin = TripStopTime("de:05116:1:1:1", "A", plannedDeparture = dep, estimatedDeparture = delay?.let { dep.plusSeconds(it) })
        val destination = TripStopTime("de:05116:2:1:1", "B", plannedArrival = dep.plusSeconds(900),
            estimatedArrival = delay?.let { dep.plusSeconds(900 + it) })
        val leg = TripLeg(LegKind.TRANSIT, origin, destination, lineId = "new:80$line", line = line, tripCode = "t$minutes",
            realtime = delay != null, cancelled = cancelled)
        return TripJourney(listOf(leg), 0, T0)
    }

    private suspend fun settle() = repeat(10) { yield() }

    @Test fun `late answer of an older search is never shown`() = runBlocking {
        val gates = mutableMapOf<String, CompletableDeferred<List<TripJourney>>>()
        // Like a blocking HTTP call: finishes even after its coroutine was cancelled.
        val engine = TripSearchEngine(this, { q, _ -> withContext(NonCancellable) { gates.getOrPut(q.destination.id) { CompletableDeferred() }.await() } }, { T0 })
        engine.search(query("old")); settle()
        engine.search(query("new")); settle()
        gates.getValue("de:05116:new").complete(listOf(journey(10)))
        settle()
        gates.getValue("de:05116:old").complete(listOf(journey(0), journey(5)))
        settle()
        val state = engine.state.value
        assertEquals("de:05116:new", state.query!!.destination.id)
        assertEquals(listOf("t10"), state.journeys.map { it.legs[0].tripCode })
    }

    @Test fun `rapid parameter changes cancel running requests`() = runBlocking {
        var cancelled = 0
        var started = 0
        val engine = TripSearchEngine(this, { q, _ ->
            started++
            try { CompletableDeferred<Unit>().await(); emptyList() }
            catch (e: kotlinx.coroutines.CancellationException) { cancelled++; throw e }
        }, { T0 })
        engine.search(query("1")); settle()
        engine.search(query("1", TripTimeMode.DEPART)); settle()
        engine.search(query("1", TripTimeMode.ARRIVE)); settle()
        assertEquals(3, started); assertEquals(2, cancelled)
        assertTrue(engine.state.value.loading)
        assertEquals(TripTimeMode.ARRIVE, engine.state.value.query!!.mode)
        engine.clear(); settle()
        assertEquals(3, cancelled)
        assertNull(engine.state.value.query)
    }

    @Test fun `later and earlier connections extend the list without duplicates`() = runBlocking {
        val pages = mutableListOf<TripPage>()
        val engine = TripSearchEngine(this, { _, page ->
            pages += page
            when {
                page.mode == TripTimeMode.NOW -> listOf(journey(0), journey(10), journey(20))
                page.mode == TripTimeMode.DEPART -> listOf(journey(20), journey(30), journey(40))
                else -> listOf(journey(-10), journey(0))
            }
        }, { T0 })
        engine.search(query("x")); settle()
        engine.later(); settle()
        assertEquals(TripPage(TripTimeMode.DEPART, T0.plusSeconds(20 * 60)), pages[1])
        engine.earlier(); settle()
        assertEquals(TripPage(TripTimeMode.ARRIVE, T0.plusSeconds(15 * 60)), pages[2])
        assertEquals(listOf(-10L, 0, 10, 20, 30, 40).map { "t$it" }, engine.state.value.journeys.map { it.legs[0].tripCode })
        assertFalse(engine.state.value.noLater)
    }

    @Test fun `paging reports the end and retries one minute further once`() = runBlocking {
        val pages = mutableListOf<TripPage>()
        val engine = TripSearchEngine(this, { _, page -> pages += page; listOf(journey(0)) }, { T0 })
        engine.search(query("x")); settle()
        engine.later(); settle()
        assertEquals(listOf(T0, T0.plusSeconds(60)), pages.drop(1).map { it.at })
        assertTrue(engine.state.value.noLater)
        assertEquals(TripPaging.NONE, engine.state.value.paging)
    }

    @Test fun `paging answer of a replaced search is dropped`() = runBlocking {
        val gate = CompletableDeferred<List<TripJourney>>()
        val engine = TripSearchEngine(this, { q, page ->
            if (page.mode == TripTimeMode.DEPART) withContext(NonCancellable) { gate.await() } else listOf(journey(if (q.destination.id.endsWith("2")) 50 else 0))
        }, { T0 })
        engine.search(query("1")); settle()
        engine.later(); settle()
        engine.search(query("2")); settle()
        gate.complete(listOf(journey(30)))
        settle()
        assertEquals(listOf("t50"), engine.state.value.journeys.map { it.legs[0].tripCode })
        assertEquals(TripPaging.NONE, engine.state.value.paging)
    }

    @Test fun `delay and cancellation arrive while results are open`() = runBlocking {
        var live = false
        val pages = mutableListOf<TripPage>()
        var now = T0
        val engine = TripSearchEngine(this, { _, page ->
            pages += page
            if (!live) listOf(journey(0), journey(10)) else listOf(journey(0, delay = 300), journey(10, cancelled = true))
        }, { now })
        engine.search(query("x")); settle()
        assertNull(engine.state.value.journeys[0].transit[0].delaySeconds)
        live = true; now = T0.plusSeconds(30)
        engine.refresh(); settle()
        val state = engine.state.value
        assertEquals(TripPage(TripTimeMode.DEPART, T0, 3), pages.last())
        assertEquals(300L, state.journeys[0].transit[0].delaySeconds)
        assertTrue(state.journeys[1].cancelled)
        assertEquals(now, state.fetchedAt)
    }

    @Test fun `a journey missing from a refresh keeps its old data age`() = runBlocking {
        var refresh = false
        val engine = TripSearchEngine(this, { _, _ -> if (refresh) listOf(journey(0, delay = 60)) else listOf(journey(0), journey(10)) }, { T0 })
        engine.search(query("x")); settle()
        refresh = true
        engine.refresh(); settle()
        val journeys = engine.state.value.journeys
        assertEquals(2, journeys.size)
        assertEquals(60L, journeys[0].transit[0].delaySeconds)
        assertNull(journeys[1].transit[0].delaySeconds)
    }

    @Test fun `errors keep paging results and show a readable message`() = runBlocking {
        var fail = false
        val engine = TripSearchEngine(this, { _, _ -> if (fail) throw java.net.UnknownHostException() else listOf(journey(0)) }, { T0 })
        engine.search(query("x")); settle()
        fail = true
        engine.later(); settle()
        assertEquals(1, engine.state.value.journeys.size)
        assertEquals("Keine Verbindung zum VRR. Prüfe dein Internet.", engine.state.value.pageError)
        engine.search(query("y")); settle()
        assertEquals("Keine Verbindung zum VRR. Prüfe dein Internet.", engine.state.value.error)
        assertTrue(engine.state.value.journeys.isEmpty())
        val missing = TripSearchEngine(this, { _, _ -> throw TripSearchException("Dein Standort ist gerade nicht bekannt. Wähle einen Start aus.") }, { T0 })
        missing.search(query("z")); settle()
        assertEquals("Dein Standort ist gerade nicht bekannt. Wähle einen Start aus.", missing.state.value.error)
    }

    @Test fun `identical searches are answered from a short cache`() = runBlocking {
        var calls = 0
        var now = T0
        val engine = TripSearchEngine(this, { _, _ -> calls++; listOf(journey(0)) }, { now })
        engine.search(query("x")); settle()
        engine.search(query("y")); settle()
        now = T0.plusSeconds(30)
        engine.search(query("x")); settle()
        assertEquals(2, calls); assertTrue(engine.state.value.fromCache)
        assertEquals(T0, engine.state.value.fetchedAt)
        engine.search(query("x"), force = true); settle()
        assertEquals(3, calls)
        now = T0.plusSeconds(200)
        engine.search(query("y")); settle()
        assertEquals(4, calls)
    }

    @Test fun `empty answer is an empty state and not an error`() = runBlocking {
        val engine = TripSearchEngine(this, { _, _ -> emptyList() }, { T0 })
        engine.search(query("x")); settle()
        assertTrue(engine.state.value.empty); assertNull(engine.state.value.error)
    }

    @Test fun `recent searches never store the own position and can be removed one by one`() {
        val home = TripPlace(PlaceKind.ADDRESS, "streetID:1", "Hauptstraße 10", "Mönchengladbach", GeoPoint(51.19, 6.44))
        val withFix = TripPlace(PlaceKind.MY_LOCATION, "", "Mein Standort", point = GeoPoint(51.123456, 6.654321))
        var state = TripPlaces.remember(TripPlacesState(), withFix, to("1"), T0)
        state = TripPlaces.remember(state, a, home, T0.plusSeconds(60))
        state = TripPlaces.remember(state, a, a, T0) // same start and destination: ignored
        state = TripPlaces.remember(state, a, TripPlace.MY_LOCATION, T0) // own position as destination is not stored either
        val encoded = TripPlaces.encode(state)
        assertFalse("51.123456" in encoded); assertFalse("6.654321" in encoded)
        val decoded = TripPlaces.decode(encoded)
        assertEquals(2, decoded.recent.size)
        assertTrue(decoded.recent[1].origin.isMyLocation); assertNull(decoded.recent[1].origin.point)
        assertEquals("Hauptstraße 10", decoded.recent[0].destination.name)
        val removed = TripPlaces.removeRecent(decoded, decoded.recent[0].key)
        assertEquals(listOf(to("1").key), removed.recent.map { it.destination.key })
    }

    @Test fun `saved destinations come first in suggestions and survive pruning`() {
        val school = to("schule")
        var state = TripPlaces.save(TripPlacesState(), school, "Schule")
        state = TripPlaces.save(state, TripPlace.MY_LOCATION)
        state = TripPlaces.remember(state, a, to("kino"), T0)
        assertEquals(1, state.saved.size); assertEquals("Schule", state.saved[0].title)
        assertEquals(listOf(school.key, to("kino").key, a.key), TripPlaces.suggestions(state, "").map { it.key })
        assertEquals(listOf(to("kino").key), TripPlaces.suggestions(state, "KIN").map { it.key })
        val pruned = TripPlaces.prune(state, T0.plusSeconds(31L * 86_400), 30)
        assertTrue(pruned.recent.isEmpty()); assertEquals(1, pruned.saved.size)
        assertEquals(state, TripPlaces.prune(state, T0.plusSeconds(400L * 86_400), 0))
        assertTrue(TripPlaces.unsave(state, school).saved.isEmpty())
        assertEquals(TripPlacesState(), TripPlaces.decode("kaputt"))
    }
}
