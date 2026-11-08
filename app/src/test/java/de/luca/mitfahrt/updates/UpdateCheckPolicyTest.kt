package de.luca.mitfahrt.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckPolicyTest {
    private val now = 30 * 86400_000L
    @Test fun `manual never auto checks and opening is throttled`() {
        assertFalse(UpdateCheckPolicy.shouldCheck("manual", 0, now))
        assertTrue(UpdateCheckPolicy.shouldCheck("open", 0, now))
        assertFalse(UpdateCheckPolicy.shouldCheck("open", now - 1000, now))
        assertTrue(UpdateCheckPolicy.shouldCheck("open", now - 3600_000L, now))
    }
    @Test fun `daily weekly and changed clocks have predictable due times`() {
        assertEquals(86400_000L, UpdateCheckPolicy.interval("daily"))
        assertEquals(7 * 86400_000L, UpdateCheckPolicy.interval("weekly"))
        assertNull(UpdateCheckPolicy.interval("open"))
        assertFalse(UpdateCheckPolicy.shouldCheck("weekly", now - 86400_000L, now))
        assertTrue(UpdateCheckPolicy.shouldCheck("weekly", now - 7 * 86400_000L, now))
        assertTrue(UpdateCheckPolicy.shouldCheck("daily", now + 1000, now))
        assertFalse(UpdateCheckPolicy.shouldCheck("invalid", 0, now))
    }
}
