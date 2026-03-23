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

    // For DENY: true = safe, false = violates
    // For TIME_CAP: true = in_scope, false = out_of_scope
    val isConditionMatched: Boolean,

    val aiResult: String? = null,
    val confidence: Double? = null,
    val imagePath: String? = null,
    val elapsedTimeMs: Long? = null,
    val isMarked: Boolean = false,

    // Action record
    val actionType: String? = null,  // TOAST, AUTO_BACK, GO_HOME, etc.
    val actionReason: String? = null, // "violation" or "timeout"
    val actionTimestamp: Long? = null
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
