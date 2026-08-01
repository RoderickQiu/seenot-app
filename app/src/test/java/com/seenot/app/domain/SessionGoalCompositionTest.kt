package com.seenot.app.domain

import com.seenot.app.data.model.ConstraintType
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGoalCompositionTest {
    private val defaultRule = SessionConstraint(
        id = "default",
        type = ConstraintType.DENY,
        description = "Do not browse recommendations",
        isDefault = true
    )
    private val sessionGoal = SessionConstraint(
        id = "goal",
        type = ConstraintType.DENY,
        description = "Only compare two hotels"
    )

    @Test
    fun combinesDefaultRuleAndSessionGoalForOneAnalysisContext() {
        assertEquals(
            listOf(defaultRule, sessionGoal),
            combineDefaultRuleAndSessionGoal(defaultRule, listOf(sessionGoal))
        )
    }

    @Test
    fun doesNotDuplicateDefaultRuleWhenItWasAlreadySelected() {
        assertEquals(
            listOf(defaultRule),
            combineDefaultRuleAndSessionGoal(defaultRule, listOf(defaultRule))
        )
    }

    @Test
    fun recentGoalExcludesDefaultAndGuardedInternalConstraints() {
        val guarded = SessionConstraint(
            id = GuardedSessionConstraint.ID,
            type = ConstraintType.TIME_CAP,
            description = "Guarded browsing"
        )

        assertEquals(
            listOf(sessionGoal),
            sessionGoalOnly(listOf(defaultRule, sessionGoal, guarded))
        )
    }

    @Test
    fun newlySelectedDefaultIsAddedWithoutRemovingTheSessionGoal() {
        assertEquals(
            listOf(defaultRule, sessionGoal),
            reconcileDefaultRuleChange(listOf(sessionGoal), emptyList(), listOf(defaultRule))
        )
    }

    @Test
    fun clearingDefaultRemovesOnlyThePreviousDefault() {
        assertEquals(
            listOf(sessionGoal),
            reconcileDefaultRuleChange(
                listOf(defaultRule, sessionGoal),
                listOf(defaultRule),
                listOf(defaultRule.copy(isDefault = false))
            )
        )
    }

    @Test
    fun legacyAppWithDefaultKeepsDirectEntry() {
        assertEquals(AppEntryIntentMode.USE_PRESET, resolveEntryMode(null, hasActiveDefaultRule = true))
    }

    @Test
    fun appWithoutDefaultKeepsTheExistingAskEveryTimeFlow() {
        assertEquals(AppEntryIntentMode.ASK_EVERY_TIME, resolveEntryMode(null, hasActiveDefaultRule = false))
        assertEquals(
            AppEntryIntentMode.ASK_EVERY_TIME,
            resolveEntryMode(AppEntryIntentMode.USE_PRESET, hasActiveDefaultRule = false)
        )
    }
}
