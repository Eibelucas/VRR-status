package de.luca.mitfahrt.shared

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HapticTestProtocolTest {
    private val now = Instant.parse("2026-11-06T08:00:00.123456Z")

    @Test fun `a watch acknowledgement roundtrip retains the exact correlation token`() {
        val request = HapticTestProtocol.create(now)
        val decoded = HapticTestProtocol.decode(HapticTestProtocol.encode(request), now)!!
        assertEquals(request, decoded)
        val ack = HapticTestProtocol.decodeAck(HapticTestProtocol.encodeAck(decoded, true), now.plusSeconds(2))!!
        assertEquals(request, ack.request)
        assertTrue(ack.triggered)
        assertFalse(HapticTestProtocol.decodeAck(HapticTestProtocol.encodeAck(decoded, false), now)!!.triggered)
    }

    @Test fun `old or implausibly future tests cannot vibrate after reconnection`() {
        val request = HapticTestProtocol.create(now)
        val bytes = HapticTestProtocol.encode(request)
        assertNull(HapticTestProtocol.decode(bytes, now.plusSeconds(16)))
        assertNull(HapticTestProtocol.decode(bytes, now.minusSeconds(6)))
        assertNull(HapticTestProtocol.decodeAck(HapticTestProtocol.encodeAck(request, true), now.plusSeconds(16)))
    }

    @Test fun `test and real arrival payloads are not interchangeable`() {
        val request = HapticTestProtocol.create(now)
        assertNotEquals(ArrivalAlert.PATH, HapticTestProtocol.REQUEST)
        assertNotEquals(ArrivalAlert.PATH, HapticTestProtocol.ACK)
        assertNull(HapticTestProtocol.decodeAck(HapticTestProtocol.encode(request), now))
        assertNull(HapticTestProtocol.decode(HapticTestProtocol.encodeAck(request, true), now))
        assertNull(ArrivalAlert.recentId(HapticTestProtocol.encode(request), now))
        assertNull(HapticTestProtocol.decode(ArrivalAlert.encode("trip", now), now))
    }

    @Test fun `malformed acknowledgement cannot look like watch confirmation`() {
        val request = HapticTestProtocol.create(now)
        assertNull(HapticTestProtocol.decodeAck(HapticTestProtocol.encode(request) + "\nyes".toByteArray(), now))
        assertNull(HapticTestProtocol.decode(ByteArray(1000), now))
        assertNull(HapticTestProtocol.decode("not-a-uuid\n0".toByteArray(), now))
        assertNotEquals(request.id, HapticTestProtocol.create(now).id)
    }

    @Test fun `only a matching fresh response from a contacted node confirms the test`() {
        val request = HapticTestProtocol.create(now)
        val ack = HapticTestProtocol.encodeAck(request, true)
        assertNotNull(HapticTestProtocol.matchingAck(ack, now, request, "watch-one", setOf("watch-one")))
        assertNull(HapticTestProtocol.matchingAck(ack, now, request, "watch-two", setOf("watch-one")))
        assertNull(HapticTestProtocol.matchingAck(ack, now, HapticTestProtocol.create(now), "watch-one", setOf("watch-one")))
        assertNull(HapticTestProtocol.matchingAck(ack, now.plusSeconds(16), request, "watch-one", setOf("watch-one")))
    }
}
