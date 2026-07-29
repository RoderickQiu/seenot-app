package com.seenot.app.domain

/** Pure timing policy for Guarded mode. Enforcement/UI lives in SessionManager and overlays. */
object GuardedModeLadder {
    const val FREE_MS = 10 * 60_000L
    const val BREATH_MS = 18 * 60_000L
    const val STRICT_MS = 30 * 60_000L
    const val WIND_DOWN_MS = 40 * 60_000L

    enum class Step { FREE, BREATH, HOLD, PER_ADVANCE, WIND_DOWN }

    fun step(consumedMs: Long): Step = when {
        consumedMs < FREE_MS -> Step.FREE
        consumedMs < BREATH_MS -> Step.BREATH
        consumedMs < STRICT_MS -> Step.HOLD
        consumedMs < WIND_DOWN_MS -> Step.PER_ADVANCE
        else -> Step.WIND_DOWN
    }

    fun holdDurationMs(consumedMs: Long): Long = if (consumedMs < STRICT_MS) 6_000L else 12_000L
}
