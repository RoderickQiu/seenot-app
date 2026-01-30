package com.roderickqiu.seenot.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.roderickqiu.seenot.data.RuleRecord
import com.roderickqiu.seenot.data.TimeConstraintAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RecordExporter(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(com.roderickqiu.seenot.data.TimeConstraint::class.java,
            TimeConstraintAdapter())
        .setPrettyPrinting()
        .create()

    companion object {
        private const val BUFFER_SIZE = 8192
    }

    /**
     * Export records to a ZIP file containing JSON data and images
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
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val zipFileName = "seenot_records_$timestamp.zip"
                val zipFile = File(exportDir, zipFileName)

                onProgress("Creating ZIP file...")

                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(BufferedOutputStream(fos)).use { zos ->

                        // Add records JSON
                        onProgress("Adding records data...")
                        val recordsJson = gson.toJson(records)
                        val jsonEntry = ZipEntry("records.json")
                        zos.putNextEntry(jsonEntry)
                        zos.write(recordsJson.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add metadata
                        onProgress("Adding metadata...")
                        val metadata = createMetadata(records)
                        val metadataEntry = ZipEntry("metadata.json")
                        zos.putNextEntry(metadataEntry)
                        zos.write(gson.toJson(metadata).toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add images
                        var imageCount = 0
                        records.forEach { record ->
                            record.imagePath?.let { imagePath ->
                                val imageFile = File(imagePath)
                                if (imageFile.exists()) {
                                    onProgress("Adding image ${++imageCount}/${records.count { it.imagePath != null }}...")
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
                        onProgress("Adding README...")
                        val readmeEntry = ZipEntry("README.txt")
                        zos.putNextEntry(readmeEntry)
                        zos.write(createReadme().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }

                onProgress("Export completed!")

                // Return URI for sharing
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zipFile
                )

            } catch (e: Exception) {
                onProgress("Export failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Create metadata for the export
     */
    private fun createMetadata(records: List<RuleRecord>): Map<String, Any> {
        val totalRecords = records.size
        val matchedRecords = records.count { it.isConditionMatched }
        val apps = records.map { it.appName }.distinct()
        val dateRange = records.minByOrNull { it.timestamp }?.let { min ->
            records.maxByOrNull { it.timestamp }?.let { max ->
                "${formatDate(min.timestamp)} to ${formatDate(max.timestamp)}"
            }
        } ?: "Unknown"

        return mapOf(
            "exportDate" to System.currentTimeMillis(),
            "totalRecords" to totalRecords,
            "matchedRecords" to matchedRecords,
            "matchRate" to if (totalRecords > 0) matchedRecords.toFloat() / totalRecords else 0f,
            "uniqueApps" to apps.size,
            "appList" to apps,
            "dateRange" to dateRange,
            "exportVersion" to "1.0"
        )
    }

    /**
     * Create README file content
     */
    private fun createReadme(): String {
        return """
            SeeNot Rule Records Export
            =========================

            This archive contains exported rule evaluation records from SeeNot app.

            Contents:
            - records.json: All rule evaluation records in JSON format
            - metadata.json: Summary statistics and export information
            - images/: Screenshots taken during rule evaluations (PNG format)
            - README.txt: This file

            Record Fields:
            - id: Unique record identifier
            - timestamp: Evaluation timestamp (milliseconds since epoch)
            - appName: Name of the monitored application
            - packageName: Package name of the application (optional)
            - ruleId: ID of the rule that was evaluated
            - condition: Rule condition details
            - action: Rule action details
            - isConditionMatched: Whether the condition was matched (true/false)
            - confidence: AI confidence score (0-100, optional)
            - aiResult: Raw AI response text (optional)
            - imagePath: Path to screenshot (in this archive)
            - elapsedTimeMs: AI processing time in milliseconds (optional)

            Generated on: ${formatDate(System.currentTimeMillis())}
        """.trimIndent()
    }

    /**
     * Format timestamp for display
     */
    private fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Share the exported ZIP file
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
                "Share SeeNot Records Export"
            )

            // Start as new task to avoid issues with context
            chooserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            onError("Failed to share file: ${e.message}")
        }
    }
}
