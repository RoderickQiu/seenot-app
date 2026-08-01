package com.seenot.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionModeSourceTest {
    private val source = File("src/main/java/com/seenot/app/domain/SessionManager.kt").readText()

    @Test
    fun guardedBehaviorUsesExplicitSessionModeInsteadOfConstraintId() {
        assertTrue(source.contains("session.mode == SessionMode.GUARDED"))
        assertTrue(source.contains("mode == SessionMode.FOCUS"))
        assertFalse(source.contains("constraints.any { it.id == \"guarded-mode\" }"))
        assertFalse(source.contains("it.id != \"guarded-mode\""))
    }

    @Test
    fun guardedSessionsDoNotPolluteFocusIntentHistory() {
        assertTrue(source.contains("if (mode == SessionMode.FOCUS && sessionGoalConstraints.isNotEmpty())"))
        assertTrue(source.contains("saveLastIntent(packageName, sessionGoalConstraints)"))
        assertTrue(source.contains("sessionGoalOnly(constraints)"))
    }

    @Test
    fun legacyGuardedConstraintsAreExcludedFromStoredFocusSurfaces() {
        assertTrue(source.contains("GuardedSessionConstraint.isInternalEntry"))
        assertTrue(source.contains("!GuardedSessionConstraint.isInternal(it)"))
    }
}
