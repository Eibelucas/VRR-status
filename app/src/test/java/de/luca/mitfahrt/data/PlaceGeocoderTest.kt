package de.luca.mitfahrt.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaceGeocoderTest {
    private val mgCenter = GeoPoint(51.1950, 6.4400)

    private fun feature(
        lat: Double,
        lon: Double,
        name: String = "",
        street: String = "",
        housenumber: String = "",
        postcode: String = "",
        city: String = "Mönchengladbach",
        osmKey: String = "shop",
        osmValue: String = "supermarket",
        type: String = "house"
    ): JSONObject = JSONObject().apply {
        put("type", "Feature")
        put("geometry", JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().put(lon).put(lat))
        })
        put("properties", JSONObject().apply {
            if (name.isNotBlank()) put("name", name)
            if (street.isNotBlank()) put("street", street)
            if (housenumber.isNotBlank()) put("housenumber", housenumber)
            if (postcode.isNotBlank()) put("postcode", postcode)
            if (city.isNotBlank()) put("city", city)
            put("osm_key", osmKey)
            put("osm_value", osmValue)
            put("type", type)
        })
    }

    @Test
    fun `parses businesses house addresses and skips duplicate transit stops`() {
        val root = JSONObject().put("features", JSONArray().apply {
            put(feature(51.1948, 6.4383, name = "Minto", street = "Am Minto", housenumber = "3",
                osmKey = "shop", osmValue = "mall", type = "house"))
            put(feature(51.1961, 6.4410, street = "Hindenburgstraße", housenumber = "75", postcode = "41061",
                osmKey = "place", osmValue = "house", type = "house"))
            put(feature(51.1965, 6.4450, name = "MG Hbf Bussteig 1",
                osmKey = "highway", osmValue = "bus_stop", type = "house"))
            put(feature(51.1900, 6.4300, name = "REWE", street = "Bismarckstraße", housenumber = "20",
                osmKey = "shop", osmValue = "supermarket", type = "house"))
        })

        val places = PlaceGeocoder.parse(root, mgCenter)
        assertEquals(3, places.size)

        val minto = places.first { it.name.contains("Minto") }
        assertEquals(PlaceKind.ADDRESS, minto.kind)
        assertTrue(minto.name.contains("Am Minto 3"))

        val house = places.first { it.name == "Hindenburgstraße 75" }
        assertEquals(PlaceKind.ADDRESS, house.kind)
        assertTrue(house.locality.contains("Mönchengladbach"))

        val rewe = places.first { it.name.contains("REWE") }
        assertNotNull(rewe.point)
    }

    @Test
    fun `merge ranks local businesses alongside VRR stops and keeps separate branches distinct`() {
        val vrrStop = TripPlace(
            id = "de:05116:23007",
            name = "Mönchengladbach Hauptbahnhof",
            locality = "Mönchengladbach",
            kind = PlaceKind.STOP,
            point = GeoPoint(51.1965, 6.4462)
        )
        val reweClose = TripPlace(
            id = "",
            name = "REWE",
            locality = "Bismarckstraße 20, Mönchengladbach",
            kind = PlaceKind.POI,
            point = GeoPoint(51.1920, 6.4410),
            category = "Supermarkt"
        )
        val reweSecond = TripPlace(
            id = "",
            name = "REWE",
            locality = "Korschenbroicher Str. 100, Mönchengladbach",
            kind = PlaceKind.POI,
            point = GeoPoint(51.1990, 6.4620),
            category = "Supermarkt"
        )
        assertNotEquals("Different branches must have distinct keys", reweClose.key, reweSecond.key)

        val merged = PlaceGeocoder.merge("REWE", listOf(vrrStop), listOf(reweSecond, reweClose), mgCenter)
        assertEquals(3, merged.size)
        assertEquals(reweClose.key, merged.first().key)
        assertTrue(merged.any { it.key == reweSecond.key })
    }

    @Test
    fun `coordinate routing label strips colons safely`() {
        val place = TripPlace(
            kind = PlaceKind.POI,
            id = "",
            name = "Café: Zentrum",
            locality = "Mönchengladbach",
            point = GeoPoint(51.195, 6.440)
        )
        val params = TripRequest.place("origin", place, ownPosition = null)!!
        assertEquals("coord", params["type_origin"])
        val coordName = params["name_origin"]!!
        assertTrue(coordName.endsWith(":Café Zentrum"))
    }
}
