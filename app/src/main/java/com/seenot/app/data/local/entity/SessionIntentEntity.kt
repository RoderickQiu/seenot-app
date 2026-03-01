package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.seenot.app.data.model.UtteranceSource

/**
 * Represents a user-declared intent for a session, parsed from voice or text input.
 */
@Entity(tableName = "session_intents")
data class SessionIntentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the session this intent belongs to */
    val sessionId: Long,

    /** Raw text of the utterance */
    val rawUtterance: String,

    /** Structured JSON of parsed intent */
    val parsedIntentJson: String,

    /** Source of the utterance (VOICE or TEXT) */
    val source: UtteranceSource,

    /** Timestamp when this intent was declared */
    val createdAt: Long = System.currentTimeMillis(),

    /** Whether this intent is currently active */
    val isActive: Boolean = true
)
