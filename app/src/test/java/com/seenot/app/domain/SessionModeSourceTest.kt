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
        assertTrue(
            source.contains(
                "if (mode == SessionMode.FOCUS) {\n            saveLastIntent(packageName, effectiveConstraints)"
            )
        )
    }
}
