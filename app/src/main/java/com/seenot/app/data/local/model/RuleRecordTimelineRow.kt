package com.seenot.app.data.local.model

data class RuleRecordTimelineRow(
    val id: String,
    val timestamp: Long,
    val sessionId: Long,
    val appName: String,
    val packageName: String?,
    val constraintId: Long?,
    val constraintType: String?,
    val constraintContent: String?,
    val isConditionMatched: Boolean,
    val actionType: String?,
    val actionReason: String?,
    val actionTimestamp: Long?
)
