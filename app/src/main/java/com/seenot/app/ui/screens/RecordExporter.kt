package com.seenot.app.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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

                onProgress("正在创建ZIP文件...")

                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(BufferedOutputStream(fos)).use { zos ->

                        // Add records JSON
                        onProgress("正在添加记录数据...")
                        val recordsJson = gson.toJson(records)
                        val jsonEntry = ZipEntry("records.json")
                        zos.putNextEntry(jsonEntry)
                        zos.write(recordsJson.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()

                        // Add metadata
                        onProgress("正在添加元数据...")
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
                                    onProgress("正在添加截图 ${++imageCount}/${recordsWithImages.size}...")
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
                        onProgress("正在添加说明文件...")
                        val readmeEntry = ZipEntry("README.txt")
                        zos.putNextEntry(readmeEntry)
                        zos.write(createReadme().toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                }

                onProgress("导出完成！")

                // Return URI for sharing using FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    zipFile
                )

            } catch (e: Exception) {
                onProgress("导出失败: ${e.message}")
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
                "分享 SeeNot 记录导出"
            )

            chooserIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            onError("分享失败: ${e.message}")
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
                "${formatDate(min.timestamp)} 至 ${formatDate(max.timestamp)}"
            }
        } ?: "未知"

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
            SeeNot 规则记录导出
            =========================

            此归档包含 SeeNot 应用的规则评估记录导出。

            内容：
            - records.json: 所有规则评估记录的 JSON 格式数据
            - metadata.json: 汇总统计和导出信息
            - images/: 规则评估期间拍摄的截图（PNG 格式）
            - README.txt: 本文件

            记录字段说明：
            - id: 唯一记录标识符
            - timestamp: 评估时间戳（毫秒）
            - appName: 被监控应用的名称
            - packageName: 应用的包名（可选）
            - screenshotHash: 截图哈希值，用于去重（可选）
            - constraintId: 被评估的约束 ID
            - constraintType: 约束类型 (ALLOW/DENY/TIME_CAP)
            - constraintContent: 约束内容描述
            - isConditionMatched: 条件是否匹配（是否违反规则）
            - confidence: AI 置信度分数 (0-100)
            - aiResult: AI 原始响应文本（可选）
            - imagePath: 截图路径（在归档中）
            - elapsedTimeMs: AI 处理耗时（毫秒，可选）
            - isMarked: 是否被用户标记

            导出时间: ${formatDate(System.currentTimeMillis())}

            注意：截图保存在应用内部存储中，不会显示在系统图库中。
        """.trimIndent()
    }
}
