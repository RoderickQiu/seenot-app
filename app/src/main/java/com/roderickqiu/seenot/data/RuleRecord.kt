package com.roderickqiu.seenot.data

import java.util.Date

/**
 * Represents a record of a rule evaluation/judgment
 */
data class RuleRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String? = null,
    val ruleId: String,
    val condition: RuleCondition,
    val action: RuleAction,
    val isConditionMatched: Boolean,
    val aiResult: String? = null, // Raw AI response text
    val imagePath: String? = null, // Path to saved screenshot image (internal storage)
    val elapsedTimeMs: Long? = null, // AI processing time
    val isMarked: Boolean = false // Whether this record is marked/tagged by user
) {
    val date: Date
        get() = Date(timestamp)
}
