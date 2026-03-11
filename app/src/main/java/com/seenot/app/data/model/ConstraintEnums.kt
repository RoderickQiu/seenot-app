package com.seenot.app.data.model

/**
 * Constraint type as defined in PRD section 3.2.1
 */
enum class ConstraintType {
    /** Only allow this content - anything else triggers intervention */
    ALLOW,

    /** Deny this content - this content triggers intervention */
    DENY,

    /** Pure time constraint - no specific content targeting */
    TIME_CAP
}

enum class TimeScope {
    SESSION,
    PER_CONTENT,
    CONTINUOUS,
    DAILY_TOTAL
}

val TimeScope.displayLabel: String get() = when (this) {
    TimeScope.SESSION    -> "本次"
    TimeScope.CONTINUOUS -> "连续"
    TimeScope.PER_CONTENT -> "计入时"
    TimeScope.DAILY_TOTAL -> "今日"
}

/**
 * Intervention level as defined in PRD section 3.2.1
 */
enum class InterventionLevel {
    /** Only remind (Toast / HUD highlight) */
    GENTLE,

    /** Remind + auto-back */
    MODERATE,

    /** Directly return to home screen */
    STRICT
}

/**
 * Source of utterance as defined in PRD section 3.2.1
 */
enum class UtteranceSource {
    VOICE,
    TEXT
}
