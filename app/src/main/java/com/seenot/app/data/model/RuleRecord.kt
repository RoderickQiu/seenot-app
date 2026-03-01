package com.seenot.app.data.model

import java.util.Date

/**
 * Represents a record of a rule/intent evaluation
 * This mirrors the concept from seenot-reborn's RuleRecord
 */
data class RuleRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: Long,
    val appName: String,
    val packageName: String? = null,
    val screenshotHash: String? = null,
    val constraintId: Long? = null,
    val constraintType: ConstraintType? = null,
    val constraintContent: String? = null,
    val isConditionMatched: Boolean,
    val aiResult: String? = null,
    val confidence: Double? = null,
    val imagePath: String? = null,
    val elapsedTimeMs: Long? = null,
    val isMarked: Boolean = false
) {
    val date: Date
        get() = Date(timestamp)
}

/**
 * Statistics for rule records
 */
data class RecordStats(
    val totalRecords: Int,
    val matchedRecords: Int,
    val matchRate: Float,
    val uniqueApps: Int,
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?
)
