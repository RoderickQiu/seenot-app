package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for rule records
 */
@Entity(
    tableName = "rule_records",
    indices = [
        Index("sessionId"),
        Index("timestamp"),
        Index("appName"),
        Index("isMarked"),
        Index("isConditionMatched")
    ]
)
data class RuleRecordEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val sessionId: Long,
    val appName: String,
    val packageName: String? = null,
    val screenshotHash: String? = null,
    val constraintId: Long? = null,
    val constraintType: String? = null,
    val constraintContent: String? = null,
    val isConditionMatched: Boolean,
    val aiResult: String? = null,
    val confidence: Double? = null,
    val imagePath: String? = null,
    val elapsedTimeMs: Long? = null,
    val isMarked: Boolean = false
)
