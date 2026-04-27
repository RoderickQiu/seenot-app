package com.seenot.app.data.model

import androidx.annotation.StringRes
import com.seenot.app.R

/**
 * Constraint type as defined in PRD section 3.2.1
 */
enum class ConstraintType {
    /** Deny this content - this content triggers intervention */
    DENY,

    /** Pure time constraint - no specific content targeting */
    TIME_CAP,

    /** User explicitly chose not to monitor this app for the current session */
    NO_MONITOR
}

enum class TimeScope {
    SESSION,
    PER_CONTENT,
    CONTINUOUS;

    @StringRes
    fun displayLabelResId(): Int = when (this) {
        SESSION -> R.string.time_scope_session
        PER_CONTENT -> R.string.time_scope_per_content
        CONTINUOUS -> R.string.time_scope_continuous
    }
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
