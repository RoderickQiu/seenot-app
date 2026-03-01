package com.seenot.app.data.model

/**
 * Domain model for an active session runtime state.
 * This is used in-memory to track the current session status.
 */
data class ActiveSession(
    val id: Long = 0,
    val appPackageName: String,
    val appDisplayName: String,
    val totalTimeLimitMs: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val constraints: List<IntentConstraint> = emptyList(),
    val currentTimeRemainingMs: Long? = null
) {
    /**
     * Calculate remaining time for this session
     */
    fun getRemainingTimeMs(): Long? {
        return if (totalTimeLimitMs != null) {
            val elapsed = System.currentTimeMillis() - startedAt
            maxOf(0, totalTimeLimitMs - elapsed)
        } else null
    }

    /**
     * Check if session has expired
     */
    fun isExpired(): Boolean {
        val remaining = getRemainingTimeMs()
        return remaining != null && remaining <= 0
    }

    /**
     * Get active constraints only
     */
    fun getActiveConstraints(): List<IntentConstraint> {
        return constraints.filter { it.isActive }
    }
}
