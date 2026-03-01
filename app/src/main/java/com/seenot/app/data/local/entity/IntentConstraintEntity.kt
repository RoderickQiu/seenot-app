package com.seenot.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope

/**
 * Single constraint derived from an intent.
 * Represents a rule like "only allow X" or "deny Y" or "time limit Z".
 */
@Entity(
    tableName = "intent_constraints",
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
data class IntentConstraintEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Foreign key to the session this constraint belongs to */
    val sessionId: Long,

    /** Foreign key to the intent this constraint was derived from */
    val intentId: Long,

    /** Type of constraint */
    val type: ConstraintType,

    /** For ALLOW/DENY: the content pattern to match */
    val contentPattern: String? = null,

    /** For TIME_CAP: time limit in milliseconds */
    val timeLimitMs: Long? = null,

    /** Time scope for time limits */
    val timeScope: TimeScope? = null,

    /** Intervention level for this constraint */
    val interventionLevel: InterventionLevel = InterventionLevel.GENTLE,

    /** Whether this constraint is currently active */
    val isActive: Boolean = true,

    /** Timestamp when this constraint was created */
    val createdAt: Long = System.currentTimeMillis()
)
