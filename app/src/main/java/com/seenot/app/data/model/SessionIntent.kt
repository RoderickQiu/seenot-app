package com.seenot.app.data.model

/**
 * Domain model for a parsed session intent.
 * This is used in-memory, separate from the Room entity.
 */
data class SessionIntent(
    val id: Long = 0,
    val sessionId: Long,
    val rawUtterance: String,
    val parsedIntentJson: String,
    val source: UtteranceSource,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)
