package com.seenot.app.domain

import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import kotlin.math.ceil

object NoMonitorTimedRest {
    const val constraintId = "no-monitor-timed-rest"
    const val defaultMinutes = 10
    val durationOptionsMinutes = listOf(5, 10, 15)

    fun createConstraint(durationMinutes: Int): SessionConstraint {
        val minutes = durationMinutes.takeIf { it in durationOptionsMinutes } ?: defaultMinutes
        return SessionConstraint(
            id = constraintId,
            type = ConstraintType.NO_MONITOR,
            description = "Timed rest",
            timeLimitMs = minutes * 60_000L,
            timeScope = TimeScope.SESSION,
            interventionLevel = InterventionLevel.STRICT
        )
    }

    fun isTimedRest(constraint: SessionConstraint): Boolean {
        return constraint.type == ConstraintType.NO_MONITOR &&
            constraint.timeScope == TimeScope.SESSION &&
            constraint.timeLimitMs != null &&
            constraint.id == constraintId
    }

    fun isTimedRestSession(session: ActiveSession): Boolean {
        return session.constraints.isNotEmpty() &&
            session.constraints.all { it.type == ConstraintType.NO_MONITOR } &&
            session.constraints.any(::isTimedRest)
    }

    fun plannedEndAt(session: ActiveSession): Long? {
        val durationMs = session.constraints.firstOrNull(::isTimedRest)?.timeLimitMs ?: return null
        return session.startTime + durationMs
    }

    fun remainingMs(session: ActiveSession): Long? {
        val constraint = session.constraints.firstOrNull(::isTimedRest) ?: return null
        return session.constraintTimeRemaining[constraint.id]?.coerceAtLeast(0L)
    }

    fun remainingMinutesCeil(session: ActiveSession): Long? {
        val remainingMs = remainingMs(session) ?: return null
        return ceil(remainingMs / 60_000.0).toLong().coerceAtLeast(1L)
    }
}
