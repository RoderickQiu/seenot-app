package com.seenot.app.data.repository

import com.seenot.app.data.local.dao.IntentConstraintDao
import com.seenot.app.data.local.dao.ScreenAnalysisResultDao
import com.seenot.app.data.local.dao.SessionDao
import com.seenot.app.data.local.dao.SessionIntentDao
import com.seenot.app.data.local.entity.IntentConstraintEntity
import com.seenot.app.data.local.entity.ScreenAnalysisResultEntity
import com.seenot.app.data.local.entity.SessionEntity
import com.seenot.app.data.local.entity.SessionIntentEntity
import com.seenot.app.data.model.InterventionLevel
import com.seenot.app.data.model.TimeScope
import com.seenot.app.data.model.UtteranceSource
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing sessions, intents, and constraints.
 * Provides a clean API for the domain layer.
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val sessionIntentDao: SessionIntentDao,
    private val intentConstraintDao: IntentConstraintDao,
    private val screenAnalysisResultDao: ScreenAnalysisResultDao
) {

    // ==================== Session Operations ====================

    suspend fun createSession(
        appPackageName: String,
        appDisplayName: String,
        totalTimeLimitMs: Long? = null
    ): Long {
        val session = SessionEntity(
            appPackageName = appPackageName,
            appDisplayName = appDisplayName,
            totalTimeLimitMs = totalTimeLimitMs
        )
        return sessionDao.insert(session)
    }

    suspend fun getSession(sessionId: Long): SessionEntity? {
        return sessionDao.getById(sessionId)
    }

    fun observeSession(sessionId: Long): Flow<SessionEntity?> {
        return sessionDao.observeById(sessionId)
    }

    suspend fun getActiveSession(): SessionEntity? {
        return sessionDao.getActiveSession()
    }

    fun observeActiveSession(): Flow<SessionEntity?> {
        return sessionDao.observeActiveSession()
    }

    suspend fun getActiveSessionForApp(packageName: String): SessionEntity? {
        return sessionDao.getActiveSessionForApp(packageName)
    }

    fun observeAllSessions(): Flow<List<SessionEntity>> {
        return sessionDao.observeAllSessions()
    }

    fun observeRecentSessions(limit: Int = 10): Flow<List<SessionEntity>> {
        return sessionDao.observeRecentSessions(limit)
    }

    suspend fun endSession(sessionId: Long, reason: String) {
        sessionDao.endSession(sessionId, reason = reason)
    }

    // ==================== Intent Operations ====================

    suspend fun addIntent(
        sessionId: Long,
        rawUtterance: String,
        parsedIntentJson: String,
        source: UtteranceSource
    ): Long {
        val intent = SessionIntentEntity(
            sessionId = sessionId,
            rawUtterance = rawUtterance,
            parsedIntentJson = parsedIntentJson,
            source = source
        )
        return sessionIntentDao.insert(intent)
    }

    fun observeIntentsForSession(sessionId: Long): Flow<List<SessionIntentEntity>> {
        return sessionIntentDao.observeIntentsForSession(sessionId)
    }

    fun observeActiveIntentsForSession(sessionId: Long): Flow<List<SessionIntentEntity>> {
        return sessionIntentDao.observeActiveIntentsForSession(sessionId)
    }

    suspend fun getLatestIntentForSession(sessionId: Long): SessionIntentEntity? {
        return sessionIntentDao.getLatestIntentForSession(sessionId)
    }

    suspend fun deactivateAllIntentsForSession(sessionId: Long) {
        sessionIntentDao.deactivateAllForSession(sessionId)
    }

    // ==================== Constraint Operations ====================

    suspend fun addConstraint(
        sessionId: Long,
        intentId: Long,
        type: com.seenot.app.data.model.ConstraintType,
        contentPattern: String?,
        timeLimitMs: Long?,
        timeScope: TimeScope?,
        interventionLevel: InterventionLevel,
        effectiveIntentJson: String? = null
    ): Long {
        val constraint = IntentConstraintEntity(
            sessionId = sessionId,
            intentId = intentId,
            type = type,
            contentPattern = contentPattern,
            timeLimitMs = timeLimitMs,
            timeScope = timeScope,
            interventionLevel = interventionLevel,
            effectiveIntentJson = effectiveIntentJson
        )
        return intentConstraintDao.insert(constraint)
    }

    suspend fun addConstraints(
        constraints: List<IntentConstraintEntity>
    ): List<Long> {
        return intentConstraintDao.insertAll(constraints)
    }

    fun observeConstraintsForSession(sessionId: Long): Flow<List<IntentConstraintEntity>> {
        return intentConstraintDao.observeConstraintsForSession(sessionId)
    }

    fun observeActiveConstraintsForSession(sessionId: Long): Flow<List<IntentConstraintEntity>> {
        return intentConstraintDao.observeActiveConstraintsForSession(sessionId)
    }

    suspend fun getActiveConstraintsForSession(sessionId: Long): List<IntentConstraintEntity> {
        return intentConstraintDao.getActiveConstraintsForSession(sessionId)
    }

    suspend fun getConstraintsForSession(sessionId: Long): List<IntentConstraintEntity> {
        return intentConstraintDao.getConstraintsForSession(sessionId)
    }

    suspend fun setConstraintActive(constraintId: Long, isActive: Boolean) {
        intentConstraintDao.setActive(constraintId, isActive)
    }

    suspend fun replaceConstraintsForSession(
        sessionId: Long,
        constraints: List<IntentConstraintEntity>
    ): List<Long> {
        intentConstraintDao.deleteAllForSession(sessionId)
        if (constraints.isEmpty()) return emptyList()
        return intentConstraintDao.insertAll(constraints)
    }

    // ==================== Screen Analysis Operations ====================

    suspend fun addScreenAnalysis(
        sessionId: Long,
        screenshotHash: String,
        isViolation: Boolean,
        violatedConstraintIds: List<Long>,
        confidence: Float,
        rawAnalysisJson: String,
        isDeduplicated: Boolean = false
    ): Long {
        val result = ScreenAnalysisResultEntity(
            sessionId = sessionId,
            screenshotHash = screenshotHash,
            isViolation = isViolation,
            violatedConstraintIdsJson = violatedConstraintIds.joinToString(",", "[", "]"),
            confidence = confidence,
            rawAnalysisJson = rawAnalysisJson,
            isDeduplicated = isDeduplicated
        )
        return screenAnalysisResultDao.insert(result)
    }

    fun observeResultsForSession(sessionId: Long): Flow<List<ScreenAnalysisResultEntity>> {
        return screenAnalysisResultDao.observeResultsForSession(sessionId)
    }

    suspend fun getLatestAnalysisForSession(sessionId: Long): ScreenAnalysisResultEntity? {
        return screenAnalysisResultDao.getLatestResultForSession(sessionId)
    }

    suspend fun findAnalysisByHash(sessionId: Long, hash: String): ScreenAnalysisResultEntity? {
        return screenAnalysisResultDao.findBySessionAndHash(sessionId, hash)
    }

    suspend fun getViolationCountForSession(sessionId: Long): Int {
        return screenAnalysisResultDao.getViolationCountForSession(sessionId)
    }
}
