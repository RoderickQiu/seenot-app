package com.seenot.app.ui.overlay

import com.seenot.app.domain.GuardedModeLadder
import kotlin.math.ceil

/** Pure presentation state derived from the Guarded ladder. */
data class GuardedHudState(
    val phase: Phase,
    val consumedMinutes: Long,
    val remainingMinutes: Long? = null
) {
    enum class Phase {
        PAUSES_LATER,
        FIRST_PAUSE_COUNTDOWN,
        PAUSES_ACTIVE,
        REPRIEVE,
        WIND_DOWN
    }

    companion object {
        private const val COUNTDOWN_WINDOW_MS = 3 * 60_000L

        fun from(consumedMs: Long, reprieveUntil: Long?, now: Long): GuardedHudState {
            val safeConsumedMs = consumedMs.coerceAtLeast(0L)
            val consumedMinutes = safeConsumedMs / 60_000L
            val reprieveRemainingMs = reprieveUntil?.minus(now)?.coerceAtLeast(0L) ?: 0L

            if (reprieveRemainingMs > 0L) {
                return GuardedHudState(
                    phase = Phase.REPRIEVE,
                    consumedMinutes = consumedMinutes,
                    remainingMinutes = ceil(reprieveRemainingMs / 60_000.0).toLong()
                )
            }

            return when (GuardedModeLadder.step(safeConsumedMs)) {
                GuardedModeLadder.Step.FREE -> {
                    val remainingMs = GuardedModeLadder.FREE_MS - safeConsumedMs
                    GuardedHudState(
                        phase = if (remainingMs <= COUNTDOWN_WINDOW_MS) {
                            Phase.FIRST_PAUSE_COUNTDOWN
                        } else {
                            Phase.PAUSES_LATER
                        },
                        consumedMinutes = consumedMinutes,
                        remainingMinutes = ceil(remainingMs / 60_000.0).toLong()
                    )
                }
                GuardedModeLadder.Step.WIND_DOWN -> GuardedHudState(
                    phase = Phase.WIND_DOWN,
                    consumedMinutes = consumedMinutes
                )
                else -> GuardedHudState(
                    phase = Phase.PAUSES_ACTIVE,
                    consumedMinutes = consumedMinutes
                )
            }
        }
    }
}
