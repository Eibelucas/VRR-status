package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test

class TripResultPreferencesTest {
    private val now = TripFixtures.T0
    private val connection = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), now).single()

    @Test fun `avoiding a station matches platforms of that station`() {
        assertTrue(TripResultPreferences.apply(listOf(connection),
            TripOptions(excludedStopIds = setOf("de:05116:7500")), now).isEmpty())
        assertEquals(listOf(connection), TripResultPreferences.apply(listOf(connection),
            TripOptions(excludedStopIds = setOf("de:05116:7400")), now))
    }

    @Test fun `preferred lines sort first without hiding alternatives`() {
        val legs = connection.legs.map { if (it.line == "017") it.copy(line = "025") else it }
        val other = connection.copy(legs = legs)
        assertEquals(listOf(connection, other), TripResultPreferences.apply(listOf(other, connection),
            TripOptions(preferredLines = setOf("17")), now))
    }

    @Test fun `extra transfer time is measured after walking and ignores stale live forecasts`() {
        val options = TripOptions(minimumTransferMinutes = 4)
        assertTrue(TripResultPreferences.apply(listOf(connection), options, now).isEmpty())
        assertEquals(listOf(connection), TripResultPreferences.apply(listOf(connection), options, now.plusSeconds(121)))
        assertTrue(TripResultPreferences.apply(listOf(connection), options.copy(minimumTransferMinutes = 5), now.plusSeconds(121)).isEmpty())
    }

    @Test fun `custom walking pace changes the local reachability estimate`() {
        val stop = Stop("de:05116:7300", "Bismarckplatz", distance = 200)
        assertEquals(225L, JourneyOptions.walking(stop, 120)?.seconds)
        assertEquals(300L, JourneyOptions.walking(stop, 90)?.seconds)
    }
}
