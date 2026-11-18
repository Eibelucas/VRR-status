package de.luca.mitfahrt.data

import de.luca.mitfahrt.data.RouteFixtures.T0
import org.junit.Assert.*
import org.junit.Test

/** Synthetic rides; the exit at stop 5 is planned at T0 + 600 s. */
class PowerPolicyTest {
    private val riding = RouteFixtures.ride().board("auto", T0)

    @Test fun `off keeps the normal rhythm`() {
        val plan = PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "off", 30, T0.minusSeconds(3600))
        assertFalse(plan.saving); assertEquals(30_000L, plan.refreshMillis)
    }

    @Test fun `long rides save until shortly before the exit`() {
        assertTrue(PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "long", 30, T0.minusSeconds(3600)).saving)
        assertFalse("ride under 20 min left", PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "long", 30, T0.minusSeconds(600)).saving)
        assertTrue(PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "always", 30, T0.minusSeconds(600)).saving)
        val near = PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "always", 30, T0.plusSeconds(400))
        assertFalse("exit within 5 min: normal again", near.saving)
        val plan = PowerPolicy.plan(riding, BoardingPhase.ON_BOARD, "always", 30, T0.minusSeconds(3600))
        assertEquals(90_000L, plan.refreshMillis); assertEquals(PowerPolicy.SAVING_LOCATION, plan.location)
    }

    @Test fun `possible boarding or exit and cancellations always use the normal rhythm`() {
        assertFalse(PowerPolicy.plan(riding, BoardingPhase.POSSIBLE_EXIT, "always", 30, T0.minusSeconds(3600)).saving)
        val waiting = RouteFixtures.ride()
        assertFalse(PowerPolicy.plan(waiting, BoardingPhase.POSSIBLE_BOARDING, "always", 15, T0.minusSeconds(3600)).saving)
        val cancelled = riding.copy(departure = riding.departure.copy(cancelled = true))
        assertFalse(PowerPolicy.plan(cancelled, BoardingPhase.ON_BOARD, "always", 30, T0.minusSeconds(3600)).saving)
        assertFalse(PowerPolicy.plan(null, null, "always", 30, T0).saving)
    }
}
