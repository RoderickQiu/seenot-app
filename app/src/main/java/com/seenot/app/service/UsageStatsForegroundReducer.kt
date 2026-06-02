package com.seenot.app.service

class UsageStatsForegroundReducer(
    private val pendingExitMs: Long = DEFAULT_PENDING_EXIT_MS
) {
    data class UsageEvent(
        val timeMs: Long,
        val packageName: String,
        val className: String?,
        val type: EventType
    )

    enum class EventType {
        ENTER,
        EXIT
    }

    enum class DecisionReason {
        NONE,
        ENTER,
        SAME_PACKAGE_REENTER,
        CROSS_PACKAGE_ENTER,
        PENDING_EXIT,
        PENDING_EXIT_EXPIRED
    }

    data class Decision(
        val foregroundPackage: String?,
        val previousForegroundPackage: String?,
        val pendingExitPackage: String?,
        val eventTimeMs: Long?,
        val reason: DecisionReason
    )

    private var foregroundPackage: String? = null
    private var pendingExitPackage: String? = null
    private var pendingExitAtMs: Long = 0L

    fun reduce(
        events: List<UsageEvent>,
        nowMs: Long
    ): Decision {
        var lastDecision = Decision(
            foregroundPackage = foregroundPackage,
            previousForegroundPackage = null,
            pendingExitPackage = pendingExitPackage,
            eventTimeMs = null,
            reason = DecisionReason.NONE
        )

        events
            .asSequence()
            .filter { it.packageName.isNotBlank() }
            .sortedBy { it.timeMs }
            .forEach { event ->
                lastDecision = when (event.type) {
                    EventType.ENTER -> handleEnter(event)
                    EventType.EXIT -> handleExit(event)
                }
            }

        if (events.isEmpty() || lastDecision.reason == DecisionReason.NONE || lastDecision.reason == DecisionReason.PENDING_EXIT) {
            val expired = expirePendingExitIfNeeded(nowMs)
            if (expired != null) {
                lastDecision = expired
            }
        }

        return lastDecision
    }

    private fun handleEnter(event: UsageEvent): Decision {
        val previous = foregroundPackage
        val reason =
            when {
                pendingExitPackage == event.packageName -> DecisionReason.SAME_PACKAGE_REENTER
                previous != null && previous != event.packageName -> DecisionReason.CROSS_PACKAGE_ENTER
                else -> DecisionReason.ENTER
            }

        foregroundPackage = event.packageName
        pendingExitPackage = null
        pendingExitAtMs = 0L

        return Decision(
            foregroundPackage = foregroundPackage,
            previousForegroundPackage = previous?.takeIf { it != foregroundPackage },
            pendingExitPackage = null,
            eventTimeMs = event.timeMs,
            reason = reason
        )
    }

    private fun handleExit(event: UsageEvent): Decision {
        if (foregroundPackage != event.packageName) {
            return Decision(
                foregroundPackage = foregroundPackage,
                previousForegroundPackage = null,
                pendingExitPackage = pendingExitPackage,
                eventTimeMs = event.timeMs,
                reason = DecisionReason.NONE
            )
        }

        pendingExitPackage = event.packageName
        pendingExitAtMs = event.timeMs

        return Decision(
            foregroundPackage = foregroundPackage,
            previousForegroundPackage = null,
            pendingExitPackage = pendingExitPackage,
            eventTimeMs = event.timeMs,
            reason = DecisionReason.PENDING_EXIT
        )
    }

    private fun expirePendingExitIfNeeded(nowMs: Long): Decision? {
        val pendingPackage = pendingExitPackage ?: return null
        if (nowMs - pendingExitAtMs < pendingExitMs) return null

        val previous = foregroundPackage
        if (previous == pendingPackage) {
            foregroundPackage = null
        }
        pendingExitPackage = null
        pendingExitAtMs = 0L

        return Decision(
            foregroundPackage = foregroundPackage,
            previousForegroundPackage = previous,
            pendingExitPackage = null,
            eventTimeMs = nowMs,
            reason = DecisionReason.PENDING_EXIT_EXPIRED
        )
    }

    companion object {
        const val DEFAULT_PENDING_EXIT_MS = 1_500L
        const val DEFAULT_FRESH_EVENT_MS = 2_500L
    }
}
