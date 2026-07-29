package com.seenot.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedModeLadderTest {
    @Test fun startsFree() = assertEquals(GuardedModeLadder.Step.FREE, GuardedModeLadder.step(0))
    @Test fun escalatesByElapsedConsumption() {
        assertEquals(GuardedModeLadder.Step.BREATH, GuardedModeLadder.step(10 * 60_000L))
        assertEquals(GuardedModeLadder.Step.HOLD, GuardedModeLadder.step(18 * 60_000L))
        assertEquals(GuardedModeLadder.Step.PER_ADVANCE, GuardedModeLadder.step(30 * 60_000L))
        assertEquals(GuardedModeLadder.Step.WIND_DOWN, GuardedModeLadder.step(40 * 60_000L))
    }

    @Test fun dimmingFollowsGuardedEscalationAndBuildsGradually() {
        assertEquals(0f, GuardedModeLadder.dimFraction(10 * 60_000L), 0f)
        assertTrue(GuardedModeLadder.dimFraction(11 * 60_000L) > 0f)
        assertTrue(
            GuardedModeLadder.dimFraction(30 * 60_000L) >
                GuardedModeLadder.dimFraction(18 * 60_000L)
        )
        assertEquals(0.4f, GuardedModeLadder.dimFraction(40 * 60_000L), 0.0001f)
        assertEquals(0.4f, GuardedModeLadder.dimFraction(Long.MAX_VALUE), 0.0001f)
    }
}
