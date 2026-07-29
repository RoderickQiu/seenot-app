package com.seenot.app.domain

/** Product-level mode for an active session. Constraints remain enforcement details. */
enum class SessionMode {
    GUARDED,
    FOCUS
}

/** Stable ID for the internal constraint used by pre-SessionMode Guarded builds. */
object GuardedSessionConstraint {
    const val ID = "guarded-mode"

    fun isInternal(constraint: SessionConstraint): Boolean = constraint.id == ID

    fun isInternalEntry(constraints: List<SessionConstraint>): Boolean =
        constraints.any(::isInternal)
}
