package com.seenot.app.domain

import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RuleRecord

enum class SessionImprovementTrigger {
    NONE,
    VIOLATION_ACTION,
    REPEATED_ACTION,
    FALSE_POSITIVE_FEEDBACK,
    LOW_CONFIDENCE
}

enum class SessionImprovementConfidence {
    HIGH,
    MEDIUM,
    LOW
}

data class SessionImprovementCandidate(
    val sessionId: Long,
    val appPackageName: String,
    val appDisplayName: String,
    val shouldGenerate: Boolean,
    val primaryTrigger: SessionImprovementTrigger,
    val confidence: SessionImprovementConfidence,
    val evidenceRecords: List<RuleRecord>
)

class SessionImprovementCandidateSelector {
    fun select(
        sessionId: Long,
        appPackageName: String,
        appDisplayName: String,
        records: List<RuleRecord>
    ): SessionImprovementCandidate {
        val relevant = records
            .filterNot { it.constraintType == ConstraintType.NO_MONITOR }
            .sortedBy { it.timestamp }
        if (relevant.isEmpty()) {
            return noCandidate(sessionId, appPackageName, appDisplayName)
        }

        val actionRecords = relevant.filter { !it.actionType.isNullOrBlank() && it.actionReason == "violation" }
        val markedRecords = relevant.filter { it.isMarked }
        val violationAnalysis = relevant.filter {
            it.actionType.isNullOrBlank() &&
                it.constraintType == ConstraintType.DENY &&
                !it.isConditionMatched
        }
        val lowConfidence = relevant.filter { (it.confidence ?: 1.0) < LOW_CONFIDENCE_THRESHOLD }

        val repeatedActionRecords = repeatedActionRecords(actionRecords)
        val primaryTrigger = when {
            repeatedActionRecords.isNotEmpty() -> SessionImprovementTrigger.REPEATED_ACTION
            actionRecords.isNotEmpty() -> SessionImprovementTrigger.VIOLATION_ACTION
            markedRecords.isNotEmpty() -> SessionImprovementTrigger.FALSE_POSITIVE_FEEDBACK
            lowConfidence.size >= LOW_CONFIDENCE_MIN_COUNT -> SessionImprovementTrigger.LOW_CONFIDENCE
            else -> SessionImprovementTrigger.NONE
        }

        if (primaryTrigger == SessionImprovementTrigger.NONE) {
            return noCandidate(sessionId, appPackageName, appDisplayName)
        }

        val evidence = buildList {
            addAll(repeatedActionRecords.ifEmpty { actionRecords })
            addAll(markedRecords)
            addAll(violationAnalysis)
            addAll(lowConfidence)
        }
            .distinctBy { it.id }
            .take(MAX_EVIDENCE_RECORDS)

        val confidence = when {
            violationAnalysis.isNotEmpty() && actionRecords.size >= 2 -> SessionImprovementConfidence.HIGH
            violationAnalysis.isNotEmpty() || markedRecords.isNotEmpty() -> SessionImprovementConfidence.MEDIUM
            else -> SessionImprovementConfidence.LOW
        }

        return SessionImprovementCandidate(
            sessionId = sessionId,
            appPackageName = appPackageName,
            appDisplayName = appDisplayName,
            shouldGenerate = true,
            primaryTrigger = primaryTrigger,
            confidence = confidence,
            evidenceRecords = evidence
        )
    }

    private fun repeatedActionRecords(actionRecords: List<RuleRecord>): List<RuleRecord> {
        return actionRecords
            .groupBy { record ->
                record.constraintId?.toString()
                    ?: record.constraintContent?.takeIf { it.isNotBlank() }
                    ?: "unknown"
            }
            .values
            .firstOrNull { it.size >= 2 }
            .orEmpty()
    }

    private fun noCandidate(
        sessionId: Long,
        appPackageName: String,
        appDisplayName: String
    ): SessionImprovementCandidate {
        return SessionImprovementCandidate(
            sessionId = sessionId,
            appPackageName = appPackageName,
            appDisplayName = appDisplayName,
            shouldGenerate = false,
            primaryTrigger = SessionImprovementTrigger.NONE,
            confidence = SessionImprovementConfidence.LOW,
            evidenceRecords = emptyList()
        )
    }

    private companion object {
        private const val LOW_CONFIDENCE_THRESHOLD = 0.65
        private const val LOW_CONFIDENCE_MIN_COUNT = 2
        private const val MAX_EVIDENCE_RECORDS = 8
    }
}
