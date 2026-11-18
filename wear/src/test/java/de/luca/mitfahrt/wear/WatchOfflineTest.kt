package de.luca.mitfahrt.wear

import de.luca.mitfahrt.shared.WatchRide
import de.luca.mitfahrt.shared.WatchTile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Synthetic snapshots and VRR answers. */
class WatchOfflineTest {
    private val t0 = Instant.parse("2026-11-18T07:00:00Z")
    private fun ride(sent: Instant, boarding: Instant = t0.plusSeconds(600)) = WatchRide(sent, active = true, running = true,
        fetchedAt = sent, line = "007", destination = "Hbf", boardingAt = boarding)

    @Test fun `old snapshot is offline for a while, then expires`() {
        val r = ride(t0)
        assertEquals(WatchDataState.LIVE, WatchFreshness.state(r, t0.plusSeconds(60)))
        assertEquals(WatchDataState.OFFLINE, WatchFreshness.state(r, t0.plusSeconds(300)))
        assertEquals("Offline · Stand 08:00", WatchFreshness.label(r, t0.plusSeconds(300)))
        assertEquals(WatchDataState.EXPIRED, WatchFreshness.state(r, t0.plusSeconds(31 * 60)))
        assertEquals("long after the departure", WatchDataState.EXPIRED, WatchFreshness.state(ride(t0, t0.plusSeconds(60)), t0.plusSeconds(20 * 60)))
    }

    private val identity = DepartureIdentity("de:05116:23007", "new:80007: :H:m26", "4711", Instant.parse("2026-11-18T07:10:00Z"))
    private fun event(line: String, trip: String, planned: String, estimated: String? = null, cancelled: Boolean = false) =
        """{"departureTimePlanned":"$planned",${estimated?.let { "\"departureTimeEstimated\":\"$it\"," } ?: ""}"isCancelled":$cancelled,
           "transportation":{"id":"$line","properties":{"tripCode":"$trip"}}}"""

    @Test fun `direct VRR result only for exactly this departure`() {
        val root = JSONObject("""{"stopEvents":[${event("new:80007: :H:m26", "4711", "2026-11-18T07:10:00Z", "2026-11-18T07:13:00Z")},
            ${event("new:80007: :H:m26", "4712", "2026-11-18T07:20:00Z", "2026-11-18T07:21:00Z")}]}""")
        val result = DirectVrr.parse(root, identity, t0)!!
        assertEquals(Instant.parse("2026-11-18T07:13:00Z"), result.at); assertTrue(result.live)
        val other = JSONObject("""{"stopEvents":[${event("new:80017: :H:m26", "4711", "2026-11-18T07:10:00Z", "2026-11-18T07:13:00Z")}]}""")
        assertNull("other line", DirectVrr.parse(other, identity, t0))
        val plan = DirectVrr.parse(JSONObject("""{"stopEvents":[${event("new:80007: :H:m26", "4711", "2026-11-18T07:10:00Z")}]}"""), identity, t0)!!
        assertFalse(plan.live)
        val twice = JSONObject("""{"stopEvents":[${event("new:80007: :H:m26", "4711", "2026-11-18T07:10:00Z")},${event("new:80007: :H:m26", "4711", "2026-11-18T07:10:00Z")}]}""")
        assertNull("ambiguous", DirectVrr.parse(twice, identity, t0))
        assertTrue(DirectVrr.parse(JSONObject("""{"stopEvents":[${event("new:80007: :H:m26", "4711", "2026-11-18T07:10:00Z", cancelled = true)}]}"""), identity, t0)!!.cancelled)
        assertNull(DepartureIdentity.from(mapOf("ride_stop_id" to "x")))
        assertEquals(identity, DepartureIdentity.from(mapOf("ride_stop_id" to identity.stopId, "ride_line_id" to identity.lineId,
            "ride_trip_code" to identity.tripCode, "ride_planned" to identity.planned.toString())))
    }

    @Test fun `watch switches only after the phone reports the new journey`() {
        assertEquals(WatchStartFlow.Result.WAITING, WatchStartFlow.resolve("old", "old", false))
        assertEquals(WatchStartFlow.Result.DONE, WatchStartFlow.resolve("old", "new", false))
        assertEquals("phone refused, e.g. stale choice", WatchStartFlow.Result.FAILED, WatchStartFlow.resolve("old", "old", true))
        assertEquals(WatchStartFlow.Result.DONE, WatchStartFlow.resolve("", "new", true))
    }

    @Test fun `complication falls back safely`() {
        val live = ComplicationFace.of(ride(t0), null, t0.plusSeconds(30))
        assertEquals(t0.plusSeconds(600), live.countdownTo); assertEquals("007", live.title)
        val offline = ComplicationFace.of(ride(t0), null, t0.plusSeconds(300))
        assertNull("no countdown from old data", offline.countdownTo); assertEquals("Stand", offline.title)
        assertEquals("–", ComplicationFace.of(ride(t0), null, t0.plusSeconds(40 * 60)).value)
        val tile = WatchTile("route", "r", "Schule", "017", "Hbf", t0.plusSeconds(400), true, t0)
        assertEquals("017", ComplicationFace.of(null, tile, t0.plusSeconds(30)).title)
        assertEquals("Mitfahrt", ComplicationFace.of(null, tile, t0.plusSeconds(300)).title)
        assertEquals("Ausfall", ComplicationFace.of(ride(t0).copy(cancelled = true), null, t0).value)
    }
}
