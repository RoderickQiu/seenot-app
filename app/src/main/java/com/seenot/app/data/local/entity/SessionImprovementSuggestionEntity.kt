package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_improvement_suggestions",
    indices = [
        Index("sessionId", unique = true),
        Index("packageName"),
        Index("createdAt"),
        Index("acceptedAt"),
        Index("dismissedAt")
    ]
)
data class SessionImprovementSuggestionEntity(
    @PrimaryKey
    val id: String,
    val sessionId: Long,
    val packageName: String,
    val appName: String,
    val status: String,
    val sessionPattern: String,
    val nextIntentSuggestion: String,
    val ruleDecision: String,
    val ruleText: String?,
    val ruleScopeType: String?,
    val ruleReason: String?,
    val confidence: String?,
    val evidenceRecordIdsJson: String,
    val createdAt: Long,
    val dismissedAt: Long?,
    val acceptedAt: Long?,
    val acceptedAction: String?
)
