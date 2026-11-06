package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class JourneyOptionsTest {
    private val now = Instant.parse("2026-11-06T08:00:00Z")
    private val original = Stop("de:05116:23007", "Hbf /Europaplatz", distance = 100)
    private val nearby = Stop("de:05116:23013", "Bismarckstraße", distance = 300)
    private fun departure(code: String = "next", seconds: Long = 600, stop: Stop = original) = Departure(
        "new:006:H", "006", "Rheydt Hbf", "${stop.id}:1:1", stop.name, code,
        now.plusSeconds(seconds), null, "1", observedAt = now)

    @Test fun `walking includes a detour allowance and a separate boarding buffer`() {
        val walk = JourneyOptions.walking(nearby)!!
        assertTrue(walk.seconds > nearby.distance!! / 1.2)
        assertEquals(6, walk.minutes)
        assertEquals(walk.seconds + 60, walk.requiredSeconds)
        assertTrue(walk.label.startsWith("ca."))
        assertNull(JourneyOptions.walking(original.copy(distance = null)))
        assertNull(JourneyOptions.walking(original.copy(distance = -1)))
    }

    @Test fun `only a forecast that fits walking and boarding is labelled reachable`() {
        val walk = JourneyOptions.walking(nearby)!!
        val comfortable = JourneyOptions.reachability(nearby, departure(seconds = walk.requiredSeconds), now)
        assertTrue(comfortable.isPossible)
        assertEquals(ReachabilityState.TIGHT,
            JourneyOptions.reachability(nearby, departure(seconds = walk.requiredSeconds - 1), now).state)
        assertEquals(ReachabilityState.TOO_LATE,
            JourneyOptions.reachability(nearby, departure(seconds = walk.seconds - 1), now).state)
    }

    @Test fun `live delay can turn a missed planned departure into a reachable bus`() {
        val d = departure(seconds = 120)
        assertFalse(JourneyOptions.reachability(nearby, d, now).isPossible)
        assertTrue(JourneyOptions.reachability(nearby, d.copy(estimated = now.plusSeconds(600)), now).isPossible)
    }

    @Test fun `old cancelled past or unknown forecasts never promise reachability`() {
        val d = departure()
        val rejected = listOf(d.copy(observedAt = null), d.copy(observedAt = now.minusSeconds(121)),
            d.copy(observedAt = now.plusSeconds(31)), d.copy(cancelled = true), d.copy(planned = now))
        rejected.forEach { assertFalse(JourneyOptions.reachability(original, it, now).isPossible) }
        assertEquals(ReachabilityState.UNKNOWN,
            JourneyOptions.reachability(original.copy(distance = null), d, now).state)
    }

    @Test fun `alternatives can use another nearby stop and another line to the same destination`() {
        val selected = departure("cancelled").copy(cancelled = true)
        val alternate = departure("other", stop = nearby).copy(lineId = "new:019:H", line = "019", destination = "MG Rheydt Hbf")
        val choice = JourneyOptions.alternatives(selected, listOf(original, nearby), listOf(selected, alternate), now).single()
        assertEquals(alternate, choice.departure)
        assertFalse(choice.isSameStop)
        assertTrue(choice.reachability.isPossible)
    }

    @Test fun `a cancelled journey is not offered again from another platform or stop`() {
        val selected = departure("same").copy(cancelled = true)
        val sameBus = departure("same", seconds = 900, stop = nearby)
        assertTrue(JourneyOptions.alternatives(selected, listOf(original, nearby), listOf(sameBus), now).isEmpty())
    }

    @Test fun `the same journey is still excluded when another boarding stop falls after midnight`() {
        val midnight = Instant.parse("2026-11-06T22:58:00Z")
        val selected = departure("night").copy(planned = midnight, observedAt = midnight, cancelled = true)
        val sameBus = selected.copy(cancelled = false, stopId = nearby.id, planned = midnight.plusSeconds(600))
        assertTrue(JourneyOptions.alternatives(selected, listOf(nearby), listOf(sameBus), midnight).isEmpty())
    }

    @Test fun `alternative selection excludes old cancelled unreachable and wrong direction buses`() {
        val selected = departure("selected").copy(cancelled = true)
        val d = departure()
        val outside = Stop("de:05116:99999", "Far", distance = 1)
        val rejected = listOf(d.copy(destination = "Hbf/Europaplatz"), d.copy(cancelled = true),
            d.copy(observedAt = null), d.copy(observedAt = now.minusSeconds(121)),
            d.copy(planned = now.plusSeconds(5)), departure("outside", stop = outside))
        assertTrue(JourneyOptions.alternatives(selected, listOf(original, nearby), rejected, now).isEmpty())
    }

    @Test fun `alternatives rank earliest feasible departures and collapse duplicate vehicle entries`() {
        val selected = departure("selected").copy(cancelled = true)
        val choices = (1..7).map { departure("trip$it", seconds = 300 + it * 60L) }
        val duplicate = choices.first().copy(stopId = "${original.id}:2:2")
        val result = JourneyOptions.alternatives(selected, listOf(original), choices.reversed() + duplicate, now)
        assertEquals(4, result.size)
        assertEquals(listOf("trip1", "trip2", "trip3", "trip4"), result.map { it.departure.tripCode })
    }

    @Test fun `without location only the original station is offered without a walking promise`() {
        val selected = departure("selected").copy(cancelled = true)
        val choice = JourneyOptions.alternatives(selected, emptyList(), listOf(departure(), departure("elsewhere", stop = nearby)), now).single()
        assertTrue(choice.isSameStop)
        assertEquals(ReachabilityState.UNKNOWN, choice.reachability.state)
        assertFalse(choice.reachability.isPossible)
    }

    @Test fun `destination normalization handles city prefixes spacing and slash formatting without dropping other cities`() {
        assertEquals(JourneyOptions.destinationKey("MG Hbf / Europaplatz"),
            JourneyOptions.destinationKey("Mönchengladbach, Hbf/Europaplatz"))
        assertNotEquals(JourneyOptions.destinationKey("Viersen Hbf"), JourneyOptions.destinationKey("Rheydt Hbf"))
    }
}
