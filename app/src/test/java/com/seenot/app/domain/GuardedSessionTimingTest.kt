package com.seenot.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardedSessionTimingTest {
    @Test
    fun firstTickEstablishesBaselineWithoutInventingUsage() {
        val snapshot = GuardedSessionTiming.advance(
            consumedMs = 18 * 60_000L,
            lastTickAt = null,
            now = 50_000L
        )

        assertEquals(18 * 60_000L, snapshot.consumedMs)
        assertEquals(50_000L, snapshot.tickAt)
    }

    @Test
    fun repeatedForegroundTicksKeepIncreasingPastFirstHoldThreshold() {
        val afterReprieve = GuardedSessionTiming.advance(
            consumedMs = 18 * 60_000L,
            lastTickAt = 1_000L,
            now = 8 * 60_000L + 1_000L
        )
        val nextTick = GuardedSessionTiming.advance(
            consumedMs = afterReprieve.consumedMs,
            lastTickAt = afterReprieve.tickAt,
            now = 8 * 60_000L + 2_000L
        )

        assertEquals(26 * 60_000L, afterReprieve.consumedMs)
        assertEquals(26 * 60_000L + 1_000L, nextTick.consumedMs)
    }

    @Test
    fun backwardsClockValueCannotReduceConsumption() {
        val snapshot = GuardedSessionTiming.advance(
            consumedMs = 20 * 60_000L,
            lastTickAt = 10_000L,
            now = 9_000L
        )

        assertEquals(20 * 60_000L, snapshot.consumedMs)
        assertEquals(9_000L, snapshot.tickAt)
    }
}
