package com.seenot.app.data.model

/**
 * Domain model for screen analysis result.
 * This is used in-memory, separate from the Room entity.
 */
data class ScreenAnalysisResult(
    val id: Long = 0,
    val sessionId: Long,
    val screenshotHash: String,
    val isViolation: Boolean,
    val violatedConstraintIds: List<Long> = emptyList(),
    val confidence: Float,
    val rawAnalysisJson: String,
    val isDeduplicated: Boolean = false,
    val analyzedAt: Long = System.currentTimeMillis()
)
