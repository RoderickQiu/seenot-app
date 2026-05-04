package com.seenot.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.gson.Gson
import com.seenot.app.data.local.SeenotDatabase
import com.seenot.app.data.local.entity.RuleRecordEntity
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.MediaContentContext
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
    private val gson = Gson()

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
            trimToRetentionLimit()
            record
        }
    }

    /**
     * Save one shared screenshot bitmap for multiple records from the same analysis pass.
     * Returns the path to the saved image.
     */
    suspend fun saveScreenshotForRecords(recordIds: List<String>, bitmap: Bitmap, isMarked: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            if (recordIds.isEmpty()) {
                return@withContext null
            }
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val markedStr = if (isMarked) "marked" else "unmarked"
                val filename = "record_${recordIds.first()}_${dateFormat.format(Date(timestamp))}_$markedStr.png"
                val imageFile = File(imagesDir, filename)

                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val imagePath = imageFile.absolutePath
                recordIds.forEach { recordId ->
                    dao.updateImagePath(recordId, imagePath)
                }
                Logger.d(TAG, "Saved shared screenshot for ${recordIds.size} records: $imagePath")

                imagePath
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving screenshot for records", e)
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
     * Get records within a time range as Flow (auto-updates when DB changes)
     */
    fun getRecordsInRangeFlow(startTime: Long, endTime: Long): Flow<List<RuleRecord>> {
        return dao.getRecordsInRangeFlow(startTime, endTime).map { entities ->
            entities.map { it.toModel() }
        }
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
     * Get the most recent violation analysis record for a constraint in the active session.
     * This excludes action records so false-positive marking lands on the history item.
     */
    suspend fun getLatestViolationAnalysisRecord(
        sessionId: Long,
        packageName: String,
        constraintContent: String
    ): RuleRecord? {
        return dao.getLatestViolationAnalysisRecord(
            sessionId = sessionId,
            packageName = packageName,
            constraintContent = constraintContent
        )?.toModel()
    }

    suspend fun getLatestAnalysisRecordForType(
        sessionId: Long,
        packageName: String,
        constraintType: ConstraintType,
        isConditionMatched: Boolean
    ): RuleRecord? {
        return dao.getLatestAnalysisRecordForType(
            sessionId = sessionId,
            packageName = packageName,
            constraintType = constraintType.name,
            isConditionMatched = isConditionMatched
        )?.toModel()
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
                dao.deleteById(recordId)
                deleteUnreferencedImageFiles(record?.imagePath?.let(::listOf).orEmpty())
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
        val records = dao.getAllRecordsOnce()
        val totalRecords = records.size
        val matchedRecords = records.count { it.isConditionMatched }
        val uniqueApps = records
            .map { normalizeAppKey(it.packageName, it.appName) }
            .distinct()
            .size
        val oldestTimestamp = records.minOfOrNull { it.timestamp }
        val newestTimestamp = records.maxOfOrNull { it.timestamp }

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
            mediaContext = mediaContextJson?.let { json ->
                runCatching { gson.fromJson(json, MediaContentContext::class.java) }.getOrNull()
            },
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
            mediaContextJson = mediaContext?.let { gson.toJson(it) },
            isMarked = isMarked,
            actionType = actionType,
            actionReason = actionReason,
            actionTimestamp = actionTimestamp
        )
    }

    private suspend fun trimToRetentionLimit() {
        val staleRecords = dao.getRecordsExceedingLimit(MAX_RECORDS)
        if (staleRecords.isEmpty()) {
            return
        }

        val staleImagePaths = staleRecords.mapNotNull { it.imagePath }.distinct()
        dao.deleteByIds(staleRecords.map { it.id })
        deleteUnreferencedImageFiles(staleImagePaths)
        Logger.d(TAG, "Trimmed ${staleRecords.size} old rule records to keep retention at $MAX_RECORDS")
    }

    private suspend fun deleteUnreferencedImageFiles(paths: List<String>) {
        val orphanPaths = paths.distinct().filter { path -> dao.countRecordsByImagePath(path) == 0 }
        deleteImageFiles(orphanPaths)
    }

    private fun deleteImageFiles(paths: List<String>) {
        paths.distinct().forEach { path ->
            try {
                File(path).delete()
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to delete image: $path", e)
            }
        }
    }

    private fun normalizeAppKey(packageName: String?, appName: String): String {
        return packageName?.takeIf { it.isNotBlank() } ?: appName
    }
}
