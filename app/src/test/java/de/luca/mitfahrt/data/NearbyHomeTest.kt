package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NearbyHomeTest {
    @Test fun `nearby station groups collapse platform duplicates and keep distance order`() = runBlocking {
        val loader = NearbyHomeLoader({ listOf(Stop("de:1:2", "Far", distance = 400),
            Stop("de:1:1", "Near", distance = 100), Stop("de:1:1:1:1", "Near platform", distance = 110)) }, { emptyList() })
        assertEquals(listOf("de:1:1", "de:1:2"), loader.stops(GeoPoint(51.2, 6.4)).map { it.id })
    }
    @Test fun `one failed departure monitor does not discard other stops`() = runBlocking {
        val d = DemoRide.create().departure
        val loader = NearbyHomeLoader({ emptyList() }, { if (it.id == "bad") error("offline") else listOf(d) })
        val result = loader.departures(listOf(Stop("bad", "Bad"), Stop("good", "Good")))
        assertEquals(1, result.failed)
        assertEquals(listOf(d), result.departures)
    }
    @Test fun `departure lookups are bounded and cancellation is not reported as an ordinary error`() = runBlocking {
        val active = AtomicInteger(); val max = AtomicInteger()
        val loader = NearbyHomeLoader({ emptyList() }, {
            val count = active.incrementAndGet(); max.updateAndGet { previous -> maxOf(previous, count) }
            delay(10); active.decrementAndGet(); emptyList()
        })
        loader.departures((1..12).map { Stop("$it", "Stop $it") })
        assertTrue(max.get() in 1..3)
        val cancelled = NearbyHomeLoader({ emptyList() }, { throw CancellationException("screen closed") })
        assertTrue(runCatching { cancelled.departures(listOf(Stop("1", "Stop"))) }.exceptionOrNull() is CancellationException)
    }
    @Test fun `old imprecise and invalid device positions cannot silently choose nearby stops`() {
        val point = GeoPoint(51.2, 6.4)
        assertTrue(validHomeFix(point, 30f, 10_000))
        assertTrue(validHomeFix(point, 2000f, 10_000))
        assertFalse(validHomeFix(point, 30f, 90_001))
        assertFalse(validHomeFix(point, 30f, -100))
        assertFalse(validHomeFix(point, 5000f, 0))
        assertFalse(validHomeFix(GeoPoint(Double.NaN, 6.4), 30f, 0))
    }
}
