package com.seenot.app.domain

import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionImprovementCandidateSelectorTest {
    private val selector = SessionImprovementCandidateSelector()

    @Test
    fun smoothSessionDoesNotNeedSuggestion() {
        val result = selector.select(
            sessionId = 10L,
            appPackageName = "com.example.app",
            appDisplayName = "Example",
            records = listOf(
                analysis("safe-1", isConditionMatched = true, confidence = 0.92),
                analysis("safe-2", isConditionMatched = true, confidence = 0.88)
            )
        )

        assertFalse(result.shouldGenerate)
        assertEquals(SessionImprovementTrigger.NONE, result.primaryTrigger)
    }

    @Test
    fun repeatedActionsForSameConstraintNeedSuggestion() {
        val result = selector.select(
            sessionId = 11L,
            appPackageName = "com.example.video",
            appDisplayName = "Video",
            records = listOf(
                action("a1", constraintId = 7L),
                action("a2", constraintId = 7L),
                analysis("v1", constraintId = 7L, isConditionMatched = false)
            )
        )

        assertTrue(result.shouldGenerate)
        assertEquals(SessionImprovementTrigger.REPEATED_ACTION, result.primaryTrigger)
        assertEquals(listOf("a1", "a2", "v1"), result.evidenceRecords.map { it.id })
    }

    @Test
    fun noMonitorTimedRestDoesNotNeedSuggestion() {
        val result = selector.select(
            sessionId = 12L,
            appPackageName = "com.example.rest",
            appDisplayName = "Rest",
            records = listOf(
                analysis(
                    id = "rest",
                    constraintType = ConstraintType.NO_MONITOR,
                    isConditionMatched = true,
                    actionReason = "timeout"
                )
            )
        )

        assertFalse(result.shouldGenerate)
    }

    @Test
    fun onlyActionRecordIsNotHighConfidence() {
        val result = selector.select(
            sessionId = 13L,
            appPackageName = "com.example.app",
            appDisplayName = "Example",
            records = listOf(action("a1", constraintId = 2L))
        )

        assertTrue(result.shouldGenerate)
        assertEquals(SessionImprovementConfidence.LOW, result.confidence)
    }

    private fun analysis(
        id: String,
        constraintId: Long? = 1L,
        constraintType: ConstraintType = ConstraintType.DENY,
        isConditionMatched: Boolean,
        confidence: Double? = 0.9,
        actionReason: String? = null
    ): RuleRecord {
        return RuleRecord(
            id = id,
            sessionId = 1L,
            appName = "Example",
            packageName = "com.example.app",
            constraintId = constraintId,
            constraintType = constraintType,
            constraintContent = "只看工作消息",
            isConditionMatched = isConditionMatched,
            confidence = confidence,
            actionReason = actionReason
        )
    }

    private fun action(id: String, constraintId: Long?): RuleRecord {
        return RuleRecord(
            id = id,
            sessionId = 1L,
            appName = "Example",
            packageName = "com.example.app",
            constraintId = constraintId,
            constraintType = ConstraintType.DENY,
            constraintContent = "只看工作消息",
            isConditionMatched = false,
            actionType = "AUTO_BACK",
            actionReason = "violation",
            actionTimestamp = System.currentTimeMillis()
        )
    }
}
