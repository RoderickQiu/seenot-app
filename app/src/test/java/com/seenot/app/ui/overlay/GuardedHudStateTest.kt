package com.seenot.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardedHudStateTest {
    @Test
    fun freeTierStartsWithPausesLater() {
        val state = GuardedHudState.from(consumedMs = 0L, reprieveUntil = null, now = 1_000L)

        assertEquals(GuardedHudState.Phase.PAUSES_LATER, state.phase)
        assertEquals(10L, state.remainingMinutes)
        assertEquals(0L, state.consumedMinutes)
    }

    @Test
    fun lastThreeFreeMinutesShowFirstPauseCountdown() {
        val state = GuardedHudState.from(
            consumedMs = 7 * 60_000L,
            reprieveUntil = null,
            now = 1_000L
        )

        assertEquals(GuardedHudState.Phase.FIRST_PAUSE_COUNTDOWN, state.phase)
        assertEquals(3L, state.remainingMinutes)
    }

    @Test
    fun ladderAfterFreeTierShowsActivePauses() {
        val state = GuardedHudState.from(
            consumedMs = 10 * 60_000L,
            reprieveUntil = null,
            now = 1_000L
        )

        assertEquals(GuardedHudState.Phase.PAUSES_ACTIVE, state.phase)
    }

    @Test
    fun earnedReprieveOverridesTheUnderlyingLadderStep() {
        val now = 1_000L
        val state = GuardedHudState.from(
            consumedMs = 20 * 60_000L,
            reprieveUntil = now + 6 * 60_000L,
            now = now
        )

        assertEquals(GuardedHudState.Phase.REPRIEVE, state.phase)
        assertEquals(6L, state.remainingMinutes)
    }

    @Test
    fun windDownHasItsOwnPresentationState() {
        val state = GuardedHudState.from(
            consumedMs = 40 * 60_000L,
            reprieveUntil = null,
            now = 1_000L
        )

        assertEquals(GuardedHudState.Phase.WIND_DOWN, state.phase)
    }
}
