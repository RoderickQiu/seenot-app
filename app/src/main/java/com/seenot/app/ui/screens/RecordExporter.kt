package com.seenot.app.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.seenot.app.R
import com.seenot.app.data.model.ConstraintType
import com.seenot.app.data.model.RecordStats
import com.seenot.app.data.model.RuleRecord
import com.seenot.app.data.repository.RuleRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export handler for rule records
 */
class RecordExporter(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    companion object {
        private const val BUFFER_SIZE = 8192

        /**
         * Format timestamp for display
         */
        fun formatDate(timestamp: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(timestamp))
        }

        /**
         * Format file size
         */
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "${bytes / (1024 * 1024)} MB"
            }
        }
    }

    /**
     * Export records to a ZIP file containing JSON data and images
     * Returns the URI of the exported file
     */
    suspend fun exportRecordsToZip(
        records: List<RuleRecord>,
        onProgress: (String) -> Unit = {}
    ): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                // Create export directory in cache
                val exportDir = File(context.cacheDir, "exports").apply {
                    if (!exists()) mkdirs()
                }

                // Generate filename with timestamp
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                val zipFileName = "seenot_records_$timestamp.zip"
                val zipFile = File(exportDir, zipFileName)

                onProgress(context.getString(R.string.export_creating_zip))

                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(BufferedOutputStream(fos)).use { zos ->

                        // Add records JSON
                        onProgress(context.getString(R.string.export_adding_records))
                        val recordsJson = gson.toJson(records)
                        val jsonEntry = ZipEntry("records.json")
                        zos.putNextEntry(jsonEntry)
                        zos.write(recordsJson.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add metadata
                        onProgress(context.getString(R.string.export_adding_metadata))
                        val metadata = createMetadata(records)
                        val metadataEntry = ZipEntry("metadata.json")
                        zos.putNextEntry(metadataEntry)
                        zos.write(gson.toJson(metadata).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add images
                        val recordsWithImages = records.filter { it.imagePath != null }
                        var imageCount = 0
                        recordsWithImages.forEach { record ->
                            record.imagePath?.let { imagePath ->
                                val imageFile = File(imagePath)
                                if (imageFile.exists()) {
                                    onProgress(context.getString(R.string.export_adding_screenshot, ++imageCount, recordsWithImages.size))
                                    val imageEntry = ZipEntry("images/${record.id}.png")
                                    zos.putNextEntry(imageEntry)
                                    imageFile.inputStream().use { input ->
                                        input.copyTo(zos, BUFFER_SIZE)
                                    }
                                    zos.closeEntry()
                                }
                            }
                        }

                        // Add README
                        onProgress(context.getString(R.string.export_adding_readme))
                        val readmeEntry = ZipEntry("README.txt")
                        zos.putNextEntry(readmeEntry)
                        zos.write(createReadme().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }

                onProgress(context.getString(R.string.export_complete))

                // Return URI for sharing using FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zipFile
                )

            } catch (e: Exception) {
                onProgress(context.getString(R.string.export_failed) + ": ${e.message}")
                null
            }
        }
    }

    /**
     * Export and share via intent
     */
    fun shareExportedFile(uri: Uri, onError: (String) -> Unit = {}) {
        try {
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = android.content.Intent.createChooser(
                shareIntent,
                context.getString(R.string.share_title)
            )

            chooserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            onError(context.getString(R.string.share_failed) + ": ${e.message}")
        }
    }

    /**
     * Create metadata for the export
     */
    private fun createMetadata(records: List<RuleRecord>): Map<String, Any> {
        val totalRecords = records.size
        val conditionMatchedRecords = records.count { it.isConditionMatched }
        val conditionUnmatchedRecords = totalRecords - conditionMatchedRecords
        val denyRecords = records.filter { it.constraintType == ConstraintType.DENY }
        val timeCapRecords = records.filter { it.constraintType == ConstraintType.TIME_CAP }
        val apps = records.map { it.appName }.distinct()
        val dateRange = records.minByOrNull { it.timestamp }?.let { min ->
            records.maxByOrNull { it.timestamp }?.let { max ->
                "${formatDate(min.timestamp)} ${context.getString(R.string.date_range_separator)} ${formatDate(max.timestamp)}"
            }
        } ?: context.getString(R.string.unknown)

        return mapOf(
            "exportDate" to System.currentTimeMillis(),
            "totalRecords" to totalRecords,
            "conditionMatchedRecords" to conditionMatchedRecords,
            "conditionUnmatchedRecords" to conditionUnmatchedRecords,
            "conditionMatchRate" to if (totalRecords > 0) conditionMatchedRecords.toFloat() / totalRecords else 0f,
            "denySafeRecords" to denyRecords.count { it.isConditionMatched },
            "denyViolationRecords" to denyRecords.count { !it.isConditionMatched },
            "timeCapInScopeRecords" to timeCapRecords.count { it.isConditionMatched },
            "timeCapOutOfScopeRecords" to timeCapRecords.count { !it.isConditionMatched },
            "uniqueApps" to apps.size,
            "appList" to apps,
            "dateRange" to dateRange,
            "exportVersion" to "1.1"
        )
    }

    /**
     * Create README file content
     */
    private fun createReadme(): String {
        val separator = "========================="
        return listOf(
            context.getString(R.string.readme_title),
            separator,
            "",
            context.getString(R.string.readme_description),
            "",
            context.getString(R.string.readme_contents),
            context.getString(R.string.readme_content_records),
            context.getString(R.string.readme_content_metadata),
            context.getString(R.string.readme_content_images),
            context.getString(R.string.readme_content_readme),
            "",
            context.getString(R.string.readme_field_title),
            context.getString(R.string.readme_field_id),
            context.getString(R.string.readme_field_timestamp),
            context.getString(R.string.readme_field_appname),
            context.getString(R.string.readme_field_packagename),
            context.getString(R.string.readme_field_hash),
            context.getString(R.string.readme_field_constraintid),
            context.getString(R.string.readme_field_type),
            context.getString(R.string.readme_field_content),
            context.getString(R.string.readme_field_matched),
            context.getString(R.string.readme_field_matched_deny),
            context.getString(R.string.readme_field_matched_timecap),
            context.getString(R.string.readme_field_confidence),
            context.getString(R.string.readme_field_airesult),
            context.getString(R.string.readme_field_imagepath),
            context.getString(R.string.readme_field_elapsed),
            context.getString(R.string.readme_field_marked),
            "",
            context.getString(R.string.readme_export_time, formatDate(System.currentTimeMillis())),
            "",
            context.getString(R.string.readme_note)
        ).joinToString("\n")
    }
}
