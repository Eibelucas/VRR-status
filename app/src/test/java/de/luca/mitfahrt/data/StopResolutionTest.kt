package de.luca.mitfahrt.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StopResolutionTest {
    private val original = Stop("20023370", "Mönchengladbach, Hehn Kirche")
    private val id = "de:05116:23370"
    private val point = GeoPoint(51.180993, 6.371238)
    private fun location(stopId: String = id, alias: String? = original.id): JSONObject = JSONObject().apply {
        put("id", stopId); put("isGlobalId", true); put("type", "stop"); put("name", original.name)
        put("coord", JSONArray(listOf(point.lat, point.lon)))
        if (alias != null) put("properties", JSONObject().put("stopId", alias))
    }
    private fun root(vararg locations: JSONObject) = JSONObject().put("locations", JSONArray(locations.toList()))
    private fun rejected(root: JSONObject, stop: Stop = original) {
        val error = runCatching { EfaParser.resolveStop(root, stop) }.exceptionOrNull()
        assertTrue("Resolution must fail rather than guess a station", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("Haltestelle"))
    }

    @Test fun `numeric search alias resolves to the explicit global stop and its public coordinates`() {
        assertEquals(Stop(id, original.name, point), EfaParser.resolveStop(root(location()), original.copy(distance = 73)))
    }

    @Test fun `client fast path normalizes an already located global stop without network access`() = runBlocking {
        val selected = original.copy(id = "$id:1:6", point = point, distance = 70)
        assertEquals(Stop(id, original.name, point), VrrClient().resolveStop(selected))
    }

    @Test fun `a matching name or shared number suffix cannot replace an explicit identity link`() {
        rejected(root(location(alias = "20099999")))
        rejected(root(location(alias = null)))
        rejected(root(location(stopId = "de:05116:23370:1:1", alias = null)))
    }

    @Test fun `two distinct global stops advertising the same alias are ambiguous`() {
        rejected(root(location(), location(stopId = "de:05116:99999")))
    }

    @Test fun `exact global identity works without an alias and normalizes platform ids`() {
        val original = Stop("$id:1:6", "Hehn", distance = 90)
        val result = EfaParser.resolveStop(root(location(alias = null)), original)
        assertEquals(Stop(id, this.original.name, point), result)
        assertNull(result.distance)
    }

    @Test fun `an alias in assigned stops explicitly resolves its canonical station`() {
        val assigned = location().apply { remove("type") }
        val parent = location(alias = null).apply { put("assignedStops", JSONArray().put(assigned)) }
        assertEquals(Stop(id, original.name, point), EfaParser.resolveStop(root(parent), original))
    }

    @Test fun `an assigned stop without a canonical id can explicitly identify its parent`() {
        val parent = location(alias = null).put("assignedStops", JSONArray().put(
            JSONObject().put("properties", JSONObject().put("stopId", original.id))))
        assertEquals(Stop(id, original.name, point), EfaParser.resolveStop(root(parent), original))
    }

    @Test fun `an explicit assigned station identity does not silently become a different parent station`() {
        val assigned = location()
        val parent = location(stopId = "de:05116:99999", alias = null).put("assignedStops", JSONArray().put(assigned))
        assertEquals(id, EfaParser.resolveStop(root(parent), original).id)
    }

    @Test fun `duplicate representations of one station are merged and can enrich missing public coordinates`() {
        val first = location().apply { remove("coord") }
        val second = location(stopId = "$id:1:6", alias = null)
        assertEquals(Stop(id, original.name, point), EfaParser.resolveStop(root(first, second), original))
    }

    @Test fun `nonstop and numeric only responses cannot be accepted as canonical station identities`() {
        rejected(root(location().put("type", "street")))
        rejected(root(location(stopId = original.id)))
        rejected(JSONObject())
    }

    @Test fun `invalid backend coordinates are dropped while already known public coordinates remain available`() {
        val bad = location().put("coord", JSONArray(listOf(999.0, 6.0)))
        assertNull(EfaParser.resolveStop(root(bad), original).point)
        assertEquals(point, EfaParser.resolveStop(root(bad), original.copy(point = point)).point)
    }

    @Test fun `an unrelated assigned alias cannot match by name`() {
        val parent = location(alias = null).put("assignedStops", JSONArray().put(location(alias = "20099999")))
        rejected(root(parent))
    }
}
