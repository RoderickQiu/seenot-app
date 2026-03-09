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

/**
 * Time scope for time limits as defined in PRD section 3.2.1
 */
enum class TimeScope {
    /** Total time for this session */
    SESSION,

    /** Time limit per specific content */
    PER_CONTENT,

    /** Continuous uninterrupted time */
    CONTINUOUS,

    /** Daily total time across all sessions (persisted across app restarts) */
    DAILY_TOTAL
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
