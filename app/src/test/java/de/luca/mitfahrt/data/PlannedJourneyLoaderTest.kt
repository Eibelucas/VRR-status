package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class PlannedJourneyLoaderTest {
    private val now = Instant.parse("2026-11-07T06:30:00Z")
    private val origin = Stop("de:05116:100", "Hehn Kirche")
    private val target = Stop("de:05116:200", "Hbf Europaplatz")
    private fun plan(id: String = "route", origin: Stop = this.origin, destination: Stop = target) =
        RoutePlan(id = id, name = "Zur Schule", origin = origin, destination = destination)
    private fun departure(trip: String = "trip", origin: Stop = this.origin, seconds: Long = 300) =
        Departure("new:006", "006", "Other terminus", "${origin.id}:1:1", origin.name, trip,
            now.plusSeconds(seconds), null, "1", observedAt = now)
    private fun stop(id: String, index: Int, cancelled: Boolean = false) =
        RideStop(id, "Stop $index", null, now.plusSeconds(index * 120L), now.plusSeconds(index * 120L), cancelled = cancelled)
    private fun ride(departure: Departure, after: List<RideStop> = listOf(stop(target.id, 1)),
        before: List<RideStop> = emptyList(), fetchedAt: Instant = now) =
        Ride(departure, before + stop(departure.stopId, 0) + after, before.size, fetchedAt, boarded = false)

    @Test fun `only a served destination after boarding makes a route suggestion`() = runBlocking {
        val departures = listOf("before", "missing", "cancelled-stop", "cancelled-trip", "served").mapIndexed { index, key ->
            departure(key, seconds = 180L + index * 60)
        }
        val loader = PlannedJourneyLoader({ departures }) { d -> when (d.tripCode) {
            "before" -> ride(d, after = listOf(stop("de:05116:300", 2)), before = listOf(stop(target.id, -1)))
            "missing" -> ride(d, after = listOf(stop("de:05116:300", 1)))
            "cancelled-stop" -> ride(d, after = listOf(stop(target.id, 1, cancelled = true)))
            "cancelled-trip" -> ride(d.copy(cancelled = true))
            else -> ride(d)
        } }
        val result = loader.load(listOf(plan()), now)
        assertEquals(listOf("served"), result.departures.getValue("route").map { it.tripCode })
        assertEquals(0, result.failed)
    }

    @Test fun `wrong origins lines and old monitors are rejected before fetching a trip`() = runBlocking {
        val valid = departure("valid").copy(line = "6")
        val all = listOf(valid,
            departure("wrong-stop", origin = Stop("de:05116:999", "Elsewhere")),
            departure("wrong-line").copy(line = "NE6"),
            departure("stale").copy(observedAt = now.minusSeconds(121)),
            departure("future-source").copy(observedAt = now.plusSeconds(31)),
            departure("cancelled").copy(cancelled = true),
            departure("departed", seconds = -60), departure("too-late", seconds = 3601))
        val fetched = mutableListOf<String>()
        val loader = PlannedJourneyLoader({ all }) { fetched += it.tripCode; ride(it) }
        val result = loader.load(listOf(plan().copy(match = RouteMatch.LINE, line = "006")), now)
        assertEquals(listOf("valid"), fetched)
        assertEquals(listOf(valid), result.departures.getValue("route"))
    }

    @Test fun `headsigns prioritize lookups but cannot replace checking the destination sequence`() = runBlocking {
        val wrong = departure("wrong").copy(destination = target.name)
        val good = departure("good", seconds = 420)
        val loader = PlannedJourneyLoader({ listOf(wrong, good) }) { d ->
            if (d == wrong) ride(d, listOf(stop("de:05116:300", 1))) else ride(d)
        }
        assertEquals(listOf(good), loader.load(listOf(plan()), now).departures.getValue("route"))
    }

    @Test fun `line diversity finds an alternative after many buses of another direction`() = runBlocking {
        val wrong = (1..12).map { departure("wrong-$it", seconds = 120L + it * 10) }
        val good = departure("good", seconds = 600).copy(lineId = "new:015", line = "015")
        val fetched = mutableListOf<String>()
        val loader = PlannedJourneyLoader({ wrong + good }) { d ->
            fetched += d.tripCode
            if (d == good) ride(d) else ride(d, listOf(stop("de:05116:300", 1)))
        }
        assertEquals(listOf(good), loader.load(listOf(plan()), now).departures.getValue("route"))
        assertTrue(fetched.size <= 8)
    }

    @Test fun `trip lookups are capped and verified departures are returned in time order`() = runBlocking {
        val fetched = AtomicInteger()
        val all = (1..20).map { departure("trip-$it", seconds = 120L + it * 30) }
        val loader = PlannedJourneyLoader({ all.reversed() }) { fetched.incrementAndGet(); ride(it) }
        val result = loader.load(listOf(plan()), now)
        assertTrue(fetched.get() in 3..8)
        assertEquals(all.take(3), result.departures.getValue("route"))
    }

    @Test fun `boarding monitor and trip checks are shared across plans and refreshed after thirty seconds`() = runBlocking {
        var clock = now
        var boards = 0
        var trips = 0
        val first = departure()
        val laterTarget = Stop("de:05116:300", "Schule")
        val plans = listOf(plan(), plan("other", origin.copy(id = "${origin.id}:2:2"), laterTarget))
        val loader = PlannedJourneyLoader({
            boards++
            listOf(first.copy(observedAt = clock, estimated = if (clock == now) null else first.planned.plusSeconds(120)))
        }) { d ->
            trips++
            ride(d, listOf(stop(target.id, 1), stop(laterTarget.id, 2)), fetchedAt = clock)
        }
        assertEquals(2, loader.load(plans, clock).departures.values.count { it.isNotEmpty() })
        clock = now.plusSeconds(29)
        loader.load(plans, clock)
        assertEquals(1, boards); assertEquals(1, trips)
        clock = now.plusSeconds(30)
        val updated = loader.load(plans, clock)
        assertEquals(2, boards); assertEquals(2, trips)
        assertEquals(first.planned.plusSeconds(120), updated.departures.getValue("route").single().effective)
    }

    @Test fun `lookup errors count affected plans and preserve independently checked departures`() = runBlocking {
        val badOrigin = Stop("de:05116:999", "Unavailable")
        val good = departure("good")
        val broken = departure("broken", seconds = 240)
        val loader = PlannedJourneyLoader({ stop ->
            if (stopKey(stop.id) == badOrigin.id) throw IOException("offline")
            listOf(good, broken)
        }) { d -> if (d == broken) throw IOException("trip unavailable") else ride(d) }
        val result = loader.load(listOf(plan(), plan("bad-origin", badOrigin)), now)
        assertEquals(listOf(good), result.departures.getValue("route"))
        assertTrue(result.departures.getValue("bad-origin").isEmpty())
        assertEquals(2, result.failed)
    }

    @Test fun `cache age cannot revive a trip whose original source has become stale`() = runBlocking {
        var clock = now
        var boards = 0
        var trips = 0
        val d = departure()
        val loader = PlannedJourneyLoader({ boards++; listOf(d) }) {
            trips++
            if (trips == 1) ride(it, fetchedAt = now.minusSeconds(110))
            else ride(it, listOf(stop(target.id, 1, cancelled = true)), fetchedAt = clock)
        }
        assertEquals(listOf(d), loader.load(listOf(plan()), clock).departures.getValue("route"))
        clock = now.plusSeconds(29)
        val result = loader.load(listOf(plan()), clock)
        assertTrue(result.departures.getValue("route").isEmpty())
        assertEquals(1, boards)
        assertEquals(2, trips)
        assertEquals(0, result.failed)
    }

    @Test fun `malformed or stale trip responses cannot validate a route`() = runBlocking {
        val all = listOf(departure("wrong-boarding"), departure("stale-trip", seconds = 420))
        val loader = PlannedJourneyLoader({ all }) { d ->
            if (d.tripCode == "wrong-boarding") ride(d).copy(boardingIndex = 1)
            else ride(d, fetchedAt = now.minusSeconds(121))
        }
        val result = loader.load(listOf(plan()), now)
        assertTrue(result.departures.getValue("route").isEmpty())
        assertEquals(1, result.failed)
    }

    @Test fun `cancellation propagates from both monitor and trip without becoming a lookup error`() = runBlocking {
        val cancelledBoard = PlannedJourneyLoader({ throw CancellationException("closed") }, { ride(it) })
        assertTrue(runCatching { cancelledBoard.load(listOf(plan()), now) }.exceptionOrNull() is CancellationException)
        val cancelledTrip = PlannedJourneyLoader({ listOf(departure()) }, { throw CancellationException("closed") })
        assertTrue(runCatching { cancelledTrip.load(listOf(plan()), now) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `explicit opening works outside a route display window and away from its origin`() = runBlocking {
        val hidden = plan().copy(enabled = false, nearbyOnly = true,
            window = RouteWindow(days = setOf(1), startMinute = 22 * 60, endMinute = 23 * 60))
        val d = departure()
        val loader = PlannedJourneyLoader({ listOf(d) }) { ride(it) }
        assertEquals(listOf(d), loader.load(listOf(hidden), now).departures.getValue("route"))
    }

    @Test fun `concurrent loads share cached requests and never exceed three network lookups`() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger(); val boardCalls = AtomicInteger(); val tripCalls = AtomicInteger()
        suspend fun pause() {
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try { delay(10) } finally { active.decrementAndGet() }
        }
        val plans = (1..3).map { plan("plan-$it", Stop("de:05116:${400 + it}", "Origin $it")) }
        val loader = PlannedJourneyLoader({ stop ->
            boardCalls.incrementAndGet(); pause(); listOf(departure(stop.id, stop))
        }) { d -> tripCalls.incrementAndGet(); pause(); ride(d) }
        coroutineScope { listOf(async { loader.load(plans, now) }, async { loader.load(plans, now) }).awaitAll() }
        assertTrue(maximum.get() in 1..3)
        assertEquals(3, boardCalls.get()); assertEquals(3, tripCalls.get())
    }

    @Test fun `a long sequence of different origins evicts old entries within the cache lifetime`() = runBlocking {
        var clock = now
        val boardCalls = mutableMapOf<String, Int>()
        val loader = PlannedJourneyLoader({ stop ->
            boardCalls[stop.id] = (boardCalls[stop.id] ?: 0) + 1
            (1..8).map { departure("${stop.id}/$it", stop, seconds = 180L + it * 30).copy(observedAt = clock) }
        }) { d -> ride(d, fetchedAt = clock) }
        val plans = (1..15).map { plan("plan-$it", Stop("de:05116:${500 + it}", "Origin $it")) }
        plans.forEachIndexed { index, plan -> clock = now.plusSeconds(index.toLong()); loader.load(listOf(plan), clock) }
        clock = now.plusSeconds(15)
        loader.load(listOf(plans.first()), clock)
        assertEquals(2, boardCalls.getValue(plans.first().origin.id))
    }
}
