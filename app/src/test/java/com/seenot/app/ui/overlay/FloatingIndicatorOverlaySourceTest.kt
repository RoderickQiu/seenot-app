package com.seenot.app.ui.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FloatingIndicatorOverlaySourceTest {
    private val source = File(
        "src/main/java/com/seenot/app/ui/overlay/FloatingIndicatorOverlay.kt"
    ).readText()

    @Test
    fun guardedStatusDoesNotAlsoShowTheMissingIntentEmptyState() {
        assertTrue(source.contains("if (!isGuarded && statuses.isEmpty())"))
    }

    @Test
    fun guardedBrowsingCanStillShowAnActiveDefaultRule() {
        assertTrue(source.contains("R.string.hud_guarded_with_default_rule"))
        assertTrue(source.contains("filterNot { GuardedSessionConstraint.isInternal(it) }"))
    }

    @Test
    fun expandedHudOffersRemovingOnlyTheTemporaryGoal() {
        assertTrue(source.contains("R.string.hud_btn_remove_session_goal"))
        assertTrue(source.contains("sessionManager.clearCurrentSessionGoal()"))
        assertTrue(source.contains("!it.isDefault && !GuardedSessionConstraint.isInternal(it)"))
    }

    @Test
    fun changingGoalUsesTheExistingSessionPath() {
        val serviceSource = File(
            "src/main/java/com/seenot/app/service/SeenotAccessibilityService.kt"
        ).readText()
        assertTrue(serviceSource.contains("replaceCurrentSessionGoal(constraints, mode)"))
    }
}
