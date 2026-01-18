package com.roderickqiu.seenot.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class RuleRecordRepo(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TimeConstraint::class.java, TimeConstraintAdapter())
        .setPrettyPrinting()
        .create()

    private val recordsFile: File = File(context.filesDir, "rule_records.json")
    private val imagesDir: File = File(context.filesDir, "record_images").apply {
        if (!exists()) mkdirs()
    }

    companion object {
        private const val TAG = "RuleRecordRepo"
        private const val MAX_RECORDS = 10000 // Limit to prevent excessive storage usage
    }

    /**
     * Save a new rule record
     */
    suspend fun saveRecord(record: RuleRecord): RuleRecord {
        return withContext(Dispatchers.IO) {
            try {
                val records = loadRecords().toMutableList()

                // Add new record at the beginning (most recent first)
                records.add(0, record)

                // Keep only the most recent records
                if (records.size > MAX_RECORDS) {
                    // Remove old records and their images
                    val recordsToRemove = records.subList(MAX_RECORDS, records.size)
                    recordsToRemove.forEach { oldRecord ->
                        oldRecord.imagePath?.let { path ->
                            try {
                                File(path).delete()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to delete old image: $path", e)
                            }
                        }
                    }
                    records.subList(MAX_RECORDS, records.size).clear()
                }

                // Save to file
                val json = gson.toJson(records)
                recordsFile.writeText(json)

                Log.d(TAG, "Saved rule record: ${record.id} for app ${record.appName}")
                record
            } catch (e: Exception) {
                Log.e(TAG, "Error saving rule record", e)
                throw e
            }
        }
    }

    /**
     * Load all records, sorted by timestamp (newest first)
     */
    fun loadRecords(): List<RuleRecord> {
        return try {
            if (!recordsFile.exists()) return emptyList()

            val json = recordsFile.readText()
            if (json.isBlank()) return emptyList()

            val type = object : TypeToken<List<RuleRecord>>() {}.type
            val records = gson.fromJson<List<RuleRecord>>(json, type) ?: emptyList()

            // Ensure records are sorted by timestamp (newest first)
            records.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading rule records", e)
            emptyList()
        }
    }

    /**
     * Load records within a date range
     */
    fun loadRecordsInRange(startTime: Long, endTime: Long): List<RuleRecord> {
        return loadRecords().filter { it.timestamp in startTime..endTime }
    }

    /**
     * Delete a specific record and its image
     */
    suspend fun deleteRecord(recordId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val records = loadRecords().toMutableList()
                val recordToDelete = records.find { it.id == recordId }

                if (recordToDelete != null) {
                    // Delete associated image
                    recordToDelete.imagePath?.let { path ->
                        try {
                            File(path).delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete image: $path", e)
                        }
                    }

                    // Remove from list
                    records.remove(recordToDelete)

                    // Save updated list
                    val json = gson.toJson(records)
                    recordsFile.writeText(json)

                    Log.d(TAG, "Deleted rule record: $recordId")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting rule record", e)
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
                val records = loadRecords()

                // Delete all images referenced in records
                records.forEach { record ->
                    record.imagePath?.let { path ->
                        try {
                            File(path).delete()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete image: $path", e)
                        }
                    }
                }

                // Delete all files in images directory to ensure complete cleanup
                if (imagesDir.exists() && imagesDir.isDirectory) {
                    imagesDir.listFiles()?.forEach { file ->
                        try {
                            if (file.isFile) {
                                file.delete()
                            } else if (file.isDirectory) {
                                file.deleteRecursively()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to delete file: ${file.absolutePath}", e)
                        }
                    }
                }

                // Clear records file
                if (recordsFile.exists()) {
                    recordsFile.writeText("[]")
                }

                Log.d(TAG, "Cleared all rule records and images")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing rule records", e)
                false
            }
        }
    }

    /**
     * Save screenshot bitmap to internal storage for a record
     */
    suspend fun saveScreenshotForRecord(recordId: String, bitmap: Bitmap): String? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val filename = "record_${recordId}_${dateFormat.format(Date(timestamp))}.png"
                val imageFile = File(imagesDir, filename)

                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val imagePath = imageFile.absolutePath
                Log.d(TAG, "Saved screenshot for record $recordId: $imagePath")
                
                // Update record with imagePath
                updateRecordImagePath(recordId, imagePath)
                
                imagePath
            } catch (e: Exception) {
                Log.e(TAG, "Error saving screenshot for record", e)
                null
            }
        }
    }
    
    /**
     * Update imagePath for a specific record
     */
    private suspend fun updateRecordImagePath(recordId: String, imagePath: String) {
        withContext(Dispatchers.IO) {
            try {
                val records = loadRecords().toMutableList()
                val recordIndex = records.indexOfFirst { it.id == recordId }
                if (recordIndex != -1) {
                    val record = records[recordIndex]
                    records[recordIndex] = record.copy(imagePath = imagePath)
                    
                    // Save updated records
                    val json = gson.toJson(records)
                    recordsFile.writeText(json)
                    
                    Log.d(TAG, "Updated imagePath for record $recordId: $imagePath")
                } else {
                    Log.w(TAG, "Record not found for updating imagePath: $recordId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating record imagePath", e)
            }
        }
    }

    /**
     * Get statistics for records
     */
    fun getRecordStats(): RecordStats {
        val records = loadRecords()
        val totalRecords = records.size
        val matchedRecords = records.count { it.isConditionMatched }
        val uniqueApps = records.map { it.appName }.distinct().size
        val oldestRecord = records.minByOrNull { it.timestamp }
        val newestRecord = records.maxByOrNull { it.timestamp }

        return RecordStats(
            totalRecords = totalRecords,
            matchedRecords = matchedRecords,
            matchRate = if (totalRecords > 0) matchedRecords.toFloat() / totalRecords else 0f,
            uniqueApps = uniqueApps,
            oldestTimestamp = oldestRecord?.timestamp,
            newestTimestamp = newestRecord?.timestamp
        )
    }

    /**
     * Get records grouped by date (for day/hour navigation)
     */
    fun getRecordsGroupedByDate(): Map<String, List<RuleRecord>> {
        val records = loadRecords()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        return records.groupBy { record ->
            dateFormat.format(record.date)
        }
    }

    /**
     * Get records for a specific date
     */
    fun getRecordsForDate(dateString: String): List<RuleRecord> {
        return getRecordsGroupedByDate()[dateString] ?: emptyList()
    }

    /**
     * Get records for a specific date and hour
     */
    fun getRecordsForDateAndHour(dateString: String, hour: Int): List<RuleRecord> {
        val dateRecords = getRecordsForDate(dateString)
        return dateRecords.filter { record ->
            val calendar = Calendar.getInstance().apply { time = record.date }
            calendar.get(Calendar.HOUR_OF_DAY) == hour
        }
    }

    /**
     * Mark or unmark a specific record
     */
    suspend fun markRecord(recordId: String, isMarked: Boolean): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val records = loadRecords().toMutableList()
                val recordIndex = records.indexOfFirst { it.id == recordId }
                if (recordIndex != -1) {
                    val record = records[recordIndex]
                    records[recordIndex] = record.copy(isMarked = isMarked)

                    // Save updated records
                    val json = gson.toJson(records)
                    recordsFile.writeText(json)

                    Log.d(TAG, "Marked record $recordId as $isMarked")
                    true
                } else {
                    Log.w(TAG, "Record not found for marking: $recordId")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error marking record", e)
                false
            }
        }
    }

    /**
     * Get only marked records
     */
    fun getMarkedRecords(): List<RuleRecord> {
        return loadRecords().filter { it.isMarked }
    }

    /**
     * Get records filtered by match status
     */
    fun getRecordsByMatchStatus(isMatched: Boolean): List<RuleRecord> {
        return loadRecords().filter { it.isConditionMatched == isMatched }
    }
}

data class RecordStats(
    val totalRecords: Int,
    val matchedRecords: Int,
    val matchRate: Float,
    val uniqueApps: Int,
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?
)
