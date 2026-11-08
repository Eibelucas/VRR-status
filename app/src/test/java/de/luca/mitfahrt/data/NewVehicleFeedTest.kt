package de.luca.mitfahrt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class NewVehicleFeedTest {
    private val now = Instant.parse("2026-11-07T20:34:20Z")

    private fun row(time: Any = "2026-11-07 21:34:20"): JSONObject = JSONObject().apply {
        put("raw", JSONObject().apply {
            put("lat", 51.180993); put("lon", 6.371238)
            put("route", "025"); put("route_translated", "025")
            put("terminus", "Mönchengladbach Hauptbahnhof")
            put("time", time); put("sendtime", "2026-11-07 21:34:20")
            put("stationid", "0023370"); put("stationname", "Hehn Kirche")
            put("trip", "02"); put("exclusion_zone", false)
        })
        put("geomatching", JSONObject().apply {
            put("IS_GEORTET", true); put("IS_ONLINE", true)
            put("lat", 51.1815); put("lon", 6.372)
            put("time", "2026-11-07 21:34:00")
            put("route", "025"); put("route_translated", "025")
        })
    }
    private fun parse(value: JSONObject = row(), at: Instant = now) = NewVehicleParser.parseVehicle("2114", value, at)
    private fun JSONObject.raw() = getJSONObject("raw")
    private fun JSONObject.geo() = getJSONObject("geomatching")

    @Test fun `raw coordinates and their own observation time remain authoritative`() {
        val vehicle = parse()!!
        assertEquals(GeoPoint(51.180993, 6.371238), vehicle.point)
        assertEquals(now, vehicle.observedAt)
        assertEquals(now.minusSeconds(20), vehicle.routeObservedAt)
        assertEquals("025", vehicle.line)
        assertEquals("Mönchengladbach Hauptbahnhof", vehicle.destination)
        assertEquals("02", vehicle.tripId)
        assertEquals("0023370", vehicle.currentStopId)
    }

    @Test fun `publication processing and fetch clocks cannot revive a stale observation`() {
        val value = row(now.minusSeconds(91).toString())
        value.raw().put("send_time", now.toEpochMilli()).put("sendtime", now.toString())
        value.geo().put("time", now.toString())
        assertNull(parse(value))
    }

    @Test fun `freshness has exact past and future bounds`() {
        assertNotNull(parse(row(now.minusSeconds(90).toString())))
        assertNull(parse(row(now.minusMillis(90_001).toString())))
        assertNotNull(parse(row(now.plusSeconds(15).toString())))
        assertNull(parse(row(now.plusMillis(15_001).toString())))
    }

    @Test fun `offline unlocated and exclusion zone vehicles stay hidden`() {
        assertNull(parse(row().also { it.geo().put("IS_ONLINE", false) }))
        assertNull(parse(row().also { it.geo().put("IS_GEORTET", false) }))
        assertNull(parse(row().also { it.raw().put("exclusion_zone", true) }))
        assertNull(parse(row().also { it.geo().put("IS_ONLINE", "true") }))
        assertNull(parse(row().also { it.raw().put("exclusion_zone", "false") }))
    }

    @Test fun `missing invalid or null island raw points never borrow map matched coordinates`() {
        assertNull(parse(row().also { it.raw().remove("lat") }))
        assertNull(parse(row().also { it.raw().put("lat", 0).put("lon", 0) }))
        assertNull(parse(row().also { it.raw().put("lat", 91) }))
        assertNull(parse(row().also { it.raw().put("lon", "NaN") }))
    }

    @Test fun `missing invalid and extreme timestamps fail without crashing an entire fleet`() {
        val missing = row().also { it.raw().remove("time"); it.raw().remove("sendtime") }
        assertNull(parse(missing))
        assertNull(parse(row("garbage")))
        assertNull(parse(row("+999999999-12-31T23:59:59Z")))
        val fleet = JSONObject().put("2114", row()).put("broken", row("+999999999-12-31T23:59:59Z"))
        assertEquals(listOf("2114"), NewVehicleParser.parse(fleet, now).map { it.id })
    }

    @Test fun `epoch seconds milliseconds explicit offsets and Berlin local times agree`() {
        listOf<Any>(now.epochSecond, now.toEpochMilli(), now.toString(), "2026-11-07T21:34:20+01:00",
            "2026-11-07T21:34:20", "2026-11-07 21:34:20").forEach { timestamp ->
            assertEquals(timestamp.toString(), now, parse(row(timestamp))!!.observedAt)
        }
    }

    @Test fun `ambiguous autumn wall time requires an explicit anchor instead of guessing from now`() {
        val overlap = Instant.parse("2026-10-25T01:30:10Z")
        val value = row("2026-10-25 02:30:00").also {
            it.raw().remove("sendtime")
            it.geo().put("time", "2026-10-25 02:29:50")
        }
        assertNull(parse(value, overlap))
        value.raw().put("sendtime", "2026-10-25T01:30:05Z")
        val bus = parse(value, overlap)!!
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), bus.observedAt)
        assertEquals(Instant.parse("2026-10-25T01:29:50Z"), bus.routeObservedAt)
    }

    @Test fun `spring clock gap and an unrelated DST anchor do not invent a point time`() {
        assertNull(parse(row("2026-03-29 02:30:00"), Instant.parse("2026-03-29T01:30:00Z")))
        val value = row("2026-10-25 02:30:00").also { it.raw().put("sendtime", "2026-10-25T05:30:00Z") }
        assertNull(parse(value, Instant.parse("2026-10-25T01:30:10Z")))
    }

    @Test fun `missing observation uses explicitly published send timestamps only`() {
        val value = row().also { it.raw().remove("time") }
        assertEquals(now, parse(value)!!.observedAt)
        value.raw().apply { remove("sendtime"); put("send_time", now.toEpochMilli()) }
        assertEquals(now, parse(value)!!.observedAt)
        value.raw().remove("send_time")
        assertNull(parse(value))
    }

    @Test fun `line normalization allows leading zeros but rejects any conflicting geometry identity`() {
        assertNotNull(parse(row().also { it.geo().put("LI_NR", "25") }))
        assertNull(parse(row().also { it.geo().put("LI_NR", "26") }))
        assertNull(parse(row().also { it.geo().put("LI_NR", "025").put("route", "026") }))
        assertEquals("SB81", NewVehicleParser.normalizeLine(" sb 081 "))
        assertNotEquals(NewVehicleParser.normalizeLine("SB1"), NewVehicleParser.normalizeLine("S1"))
    }

    @Test fun `published delay is seconds and course id is not converted into a scheduled trip`() {
        val value = row().also { it.geo().put("PLAN_ABWEICHNG", 90).put("PLAN_ABWEICHNG_MIN", 1) }
        assertEquals(90, parse(value)!!.delaySeconds)
        assertEquals("02", parse(value)!!.tripId)
        value.geo().remove("PLAN_ABWEICHNG")
        assertEquals(60, parse(value)!!.delaySeconds)
        value.geo().put("PLAN_ABWEICHNG_MIN", "unknown")
        assertNull(parse(value)!!.delaySeconds)
    }

    @Test fun `route path keeps only explicitly published day and time without constructing dates`() {
        fun stop(at: String?) = JSONObject().apply {
            put("ORT_POS_BREITE_GPS", 51.180993); put("ORT_POS_LAENGE_GPS", 6.371238)
            put("ORT_REF_ORT_NAME", "Hehn Kirche")
            at?.let { put("ABFAHRT_ZEIT", it) }
        }
        val value = row().also { it.geo().put("FAHRTWEG", JSONArray().put(stop("07 21:35:00")).put(stop(null))) }
        val path = parse(value)!!.routeStops
        assertEquals(2, path.size)
        assertEquals(7, path[0].plannedDayOfMonth)
        assertEquals(21 * 3600 + 35 * 60, path[0].plannedSeconds)
        assertNull(path[1].plannedDayOfMonth)
        assertNull(path[1].plannedSeconds)
        value.geo().put("FAHRTWEG", JSONArray().put(stop("07 99:35:00")))
        assertTrue(parse(value)!!.routeStops.isEmpty())
    }

    @Test fun `missing or malformed geometry time remains unknown while raw observation is usable`() {
        val value = row().also { it.geo().remove("time") }
        assertNotNull(parse(value))
        assertNull(parse(value)!!.routeObservedAt)
        value.geo().put("time", "invalid")
        assertNull(parse(value)!!.routeObservedAt)
    }

    @Test fun `fresh GPS does not revive old route next stop or delay metadata`() {
        val value = row().also {
            it.geo().put("time", now.minusSeconds(91).toString()).put("NEXT_ORT_NR", "23371")
                .put("NEXT_ORT_REF_ORT_NAME", "Old next stop").put("PLAN_ABWEICHNG", 120)
                .put("FRT_START", 72_000).put("SCHEDULE_ID", 123)
        }
        val bus = parse(value)!!
        assertEquals(now, bus.observedAt)
        assertEquals(now.minusSeconds(91), bus.routeObservedAt)
        assertNull(bus.nextStopId); assertNull(bus.nextStopName); assertNull(bus.delaySeconds)
        assertNull(bus.journeyStartSeconds); assertNull(bus.scheduleId); assertTrue(bus.routeStops.isEmpty())
        assertEquals("Mönchengladbach Hauptbahnhof", bus.destination)
    }

    @Test fun `explicit internal and translated route aliases remain distinct from guessed mappings`() {
        val value = row().also {
            it.raw().put("route", "901").put("route_translated", "NE01")
            it.geo().put("route", "901").put("route_translated", "NE01").put("LI_NR", "NE1")
        }
        assertEquals("NE01", parse(value)!!.line)
        value.geo().put("LI_NR", "NE02")
        assertNull(parse(value))
    }

    @Test fun `actual packed MG headsign is display text and not a proven destination`() {
        val value = row().also {
            it.raw().put("terminus", "5MÖNCHENGLADBACH HAUPTBAHNHOF    MPL-HARDTERBR.  MÖNCHENGLADB.HBF                ")
            it.geo().put("LIDNAME", "Mönchengladbach Hauptbahnhof")
        }
        val bus = parse(value)!!
        assertFalse(bus.directionReliable)
        assertEquals("MÖNCHENGLADBACH HAUPTBAHNHOF MPL-HARDTERBR. MÖNCHENGLADB.HBF", bus.destination)
        assertFalse(bus.destination.startsWith("5"))
    }

    @Test fun `actual NEW advertisement never becomes a reliable destination`() {
        val value = row().also {
            it.raw().put("terminus", "5DEIN ZIEL       NEW.DE/BUSFAHRENDEIN ZIEL       NEW.DE/BUSFAHREN                ")
            it.geo().put("LIDNAME", "Dein Ziel New.De/Busfahren")
        }
        val bus = parse(value)!!
        assertFalse(bus.directionReliable)
        assertFalse(bus.destination.startsWith("5"))
        assertFalse(bus.destination.contains("  "))
        assertTrue(bus.destination.contains("NEW.DE/BUSFAHREN"))
    }

    @Test fun `fresh explicitly separated route label can clarify an opaque display`() {
        val value = row().also {
            it.raw().put("terminus", "5MÖNCHENGLADBACH HAUPTBAHNHOF    MPL-HARDTERBR.  MÖNCHENGLADB.HBF")
            it.geo().put("LIDNAME", "Hehn Kirche > Mönchengladbach Hauptbahnhof")
        }
        val bus = parse(value)!!
        assertTrue(bus.directionReliable)
        assertEquals("Mönchengladbach Hauptbahnhof", bus.destination)
        value.geo().put("time", now.minusSeconds(91).toString())
        assertFalse(parse(value)!!.directionReliable)
    }

    @Test fun `plain raw direction stays authoritative while missing or advert labels stay unknown`() {
        assertTrue(parse()!!.directionReliable)
        val blank = row().also { it.raw().put("terminus", "5                                                                                ") }
        assertFalse(parse(blank)!!.directionReliable)
        assertEquals("", parse(blank)!!.destination)
        val absent = row().also {
            it.raw().remove("terminus")
            it.geo().put("LIDNAME", "Mönchengladbach Hauptbahnhof")
        }
        assertFalse(parse(absent)!!.directionReliable)
        absent.geo().put("LIDNAME", "Hehn Kirche > Mönchengladbach Hauptbahnhof")
        assertTrue(parse(absent)!!.directionReliable)
        absent.geo().put("LIDNAME", "Hehn Kirche > Dein Ziel NEW.DE/BUSFAHREN")
        assertFalse(parse(absent)!!.directionReliable)
    }

    @Test fun `opaque display labels remove controls and remain bounded without decoding LED blocks`() {
        val value = row().also { it.raw().put("terminus", "\u00005STADT\t" + "LANGER ANZEIGETEXT ".repeat(12)) }
        val bus = parse(value)!!
        assertFalse(bus.directionReliable)
        assertFalse(bus.destination.any(Char::isISOControl))
        assertFalse(bus.destination.startsWith("5"))
        assertTrue(bus.destination.length <= 96)
    }

    @Test fun `fleet ignores malformed rows and unsafe vehicle identifiers`() {
        val fleet = JSONObject().put("2114", row()).put("../other", row()).put("oops", "not a vehicle")
            .put("offline", row().also { it.geo().put("IS_ONLINE", false) })
        assertEquals(listOf("2114"), NewVehicleParser.parse(fleet, now).map { it.id })
        assertFalse(NewVehicleParser.validVehicleId("../2114"))
        assertFalse(NewVehicleParser.validVehicleId("2114?auth=anything"))
        assertNull(NewVehicleParser.parseVehicle("bad/id", row(), now))
    }

    @Test fun `fleet cache does not renew observation freshness`() = runBlocking {
        var clock = now
        var requests = 0
        val client = NewVehicleClient({ clock }) { requests++; JSONObject().put("2114", row(now.minusSeconds(85).toString())).toString() }
        assertEquals(1, client.fetch().size)
        clock = now.plusSeconds(6)
        assertTrue(client.fetch().isEmpty())
        assertEquals(1, requests)
        clock = now.plusSeconds(10)
        assertTrue(client.fetch().isEmpty())
        assertEquals(2, requests)
    }

    @Test fun `single child null is cached briefly and unsafe ids never issue a request`() = runBlocking {
        var requests = 0
        val client = NewVehicleClient({ now }) { url ->
            assertTrue(url.endsWith("/2114.json")); requests++; "null"
        }
        assertNull(client.vehicle("2114")); assertNull(client.vehicle("2114"))
        assertEquals(1, requests)
        try { client.vehicle("../2114"); fail("Unsafe identifier accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, requests)
    }

    @Test fun `failed refresh cannot be reported as a successful old fleet response`() = runBlocking {
        var clock = now
        var requests = 0
        val client = NewVehicleClient({ clock }) {
            requests++
            if (requests == 1) JSONObject().put("2114", row()).toString() else throw IOException("offline")
        }
        assertEquals(1, client.fetch().size)
        clock = now.plusSeconds(11)
        repeat(2) {
            try { client.fetch(); fail("Failed network request was hidden") } catch (_: IOException) { }
        }
        assertEquals(3, requests)
    }

    @Test fun `malformed response does not enter the fleet cache`() = runBlocking {
        var requests = 0
        val client = NewVehicleClient({ now }) { if (++requests == 1) "not json" else JSONObject().put("2114", row()).toString() }
        try { client.fetch(); fail("Malformed response was accepted") } catch (_: org.json.JSONException) { }
        assertEquals(1, client.fetch().size)
        assertEquals(2, requests)
    }

    @Test fun `parallel fleet requests share one fetch and cancellation remains cancellation`() = runBlocking {
        var requests = 0
        val client = NewVehicleClient({ now }) { requests++; delay(10); JSONObject().put("2114", row()).toString() }
        coroutineScope { listOf(async { client.fetch() }, async { client.fetch() }).awaitAll() }.forEach { assertEquals(1, it.size) }
        assertEquals(1, requests)
        val cancelled = NewVehicleClient({ now }) { throw CancellationException("cancelled") }
        try { cancelled.fetch(); fail("Cancellation was hidden") } catch (_: CancellationException) { }
    }
}
