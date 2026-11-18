package de.luca.mitfahrt.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Uses VRR answers recorded on 18.11.2026 (shortened), plus synthetic edge cases. */
class StationPlatformsTest {
    private fun json(name: String) = JSONObject(javaClass.classLoader!!.getResource(name)!!.readText())
    private val now = Instant.parse("2026-11-18T08:00:00Z")

    @Test fun `MG Hbf bus platforms carry lines and VRR coordinates`() {
        val result = StationPlatformParser.parse(json("platforms-live-mg-hbf.json"), "de:05116:23007", now)
        val bus = result.platforms.filter { it.kind == PlatformKind.BUS }
        val rail = result.platforms.filter { it.kind == PlatformKind.RAIL }
        assertTrue(bus.size >= 10); assertTrue(rail.isNotEmpty())
        val five = bus.single { it.id == "de:05116:23007:2:5" }
        assertEquals("5", five.name); assertTrue(five.lines.containsAll(listOf("007", "017")))
        assertEquals(51.19776, five.point!!.lat, 1e-6)
        // Bus platform 2 and rail track 2 share a name but are different platforms.
        assertTrue(bus.any { it.name == "2" } && rail.any { it.name == "2" })
        assertTrue(result.platforms.all { it.point != null })
    }

    @Test fun `Rheydt Hbf platforms`() {
        val result = StationPlatformParser.parse(json("platforms-live-rheydt-hbf.json"), "de:05116:23023", now)
        assertEquals((1..9).map { it.toString() }, result.platforms.filter { it.kind == PlatformKind.BUS }.map { it.name })
        assertTrue(PlatformStations.isKnown("de:05116:23023:1:3"))
    }

    private fun event(id: String, lat: Double, line: String = "007") = """{"location":{"id":"$id","type":"platform","coord":[$lat,6.44],
        "properties":{"platformName":"${id.substringAfterLast(':')}"}},"departureTimePlanned":"2026-11-18T08:05:00Z",
        "transportation":{"number":"$line","product":{"class":5},"destination":{"name":"Hbf"}}}"""

    @Test fun `contradicting or far away coordinates are not placed, the platform stays as text`() {
        val root = JSONObject("""{"stopEvents":[${event("de:05116:1:1:1", 51.19)}, ${event("de:05116:1:1:1", 51.20)},
            ${event("de:05116:1:1:2", 51.19)}, ${event("de:05116:1:1:3", 51.19)}, ${event("de:05116:1:1:4", 53.0)}, ${event("de:05116:9:1:1", 51.19)}]}""")
        val result = StationPlatformParser.parse(root, "de:05116:1", now)
        assertEquals(listOf("1", "2", "3", "4"), result.platforms.map { it.name })
        assertNull(result.platforms[0].point)
        assertNotNull(result.platforms[1].point)
        assertNull("far from the station", result.platforms[3].point)
    }

    @Test fun `line search keeps only NEW lines and both directions`() {
        val lines = NetworkParser.lines(json("servinglines-live-line-007.json"))
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.id.startsWith("new:") && it.number == "007" })
        assertTrue(lines.map { it.direction }.distinct().size >= 2)
        val atHbf = NetworkParser.lines(json("servinglines-live-mg-hbf.json"))
        assertTrue(atHbf.none { it.id.startsWith("ddb:") })
        assertTrue(atHbf.any { it.number == "017" })
    }

    @Test fun `stop sequence is reduced to stops in order`() {
        val stops = NetworkParser.stops(json("linestop-live-007.json"))
        assertTrue(stops.size >= 30)
        assertEquals("Viersen Heimer", stops.first().name)
        assertTrue(stops.all { it.id.split(':').size == 3 && it.point != null })
        assertTrue(stops.zipWithNext().none { (a, b) -> a.id == b.id })
    }

    private fun ride(lineId: String) = RouteFixtures.ride().let { it.copy(departure = it.departure.copy(lineId = lineId)) }

    @Test fun `geometry only from a trip of exactly this line and direction`() = runBlocking {
        val line = NetworkLine("new:80007: :H:m26", "007", "Hbf", "", true)
        val stops = listOf(LineStop("de:05116:200", "Halt 0", null))
        val departures = listOf(
            RouteFixtures.ride().departure.copy(lineId = "new:80007: :R:m26", planned = now.plusSeconds(60)),
            RouteFixtures.ride().departure.copy(lineId = "new:80007: :H:j27", planned = now.plusSeconds(600)))
        var loaded: String? = null
        val shaped = NetworkPlanLoader({ departures }, { departure -> loaded = departure.lineId; ride(departure.lineId) },
            { RouteFixtures.shape(RouteFixtures.ride()) }).geometry(line, stops, now)
        assertEquals("new:80007: :H:j27", loaded)
        assertTrue(shaped.points.size > 1); assertNull(shaped.note)
        val none = NetworkPlanLoader({ departures.take(1) }, { error("not called") }, { null }).geometry(line, stops, now)
        assertTrue(none.points.isEmpty()); assertNotNull(none.note)
        val noShape = NetworkPlanLoader({ departures }, { ride(it.lineId) }, { null }).geometry(line, stops, now)
        assertTrue(noShape.points.isEmpty()); assertTrue(noShape.note!!.contains("keinen Streckenverlauf"))
    }
}
