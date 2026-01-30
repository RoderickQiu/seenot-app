package com.roderickqiu.seenot.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.roderickqiu.seenot.utils.Logger
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
     * Uses append-first strategy to prevent data loss
     */
    suspend fun saveRecord(record: RuleRecord): RuleRecord {
        return withContext(Dispatchers.IO) {
            try {
                // First, try to append the record directly to avoid loading entire file
                val success = appendRecordToFile(record)
                if (success) {
                    Logger.d(TAG, "Appended rule record: ${record.id} for app ${record.appName}")
                    
                    // Periodically check if we need to trim old records (every ~100 records)
                    if (System.currentTimeMillis() % 100 == 0L) {
                        trimOldRecordsIfNeeded()
                    }
                    return@withContext record
                }
                
                // Fallback: load all records and save
                val records = loadRecords().toMutableList()
                
                // Safety check: if loaded records seem suspiciously few, don't overwrite
                val fileSize = recordsFile.length()
                val expectedMinRecords = (fileSize / 2000).toInt() // rough estimate: ~2KB per record
                if (records.size < expectedMinRecords / 2 && records.size < 100) {
                    Logger.e(TAG, "Safety check failed: loaded ${records.size} records but file is ${fileSize} bytes. Not overwriting.")
                    // Just append the new record without overwriting
                    appendRecordToFile(record)
                    return@withContext record
                }

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
                                Logger.w(TAG, "Failed to delete old image: $path", e)
                            }
                        }
                    }
                    records.subList(MAX_RECORDS, records.size).clear()
                }

                // Save to file
                val json = gson.toJson(records)
                recordsFile.writeText(json)

                Logger.d(TAG, "Saved rule record: ${record.id} for app ${record.appName}")
                record
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving rule record", e)
                throw e
            }
        }
    }
    
    /**
     * Append a single record to the JSON file without loading all records
     * This is more efficient and safer for large files
     */
    private fun appendRecordToFile(record: RuleRecord): Boolean {
        var file: java.io.RandomAccessFile? = null
        try {
            if (!recordsFile.exists()) {
                // Create new file with single record
                recordsFile.writeText("[${gson.toJson(record)}]")
                return true
            }

            file = java.io.RandomAccessFile(recordsFile, "rw")
            val fileLength = file.length()

            if (fileLength < 2) {
                // File is empty or just "[]"
                file.close()
                recordsFile.writeText("[${gson.toJson(record)}]")
                return true
            }

            // Seek to before the closing bracket
            file.seek(fileLength - 1)
            val lastChar = file.readByte().toInt().toChar()

            if (lastChar == ']') {
                // Position before the ]
                file.seek(fileLength - 1)

                // Check if array is empty (just [])
                file.seek(fileLength - 2)
                val prevChar = file.readByte().toInt().toChar()

                file.seek(fileLength - 1)
                val payload = if (prevChar == '[') {
                    "${gson.toJson(record)}]"
                } else {
                    ",${gson.toJson(record)}]"
                }
                file.write(payload.toByteArray(Charsets.UTF_8))
                return true
            }

            return false
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to append record to file", e)
            return false
        } finally {
            try {
                file?.close()
            } catch (closeError: Exception) {
                Logger.w(TAG, "Failed to close record file after append", closeError)
            }
        }
    }
    
    /**
     * Trim old records if we've exceeded the limit
     */
    private suspend fun trimOldRecordsIfNeeded() {
        withContext(Dispatchers.IO) {
            try {
                val records = loadRecords()
                if (records.size > MAX_RECORDS) {
                    val recordsToKeep = records.take(MAX_RECORDS)
                    val recordsToRemove = records.drop(MAX_RECORDS)
                    
                    // Delete old images
                    recordsToRemove.forEach { oldRecord ->
                        oldRecord.imagePath?.let { path ->
                            try {
                                File(path).delete()
                            } catch (e: Exception) {
                                Logger.w(TAG, "Failed to delete old image: $path", e)
                            }
                        }
                    }
                    
                    // Save trimmed list
                    val json = gson.toJson(recordsToKeep)
                    recordsFile.writeText(json)
                    Logger.d(TAG, "Trimmed records from ${records.size} to ${recordsToKeep.size}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error trimming old records", e)
            }
        }
    }

    /**
     * Load all records, sorted by timestamp (newest first)
     * Uses streaming parser for large files to avoid memory issues
     */
    fun loadRecords(): List<RuleRecord> {
        return try {
            if (!recordsFile.exists()) return emptyList()

            val fileSize = recordsFile.length()
            Logger.d(TAG, "Loading records from file, size: ${fileSize / 1024}KB")

            // For large files (>5MB), use streaming parser
            if (fileSize > 5 * 1024 * 1024) {
                Logger.d(TAG, "Using streaming parser for large file")
                loadRecordsStreaming()
            } else {
                val json = recordsFile.readText()
                if (json.isBlank()) return emptyList()

                val type = object : TypeToken<List<RuleRecord>>() {}.type
                val records = gson.fromJson<List<RuleRecord>>(json, type) ?: emptyList()
                Logger.d(TAG, "Loaded ${records.size} records using standard parser")
                records.sortedByDescending { it.timestamp }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error loading rule records, trying streaming recovery", e)
            // Fallback to streaming parser on error
            try {
                loadRecordsStreaming()
            } catch (e2: Exception) {
                Logger.e(TAG, "Streaming recovery also failed", e2)
                emptyList()
            }
        }
    }

    /**
     * Load records using streaming JSON parser to handle large files
     */
    private fun loadRecordsStreaming(): List<RuleRecord> {
        val records = mutableListOf<RuleRecord>()
        var successCount = 0
        var errorCount = 0

        try {
            val reader = com.google.gson.stream.JsonReader(recordsFile.bufferedReader())
            reader.beginArray()

            while (reader.hasNext()) {
                try {
                    val record = gson.fromJson<RuleRecord>(reader, RuleRecord::class.java)
                    if (record != null) {
                        records.add(record)
                        successCount++
                    }
                } catch (e: Exception) {
                    errorCount++
                    // Skip malformed record and continue
                    try {
                        reader.skipValue()
                    } catch (skipError: Exception) {
                        // If we can't skip, try to continue anyway
                    }
                }
            }

            reader.endArray()
            reader.close()

            Logger.d(TAG, "Streaming load complete: $successCount records loaded, $errorCount errors")
        } catch (e: Exception) {
            Logger.e(TAG, "Streaming parse error at record $successCount", e)
        }

        return records.sortedByDescending { it.timestamp }
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
                            Logger.w(TAG, "Failed to delete image: $path", e)
                        }
                    }

                    // Remove from list
                    records.remove(recordToDelete)

                    // Save updated list
                    val json = gson.toJson(records)
                    recordsFile.writeText(json)

                    Logger.d(TAG, "Deleted rule record: $recordId")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error deleting rule record", e)
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
                            Logger.w(TAG, "Failed to delete image: $path", e)
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
                            Logger.w(TAG, "Failed to delete file: ${file.absolutePath}", e)
                        }
                    }
                }

                // Clear records file
                if (recordsFile.exists()) {
                    recordsFile.writeText("[]")
                }

                Logger.d(TAG, "Cleared all rule records and images")
                true
            } catch (e: Exception) {
                Logger.e(TAG, "Error clearing rule records", e)
                false
            }
        }
    }

    /**
     * Save screenshot bitmap to internal storage for a record
     * Filename format: record_{recordId}_{yyyyMMdd_HHmmss}_{marked}.png
     */
    suspend fun saveScreenshotForRecord(recordId: String, bitmap: Bitmap, isMarked: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val markedStr = if (isMarked) "true" else "false"
                val filename = "record_${recordId}_${dateFormat.format(Date(timestamp))}_${markedStr}.png"
                val imageFile = File(imagesDir, filename)

                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }

                val imagePath = imageFile.absolutePath
                Logger.d(TAG, "Saved screenshot for record $recordId (marked: $isMarked): $imagePath")

                // Update record with imagePath
                updateRecordImagePath(recordId, imagePath)

                imagePath
            } catch (e: Exception) {
                Logger.e(TAG, "Error saving screenshot for record", e)
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
                } else {
                    Logger.w(TAG, "Record not found for updating imagePath: $recordId")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error updating record imagePath", e)
            }
        }
    }

    /**
     * Parse marked status from image filename
     * Filename format: record_{recordId}_{yyyyMMdd_HHmmss}_{marked}.png
     */
    fun parseMarkedStatusFromFilename(filename: String): Boolean {
        try {
            // Extract the marked part from filename
            val parts = filename.removeSuffix(".png").split("_")
            if (parts.size >= 4) {
                val markedStr = parts.last()
                return markedStr.equals("true", ignoreCase = true)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse marked status from filename: $filename", e)
        }
        return false // Default to false if parsing fails
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

                    Logger.d(TAG, "Marked record $recordId as $isMarked")
                    true
                } else {
                    Logger.w(TAG, "Record not found for marking: $recordId")
                    false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error marking record", e)
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
