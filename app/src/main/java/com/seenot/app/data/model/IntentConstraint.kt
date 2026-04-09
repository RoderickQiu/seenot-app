package com.seenot.app.data.model

/**
 * Domain model for a constraint derived from an intent.
 * This is used in-memory, separate from the Room entity.
 */
data class IntentConstraint(
    val id: Long = 0,
    val sessionId: Long,
    val intentId: Long,
    val type: ConstraintType,
    val contentPattern: String? = null,
    val timeLimitMs: Long? = null,
    val timeScope: TimeScope? = null,
    val interventionLevel: InterventionLevel = InterventionLevel.GENTLE,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
