package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an active or completed session.
 * A session is created when user opens a controlled app and declares an intent.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Package name of the controlled app */
    val appPackageName: String,

    /** Display name of the controlled app */
    val appDisplayName: String,

    /** Total time limit for the session in milliseconds (null if no limit) */
    val totalTimeLimitMs: Long? = null,

    /** When the session started */
    val startedAt: Long = System.currentTimeMillis(),

    /** When the session ended (null if still active) */
    val endedAt: Long? = null,

    /** Whether the session is currently active */
    val isActive: Boolean = true,

    /** How the session ended: COMPLETED, CANCELLED, TIMEOUT, VIOLATED */
    val endReason: String? = null
)
