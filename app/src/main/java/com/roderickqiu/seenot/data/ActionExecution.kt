package com.roderickqiu.seenot.data

/**
 * Represents a record of an action being executed
 */
data class ActionExecution(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val appName: String,
    val packageName: String? = null,
    val ruleId: String,
    val ruleName: String? = null,
    val actionType: ActionType,
    val actionParameter: String? = null,
    val conditionDescription: String? = null, // Description of what triggered the action
    val isSuccess: Boolean = true, // Whether the action was executed successfully
    val errorMessage: String? = null // Error message if action failed
) {
    val date: java.util.Date
        get() = java.util.Date(timestamp)
}

/**
 * Represents a continuous app usage session composed of one or more usage segments
 * This is used for timeline display
 */
data class AppSession(
    val appName: String,
    val startMs: Long,
    val totalDurationMs: Long,
    val items: List<SessionItem> // Interleaved segments and actions
) {
    val segments: List<UsageSegment>
        get() = items.filterIsInstance<SessionItem.SegmentItem>().map { it.segment }

    val actions: List<ActionExecution>
        get() = items.filterIsInstance<SessionItem.ActionItem>().map { it.action }

    val hasActions: Boolean
        get() = items.any { it is SessionItem.ActionItem }

    val hasMultipleSegments: Boolean
        get() = segments.size > 1
}

/**
 * Sealed class representing items within a session timeline.
 * Can be either a usage segment or an action execution, allowing them to be interleaved.
 */
sealed class SessionItem {
    abstract val timestamp: Long

    data class SegmentItem(val segment: UsageSegment) : SessionItem() {
        override val timestamp: Long = segment.startMs
    }

    data class ActionItem(val action: ActionExecution) : SessionItem() {
        override val timestamp: Long = action.timestamp
    }
}

/**
 * Timeline event representing an app usage session with interleaved items
 */
data class TimelineEvent(
    val session: AppSession,
    val timestamp: Long = session.startMs
)
