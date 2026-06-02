package com.seenot.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatsForegroundReducerTest {
    @Test
    fun keepsSamePackageForegroundAcrossActivitySwitches() {
        val reducer = UsageStatsForegroundReducer()

        val first = reducer.reduce(
            events = listOf(
                usageEvent(1_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(2_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(2_024L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER)
            ),
            nowMs = 2_100L
        )

        assertEquals("com.tencent.mm", first.foregroundPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.SAME_PACKAGE_REENTER, first.reason)
        assertNull(first.pendingExitPackage)
    }

    @Test
    fun confirmsCrossPackageSwitchWhenDifferentPackageEnters() {
        val reducer = UsageStatsForegroundReducer()

        val decision = reducer.reduce(
            events = listOf(
                usageEvent(10_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(12_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(13_700L, "com.tencent.mobileqq", UsageStatsForegroundReducer.EventType.ENTER)
            ),
            nowMs = 13_800L
        )

        assertEquals("com.tencent.mobileqq", decision.foregroundPackage)
        assertEquals("com.tencent.mm", decision.previousForegroundPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.CROSS_PACKAGE_ENTER, decision.reason)
        assertNull(decision.pendingExitPackage)
    }

    @Test
    fun clearsForegroundAfterPendingExitExpiresWithoutReentry() {
        val reducer = UsageStatsForegroundReducer(pendingExitMs = 1_500L)

        reducer.reduce(
            events = listOf(
                usageEvent(20_000L, "com.tencent.mobileqq", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(22_000L, "com.tencent.mobileqq", UsageStatsForegroundReducer.EventType.EXIT)
            ),
            nowMs = 22_200L
        )

        val expired = reducer.reduce(events = emptyList(), nowMs = 23_600L)

        assertNull(expired.foregroundPackage)
        assertEquals("com.tencent.mobileqq", expired.previousForegroundPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.PENDING_EXIT_EXPIRED, expired.reason)
        assertNull(expired.pendingExitPackage)
    }

    @Test
    fun retainsForegroundWhilePendingExitHasNotExpired() {
        val reducer = UsageStatsForegroundReducer(pendingExitMs = 1_500L)

        val pending = reducer.reduce(
            events = listOf(
                usageEvent(30_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(31_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT)
            ),
            nowMs = 31_500L
        )

        assertEquals("com.tencent.mm", pending.foregroundPackage)
        assertEquals("com.tencent.mm", pending.pendingExitPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.PENDING_EXIT, pending.reason)
    }

    @Test
    fun compressesWechatInternalSwitchesThenSwitchesToQq() {
        val reducer = UsageStatsForegroundReducer(pendingExitMs = 1_500L)

        val wechat = reducer.reduce(
            events = listOf(
                usageEvent(1_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(1_200L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(1_224L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(2_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(2_015L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER),
                usageEvent(5_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(5_013L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.ENTER)
            ),
            nowMs = 5_100L
        )

        assertEquals("com.tencent.mm", wechat.foregroundPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.SAME_PACKAGE_REENTER, wechat.reason)

        val qq = reducer.reduce(
            events = listOf(
                usageEvent(8_000L, "com.tencent.mm", UsageStatsForegroundReducer.EventType.EXIT),
                usageEvent(9_776L, "com.tencent.mobileqq", UsageStatsForegroundReducer.EventType.ENTER)
            ),
            nowMs = 9_900L
        )

        assertEquals("com.tencent.mobileqq", qq.foregroundPackage)
        assertEquals("com.tencent.mm", qq.previousForegroundPackage)
        assertEquals(UsageStatsForegroundReducer.DecisionReason.CROSS_PACKAGE_ENTER, qq.reason)
    }

    private fun usageEvent(
        timeMs: Long,
        packageName: String,
        type: UsageStatsForegroundReducer.EventType
    ): UsageStatsForegroundReducer.UsageEvent {
        return UsageStatsForegroundReducer.UsageEvent(
            timeMs = timeMs,
            packageName = packageName,
            className = null,
            type = type
        )
    }
}
