package com.seenot.app.ui.overlay

import androidx.compose.ui.graphics.Color
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope

/**
 * UI state for SessionStatusHUD
 * This is a UI-specific state class that wraps the domain models for display purposes
 */
data class SessionHudState(
    val appPackageName: String = "",
    val appDisplayName: String = "",
    val isExpanded: Boolean = false,
    val isViolating: Boolean = false,
    val violationMessage: String? = null,
    val constraints: List<ConstraintUiModel> = emptyList(),
    val totalTimeRemainingMs: Long? = null,
    val totalTimeLimitMs: Long? = null,
    val isPaused: Boolean = false,
    val position: HudPosition = HudPosition.DEFAULT
)

/**
 * UI model for a single constraint displayed in the HUD
 */
data class ConstraintUiModel(
    val id: String,
    val type: ConstraintType,
    val description: String,
    val isActive: Boolean = true,
    val isViolating: Boolean = false,
    val timeRemainingMs: Long? = null,
    val timeLimitMs: Long? = null,
    val timeScope: TimeScope? = null,
    val interventionLevel: InterventionLevel = InterventionLevel.MODERATE
)

/**
 * Position of the HUD on screen
 */
data class HudPosition(
    val x: Int = -1,  // -1 means default position
    val y: Int = -1
) {
    companion object {
        val DEFAULT = HudPosition(-1, -1)
    }
}

/**
 * Calculate color based on remaining time percentage
 */
fun calculateTimeColor(remainingMs: Long?, totalMs: Long?): Color {
    if (remainingMs == null || totalMs == null || totalMs <= 0) {
        return Color(0xFF4CAF50) // Green - no time limit
    }

    val percentage = (remainingMs.toFloat() / totalMs.toFloat()) * 100

    return when {
        percentage > 50 -> Color(0xFF4CAF50) // Green
        percentage > 20 -> Color(0xFFFFC107) // Yellow/Amber
        else -> Color(0xFFF44336) // Red
    }
}

/**
 * Format milliseconds to display string (MM:SS or HH:MM:SS)
 */
fun formatTimeRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

/**
 * Get icon for constraint type
 */
fun getConstraintTypeIcon(type: ConstraintType): String {
    return when (type) {
        ConstraintType.ALLOW -> "✓"
        ConstraintType.DENY -> "✗"
        ConstraintType.TIME_CAP -> "⏱"
    }
}

/**
 * Get color for constraint type
 */
fun getConstraintTypeColor(type: ConstraintType, isViolating: Boolean): Color {
    if (isViolating) return Color(0xFFF44336) // Red

    return when (type) {
        ConstraintType.ALLOW -> Color(0xFF4CAF50) // Green
        ConstraintType.DENY -> Color(0xFFFF9800) // Orange
        ConstraintType.TIME_CAP -> Color(0xFF2196F3) // Blue
    }
}
