package com.seenot.app.data.repository

import android.content.Context
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.AppHintEntity
import com.seenot.app.data.model.AppHint
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
    suspend fun addHintFromFeedback(packageName: String, hintText: String): AppHint {
        val hint = AppHint(
            packageName = packageName,
            hintText = hintText.trim()
        )
        return saveHint(hint)
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

    /**
     * Get hints for a specific package as Flow
     */
    fun getHintsForPackageFlow(packageName: String): Flow<List<AppHint>> {
        return dao.getHintsForPackageFlow(packageName).map { entities ->
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
            hintText = hintText,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun AppHint.toEntity(): AppHintEntity {
        return AppHintEntity(
            id = id,
            packageName = packageName,
            hintText = hintText,
            isActive = isActive,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
