package com.seenot.app.domain

import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoMonitorTimedRestTest {
    @Test
    fun createsTypedNoMonitorRestConstraintWithSessionTimer() {
        val constraint = NoMonitorTimedRest.createConstraint(durationMinutes = 10)

        assertEquals(ConstraintType.NO_MONITOR, constraint.type)
        assertEquals(TimeScope.SESSION, constraint.timeScope)
        assertEquals(10 * 60_000L, constraint.timeLimitMs)
        assertEquals(InterventionLevel.STRICT, constraint.interventionLevel)
        assertTrue(NoMonitorTimedRest.isTimedRest(constraint))
    }

    @Test
    fun computesPlannedEndAtAndRemainingTimeFromActiveSessionState() {
        val session = ActiveSession(
            sessionId = 1L,
            appPackageName = "com.example.app",
            appDisplayName = "Example",
            constraints = listOf(NoMonitorTimedRest.createConstraint(durationMinutes = 5)),
            startTime = 1_000L,
            constraintTimeRemaining = mapOf(NoMonitorTimedRest.constraintId to 125_000L)
        )

        assertEquals(301_000L, NoMonitorTimedRest.plannedEndAt(session))
        assertEquals(3L, NoMonitorTimedRest.remainingMinutesCeil(session))
    }

    @Test
    fun ignoresUntimedNoMonitorSessions() {
        val session = ActiveSession(
            sessionId = 1L,
            appPackageName = "com.example.app",
            appDisplayName = "Example",
            constraints = listOf(
                SessionConstraint(
                    id = "manual-no-monitor",
                    type = ConstraintType.NO_MONITOR,
                    description = "No monitoring this time"
                )
            ),
            startTime = 1_000L
        )

        assertFalse(NoMonitorTimedRest.isTimedRest(session.constraints.first()))
        assertNull(NoMonitorTimedRest.plannedEndAt(session))
        assertNull(NoMonitorTimedRest.remainingMinutesCeil(session))
    }
}
