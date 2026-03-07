package com.seenot.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.RuleRecordEntity
import com.seenot.app.data.model.RecordStats
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for rule records with internal storage for screenshots
 * Screenshots are stored in app's internal storage, NOT in user's gallery
 */
class RuleRecordRepository(private val context: Context) {

    private val dao = SeenotDatabase.getInstance(context).ruleRecordDao()

    private val imagesDir: File = File(context.filesDir, "rule_record_images").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val TAG = "RuleRecordRepository"
        private const val MAX_RECORDS = 10000
    }

    /**
     * Save a new rule record
     */
    suspend fun saveRecord(record: RuleRecord): RuleRecord {
        return withContext(Dispatchers.IO) {
            val entity = record.toEntity()
            dao.insert(entity)
            record
        }
    }

    /**
     * Save screenshot bitmap to internal storage for a record
     * Returns the path to the saved image
     */
    suspend fun saveScreenshotForRecord(recordId: String, bitmap: Bitmap, isMarked: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val markedStr = if (isMarked) "marked" else "unmarked"
                val filename = "record_${recordId}_${dateFormat.format(Date(timestamp))}_$markedStr.png"
                val imageFile = File(imagesDir, filename)

                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val imagePath = imageFile.absolutePath
                Logger.d(TAG, "Saved screenshot for record $recordId: $imagePath")

                // Update record with imagePath
                dao.updateImagePath(recordId, imagePath)

                imagePath
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving screenshot for record", e)
                null
            }
        }
    }

    /**
     * Get all records as Flow
     */
    fun getAllRecordsFlow(): Flow<List<RuleRecord>> {
        return dao.getAllRecords().map { entities ->
            entities.map { it.toModel() }
        }
    }

    /**
     * Get all records (one-time)
     */
    suspend fun getAllRecords(): List<RuleRecord> {
        return dao.getAllRecordsOnce().map { it.toModel() }
    }

    /**
     * Get record by ID
     */
    suspend fun getRecordById(recordId: String): RuleRecord? {
        return dao.getRecordById(recordId)?.toModel()
    }

    /**
     * Get records within a date range
     */
    suspend fun getRecordsInRange(startTime: Long, endTime: Long): List<RuleRecord> {
        return dao.getRecordsInRange(startTime, endTime).map { it.toModel() }
    }

    /**
     * Get records for a specific date
     */
    suspend fun getRecordsForDate(year: Int, month: Int, dayOfMonth: Int): List<RuleRecord> {
        val calendar = Calendar.getInstance().apply {
            set(year, month, dayOfMonth, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis

        return dao.getRecordsForDate(startOfDay, endOfDay).map { it.toModel() }
    }

    /**
     * Get only marked records
     */
    suspend fun getMarkedRecords(): List<RuleRecord> {
        return dao.getMarkedRecords().map { it.toModel() }
    }

    /**
     * Get records filtered by match status
     */
    suspend fun getRecordsByMatchStatus(isMatched: Boolean): List<RuleRecord> {
        return dao.getRecordsByMatchStatus(isMatched).map { it.toModel() }
    }

    /**
     * Mark or unmark a record
     */
    suspend fun markRecord(recordId: String, isMarked: Boolean): Boolean {
        return try {
            dao.updateMarkedStatus(recordId, isMarked)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Error marking record", e)
            false
        }
    }

    /**
     * Delete a specific record and its image
     */
    suspend fun deleteRecord(recordId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val record = dao.getRecordById(recordId)
                record?.imagePath?.let { path ->
                    try {
                        File(path).delete()
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to delete image: $path", e)
                    }
                }
                dao.deleteById(recordId)
                true
            } catch (e: Exception) {
                Logger.e(TAG, "Error deleting record", e)
                false
            }
        }
    }

    /**
     * Clear all records and their images
     */
    suspend fun clearAllRecords(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Delete all images
                imagesDir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile) {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to delete file: ${file.absolutePath}", e)
                    }
                }

                // Clear records from database
                dao.deleteAll()

                Logger.d(TAG, "Cleared all rule records and images")
                true
            } catch (e: Exception) {
                Logger.e(TAG, "Error clearing records", e)
                false
            }
        }
    }

    /**
     * Get statistics for records
     */
    suspend fun getRecordStats(): RecordStats {
        val totalRecords = dao.getTotalCount()
        val matchedRecords = dao.getMatchedCount()
        val uniqueApps = dao.getUniqueAppCount()
        val oldestTimestamp = dao.getOldestTimestamp()
        val newestTimestamp = dao.getNewestTimestamp()

        return RecordStats(
            totalRecords = totalRecords,
            matchedRecords = matchedRecords,
            matchRate = if (totalRecords > 0) matchedRecords.toFloat() / totalRecords else 0f,
            uniqueApps = uniqueApps,
            oldestTimestamp = oldestTimestamp,
            newestTimestamp = newestTimestamp
        )
    }

    /**
     * Get storage size of records (images + database)
     */
    suspend fun getStorageSize(): Long {
        return withContext(Dispatchers.IO) {
            var size = 0L

            // Images directory size
            imagesDir.listFiles()?.forEach { file ->
                size += file.length()
            }

            // Rough estimate of database size (Room doesn't expose this easily)
            // Could use context.getDatabasePath() to get actual file size
            val dbPath = context.getDatabasePath("seenot_database")
            if (dbPath.exists()) {
                size += dbPath.length()
            }

            size
        }
    }

    /**
     * Get image file from path
     */
    fun getImageFile(imagePath: String): File? {
        val file = File(imagePath)
        return if (file.exists()) file else null
    }

    /**
     * Extension function to convert entity to model
     */
    private fun RuleRecordEntity.toModel(): RuleRecord {
        return RuleRecord(
            id = id,
            timestamp = timestamp,
            sessionId = sessionId,
            appName = appName,
            packageName = packageName,
            screenshotHash = screenshotHash,
            constraintId = constraintId,
            constraintType = constraintType?.let {
                try {
                    com.seenot.app.data.model.ConstraintType.valueOf(it)
                } catch (e: Exception) {
                    null
                }
            },
            constraintContent = constraintContent,
            isConditionMatched = isConditionMatched,
            aiResult = aiResult,
            confidence = confidence,
            imagePath = imagePath,
            elapsedTimeMs = elapsedTimeMs,
            isMarked = isMarked,
            actionType = actionType,
            actionReason = actionReason,
            actionTimestamp = actionTimestamp
        )
    }

    /**
     * Extension function to convert model to entity
     */
    private fun RuleRecord.toEntity(): RuleRecordEntity {
        return RuleRecordEntity(
            id = id,
            timestamp = timestamp,
            sessionId = sessionId,
            appName = appName,
            packageName = packageName,
            screenshotHash = screenshotHash,
            constraintId = constraintId,
            constraintType = constraintType?.name,
            constraintContent = constraintContent,
            isConditionMatched = isConditionMatched,
            aiResult = aiResult,
            confidence = confidence,
            imagePath = imagePath,
            elapsedTimeMs = elapsedTimeMs,
            isMarked = isMarked,
            actionType = actionType,
            actionReason = actionReason,
            actionTimestamp = actionTimestamp
        )
    }
}
