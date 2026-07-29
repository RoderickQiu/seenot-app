package com.seenot.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardedModeLadderTest {
    @Test fun startsFree() = assertEquals(GuardedModeLadder.Step.FREE, GuardedModeLadder.step(0))
    @Test fun escalatesByElapsedConsumption() {
        assertEquals(GuardedModeLadder.Step.BREATH, GuardedModeLadder.step(10 * 60_000L))
        assertEquals(GuardedModeLadder.Step.HOLD, GuardedModeLadder.step(18 * 60_000L))
        assertEquals(GuardedModeLadder.Step.PER_ADVANCE, GuardedModeLadder.step(30 * 60_000L))
        assertEquals(GuardedModeLadder.Step.WIND_DOWN, GuardedModeLadder.step(40 * 60_000L))
    }
}
