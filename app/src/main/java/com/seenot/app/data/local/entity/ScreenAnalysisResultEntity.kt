package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Result of AI vision analysis of a screen screenshot.
 */
@Entity(
    tableName = "screen_analysis_results",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ScreenAnalysisResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the session this analysis belongs to */
    val sessionId: Long,

    /** Hash of the screenshot for deduplication */
    val screenshotHash: String,

    /** Whether any constraint was violated */
    val isViolation: Boolean,

    /** IDs of violated constraints (JSON array) */
    val violatedConstraintIdsJson: String = "[]",

    /** Confidence score of the analysis (0.0 - 1.0) */
    val confidence: Float,

    /** Raw analysis result from AI */
    val rawAnalysisJson: String,

    /** Whether this was a deduplicated analysis (skipped API call) */
    val isDeduplicated: Boolean = false,

    /** Timestamp of the analysis */
    val analyzedAt: Long = System.currentTimeMillis()
)
