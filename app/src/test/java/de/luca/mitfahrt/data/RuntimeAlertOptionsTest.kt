package de.luca.mitfahrt.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RuntimeAlertOptionsTest {
    @Test fun `multiple warnings are bounded unique and ordered`() {
        assertEquals(listOf(300, 180, 60), RuntimeAlertOptions.parseLeads("60,180,300,60,0,999,text"))
        assertEquals(listOf(60), RuntimeAlertOptions.parseLeads("0,wrong"))
    }

    @Test fun `overnight quiet hours use Berlin and exclude their end`() {
        val options = RuntimeAlertOptions(quietEnabled = true, quietStart = "22:00", quietEnd = "07:00")
        assertFalse(options.quiet(Instant.parse("2026-11-08T20:59:00Z")))
        assertTrue(options.quiet(Instant.parse("2026-11-08T21:00:00Z")))
        assertTrue(options.quiet(Instant.parse("2026-11-09T05:59:00Z")))
        assertFalse(options.quiet(Instant.parse("2026-11-09T06:00:00Z")))
    }

    @Test fun `same day and all day quiet windows work without overnight reversal`() {
        val daytime = RuntimeAlertOptions(quietEnabled = true, quietStart = "08:00", quietEnd = "15:00")
        assertTrue(daytime.quiet(Instant.parse("2026-11-08T10:00:00Z")))
        assertFalse(daytime.quiet(Instant.parse("2026-11-08T18:00:00Z")))
        assertTrue(daytime.copy(quietStart = "08:00", quietEnd = "08:00").quiet(Instant.parse("2026-11-08T18:00:00Z")))
    }

    @Test fun `critical exception never unmutes ordinary bus arrival`() {
        val time = Instant.parse("2026-11-08T23:00:00Z")
        val options = RuntimeAlertOptions(quietEnabled = true, criticalDuringQuiet = true)
        assertTrue(options.allowed("cancelled", time))
        assertTrue(options.allowed("delay", time))
        assertFalse(options.allowed("arrival", time))
        assertFalse(options.allowed("exit", time))
    }

    @Test fun `automatic target uses exactly one connected device`() {
        val automatic = RuntimeAlertOptions(target = "auto")
        assertFalse(automatic.phone(true)); assertTrue(automatic.watch(true))
        assertTrue(automatic.phone(false)); assertFalse(automatic.watch(false))
        val phone = RuntimeAlertOptions(target = "phone")
        assertTrue(phone.phone(true)); assertFalse(phone.watch(true))
        val watch = RuntimeAlertOptions(target = "watch")
        assertFalse(watch.phone(false)); assertTrue(watch.watch(false))
    }

    @Test fun `duplicate avoidance converts both devices into automatic delivery`() {
        val options = RuntimeAlertOptions.from(AppSettings(values = mapOf("watch_avoid_duplicates" to "true")))
        assertEquals("auto", options.target)
    }
}
