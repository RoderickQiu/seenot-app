package com.seenot.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.SessionImprovementSuggestionEntity
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.SessionImprovementRuleDecision
import com.seenot.app.data.model.SessionImprovementSuggestion
import com.seenot.app.data.model.SessionImprovementSuggestionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SessionImprovementSuggestionRepository(context: Context) {
    private val dao = SeenotDatabase.getInstance(context).sessionImprovementSuggestionDao()
    private val gson = Gson()

    suspend fun saveIfAbsent(suggestion: SessionImprovementSuggestion): Boolean = withContext(Dispatchers.IO) {
        if (dao.getBySessionId(suggestion.sessionId) != null) return@withContext false
        dao.upsert(suggestion.toEntity())
        true
    }

    fun getSuggestionsInRangeFlow(startTime: Long, endTime: Long): Flow<List<SessionImprovementSuggestion>> {
        return dao.getSuggestionsInRangeFlow(startTime, endTime).map { entities ->
            entities.map { it.toModel() }
        }
    }

    suspend fun getPendingSuggestionForPackage(packageName: String): SessionImprovementSuggestion? {
        return withContext(Dispatchers.IO) {
            dao.getPendingSuggestionForPackage(packageName)?.toModel()
        }
    }

    suspend fun acceptNextIntent(suggestionId: String) = withContext(Dispatchers.IO) {
        dao.accept(suggestionId, System.currentTimeMillis(), "next_intent")
    }

    suspend fun dismiss(suggestionId: String) = withContext(Dispatchers.IO) {
        dao.dismiss(suggestionId, System.currentTimeMillis())
    }

    private fun SessionImprovementSuggestion.toEntity(): SessionImprovementSuggestionEntity {
        return SessionImprovementSuggestionEntity(
            id = id,
            sessionId = sessionId,
            packageName = packageName,
            appName = appName,
            status = status.name,
            sessionPattern = sessionPattern,
            nextIntentSuggestion = nextIntentSuggestion,
            ruleDecision = ruleDecision.name,
            ruleText = ruleText,
            ruleScopeType = ruleScopeType?.name,
            ruleReason = ruleReason,
            confidence = confidence,
            evidenceRecordIdsJson = gson.toJson(evidenceRecordIds),
            createdAt = createdAt,
            dismissedAt = dismissedAt,
            acceptedAt = acceptedAt,
            acceptedAction = acceptedAction
        )
    }

    private fun SessionImprovementSuggestionEntity.toModel(): SessionImprovementSuggestion {
        val idsType = object : TypeToken<List<String>>() {}.type
        return SessionImprovementSuggestion(
            id = id,
            sessionId = sessionId,
            packageName = packageName,
            appName = appName,
            status = runCatching { SessionImprovementSuggestionStatus.valueOf(status) }
                .getOrDefault(SessionImprovementSuggestionStatus.FAILED),
            sessionPattern = sessionPattern,
            nextIntentSuggestion = nextIntentSuggestion,
            ruleDecision = runCatching { SessionImprovementRuleDecision.valueOf(ruleDecision) }
                .getOrDefault(SessionImprovementRuleDecision.NO_RULE),
            ruleText = ruleText,
            ruleScopeType = ruleScopeType?.let { runCatching { AppHintScopeType.valueOf(it) }.getOrNull() },
            ruleReason = ruleReason,
            confidence = confidence,
            evidenceRecordIds = runCatching { gson.fromJson<List<String>>(evidenceRecordIdsJson, idsType) }
                .getOrDefault(emptyList()),
            createdAt = createdAt,
            dismissedAt = dismissedAt,
            acceptedAt = acceptedAt,
            acceptedAction = acceptedAction
        )
    }
}
