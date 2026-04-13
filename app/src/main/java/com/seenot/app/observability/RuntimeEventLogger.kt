package com.seenot.app.observability

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.seenot.app.BuildConfig
import com.seenot.app.R
import com.seenot.app.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RuntimeEventLogger private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val eventsDir = File(appContext.filesDir, "runtime_events").apply {
        if (!exists()) mkdirs()
    }
    private val prefs = appContext.getSharedPreferences("runtime_event_logger", Context.MODE_PRIVATE)
    private val fileWriteLock = Any()

    companion object {
        private const val TAG = "RuntimeEventLogger"
        private const val PREF_PARTICIPANT_ID = "participant_id"

        @Volatile
        private var instance: RuntimeEventLogger? = null

        fun getInstance(context: Context): RuntimeEventLogger {
            return instance ?: synchronized(this) {
                instance ?: RuntimeEventLogger(context).also { instance = it }
            }
        }
    }

    fun isEnabled(): Boolean = BuildConfig.ENABLE_RUNTIME_EVENT_LOGGING

    fun newEventId(): String = UUID.randomUUID().toString()

    fun log(
        eventType: RuntimeEventType,
        sessionId: Long? = null,
        appPackage: String? = null,
        appDisplayName: String? = null,
        payload: Map<String, Any?> = emptyMap(),
        eventId: String = newEventId(),
        timestamp: Long = System.currentTimeMillis()
    ): String? {
        if (!isEnabled()) return null

        return runCatching {
            val event = RuntimeEvent(
                eventId = eventId,
                eventType = eventType.name.lowercase(),
                timestamp = timestamp,
                sessionId = sessionId,
                appPackage = appPackage,
                appDisplayName = appDisplayName,
                participantId = getParticipantId(),
                payload = payload
            )
            val dailyFile = File(
                eventsDir,
                "runtime_events_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))}.jsonl"
            )
            synchronized(fileWriteLock) {
                dailyFile.appendText(gson.toJson(event) + "\n", Charsets.UTF_8)
            }
            eventId
        }.onFailure { e ->
            Logger.w(TAG, "Failed to write runtime event ${eventType.name}: ${e.message}")
        }.getOrNull()
    }

    suspend fun exportAll(onProgress: (String) -> Unit = {}): Uri? {
        return export(null, null, onProgress)
    }

    suspend fun export(
        startDate: Date?,
        endDate: Date?,
        onProgress: (String) -> Unit = {}
    ): Uri? = withContext(Dispatchers.IO) {
        if (!isEnabled()) {
            onProgress(appContext.getString(R.string.event_log_not_enabled))
            return@withContext null
        }

        runCatching {
            onProgress(appContext.getString(R.string.event_log_organizing))
            val sourceFiles = eventsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }
                .orEmpty()

            if (sourceFiles.isEmpty()) {
                onProgress(appContext.getString(R.string.event_log_no_events))
                return@runCatching null
            }

            val exportDir = File(appContext.cacheDir, "exports").apply {
                if (!exists()) mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val exportFile = File(exportDir, "seenot_runtime_events_$timestamp.jsonl")

            exportFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                sourceFiles.forEach { file ->
                    file.forEachLine(Charsets.UTF_8) { line ->
                        if (line.isBlank()) return@forEachLine
                        if (isLineInRange(line, startDate, endDate)) {
                            writer.appendLine(line)
                        }
                    }
                }
            }

            if (exportFile.length() == 0L) {
                exportFile.delete()
                onProgress(appContext.getString(R.string.event_log_no_events_in_range))
                return@runCatching null
            }

            onProgress(appContext.getString(R.string.event_log_export_complete))
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                exportFile
            )
        }.onFailure { e ->
            onProgress(appContext.getString(R.string.event_log_export_failed, e.message ?: "unknown"))
        }.getOrNull()
    }

    fun shareExportedFile(uri: Uri, onError: (String) -> Unit = {}) {
        try {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/x-ndjson"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserIntent = Intent.createChooser(shareIntent, appContext.getString(R.string.event_log_share_title))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooserIntent)
        } catch (e: Exception) {
            onError(appContext.getString(R.string.share_failed, e.message ?: "unknown"))
        }
    }

    private fun getParticipantId(): String {
        val existing = prefs.getString(PREF_PARTICIPANT_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_PARTICIPANT_ID, newId).apply()
        return newId
    }

    private fun isLineInRange(line: String, startDate: Date?, endDate: Date?): Boolean {
        if (startDate == null && endDate == null) return true

        return runCatching {
            val timestamp = gson.fromJson(line, RuntimeEvent::class.java).timestamp
            val afterStart = startDate == null || timestamp >= startDate.time
            val beforeEnd = endDate == null || timestamp <= endDate.time + 86_399_999L
            afterStart && beforeEnd
        }.getOrDefault(false)
    }
}
