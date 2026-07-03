package com.seenot.app.data.model

import java.util.UUID

enum class SessionImprovementSuggestionStatus {
    READY,
    FAILED
}

enum class SessionImprovementRuleDecision {
    CREATE_RULE,
    NO_RULE
}

data class SessionImprovementSuggestion(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: Long,
    val packageName: String,
    val appName: String,
    val status: SessionImprovementSuggestionStatus = SessionImprovementSuggestionStatus.READY,
    val sessionPattern: String,
    val nextIntentSuggestion: String,
    val ruleDecision: SessionImprovementRuleDecision = SessionImprovementRuleDecision.NO_RULE,
    val ruleText: String? = null,
    val ruleScopeType: AppHintScopeType? = null,
    val ruleReason: String? = null,
    val confidence: String? = null,
    val evidenceRecordIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val dismissedAt: Long? = null,
    val acceptedAt: Long? = null,
    val acceptedAction: String? = null
) {
    val isPending: Boolean
        get() = dismissedAt == null && acceptedAt == null

    val intentText: String
        get() = nextIntentSuggestion
}
