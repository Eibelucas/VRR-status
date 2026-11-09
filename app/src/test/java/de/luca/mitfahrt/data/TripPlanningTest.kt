package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.TripFixtures.T0
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TripPlanningTest {
    private val bismarck = TripPlace(PlaceKind.STOP, "de:05116:7300", "MG Bismarckplatz", "Mönchengladbach", GeoPoint(51.192, 6.442))
    private val koeln = TripPlace(PlaceKind.STOP, "de:05315:11201", "Köln Hbf", "Köln", GeoPoint(50.943, 6.958))
    private val own = GeoPoint(51.19, 6.44)

    @Test fun `manual search works without any location`() {
        val params = TripRequest.parameters(TripQuery(bismarck, koeln), TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("any", params["type_origin"]); assertEquals("de:05116:7300", params["name_origin"])
        assertEquals("de:05315:11201", params["name_destination"])
        assertTrue(params.keys.none { it.startsWith("type_via") })
        assertFalse(params.values.any { "51.19" in it })
    }

    @Test fun `own position is only sent when chosen and never without a fix`() {
        val mine = TripQuery(TripPlace.MY_LOCATION, koeln)
        assertTrue(mine.needsOwnPosition)
        assertNull(TripRequest.parameters(mine, TripPage(TripTimeMode.NOW, null), T0))
        val params = TripRequest.parameters(mine.copy(ownPosition = own), TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("coord", params["type_origin"])
        assertEquals("6.440000:51.190000:WGS84[dd.ddddd]", params["name_origin"])
    }

    @Test fun `swap exchanges start and destination including own position`() {
        val query = TripQuery(TripPlace.MY_LOCATION, koeln, ownPosition = own)
        val swapped = query.swapped()
        assertEquals(koeln, swapped.origin); assertTrue(swapped.destination.isMyLocation)
        val params = TripRequest.parameters(swapped, TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("de:05315:11201", params["name_origin"]); assertEquals("coord", params["type_destination"])
        assertEquals(query, swapped.swapped())
    }

    @Test fun `departure and arrival searches use local date and time`() {
        val at = Instant.parse("2026-11-10T16:45:00Z") // 17:45 CET
        val dep = TripRequest.parameters(TripQuery(bismarck, koeln, mode = TripTimeMode.DEPART, at = at), TripPage(TripTimeMode.DEPART, at), T0)!!
        assertEquals("dep", dep["itdTripDateTimeDepArr"]); assertEquals("20261110", dep["itdDate"]); assertEquals("1745", dep["itdTime"])
        val arr = TripRequest.parameters(TripQuery(bismarck, koeln, mode = TripTimeMode.ARRIVE, at = at), TripPage(TripTimeMode.ARRIVE, at), T0)!!
        assertEquals("arr", arr["itdTripDateTimeDepArr"]); assertEquals("1745", arr["itdTime"])
        val summer = Instant.parse("2026-07-01T06:05:00Z") // 08:05 CEST
        assertEquals("0805", TripRequest.parameters(TripQuery(bismarck, koeln), TripPage(TripTimeMode.DEPART, summer), T0)!!["itdTime"])
        val now = TripRequest.parameters(TripQuery(bismarck, koeln), TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("0800", now["itdTime"]); assertEquals("dep", now["itdTripDateTimeDepArr"])
    }

    @Test fun `options map only to documented parameters and only when chosen`() {
        val plain = TripRequest.parameters(TripQuery(bismarck, koeln), TripPage(TripTimeMode.NOW, null), T0)!!
        assertNull(plain["maxChanges"]); assertNull(plain["imparedOptionsActive"]); assertNull(plain["excludedMeans"]); assertNull(plain["trITMOT"])
        assertEquals("leasttime", plain["routeType"]); assertEquals("normal", plain["changeSpeed"]); assertEquals("1", plain["useRealtime"])
        val options = TripOptions(excluded = setOf(ProductGroup.TRAIN), maxChanges = 0, walkSpeed = WalkSpeed.SLOW, maxWalkMinutes = 15,
            preference = RoutePreference.FEWEST_CHANGES, wheelchair = true)
        val via = TripPlace(PlaceKind.STOP, "de:05116:7400", "MG Marienplatz")
        val params = TripRequest.parameters(TripQuery(bismarck, koeln, via = via, options = options), TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("0", params["maxChanges"]); assertEquals("slow", params["changeSpeed"])
        assertEquals("100", params["trITMOT"]); assertEquals("15", params["trITMOTvalue100"])
        assertEquals("leastinterchange", params["routeType"])
        assertEquals("checkbox", params["excludedMeans"]); assertEquals("1", params["exclMOT_0"]); assertEquals("1", params["exclMOT_13"])
        assertNull(params["exclMOT_5"])
        assertEquals("1", params["imparedOptionsActive"]); assertEquals("1", params["wheelchair"])
        // EFA treats any value as "on", so unchecked flags must be absent.
        assertNull(params["noSolidStairs"]); assertNull(params["lowPlatformVhcl"])
        assertEquals("de:05116:7400", params["name_via"])
    }

    @Test fun `journey with transfers keeps trains and other operators`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), T0).single()
        assertEquals(listOf(LegKind.WALK, LegKind.TRANSIT, LegKind.WALK, LegKind.TRANSIT, LegKind.WALK), journey.legs.map { it.kind })
        val (bus, train) = journey.transit
        assertTrue(bus.isNew); assertFalse(train.isNew)
        assertEquals("RE8", train.line); assertEquals("Köln/Bonn Flughafen", train.direction); assertEquals(13, train.productClass)
        assertEquals(ProductGroup.TRAIN, ProductGroup.of(train.productClass)); assertEquals(ProductGroup.BUS, ProductGroup.of(bus.productClass))
        assertEquals(1, journey.interchanges)
        assertEquals("Bstg. 2", bus.origin.platform); assertEquals("Gleis 2", train.origin.platform)
        assertEquals("MG Bismarckplatz", bus.origin.name)
        assertEquals(listOf("MG Marienplatz"), bus.intermediate.map { it.name })
        assertEquals(listOf("Umleitung Marienplatz"), bus.notices)
        assertEquals(listOf("Treppe", "Aufzug"), journey.legs[2].footpath)
        assertEquals(180L, bus.delaySeconds)
        assertEquals(4, bus.path.size); assertTrue(train.path.isEmpty())
        assertEquals(T0.plusSeconds(-4 * 60), journey.plannedDeparture)
        assertEquals(T0.plusSeconds(80 * 60), journey.plannedArrival)
        // Footpaths carry planned times only: live bus/train time shifted by the reported walking time.
        assertEquals(T0.plusSeconds(-60), journey.departure)
        assertEquals(T0.plusSeconds(82 * 60), journey.arrival)
        assertEquals(83 * 60L, journey.durationSeconds)
        assertEquals(720L, journey.walkingSeconds)
    }

    @Test fun `NEW departure filtering is not applied to journeys`() {
        // The departure board filter would drop the train; the planner must not.
        val stopEvents = JSONObject().put("stopEvents", JSONArray().put(JSONObject()
            .put("transportation", JSONObject().put("id", "ddb:90E08:R:j26").put("operator", JSONObject().put("code", "DBNW"))
                .put("properties", JSONObject().put("tripCode", "10123")))
            .put("departureTimePlanned", T0.toString()).put("location", JSONObject().put("id", "x"))))
        assertTrue(EfaParser.departures(stopEvents, T0).isEmpty())
        assertEquals(2, TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), T0).single().transit.size)
    }

    @Test fun `planned times are not shown as live`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainEstDep = null, busEstArr = null)), T0).single()
        val train = journey.transit[1]
        assertFalse(train.realtime); assertNull(train.origin.estimatedDeparture); assertNull(train.delaySeconds)
        assertEquals(journey.plannedArrival, journey.arrival)
        assertEquals(train.origin.plannedDeparture, train.departure)
        assertTrue(journey.transit[0].realtime)
        // No live forecast for one side: no statement on the transfer.
        assertTrue(TripTransfers.assess(journey).isEmpty())
    }

    @Test fun `transfer is judged only with live data on both sides`() {
        fun risk(trainEstDep: Long?, busEstArr: Long?) =
            TripTransfers.assess(TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainEstDep, busEstArr)), T0).single())
        assertEquals(TransferRisk.OK, risk(22, 16).single().risk)
        assertEquals(180L, risk(22, 16).single().slackSeconds)
        assertEquals(TransferRisk.TIGHT, risk(21, 17).single().risk)
        assertEquals(TransferRisk.MISSED, risk(20, 18).single().risk)
        assertTrue(risk(null, 18).isEmpty())
        val single = TripTransfers.assess(TripJourney(listOf(TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), T0).single().transit[0]), 0, T0))
        assertTrue(single.isEmpty())
    }

    @Test fun `cancelled leg marks the journey and the transfer as missed`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(trainStatus = listOf("TRIP_CANCELLED"))), T0).single()
        assertTrue(journey.cancelled); assertTrue(journey.transit[1].cancelled); assertFalse(journey.transit[0].cancelled)
        assertEquals(TransferRisk.MISSED, TripTransfers.assess(journey).single().risk)
    }

    @Test fun `stay seated and similar EFA pseudo legs are not transit`() {
        val a = TripFixtures.stop("de:05116:1:1:1", "A", 51.0, 6.0, planDep = 0)
        val b = TripFixtures.stop("de:05116:2:1:1", "B", 51.01, 6.0, planArr = 5)
        val seated = TripFixtures.transit("x", "", "", 97, "Sitzenbleiben", "", "", a, b)
        val bus = TripFixtures.transit("new:80001: :H:m26", "001", "NEW", 5, "Bus", "B", "1", a, b)
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.journey(bus, seated)), T0).single()
        assertEquals(listOf(LegKind.TRANSIT, LegKind.OTHER), journey.legs.map { it.kind })
    }

    @Test fun `a leg can be handed to single-ride tracking only with VRR trip identity`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), T0).single()
        val departure = journey.transit[0].asDeparture(T0)!!
        assertEquals("new:80017: :H:m26", departure.lineId); assertEquals("4711", departure.tripCode)
        assertEquals("de:05116:7300:1:2", departure.stopId); assertEquals(T0, departure.planned)
        assertEquals(T0.plusSeconds(180), departure.estimated); assertEquals("Bstg. 2", departure.platform)
        assertNull(journey.legs[0].asDeparture(T0))
        val unidentified = journey.transit[1].copy(tripCode = "")
        assertNull(unidentified.asDeparture(T0))
    }

    @Test fun `invalid or missing geometry yields no line`() {
        val a = TripFixtures.stop("de:05116:1:1:1", "A", 51.0, 6.0, planDep = 0)
        val b = TripFixtures.stop("de:05116:2:1:1", "B", 51.01, 6.0, planArr = 5)
        // org.json refuses NaN, so an unreadable value stands for a broken coordinate.
        val broken = TripFixtures.transit("new:1", "001", "NEW", 5, "Bus", "B", "1", a, b).put("coords",
            JSONArray().put(JSONArray().put(51.0).put(6.0)).put(JSONArray().put("kaputt").put(6.0)))
        val nested = TripFixtures.transit("new:1", "001", "NEW", 5, "Bus", "B", "2", a, b).put("coords",
            JSONArray().put(JSONArray(listOf(JSONArray(listOf(51.0, 6.0)), JSONArray(listOf(51.01, 6.0))))))
        val journeys = TripParser.journeys(TripFixtures.root(TripFixtures.journey(broken), TripFixtures.journey(nested)), T0)
        assertTrue(journeys[0].legs.single().path.isEmpty())
        assertEquals(2, journeys[1].legs.single().path.size)
    }

    @Test fun `empty or broken answers produce no journeys`() {
        assertTrue(TripParser.journeys(JSONObject(), T0).isEmpty())
        assertTrue(TripParser.journeys(JSONObject().put("journeys", JSONArray().put(JSONObject().put("legs", JSONArray()))), T0).isEmpty())
        val messages = JSONObject().put("systemMessages", JSONArray().put(JSONObject().put("type", "error").put("module", "BROKER").put("code", -8010)))
        assertEquals(listOf("BROKER:-8010"), TripParser.errors(messages))
    }

    @Test fun `place search returns stops addresses and places best first`() {
        val root = JSONObject().put("locations", JSONArray()
            .put(JSONObject().put("id", "streetID:1").put("name", "Mönchengladbach, Hauptstraße 10").put("disassembledName", "Hauptstraße 10")
                .put("type", "address").put("matchQuality", 900).put("coord", JSONArray(listOf(51.19, 6.44)))
                .put("parent", JSONObject().put("name", "Mönchengladbach").put("type", "locality")))
            .put(JSONObject().put("id", "de:05116:7300").put("name", "MG Bismarckplatz").put("type", "stop").put("isBest", true)
                .put("matchQuality", 950).put("coord", JSONArray(listOf(51.192, 6.442))))
            .put(JSONObject().put("id", "poi:42").put("name", "Borussia-Park").put("type", "poi").put("matchQuality", 500))
            .put(JSONObject().put("id", "de:05116:7300").put("name", "MG Bismarckplatz").put("type", "stop"))
            .put(JSONObject().put("id", "").put("name", "ohne Kennung").put("type", "stop")))
        val places = TripParser.places(root)
        assertEquals(listOf(PlaceKind.STOP, PlaceKind.ADDRESS, PlaceKind.POI), places.map { it.kind })
        assertEquals("Hauptstraße 10", places[1].name); assertEquals("Mönchengladbach", places[1].locality)
        assertEquals(51.19, places[1].point!!.lat, 0.0001)
        assertEquals(mapOf("locationServerActive" to "1", "type_sf" to "any", "name_sf" to "Hauptstr", "anyMaxSizeHitList" to "20"),
            TripRequest.placeSearch("  Hauptstr "))
    }

    @Test fun `places without id are sent as public coordinates`() {
        val coordinateOnly = TripPlace(PlaceKind.ADDRESS, "", "Hauptstraße 10", point = GeoPoint(51.19, 6.44))
        val params = TripRequest.parameters(TripQuery(coordinateOnly, koeln), TripPage(TripTimeMode.NOW, null), T0)!!
        assertEquals("coord", params["type_origin"]); assertEquals("6.440000:51.190000:WGS84[dd.ddddd]:Hauptstraße 10", params["name_origin"])
        assertNull(TripRequest.parameters(TripQuery(TripPlace(PlaceKind.OTHER, "", "?"), koeln), TripPage(TripTimeMode.NOW, null), T0))
    }

    @Test fun `journey identity ignores live changes`() {
        val a = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(22, 16)), T0).single()
        val b = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain(30, 19)), T0).single()
        assertEquals(a.key, b.key)
        assertNotEquals(a.arrival, b.arrival)
    }

    @Test fun `journey with two transfers is assessed per transfer`() {
        val f = TripFixtures
        val a = f.stop("de:05116:1:1:1", "A", 51.20, 6.44, planDep = 0, estDep = 1)
        val b1 = f.stop("de:05116:2:1:1", "B", 51.19, 6.44, planArr = 10, estArr = 11)
        val b2 = f.stop("de:05116:2:2:1", "B", 51.19, 6.44, planDep = 14, estDep = 14)
        val c1 = f.stop("de:05116:3:1:1", "C", 51.18, 6.44, planArr = 25, estArr = 29)
        val c2 = f.stop("de:05116:3:2:1", "C", 51.18, 6.44, planDep = 30, platform = "Gleis 1")
        val d = f.stop("de:05315:4:1:1", "D", 50.94, 6.96, planArr = 70)
        val journey = TripParser.journeys(f.root(f.journey(
            f.transit("new:80001: :H:m26", "001", "NEW", 5, "Bus", "B", "1", a, b1),
            f.transit("vrr:U70", "U70", "RBG", 4, "Stadtbahn", "C", "2", b2, c1),
            f.walk(c1, c2, 120, productClass = 99),
            f.transit("ddb:RE1", "RE1", "DBNW", 0, "Zug", "D", "3", c2, d, realtime = false),
            interchanges = 2)), T0).single()
        assertEquals(2, journey.interchanges)
        assertEquals(listOf("001", "U70", "RE1"), journey.transit.map { it.line })
        assertEquals(listOf(ProductGroup.BUS, ProductGroup.LIGHT_RAIL, ProductGroup.TRAIN), journey.transit.map { ProductGroup.of(it.productClass) })
        val transfers = TripTransfers.assess(journey)
        // B: bus 11 -> tram 14 (live both) = 3 min. C: tram 29 + 2 min walk -> train has no live forecast: no statement.
        assertEquals(1, transfers.size)
        assertEquals(TransferRisk.OK, transfers.single().risk); assertEquals(180L, transfers.single().slackSeconds)
        // Train without forecast: arrival stays the planned one.
        assertEquals(T0.plusSeconds(70 * 60), journey.arrival)
        assertEquals(T0.plusSeconds(60), journey.departure)
    }
    @Test fun `recorded live VRR places and departure arrival journeys parse correctly`() {
        fun sample(name: String) = org.json.JSONObject(javaClass.classLoader!!.getResource("planner-live-$name.json")!!.readText())
        assertTrue(TripParser.places(sample("places")).any { it.kind == PlaceKind.STOP && it.id == "de:05116:23007" })
        for (mode in listOf("dep", "arr")) {
            val journeys = TripParser.journeys(sample(mode), java.time.Instant.now())
            assertTrue(journeys.isNotEmpty())
            assertTrue(journeys.any { it.legs.count { leg -> leg.kind == LegKind.TRANSIT } >= 2 })
            assertTrue(journeys.flatMap { it.legs }.any { it.path.size > 2 })
            assertTrue(journeys.flatMap { it.legs }.any { it.line == "025" })
        }
    }

    @Test fun `old forecasts cannot generate a current transfer warning`() {
        val journey = TripParser.journeys(TripFixtures.root(TripFixtures.busAndTrain()), TripFixtures.T0).single()
        assertTrue(TripTransfers.assess(journey, TripFixtures.T0.plusSeconds(121)).isEmpty())
    }

}
