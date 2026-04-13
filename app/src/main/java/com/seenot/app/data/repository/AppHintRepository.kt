package com.seenot.app.data.repository

import android.content.Context
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.AppHintEntity
import com.seenot.app.data.model.AppHint
import com.seenot.app.data.model.APP_HINT_SOURCE_FEEDBACK_GENERATED
import com.seenot.app.data.model.APP_HINT_SOURCE_MANUAL
import com.seenot.app.data.model.AppHintScopeType
import com.seenot.app.data.model.buildAppGeneralScopeKey
import com.seenot.app.data.model.buildAppGeneralScopeLabel
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository for app-specific hints
 */
class AppHintRepository(private val context: Context) {

    private val dao = SeenotDatabase.getInstance(context).appHintDao()

    companion object {
        private const val TAG = "AppHintRepository"
    }

    data class SaveHintResult(
        val hint: AppHint,
        val created: Boolean
    )

    /**
     * Save a new hint
     */
    suspend fun saveHint(hint: AppHint): AppHint {
        return withContext(Dispatchers.IO) {
            val entity = hint.toEntity()
            dao.insert(entity)
            hint
        }
    }

    /**
     * Save hint for a specific app from misclassification feedback
     * This is the key method for the feedback loop
     */
    suspend fun addHintFromFeedback(
        packageName: String,
        scopeType: AppHintScopeType,
        scopeKey: String,
        intentId: String,
        intentLabel: String,
        hintText: String
    ): AppHint {
        val hint = AppHint(
            packageName = packageName,
            scopeType = scopeType,
            scopeKey = scopeKey,
            intentId = intentId,
            intentLabel = intentLabel,
            source = APP_HINT_SOURCE_MANUAL,
            hintText = hintText.trim()
        )
        return saveHint(hint)
    }

    suspend fun saveHintIfNew(
        packageName: String,
        scopeType: AppHintScopeType,
        scopeKey: String,
        intentId: String,
        intentLabel: String,
        hintText: String,
        source: String = APP_HINT_SOURCE_FEEDBACK_GENERATED,
        sourceHintId: String? = null
    ): SaveHintResult {
        val normalized = normalizeHintText(hintText)
        val existing = getHintsForScopeKey(packageName, scopeType, scopeKey).firstOrNull {
            normalizeHintText(it.hintText) == normalized
        }
        if (existing != null) {
            return SaveHintResult(existing, created = false)
        }

        val saved = saveHint(
            AppHint(
                packageName = packageName,
                scopeType = scopeType,
                scopeKey = scopeKey,
                intentId = intentId,
                intentLabel = intentLabel,
                hintText = hintText.trim(),
                source = source,
                sourceHintId = sourceHintId
            )
        )
        return SaveHintResult(saved, created = true)
    }

    /**
     * Get all active hints as Flow
     */
    fun getAllActiveHintsFlow(): Flow<List<AppHint>> {
        return dao.getAllActiveHints().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * Get all active hints (one-time)
     */
    suspend fun getAllActiveHints(): List<AppHint> {
        return dao.getAllActiveHintsOnce().map { it.toModel() }
    }

    /**
     * Get hints for a specific package
     */
    suspend fun getHintsForPackage(packageName: String): List<AppHint> {
        return dao.getHintsForPackage(packageName).map { it.toModel() }
    }

    suspend fun getHintsForAppGeneral(packageName: String): List<AppHint> {
        return dao.getHintsForScopeType(packageName, AppHintScopeType.APP_GENERAL.name).map { it.toModel() }
    }

    suspend fun getHintsForIntent(packageName: String, intentId: String): List<AppHint> {
        return dao.getHintsForIntent(packageName, intentId).map { it.toModel() }
    }

    suspend fun getHintsForScopeKey(
        packageName: String,
        scopeType: AppHintScopeType,
        scopeKey: String
    ): List<AppHint> {
        return dao.getHintsForScopeKey(packageName, scopeType.name, scopeKey).map { it.toModel() }
    }

    /**
     * Get hints for a specific package as Flow
     */
    fun getHintsForPackageFlow(packageName: String): Flow<List<AppHint>> {
        return dao.getHintsForPackageFlow(packageName).map { entities ->
            entities.map { it.toModel() }
        }
    }

    fun getHintsForAppGeneralFlow(packageName: String): Flow<List<AppHint>> {
        return dao.getHintsForScopeTypeFlow(packageName, AppHintScopeType.APP_GENERAL.name).map { entities ->
            entities.map { it.toModel() }
        }
    }

    fun getHintsForIntentFlow(packageName: String, intentId: String): Flow<List<AppHint>> {
        return dao.getHintsForIntentFlow(packageName, intentId).map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * Get hint by ID
     */
    suspend fun getHintById(hintId: String): AppHint? {
        return dao.getHintById(hintId)?.toModel()
    }

    /**
     * Update hint text
     */
    suspend fun updateHintText(hintId: String, hintText: String): Boolean {
        return try {
            dao.updateHintText(hintId, hintText.trim())
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating hint", e)
            false
        }
    }

    suspend fun updateHint(
        hintId: String,
        hintText: String,
        scopeType: AppHintScopeType,
        scopeKey: String,
        intentId: String,
        intentLabel: String
    ): Boolean {
        return try {
            dao.updateHintText(hintId, hintText.trim())
            dao.updateHintScope(
                hintId = hintId,
                scopeType = scopeType.name,
                scopeKey = scopeKey,
                intentId = intentId,
                intentLabel = intentLabel
            )
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error updating hint intent", e)
            false
        }
    }

    suspend fun moveHintsToIntent(
        packageName: String,
        fromIntentId: String,
        toIntentId: String,
        toIntentLabel: String
    ): Boolean {
        return try {
            val sourceHints = getHintsForIntent(packageName, fromIntentId)
            if (sourceHints.isEmpty()) return true

            if (fromIntentId == toIntentId) {
                sourceHints.forEach { hint ->
                    if (hint.intentLabel != toIntentLabel) {
                        dao.updateHintIntent(hint.id, toIntentId, toIntentLabel)
                    }
                }
                return true
            }

            val targetNormalized = getHintsForIntent(packageName, toIntentId)
                .map { normalizeHintText(it.hintText) }
                .toMutableSet()

            sourceHints.forEach { hint ->
                val normalized = normalizeHintText(hint.hintText)
                if (normalized in targetNormalized) {
                    dao.deleteById(hint.id)
                } else {
                    dao.updateHintIntent(hint.id, toIntentId, toIntentLabel)
                    targetNormalized += normalized
                }
            }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error moving hints to new intent", e)
            false
        }
    }

    suspend fun moveHintsToScope(
        packageName: String,
        fromScopeType: AppHintScopeType,
        fromScopeKey: String,
        toScopeType: AppHintScopeType,
        toScopeKey: String,
        toIntentId: String,
        toIntentLabel: String
    ): Boolean {
        return try {
            val sourceHints = getHintsForScopeKey(packageName, fromScopeType, fromScopeKey)
            if (sourceHints.isEmpty()) return true

            if (fromScopeType == toScopeType && fromScopeKey == toScopeKey) {
                sourceHints.forEach { hint ->
                    if (hint.intentLabel != toIntentLabel || hint.intentId != toIntentId) {
                        dao.updateHintScope(
                            hintId = hint.id,
                            scopeType = toScopeType.name,
                            scopeKey = toScopeKey,
                            intentId = toIntentId,
                            intentLabel = toIntentLabel
                        )
                    }
                }
                return true
            }

            val targetNormalized = getHintsForScopeKey(packageName, toScopeType, toScopeKey)
                .map { normalizeHintText(it.hintText) }
                .toMutableSet()

            sourceHints.forEach { hint ->
                val normalized = normalizeHintText(hint.hintText)
                if (normalized in targetNormalized) {
                    dao.deleteById(hint.id)
                } else {
                    dao.updateHintScope(
                        hintId = hint.id,
                        scopeType = toScopeType.name,
                        scopeKey = toScopeKey,
                        intentId = toIntentId,
                        intentLabel = toIntentLabel
                    )
                    targetNormalized += normalized
                }
            }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error moving hints to new scope", e)
            false
        }
    }

    fun buildManualHintIdentity(
        packageName: String,
        scopeType: AppHintScopeType,
        intentId: String?,
        intentLabel: String?
    ): Triple<String, String, String> {
        return when (scopeType) {
            AppHintScopeType.APP_GENERAL -> Triple(
                buildAppGeneralScopeKey(packageName),
                buildAppGeneralScopeKey(packageName),
                buildAppGeneralScopeLabel(null)
            )
            AppHintScopeType.INTENT_SPECIFIC -> Triple(
                intentId.orEmpty(),
                intentId.orEmpty(),
                intentLabel.orEmpty()
            )
        }
    }

    /**
     * Toggle hint active status
     */
    suspend fun toggleHintActive(hintId: String, isActive: Boolean): Boolean {
        return try {
            dao.updateActiveStatus(hintId, isActive)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error toggling hint", e)
            false
        }
    }

    /**
     * Delete a hint
     */
    suspend fun deleteHint(hintId: String): Boolean {
        return try {
            dao.deleteById(hintId)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error deleting hint", e)
            false
        }
    }

    /**
     * Delete all hints for a package
     */
    suspend fun deleteHintsForPackage(packageName: String): Boolean {
        return try {
            dao.deleteByPackage(packageName)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error deleting hints for package", e)
            false
        }
    }

    /**
     * Clear all hints
     */
    suspend fun clearAllHints(): Boolean {
        return try {
            dao.deleteAll()
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error clearing hints", e)
            false
        }
    }

    /**
     * Get count of active hints for a package
     */
    suspend fun getActiveHintCountForPackage(packageName: String): Int {
        return dao.getActiveHintCountForPackage(packageName)
    }

    private fun AppHintEntity.toModel(): AppHint {
        return AppHint(
            id = id,
            packageName = packageName,
            scopeType = runCatching { AppHintScopeType.valueOf(scopeType) }.getOrDefault(AppHintScopeType.INTENT_SPECIFIC),
            scopeKey = scopeKey.ifBlank {
                if (intentId.isNotBlank()) intentId else buildAppGeneralScopeKey(packageName)
            },
            intentId = intentId,
            intentLabel = intentLabel,
            hintText = hintText,
            source = source,
            sourceHintId = sourceHintId,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun AppHint.toEntity(): AppHintEntity {
        return AppHintEntity(
            id = id,
            packageName = packageName,
            scopeType = scopeType.name,
            scopeKey = scopeKey,
            intentId = intentId,
            intentLabel = intentLabel,
            hintText = hintText,
            source = source,
            sourceHintId = sourceHintId,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun normalizeHintText(text: String): String {
        return text
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }
}
